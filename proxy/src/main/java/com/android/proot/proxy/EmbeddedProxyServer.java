package com.android.proot.proxy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server compatible with OpenAI /v1/chat/completions API,
 * reverse-proxying requests to Tencent CNB with ToolForge function calling support.
 */
public final class EmbeddedProxyServer {
    private static final String CNB_CHAT = "https://cnb.cool/ai/chat/completions";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36";

    public static final class Request {
        public final String method;
        public final String path;
        public final Map<String, String> headers;
        public String body;

        Request(String method, String rawPath) {
            this.headers = new HashMap<>();
            this.body = "";
            this.method = method;
            int query = rawPath.indexOf('?');
            this.path = query >= 0 ? rawPath.substring(0, query) : rawPath;
        }
    }

    public static final class UpstreamResult {
        public String id = "";
        public String model = "";
        public long created = 0;
        public final StringBuilder content = new StringBuilder();
        public final StringBuilder reasoning = new StringBuilder();
        public JSONObject usage = null;
    }

    private final ProxyConfig config;
    private final ProxyLogListener logger;
    private final CsrfPool pool;
    private final ExecutorService clients;
    private ServerSocket listener;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private int actualPort;

    public EmbeddedProxyServer(ProxyConfig config, ProxyLogListener logger) {
        this.config = config;
        this.logger = logger != null ? logger : (tag, msg) -> {};
        this.clients = Executors.newCachedThreadPool();
        this.pool = new CsrfPool(config, this.logger);
        this.actualPort = config.getPort();
    }

    public synchronized void start() throws IOException {
        if (running) return;
        if (stopRequested) {
            throw new IOException("service start cancelled");
        }

        if (!pool.start()) {
            throw new IOException("unable to acquire CNB CSRF credentials");
        }

        if (stopRequested) {
            pool.close();
            throw new IOException("service start cancelled");
        }

        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(config.getListenHost(), config.getPort()), 64);
        actualPort = listener.getLocalPort();
        running = true;

