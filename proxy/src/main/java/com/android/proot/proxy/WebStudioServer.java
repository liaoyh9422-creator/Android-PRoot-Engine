package com.android.proot.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Embedded Web Studio Server for iFlow.
 * Hosts the Coomi Vue 3 Web frontend SPA, exposes REST diagnostics/files endpoints,
 * and handles WebSocket connections bridging between Coomi protocol and iFlow AI proxy.
 */
public final class WebStudioServer {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ProxyConfig config;
    private final ProxyLogListener logger;
    private final ExecutorService clientPool;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private int actualPort;

    public WebStudioServer(ProxyConfig config, ProxyLogListener logger) {
        this.config = config;
        this.logger = logger != null ? logger : (tag, msg) -> {};
        this.clientPool = Executors.newCachedThreadPool();
        this.actualPort = config.getWebPort();
    }

    public synchronized void start() throws IOException {
        if (running) return;

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.getListenHost(), config.getWebPort()), 64);
        actualPort = serverSocket.getLocalPort();
        running = true;

        acceptThread = new Thread(this::acceptLoop, "iflow-web-studio-accept");
        acceptThread.start();
        logger.onLog("WEB_STUDIO", "Web Studio Server running on http://" + config.getListenHost() + ":" + actualPort);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }
        clientPool.shutdownNow();
        logger.onLog("WEB_STUDIO", "Web Studio Server stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public int getActualPort() {
        return actualPort;
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                clientPool.execute(() -> handleClient(socket));
            } catch (IOException e) {
                if (!running) break;
                logger.onLog("WEB_STUDIO", "Accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(60000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());

            HttpRequest req = readHttpRequest(in);
            if (req == null) {
                socket.close();
                return;
            }

            // Check for WebSocket upgrade
            String upgrade = req.getHeader("upgrade");
            String connection = req.getHeader("connection");
            if ("websocket".equalsIgnoreCase(upgrade) && connection != null && connection.toLowerCase().contains("upgrade")) {
                handleWebSocket(socket, in, out, req);
                return;
            }

            // Handle HTTP requests
            if ("OPTIONS".equalsIgnoreCase(req.method)) {
                writeCorsHeaders(out, 204, "application/json", 0);
                out.flush();
                socket.close();
                return;
            }

            if (req.path.startsWith("/api/")) {
                handleRestApi(out, req);
            } else {
                handleStaticFile(out, req);
            }
            out.flush();
            socket.close();
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ── WebSocket Protocol (RFC 6455) ───────────────────────────────

    private void handleWebSocket(Socket socket, BufferedInputStream in, BufferedOutputStream out, HttpRequest req) throws Exception {
        String key = req.getHeader("sec-websocket-key");
        if (key == null || key.isEmpty()) {
            writeResponse(out, 400, "text/plain", "Missing Sec-WebSocket-Key".getBytes(StandardCharsets.UTF_8));
            socket.close();
            return;
        }

        String accept = computeWebSocketAccept(key);
        String handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n" +
                "\r\n";
        out.write(handshake.getBytes(StandardCharsets.UTF_8));
        out.flush();

        String sessionId = parseSessionId(req.path);
        logger.onLog("WS", "WebSocket connected for session: " + sessionId);

        // Initial session_loaded event
        JSONObject loaded = new JSONObject()
                .put("v", 1)
                .put("type", "event")
                .put("ts", System.currentTimeMillis())
                .put("payload", new JSONObject()
                        .put("event_type", "session_loaded")
                        .put("session_id", sessionId)
                        .put("cwd", "/root")
                        .put("usage", new JSONObject().put("input_tokens", 0).put("output_tokens", 0).put("total_tokens", 0)));
        sendWsTextFrame(out, loaded.toString());

        // Frame read loop
        while (running && !socket.isClosed()) {
            socket.setSoTimeout(0); // WebSocket persistent connection
            String message = readWsTextFrame(in);
            if (message == null) break;

            try {
                JSONObject envelope = new JSONObject(message);
                String type = envelope.optString("type");
                String id = envelope.optString("id", "");
                if ("command".equals(type)) {
                    JSONObject payload = envelope.optJSONObject("payload");
                    if (payload != null) {
                        String cmd = payload.optString("command");
                        if ("send_message".equals(cmd)) {
                            // Acknowledge command
                            JSONObject ack = new JSONObject()
                                    .put("v", 1)
                                    .put("type", "ack")
                                    .put("id", id)
                                    .put("ts", System.currentTimeMillis())
                                    .put("payload", new JSONObject().put("ok", true));
                            sendWsTextFrame(out, ack.toString());

                            String userText = payload.optString("text", "").trim();
                            dispatchAiTurn(out, userText);
                        } else if ("cancel".equals(cmd)) {
                            JSONObject cancelled = new JSONObject()
                                    .put("v", 1)
                                    .put("type", "event")
                                    .put("ts", System.currentTimeMillis())
                                    .put("payload", new JSONObject().put("event_type", "agent_cancelled"));
                            sendWsTextFrame(out, cancelled.toString());
                        }
                    }
                }
            } catch (Exception e) {
                logger.onLog("WS", "Error processing command: " + e.getMessage());
            }
        }
        try { socket.close(); } catch (Exception ignored) {}
    }

    private void dispatchAiTurn(BufferedOutputStream out, String prompt) {
        clientPool.execute(() -> {
            try {
                // Call local proxy on 7864
                int proxyPort = config.getPort();
                URL url = new URL("http://127.0.0.1:" + proxyPort + "/v1/chat/completions");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(90000);

                JSONObject body = new JSONObject();
                body.put("model", config.getModel());
                body.put("stream", true);
                body.put("enable_thinking", config.isEnableThinking());
                body.put("reasoning_effort", config.getReasoningEffort());

                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "user").put("content", prompt));
                body.put("messages", messages);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if ("[DONE]".equals(data)) {
                                    JSONObject turnEnd = new JSONObject()
                                            .put("v", 1)
                                            .put("type", "event")
                                            .put("ts", System.currentTimeMillis())
                                            .put("payload", new JSONObject().put("event_type", "turn_end"));
                                    sendWsTextFrame(out, turnEnd.toString());
                                    break;
                                }
                                try {
                                    JSONObject chunk = new JSONObject(data);
                                    JSONArray choices = chunk.optJSONArray("choices");
                                    if (choices != null && choices.length() > 0) {
                                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                        if (delta != null) {
                                            String reasoning = delta.optString("reasoning_content", "");
                                            if (!reasoning.isEmpty()) {
                                                JSONObject rEvent = new JSONObject()
                                                        .put("v", 1)
                                                        .put("type", "event")
                                                        .put("ts", System.currentTimeMillis())
                                                        .put("payload", new JSONObject()
                                                                .put("event_type", "reasoning_chunk")
                                                                .put("content", reasoning));
                                                sendWsTextFrame(out, rEvent.toString());
                                            }

                                            String content = delta.optString("content", "");
                                            if (!content.isEmpty()) {
                                                JSONObject tEvent = new JSONObject()
                                                        .put("v", 1)
                                                        .put("type", "event")
                                                        .put("ts", System.currentTimeMillis())
                                                        .put("payload", new JSONObject()
                                                                .put("event_type", "text_chunk")
                                                                .put("content", content));
                                                sendWsTextFrame(out, tEvent.toString());
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                } else {
                    String err = "Upstream returned HTTP " + code;
                    JSONObject errEvent = new JSONObject()
                            .put("v", 1)
                            .put("type", "event")
                            .put("ts", System.currentTimeMillis())
                            .put("payload", new JSONObject()
                                    .put("event_type", "agent_error")
                                    .put("message", err)
                                    .put("is_fatal", false));
                    sendWsTextFrame(out, errEvent.toString());
                    JSONObject turnEnd = new JSONObject()
                            .put("v", 1)
                            .put("type", "event")
                            .put("ts", System.currentTimeMillis())
                            .put("payload", new JSONObject().put("event_type", "turn_end"));
                    sendWsTextFrame(out, turnEnd.toString());
                }
            } catch (Exception e) {
                logger.onLog("WS", "Error in AI turn: " + e.getMessage());
                try {
                    JSONObject errEvent = new JSONObject()
                            .put("v", 1)
                            .put("type", "event")
                            .put("ts", System.currentTimeMillis())
                            .put("payload", new JSONObject()
                                    .put("event_type", "agent_error")
                                    .put("message", "执行错误: " + e.getMessage())
                                    .put("is_fatal", false));
                    sendWsTextFrame(out, errEvent.toString());
                    JSONObject turnEnd = new JSONObject()
                            .put("v", 1)
                            .put("type", "event")
                            .put("ts", System.currentTimeMillis())
                            .put("payload", new JSONObject().put("event_type", "turn_end"));
                    sendWsTextFrame(out, turnEnd.toString());
                } catch (Exception ignored) {}
            }
        });
    }

    private static String parseSessionId(String path) {
        if (path != null && path.startsWith("/ws/session/")) {
            String sub = path.substring("/ws/session/".length());
            int q = sub.indexOf('?');
            return q >= 0 ? sub.substring(0, q) : sub;
        }
        return "default";
    }

    public static synchronized void sendWsTextFrame(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int len = payload.length;

        ByteArrayOutputStream buf = new ByteArrayOutputStream(len + 10);
        buf.write(0x81); // FIN + Text opcode

        if (len <= 125) {
            buf.write(len);
        } else if (len <= 65535) {
            buf.write(126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(127);
            for (int i = 7; i >= 0; i--) {
                buf.write((int) ((len >> (i * 8)) & 0xFF));
            }
        }
        buf.write(payload);
        out.write(buf.toByteArray());
        out.flush();
    }

    private static String readWsTextFrame(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 == -1) return null;
        int b2 = in.read();
        if (b2 == -1) return null;

        int opcode = b1 & 0x0F;
        if (opcode == 0x8) return null; // Close frame

        boolean masked = (b2 & 0x80) != 0;
        int payloadLen = b2 & 0x7F;

        if (payloadLen == 126) {
            int byte1 = in.read();
            int byte2 = in.read();
            if (byte1 == -1 || byte2 == -1) return null;
            payloadLen = (byte1 << 8) | byte2;
        } else if (payloadLen == 127) {
            long total = 0;
            for (int i = 0; i < 8; i++) {
                int b = in.read();
                if (b == -1) return null;
                total = (total << 8) | (b & 0xFF);
            }
            if (total > 16 * 1024 * 1024) throw new IOException("Frame too large");
            payloadLen = (int) total;
        }

        byte[] mask = new byte[4];
        if (masked) {
            readFully(in, mask, 0, 4);
        }

        byte[] data = new byte[payloadLen];
        readFully(in, data, 0, payloadLen);

        if (masked) {
            for (int i = 0; i < payloadLen; i++) {
                data[i] = (byte) (data[i] ^ mask[i % 4]);
            }
        }

        return new String(data, StandardCharsets.UTF_8);
    }

    // ── REST APIs ──────────────────────────────────────────────────

    private void handleRestApi(BufferedOutputStream out, HttpRequest req) throws Exception {
        String path = req.path;

        if ("/api/runtime/health".equals(path)) {
            JSONObject health = new JSONObject()
                    .put("status", "ok")
                    .put("version", "1.4.6-iflow")
                    .put("runtime", "proot_linux")
                    .put("cwd", "/root")
                    .put("engine", new JSONObject()
                            .put("initialized", true)
                            .put("llm", config.getModel())
                            .put("tools", 12));
            writeJson(out, 200, health);
            return;
        }

        if ("/api/runtime/doctor".equals(path)) {
            JSONObject doc = new JSONObject()
                    .put("ok", true)
                    .put("backend", "proot_linux")
                    .put("node", "v22.22.1")
                    .put("iflow", "ready");
            writeJson(out, 200, doc);
            return;
        }

        if ("/api/runtime/port".equals(path)) {
            JSONObject portJson = new JSONObject().put("port", actualPort);
            writeJson(out, 200, portJson);
            return;
        }

        if ("/api/providers".equals(path)) {
            JSONArray providers = new JSONArray();
            JSONObject iflow = new JSONObject()
                    .put("id", "iflow")
                    .put("name", "iFlow Engine (Tencent CNB)")
                    .put("protocol", "openai")
                    .put("base_url", "http://127.0.0.1:" + config.getPort() + "/v1")
                    .put("api_key", "cnb-free")
                    .put("models", new JSONArray().put("deepseek-v4-flash").put("deepseek-r1").put("deepseek-v3"))
                    .put("active_model", config.getModel())
                    .put("enabled", true);
            providers.put(iflow);

            JSONObject res = new JSONObject()
                    .put("providers", providers)
                    .put("active", "iflow");
            writeJson(out, 200, res);
            return;
        }

        if ("/api/settings/connection".equals(path)) {
            JSONObject conn = new JSONObject()
                    .put("providerRetryCount", 3)
                    .put("wsRetryCount", 10)
                    .put("reconnectInitialDelayMs", 500)
                    .put("reconnectMaxDelayMs", 10000);
            writeJson(out, 200, conn);
            return;
        }

        if ("/api/tasks".equals(path)) {
            JSONObject tasks = new JSONObject()
                    .put("tasks", new JSONArray())
                    .put("running_count", 0)
                    .put("concurrency_limit", 1);
            writeJson(out, 200, tasks);
            return;
        }

        if ("/api/sessions".equals(path)) {
            writeJson(out, 200, new JSONArray());
            return;
        }

        if ("/api/fs/list".equals(path)) {
            String dirPath = req.getQueryParam("path");
            if (dirPath == null || dirPath.isEmpty()) dirPath = "/root";
            File dir = new File(dirPath);
            JSONArray entries = new JSONArray();
            if (dir.exists() && dir.isDirectory()) {
                File[] list = dir.listFiles();
                if (list != null) {
                    for (File f : list) {
                        JSONObject entry = new JSONObject()
                                .put("name", f.getName())
                                .put("is_dir", f.isDirectory())
                                .put("size", f.length())
                                .put("modified", f.lastModified() / 1000);
                        entries.put(entry);
                    }
                }
            }
            JSONObject res = new JSONObject()
                    .put("path", dirPath)
                    .put("entries", entries);
            writeJson(out, 200, res);
            return;
        }

        if ("/api/fs/raw".equals(path)) {
            String filePath = req.getQueryParam("path");
            if (filePath != null) {
                File file = new File(filePath);
                if (file.exists() && file.isFile()) {
                    byte[] bytes = readFileBytes(file);
                    writeResponse(out, 200, "text/plain; charset=utf-8", bytes);
                    return;
                }
            }
            writeResponse(out, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        // Default empty object for other settings
        writeJson(out, 200, new JSONObject());
    }

    // ── Static Files (Vue 3 SPA) ───────────────────────────────────

    private void handleStaticFile(BufferedOutputStream out, HttpRequest req) throws Exception {
        File webRoot = config.getWebRoot();
        if (webRoot == null || !webRoot.exists()) {
            String html = "<html><body style='background:#0d1117;color:#c9d1d9;font-family:sans-serif;padding:30px;'>" +
                    "<h2>iFlow Web Studio</h2><p>Web 资源尚未解压，请稍候刷新。</p></body></html>";
            writeResponse(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
            return;
        }

        String path = req.path;
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }

        File target = new File(webRoot, path.startsWith("/") ? path.substring(1) : path);
        if (target.exists() && target.isFile()) {
            writeResponse(out, 200, getMimeType(target.getName()), readFileBytes(target));
            return;
        }

        // SPA History API fallback -> /index.html
        File indexFile = new File(webRoot, "index.html");
        if (indexFile.exists() && indexFile.isFile()) {
            writeResponse(out, 200, "text/html; charset=utf-8", readFileBytes(indexFile));
        } else {
            writeResponse(out, 404, "text/plain", "404 Not Found".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String getMimeType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    // ── Asset Deployment Utility ───────────────────────────────────

    public static synchronized boolean deployWebZip(InputStream zipStream, File targetDir) {
        if (zipStream == null || targetDir == null) return false;
        try {
            targetDir.mkdirs();
            try (ZipInputStream zis = new ZipInputStream(zipStream)) {
                ZipEntry entry;
                byte[] buf = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    File file = new File(targetDir, entry.getName());
                    if (entry.isDirectory()) {
                        file.mkdirs();
                    } else {
                        File parent = file.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            int len;
                            while ((len = zis.read(buf)) > 0) {
                                fos.write(buf, 0, len);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }
            return new File(targetDir, "index.html").exists();
        } catch (Exception e) {
            return false;
        }
    }

    // ── Helper Utilities ───────────────────────────────────────────

    public static String computeWebSocketAccept(String key) {
        try {
            String input = key.trim() + WS_GUID;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return encodeBase64(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String encodeBase64(byte[] data) {
        try {
            Class<?> androidBase64 = Class.forName("android.util.Base64");
            java.lang.reflect.Method method = androidBase64.getMethod("encodeToString", byte[].class, int.class);
            return ((String) method.invoke(null, data, 2)).trim(); // 2 = NO_WRAP
        } catch (Throwable t) {
            return java.util.Base64.getEncoder().encodeToString(data).trim();
        }
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int read = in.read(buf, off + total, len - total);
            if (read == -1) throw new IOException("Unexpected EOF");
            total += read;
        }
    }

    private static byte[] readFileBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void writeCorsHeaders(OutputStream out, int status, String mime, int len) throws IOException {
        String res = "HTTP/1.1 " + status + " OK\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "Content-Length: " + len + "\r\n" +
                "\r\n";
        out.write(res.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeResponse(OutputStream out, int status, String mime, byte[] data) throws IOException {
        writeCorsHeaders(out, status, mime, data.length);
        out.write(data);
        out.flush();
    }

    private static void writeJson(OutputStream out, int status, Object obj) throws IOException {
        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        writeResponse(out, status, "application/json; charset=utf-8", bytes);
    }

    private static HttpRequest readHttpRequest(InputStream in) throws IOException {
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        List<String> headerLines = new ArrayList<>();
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n' && prev == '\r') {
                byte[] raw = lineBuf.toByteArray();
                String line = new String(raw, 0, raw.length - 1, StandardCharsets.UTF_8).trim();
                if (line.isEmpty()) break;
                headerLines.add(line);
                lineBuf.reset();
            } else {
                lineBuf.write(b);
            }
            prev = b;
        }

        if (headerLines.isEmpty()) return null;
        String[] parts = headerLines.get(0).split(" ");
        if (parts.length < 2) return null;

        String method = parts[0];
        String rawPath = parts[1];
        HttpRequest req = new HttpRequest(method, rawPath);

        for (int i = 1; i < headerLines.size(); i++) {
            String l = headerLines.get(i);
            int idx = l.indexOf(':');
            if (idx > 0) {
                req.headers.put(l.substring(0, idx).trim().toLowerCase(), l.substring(idx + 1).trim());
            }
        }
        return req;
    }

    public static final class HttpRequest {
        public final String method;
        public final String rawPath;
        public final String path;
        public final Map<String, String> headers = new HashMap<>();

        HttpRequest(String method, String rawPath) {
            this.method = method;
            this.rawPath = rawPath;
            int q = rawPath.indexOf('?');
            this.path = q >= 0 ? rawPath.substring(0, q) : rawPath;
        }

        public String getHeader(String name) {
            return headers.get(name != null ? name.toLowerCase() : "");
        }

        public String getQueryParam(String name) {
            int q = rawPath.indexOf('?');
            if (q < 0) return null;
            String query = rawPath.substring(q + 1);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    try {
                        return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    } catch (Exception e) {
                        return pair.substring(eq + 1);
                    }
                }
            }
            return null;
        }
    }
}