        acceptThread = new Thread(this::acceptLoop, "cnb2api-accept");
        acceptThread.start();
        logger.onLog("HTTP", "Server listening on http://" + config.getListenHost() + ":" + actualPort);
    }

    private void acceptLoop() {
        while (running && !stopRequested && listener != null && !listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                clients.execute(() -> handle(socket));
            } catch (IOException e) {
                if (!running || stopRequested) break;
                logger.onLog("HTTP", "Accept error: " + e.getMessage());
            }
        }
    }

    public synchronized void stop() {
        stopRequested = true;
        running = false;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {}
            listener = null;
        }
        clients.shutdownNow();
        pool.close();
        logger.onLog("HTTP", "Server stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public int getActualPort() {
        return actualPort;
    }

    public CsrfPool getCsrfPool() {
        return pool;
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(30000);
            BufferedInputStream input = new BufferedInputStream(client.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream());

            Request request = readRequest(input);
            if (request == null) return;

            logger.onLog("HTTP", request.method + " " + request.path + " from " +
                    (client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : "unknown"));

            if ("OPTIONS".equals(request.method)) {
                writeBytes(output, 204, "application/json", new byte[0]);
                return;
            }

            if (!authorized(request)) {
                JSONObject err = new JSONObject().put("error", "unauthorized");
                writeJson(output, 401, err);
                return;
            }

            if ("GET".equals(request.method) && "/healthz".equals(request.path)) {
                JSONObject res = new JSONObject().put("ok", true).put("status", "running");
                writeJson(output, 200, res);
                return;
            }

            if ("GET".equals(request.method) && "/pool".equals(request.path)) {
                JSONObject res = new JSONObject().put("pool", pool.stats());
                writeJson(output, 200, res);
                return;
            }

            if ("GET".equals(request.method) && "/v1/models".equals(request.path)) {
                writeJson(output, 200, models());
                return;
            }

            if ("POST".equals(request.method) && "/v1/chat/completions".equals(request.path)) {
                handleChat(request, output);
                return;
            }

            JSONObject err = new JSONObject().put("error", "not found");
            writeJson(output, 404, err);
        } catch (Exception e) {
            logger.onLog("HTTP", "Request failed: " + message(e));
            try {
                if (socket.isConnected() && !socket.isClosed()) {
                    OutputStream out = socket.getOutputStream();
                    JSONObject err = new JSONObject().put("error", e.getMessage() != null ? e.getMessage() : "internal error");
                    writeJson(out, 502, err);
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean authorized(Request request) {
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return true;
        }
        String authHeader = request.headers.get("authorization");
        if (authHeader == null) return false;
        if (authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authHeader.substring(7).trim().equals(apiKey);
        }
        return authHeader.trim().equals(apiKey);
    }

    private void handleChat(Request request, OutputStream output) throws Exception {
        long started = System.currentTimeMillis();
        JSONObject body;
        try {
            body = new JSONObject(request.body);
        } catch (JSONException e) {
            JSONObject err = new JSONObject().put("error", "invalid JSON body");
            writeJson(output, 400, err);
            return;
        }

        JSONArray messages = body.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            JSONObject err = new JSONObject().put("error", "messages array is required");
            writeJson(output, 400, err);
            return;
        }

        JSONArray tools = body.optJSONArray("tools");
        if (tools == null) tools = new JSONArray();

        boolean stream = body.optBoolean("stream", false);
        boolean promptTools = config.isForcePromptTools() && tools.length() > 0;
        String requestedModel = body.optString("model", config.getModel());
        String model = resolveModel(requestedModel);

        logger.onLog("REQ", "model=" + requestedModel + " stream=" + stream + " msgs=" + messages.length() +
                " chars=" + messageChars(messages) + " tools=" + tools.length() + " fc=" + (promptTools ? "XYML" : "off"));

        JSONArray upstreamMessages;
        if (promptTools) {
            upstreamMessages = ToolForge.injectMessages(messages, tools);
        } else {
            upstreamMessages = convertMessages(messages);
        }

        JSONObject upstreamReq = new JSONObject();
        upstreamReq.put("model", model);
        upstreamReq.put("stream", true); // Upstream always streams SSE
        upstreamReq.put("messages", upstreamMessages);
        upstreamReq.put("maxTokens", 60000);

        if (body.has("temperature")) upstreamReq.put("temperature", body.opt("temperature"));
        if (body.has("top_p")) upstreamReq.put("top_p", body.opt("top_p"));
        if (body.has("enable_thinking")) upstreamReq.put("enable_thinking", body.opt("enable_thinking"));

        if (!promptTools && tools.length() > 0) {
            logger.onLog("TOOL", "tools present but XYML fallback disabled; CNB native tools are not sent");
        }

        UpstreamResult upstream = chatUpstream(upstreamReq);
        String upstreamText = upstream.content.toString();

        JSONArray calls = promptTools ? ToolForge.parseToolCalls(upstreamText, tools) : new JSONArray();
        String content = calls.length() > 0 ? ToolForge.stripProtocolMarkup(upstreamText) : upstreamText;

        if (calls.length() > 0) {
            logger.onLog("TOOL", "parsed " + calls.length() + " tool call(s): " + callNames(calls));
        }

        logger.onLog("RESP", "status=200 elapsed=" + (System.currentTimeMillis() - started) +
                "ms chars=" + content.length() + " tool_calls=" + calls.length());

        if (stream) {
            writeStream(output, upstream, model, content, calls);
        } else {
            writeJson(output, 200, completion(upstream, model, content, calls));
        }
    }

    private UpstreamResult chatUpstream(JSONObject request) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            CsrfPool.CsrfToken token = pool.acquire();
            HttpURLConnection conn = null;
            boolean reported = false;
            try {
                URL url = new URL(CNB_CHAT);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(180000);
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "text/event-stream, application/json, text/plain, */*");
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Origin", "https://cnb.cool");
                conn.setRequestProperty("Referer", "https://cnb.cool/");
                conn.setRequestProperty("Csrftoken", token.token);
                conn.setRequestProperty("Cookie", "csrfkey=" + token.key);

                byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                int status = conn.getResponseCode();
                logger.onLog("UP", "attempt=" + attempt + " http=" + status + " bytes=" + payload.length);

                if (status == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        UpstreamResult res = readSse(is);
                        pool.report(token, true);
                        reported = true;
                        return res;
                    }
                } else {
                    String error = readError(conn);
                    boolean isCsrf = (status == 401 || status == 403) && error.toLowerCase(Locale.US).contains("csrf");
                    pool.report(token, !isCsrf);
                    reported = true;
                    throw new IOException("upstream returned " + status + ": " + error);
                }
            } catch (Exception e) {
                if (!reported) {
                    pool.report(token, false);
                }
                last = e;
                if (attempt < 3) {
                    try {
                        Thread.sleep(200L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw last != null ? last : new IOException("upstream chat failed after 3 attempts");
    }

    private UpstreamResult readSse(InputStream input) throws IOException {
        UpstreamResult result = new UpstreamResult();
        StringBuilder event = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumeSseLine(line, event, result);
            }
        }
        if (event.length() > 0) {
            consumePayload(event.toString(), result);
        }
        return result;
    }

    private void consumeSseLine(String line, StringBuilder event, UpstreamResult result) {
        if (line.startsWith("data:")) {
            String payload = line.substring(5).trim();
            if ("[DONE]".equals(payload)) {
                if (event.length() > 0) {
                    consumePayload(event.toString(), result);
                    event.setLength(0);
                }
            } else {
                if (event.length() > 0) {
                    event.append('\n');
                }
                event.append(payload);
            }
        } else if (line.isEmpty() && event.length() > 0) {
            consumePayload(event.toString(), result);
            event.setLength(0);
        }
    }

    private void consumePayload(String payload, UpstreamResult result) {
        try {
            JSONObject obj = new JSONObject(payload);
            result.id = obj.optString("id", result.id);
            result.model = obj.optString("model", result.model);
            result.created = obj.optLong("created", result.created);
            if (obj.has("usage")) {
                result.usage = obj.optJSONObject("usage");
            }

            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return;
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) return;

            JSONObject delta = choice.optJSONObject("delta");
            JSONObject message = choice.optJSONObject("message");
            JSONObject textObj = delta != null ? delta : message;
            if (textObj != null) {
                result.content.append(textObj.optString("content", ""));
                result.reasoning.append(textObj.optString("reasoning_content", ""));
            }
        } catch (JSONException ignored) {}
    }

    private void writeStream(OutputStream output, UpstreamResult upstream, String model, String content, JSONArray calls) throws IOException, JSONException {
        writeHead(output, 200, "text/event-stream; charset=utf-8", -1);
        String id = upstream.id.isEmpty() ? "chatcmpl_" + shortId() : upstream.id;
        long created = upstream.created == 0 ? System.currentTimeMillis() / 1000 : upstream.created;

        if (calls.length() > 0) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call == null) continue;
                JSONObject function = call.optJSONObject("function");
                JSONObject delta = new JSONObject();
                JSONArray toolCalls = new JSONArray();
                JSONObject tc = new JSONObject();
                tc.put("index", i);
                tc.put("id", call.optString("id", "call_" + shortId()));
                tc.put("type", "function");
                JSONObject fn = new JSONObject();
                if (function != null) {
                    fn.put("name", function.optString("name", ""));
                    fn.put("arguments", function.optString("arguments", ""));
                }
                tc.put("function", fn);
                toolCalls.put(tc);
                delta.put("tool_calls", toolCalls);

                JSONObject chunk = chunk(id, model, created, delta, null);
                writeSse(output, chunk);
            }
            JSONObject finishChunk = chunk(id, model, created, new JSONObject(), "tool_calls");
            writeSse(output, finishChunk);
        } else {
            String reasoning = upstream.reasoning.toString();
            String[] reasoningPieces = split(reasoning, 120);
            for (String piece : reasoningPieces) {
                JSONObject delta = new JSONObject().put("reasoning_content", piece);
                writeSse(output, chunk(id, model, created, delta, null));
            }

            String[] contentPieces = split(content, 120);
            for (String piece : contentPieces) {
                JSONObject delta = new JSONObject().put("content", piece);
                writeSse(output, chunk(id, model, created, delta, null));
            }

            JSONObject finishChunk = chunk(id, model, created, new JSONObject(), "stop");
            writeSse(output, finishChunk);
        }

        writeRaw(output, "data: [DONE]\n\n");
        output.flush();
    }

    private JSONObject completion(UpstreamResult upstream, String model, String content, JSONArray calls) throws JSONException {
        JSONObject message = new JSONObject().put("role", "assistant");
        if (content.isEmpty()) {
            message.put("content", JSONObject.NULL);
        } else {
            message.put("content", content);
        }
        String reasoning = upstream.reasoning.toString();
        if (!reasoning.isEmpty()) {
            message.put("reasoning_content", reasoning);
        }
        if (calls.length() > 0) {
            message.put("tool_calls", calls);
        }

        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", calls.length() > 0 ? "tool_calls" : "stop");

        JSONObject resp = new JSONObject();
        resp.put("id", upstream.id.isEmpty() ? "chatcmpl_" + shortId() : upstream.id);
        resp.put("object", "chat.completion");
        resp.put("created", upstream.created == 0 ? System.currentTimeMillis() / 1000 : upstream.created);
        resp.put("model", upstream.model.isEmpty() ? model : upstream.model);
        resp.put("choices", new JSONArray().put(choice));
        if (upstream.usage != null) {
            resp.put("usage", upstream.usage);
        }
        return resp;
    }

    private JSONObject chunk(String id, String model, long created, JSONObject delta, String finish) throws JSONException {
        JSONObject choice = new JSONObject().put("index", 0).put("delta", delta);
        if (finish != null) {
            choice.put("finish_reason", finish);
        }
        return new JSONObject()
                .put("id", id)
                .put("object", "chat.completion.chunk")
                .put("created", created)
                .put("model", model)
                .put("choices", new JSONArray().put(choice));
    }

    private JSONArray convertMessages(JSONArray messages) throws JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject src = messages.optJSONObject(i);
            if (src == null) continue;
            String role = src.optString("role", "user");
            if ("tool".equals(role) || "toolResult".equals(role)) {
                role = "user";
            } else if (!"system".equals(role) && !"user".equals(role) && !"assistant".equals(role)) {
                role = "user";
            }
            JSONObject converted = new JSONObject();
            converted.put("role", role);
            converted.put("content", text(src.opt("content")));
            result.put(converted);
        }
        return result;
    }

    private JSONObject models() throws JSONException {
        JSONArray data = new JSONArray();
        data.put(new JSONObject().put("id", "deepseek-v4-flash").put("object", "model").put("owned_by", "cnb"));
        data.put(new JSONObject().put("id", "deepseek-v4-pro").put("object", "model").put("owned_by", "cnb"));
        return new JSONObject().put("object", "list").put("data", data);
    }

    private String resolveModel(String requested) {
        if ("deepseek-v4-pro".equals(requested) || "deepseek-v4-flash".equals(requested)) {
            return requested;
        }
        return config.getModel();
    }

    private static Request readRequest(InputStream input) throws IOException {
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) throw new IOException("invalid request line");

        Request req = new Request(parts[0], parts[1]);
        String headerLine;
        while ((headerLine = readLine(input)) != null && !headerLine.isEmpty()) {
            int sep = headerLine.indexOf(':');
            if (sep > 0) {
                String name = headerLine.substring(0, sep).trim().toLowerCase(Locale.US);
                String val = headerLine.substring(sep + 1).trim();
                req.headers.put(name, val);
            }
        }

        int length;
        try {
            length = Integer.parseInt(req.headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException e) {
            throw new IOException("invalid content length");
        }

        if (length > 0x4000000) { // 64MB
            throw new IOException("request body too large");
        }

        if (length > 0) {
            byte[] bodyBytes = readExact(input, length);
            req.body = new String(bodyBytes, StandardCharsets.UTF_8);
        }
        return req;
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') out.write(b);
            if (out.size() > 16384) {
                throw new IOException("header line too long");
            }
        }
        if (b < 0 && out.size() == 0) return null;
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) {
                throw new IOException("unexpected end of request body");
            }
            offset += count;
        }
        return data;
    }

    private static void writeJson(OutputStream output, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        writeBytes(output, status, "application/json; charset=utf-8", bytes);
    }

    private static void writeBytes(OutputStream output, int status, String contentType, byte[] body) throws IOException {
        writeHead(output, status, contentType, body.length);
        output.write(body);
        output.flush();
    }

    private static void writeHead(OutputStream output, int status, String contentType, int length) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(" ").append(statusText(status)).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        if (length >= 0) {
            sb.append("Content-Length: ").append(length).append("\r\n");
        }
        sb.append("Cache-Control: no-cache\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type, X-ToolForge-FC-Mode\r\n\r\n");
        output.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeSse(OutputStream output, JSONObject value) throws IOException {
        String msg = "data: " + value.toString() + "\n\n";
        output.write(msg.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void writeRaw(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String readError(HttpURLConnection conn) {
        try {
            InputStream is = conn.getErrorStream();
            if (is == null) is = conn.getInputStream();
            if (is == null) return "";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
                if (baos.size() > 65536) break;
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            default: return "Error";
        }
    }

    private static String[] split(String value, int maxLen) {
        if (value == null || value.isEmpty()) return new String[0];
        int count = (value.length() + maxLen - 1) / maxLen;
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            int start = i * maxLen;
            int end = Math.min(value.length(), (i + 1) * maxLen);
            result[i] = value.substring(start, end);
        }
        return result;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String text(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) {
                    sb.append(part.optString("text"));
                }
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    private static int messageChars(JSONArray messages) {
        int total = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg != null) {
                total += text(msg.opt("content")).length();
            }
        }
        return total;
    }

    private static String callNames(JSONArray calls) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            JSONObject fn = call != null ? call.optJSONObject("function") : null;
            String name = fn != null ? fn.optString("name", "?") : "?";
            if (names.length() > 0) names.append(", ");
            names.append(name);
        }
        return names.toString();
    }

    private static String message(Exception e) {
        String msg = e.getMessage();
        return (msg != null && !msg.isEmpty()) ? msg : e.getClass().getSimpleName();
    }
}
