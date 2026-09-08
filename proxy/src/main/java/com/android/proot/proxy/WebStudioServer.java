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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    public interface ProviderChangeListener {
        void onProviderChanged(String id, String baseUrl, String apiKey, String model);
    }

    public static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if ("cnb-free".equalsIgnoreCase(key)) return "cnb-free";
        if (key.length() <= 8) return "••••" + key;
        return "••••" + key.substring(key.length() - 4);
    }

    public static class ProviderData {
        public String id = "";
        public String name = "";
        public String apiKey = "";
        public String apiKeyMasked = "";
        public boolean hasKey = true;
        public final List<String> models = new ArrayList<>();
        public String baseUrl = "";
        public String type = "openai_compatible";
        public String toolProtocol = "openai_compatible";
        public String model = "deepseek-v4-flash";
        public long contextWindow = 256000;
        public boolean supportsWebSearch = false;
        public boolean supportsVision = false;
        public boolean builtin = true;
        public JSONObject extra = new JSONObject();

        public JSONObject toJson(boolean isActive) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("name", (name != null && !name.isEmpty()) ? name : id);
                obj.put("apiKeyMasked", (apiKeyMasked != null && !apiKeyMasked.isEmpty()) ? apiKeyMasked : maskKey(apiKey));
                obj.put("hasKey", hasKey || (apiKey != null && !apiKey.isEmpty()));
                JSONArray mArr = new JSONArray();
                for (String m : models) mArr.put(m);
                obj.put("models", mArr);
                obj.put("baseUrl", baseUrl != null ? baseUrl : "");
                obj.put("base_url", baseUrl != null ? baseUrl : "");
                obj.put("type", type != null ? type : "openai_compatible");
                obj.put("toolProtocol", toolProtocol != null ? toolProtocol : (type != null ? type : "openai_compatible"));
                obj.put("contextWindow", contextWindow);
                obj.put("model", model != null ? model : (!models.isEmpty() ? models.get(0) : ""));
                obj.put("active_model", model != null ? model : (!models.isEmpty() ? models.get(0) : ""));
                obj.put("fastModel", JSONObject.NULL);
                obj.put("supportsWebSearch", supportsWebSearch);
                obj.put("supportsVision", supportsVision);
                obj.put("modelDescriptions", extra.optJSONObject("modelDescriptions") != null ? extra.optJSONObject("modelDescriptions") : new JSONObject());
                obj.put("modelParameters", extra.optJSONObject("modelParameters") != null ? extra.optJSONObject("modelParameters") : new JSONObject());
                obj.put("modelContextWindows", extra.optJSONObject("modelContextWindows") != null ? extra.optJSONObject("modelContextWindows") : new JSONObject());
                obj.put("capabilityOverrides", extra.optJSONObject("capabilityOverrides") != null ? extra.optJSONObject("capabilityOverrides") : new JSONObject());
                obj.put("active", isActive);
                obj.put("enabled", true);
                obj.put("builtin", builtin);
            } catch (Exception ignored) {}
            return obj;
        }
    }

    private final ProxyConfig config;
    private final ProxyLogListener logger;
    private final ExecutorService clientPool;
    private final Map<String, ProviderData> providers = new LinkedHashMap<>();
    private volatile String activeProviderId = "deepseek";
    private ProviderChangeListener providerChangeListener;
    private final Map<String, List<JSONObject>> sessionHistories = new ConcurrentHashMap<>();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private int actualPort;

    public WebStudioServer(ProxyConfig config, ProxyLogListener logger) {
        this.config = config;
        this.logger = logger != null ? logger : (tag, msg) -> {};
        this.clientPool = Executors.newCachedThreadPool();
        this.actualPort = config.getWebPort();
        initProviders();
    }

    public void setProviderChangeListener(ProviderChangeListener listener) {
        this.providerChangeListener = listener;
    }

    public synchronized String getActiveProviderId() {
        return activeProviderId;
    }

    public synchronized String getActiveModel() {
        ProviderData p = providers.get(activeProviderId);
        if (p != null && p.model != null && !p.model.isEmpty()) {
            return p.model;
        }
        return config.getModel();
    }

    public synchronized ProviderData getActiveProvider() {
        ProviderData data = providers.get(activeProviderId);
        if (data != null) return data;
        if (!providers.isEmpty()) {
            Map.Entry<String, ProviderData> entry = providers.entrySet().iterator().next();
            activeProviderId = entry.getKey();
            return entry.getValue();
        }
        initProviders();
        return providers.get("deepseek");
    }

    public synchronized Map<String, ProviderData> getProviders() {
        return new LinkedHashMap<>(providers);
    }

    private File getProvidersConfigFile() {
        if (config.getProvidersFile() != null) {
            return config.getProvidersFile();
        }
        File webRoot = config.getWebRoot();
        if (webRoot != null && webRoot.getParentFile() != null) {
            return new File(webRoot.getParentFile(), "iflow-providers.json");
        }
        return null;
    }

    private synchronized void initProviders() {
        loadProvidersFromDisk();
        if (providers.isEmpty()) {
            String defaultKey = config.getApiKey().isEmpty() ? "cnb-free" : config.getApiKey();
            String defaultModel = config.getModel().isEmpty() ? "deepseek-v4-flash" : config.getModel();
            String defaultBaseUrl = "http://127.0.0.1:" + config.getPort() + "/v1";

            ProviderData deepseek = new ProviderData();
            deepseek.id = "deepseek";
            deepseek.name = "DeepSeek (iFlow CNB)";
            deepseek.apiKey = defaultKey;
            deepseek.apiKeyMasked = "cnb-free";
            deepseek.hasKey = true;
            deepseek.models.add("deepseek-v4-flash");
            deepseek.models.add("deepseek-r1");
            deepseek.models.add("deepseek-v3");
            deepseek.model = defaultModel;
            deepseek.baseUrl = defaultBaseUrl;
            deepseek.type = "openai_compatible";
            deepseek.toolProtocol = "openai_compatible";
            deepseek.builtin = true;
            providers.put(deepseek.id, deepseek);

            ProviderData iflow = new ProviderData();
            iflow.id = "iflow";
            iflow.name = "iFlow Engine";
            iflow.apiKey = defaultKey;
            iflow.apiKeyMasked = "cnb-free";
            iflow.hasKey = true;
            iflow.models.add("deepseek-v4-flash");
            iflow.models.add("deepseek-r1");
            iflow.models.add("deepseek-v3");
            iflow.model = defaultModel;
            iflow.baseUrl = defaultBaseUrl;
            iflow.type = "openai_compatible";
            iflow.toolProtocol = "openai_compatible";
            iflow.builtin = false;
            providers.put(iflow.id, iflow);

            activeProviderId = "deepseek";
            saveProvidersToDisk();
        }
    }

    private synchronized void loadProvidersFromDisk() {
        File f = getProvidersConfigFile();
        if (f == null || !f.exists()) return;
        try {
            byte[] bytes = readFileBytes(f);
            if (bytes.length == 0) return;
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("providers");
            if (arr != null) {
                providers.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    ProviderData p = new ProviderData();
                    p.id = obj.optString("id");
                    p.name = obj.optString("name", p.id);
                    p.apiKey = obj.optString("apiKey", obj.optString("api_key", ""));
                    p.apiKeyMasked = obj.optString("apiKeyMasked", maskKey(p.apiKey));
                    p.hasKey = obj.optBoolean("hasKey", !p.apiKey.isEmpty());
                    p.baseUrl = obj.optString("baseUrl", obj.optString("base_url", ""));
                    p.type = obj.optString("type", "openai_compatible");
                    p.toolProtocol = obj.optString("toolProtocol", "openai_compatible");
                    p.model = obj.optString("model", obj.optString("active_model", ""));
                    p.contextWindow = obj.optLong("contextWindow", 256000);
                    p.supportsWebSearch = obj.optBoolean("supportsWebSearch", false);
                    p.supportsVision = obj.optBoolean("supportsVision", false);
                    p.builtin = obj.optBoolean("builtin", true);
                    JSONArray mArr = obj.optJSONArray("models");
                    if (mArr != null) {
                        for (int j = 0; j < mArr.length(); j++) {
                            String m = mArr.optString(j);
                            if (!m.isEmpty()) p.models.add(m);
                        }
                    }
                    if (p.models.isEmpty() && !p.model.isEmpty()) {
                        p.models.add(p.model);
                    }
                    if (!p.id.isEmpty()) {
                        providers.put(p.id, p);
                    }
                }
                String act = root.optString("active");
                if (!act.isEmpty() && providers.containsKey(act)) {
                    activeProviderId = act;
                }
            }
        } catch (Exception e) {
            logger.onLog("WEB_STUDIO", "Failed to load providers from disk: " + e.getMessage());
        }
    }

    private synchronized void saveProvidersToDisk() {
        File f = getProvidersConfigFile();
        if (f == null) return;
        try {
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (ProviderData p : providers.values()) {
                arr.put(p.toJson(p.id.equals(activeProviderId)));
            }
            root.put("providers", arr);
            root.put("active", activeProviderId);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            logger.onLog("WEB_STUDIO", "Failed to save providers: " + e.getMessage());
        }
    }

    private void notifyProviderChanged(ProviderData data) {
        if (data == null) return;
        ProviderChangeListener listener = this.providerChangeListener;
        if (listener != null) {
            try {
                listener.onProviderChanged(data.id, data.baseUrl, data.apiKey, data.model);
            } catch (Exception e) {
                logger.onLog("WEB_STUDIO", "Error notifying provider change: " + e.getMessage());
            }
        }
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
                            dispatchAiTurn(out, sessionId, userText);
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

    private void dispatchAiTurn(BufferedOutputStream out, String sessionId, String prompt) {
        clientPool.execute(() -> {
            try {
                ProviderData provider = getActiveProvider();
                String baseUrl = (provider != null && provider.baseUrl != null && !provider.baseUrl.trim().isEmpty())
                        ? provider.baseUrl.trim()
                        : ("http://127.0.0.1:" + config.getPort() + "/v1");
                String apiKey = (provider != null && provider.apiKey != null && !provider.apiKey.trim().isEmpty())
                        ? provider.apiKey.trim()
                        : config.getApiKey();
                String model = (provider != null && provider.model != null && !provider.model.trim().isEmpty())
                        ? provider.model.trim()
                        : config.getModel();

                String endpoint = baseUrl;
                while (endpoint.endsWith("/")) {
                    endpoint = endpoint.substring(0, endpoint.length() - 1);
                }
                if (!endpoint.endsWith("/chat/completions")) {
                    endpoint += "/chat/completions";
                }

                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                if (apiKey != null && !apiKey.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);

                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("stream", true);
                body.put("enable_thinking", config.isEnableThinking());
                body.put("reasoning_effort", config.getReasoningEffort());

                List<JSONObject> history = sessionHistories.computeIfAbsent(sessionId != null ? sessionId : "default", k -> new ArrayList<>());
                JSONArray messages = new JSONArray();
                synchronized (history) {
                    for (JSONObject m : history) messages.put(m);
                }
                JSONObject userMsg = new JSONObject().put("role", "user").put("content", prompt);
                messages.put(userMsg);
                body.put("messages", messages);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    StringBuilder assistantText = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if ("[DONE]".equals(data)) {
                                    synchronized (history) {
                                        history.add(userMsg);
                                        history.add(new JSONObject().put("role", "assistant").put("content", assistantText.toString()));
                                        while (history.size() > 20) history.remove(0);
                                    }
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
                                                assistantText.append(content);
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
        String method = req.method != null ? req.method.toUpperCase() : "GET";

        if ("/api/runtime/health".equals(path)) {
            JSONObject health = new JSONObject()
                    .put("status", "ok")
                    .put("version", "1.4.6-iflow")
                    .put("runtime", "proot_linux")
                    .put("cwd", "/root")
                    .put("engine", new JSONObject()
                            .put("initialized", true)
                            .put("llm", getActiveModel())
                            .put("tools", 12));
            writeJson(out, 200, health);
            return;
        }

        if ("/api/runtime/doctor".equals(path)) {
            JSONObject doc = new JSONObject()
                    .put("ok", true)
                    .put("runtime", new JSONObject()
                            .put("backend", "proot_linux")
                            .put("status", "ready")
                            .put("active_version", "1.4.6-iflow"))
                    .put("facts", new JSONObject()
                            .put("backend", "proot_linux")
                            .put("sh", true)
                            .put("python", "3.11.2")
                            .put("git", "2.39.5")
                            .put("node", "v22.22.1")
                            .put("curl", "7.88.1")
                            .put("workspace", true)
                            .put("tmp_writable", true))
                    .put("termux_available", false);
            writeJson(out, 200, doc);
            return;
        }

        if ("/api/runtime/v2".equals(path)) {
            JSONObject v2 = new JSONObject()
                    .put("runtime", new JSONObject()
                            .put("backend", "proot_linux")
                            .put("status", "installed"))
                    .put("manifest_available", true);
            writeJson(out, 200, v2);
            return;
        }

        if ("/api/runtime/port".equals(path)) {
            JSONObject portJson = new JSONObject().put("port", actualPort);
            writeJson(out, 200, portJson);
            return;
        }

        // ── Provider Routes ───────────────────────────────────────
        if ("/api/providers".equals(path)) {
            if ("POST".equals(method)) {
                JSONObject input = req.getJsonBody();
                String id = input.optString("id", "").trim();
                if (id.isEmpty()) {
                    writeResponse(out, 400, "application/json", "{\"error\":\"provider id is required\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                ProviderData p;
                synchronized (this) {
                    p = providers.computeIfAbsent(id, k -> new ProviderData());
                    p.id = id;
                    if (input.has("name")) p.name = input.optString("name", id);
                    if (input.has("baseUrl")) p.baseUrl = input.optString("baseUrl");
                    if (p.baseUrl == null || p.baseUrl.isEmpty()) {
                        p.baseUrl = input.optString("base_url", "http://127.0.0.1:" + config.getPort() + "/v1");
                    }
                    String key = input.optString("apiKey", "").trim();
                    if (!key.isEmpty()) {
                        p.apiKey = key;
                        p.apiKeyMasked = maskKey(key);
                        p.hasKey = true;
                    } else if (p.apiKey == null || p.apiKey.isEmpty()) {
                        p.apiKey = config.getApiKey().isEmpty() ? "cnb-free" : config.getApiKey();
                        p.apiKeyMasked = maskKey(p.apiKey);
                        p.hasKey = true;
                    }
                    if (input.has("type")) p.type = input.optString("type");
                    if (input.has("toolProtocol")) p.toolProtocol = input.optString("toolProtocol");
                    if (input.has("contextWindow")) p.contextWindow = input.optLong("contextWindow", 256000);
                    if (input.has("supportsWebSearch")) p.supportsWebSearch = input.optBoolean("supportsWebSearch", false);
                    if (input.has("supportsVision")) p.supportsVision = input.optBoolean("supportsVision", false);

                    JSONArray modelsArr = input.optJSONArray("models");
                    if (modelsArr != null && modelsArr.length() > 0) {
                        p.models.clear();
                        for (int i = 0; i < modelsArr.length(); i++) {
                            String m = modelsArr.optString(i, "").trim();
                            if (!m.isEmpty() && !p.models.contains(m)) p.models.add(m);
                        }
                    }
                    if (input.has("model") && !input.optString("model").isEmpty()) {
                        p.model = input.optString("model").trim();
                        if (!p.models.contains(p.model)) p.models.add(0, p.model);
                    } else if (p.model == null || p.model.isEmpty()) {
                        p.model = !p.models.isEmpty() ? p.models.get(0) : "deepseek-v4-flash";
                    }
                    if (p.models.isEmpty()) {
                        p.models.add(p.model);
                    }

                    if (input.optBoolean("activate", false)) {
                        activeProviderId = id;
                        notifyProviderChanged(p);
                    }
                    saveProvidersToDisk();
                }
                writeJson(out, 200, new JSONObject().put("ok", true).put("provider", p.toJson(id.equals(activeProviderId))));
                return;
            } else {
                JSONArray arr = new JSONArray();
                synchronized (this) {
                    for (ProviderData p : providers.values()) {
                        arr.put(p.toJson(p.id.equals(activeProviderId)));
                    }
                }
                JSONObject res = new JSONObject()
                        .put("providers", arr)
                        .put("active", activeProviderId);
                writeJson(out, 200, res);
                return;
            }
        }

        if (path.startsWith("/api/providers/")) {
            String sub = path.substring("/api/providers/".length());
            if (sub.endsWith("/activate") && "POST".equals(method)) {
                String id = URLDecoder.decode(sub.substring(0, sub.length() - "/activate".length()), StandardCharsets.UTF_8.name());
                synchronized (this) {
                    ProviderData p = providers.get(id);
                    if (p != null) {
                        activeProviderId = id;
                        notifyProviderChanged(p);
                        saveProvidersToDisk();
                        writeJson(out, 200, new JSONObject().put("ok", true));
                    } else {
                        writeResponse(out, 404, "application/json", "{\"error\":\"provider not found\"}".getBytes(StandardCharsets.UTF_8));
                    }
                }
                return;
            }

            if (sub.endsWith("/select-model") && "POST".equals(method)) {
                String id = URLDecoder.decode(sub.substring(0, sub.length() - "/select-model".length()), StandardCharsets.UTF_8.name());
                JSONObject input = req.getJsonBody();
                String model = input.optString("model", "").trim();
                synchronized (this) {
                    ProviderData p = providers.get(id);
                    if (p != null && !model.isEmpty()) {
                        p.model = model;
                        if (!p.models.contains(model)) p.models.add(0, model);
                        if (id.equals(activeProviderId)) {
                            notifyProviderChanged(p);
                        }
                        saveProvidersToDisk();
                    }
                }
                writeJson(out, 200, new JSONObject().put("ok", true));
                return;
            }

            if (sub.endsWith("/discover-models") && "POST".equals(method)) {
                String id = URLDecoder.decode(sub.substring(0, sub.length() - "/discover-models".length()), StandardCharsets.UTF_8.name());
                JSONObject input = req.getJsonBody();
                boolean persist = input.optBoolean("persist", false);
                List<String> list = new ArrayList<>();
                synchronized (this) {
                    ProviderData p = providers.get(id);
                    if (p != null) {
                        list = discoverModelsRemote(p.baseUrl, p.apiKey);
                        if (list.isEmpty()) {
                            list = new ArrayList<>(p.models);
                        }
                        if (list.isEmpty()) {
                            list.add("deepseek-v4-flash");
                            list.add("deepseek-r1");
                            list.add("deepseek-v3");
                        }
                        if (persist) {
                            p.models.clear();
                            p.models.addAll(list);
                            saveProvidersToDisk();
                        }
                    } else {
                        list.add("deepseek-v4-flash");
                        list.add("deepseek-r1");
                        list.add("deepseek-v3");
                    }
                }
                JSONArray mArr = new JSONArray();
                for (String m : list) mArr.put(m);
                writeJson(out, 200, new JSONObject().put("models", mArr));
                return;
            }

            if (sub.endsWith("/reveal") && "POST".equals(method)) {
                String id = URLDecoder.decode(sub.substring(0, sub.length() - "/reveal".length()), StandardCharsets.UTF_8.name());
                ProviderData p;
                synchronized (this) {
                    p = providers.get(id);
                }
                writeJson(out, 200, new JSONObject().put("apiKey", p != null && p.apiKey != null ? p.apiKey : ""));
                return;
            }

            if (sub.endsWith("/copy") && "POST".equals(method)) {
                String id = URLDecoder.decode(sub.substring(0, sub.length() - "/copy".length()), StandardCharsets.UTF_8.name());
                String newId = id + "-copy";
                synchronized (this) {
                    ProviderData src = providers.get(id);
                    if (src != null) {
                        ProviderData copy = new ProviderData();
                        copy.id = newId;
                        copy.name = src.name + " (Copy)";
                        copy.apiKey = src.apiKey;
                        copy.apiKeyMasked = src.apiKeyMasked;
                        copy.hasKey = src.hasKey;
                        copy.models.addAll(src.models);
                        copy.model = src.model;
                        copy.baseUrl = src.baseUrl;
                        copy.type = src.type;
                        copy.toolProtocol = src.toolProtocol;
                        copy.contextWindow = src.contextWindow;
                        copy.builtin = false;
                        providers.put(newId, copy);
                        saveProvidersToDisk();
                        writeJson(out, 200, new JSONObject().put("id", newId));
                    } else {
                        writeResponse(out, 404, "application/json", "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
                    }
                }
                return;
            }

            if ("DELETE".equals(method)) {
                String id = URLDecoder.decode(sub, StandardCharsets.UTF_8.name());
                synchronized (this) {
                    providers.remove(id);
                    if (id.equals(activeProviderId) && !providers.isEmpty()) {
                        activeProviderId = providers.keySet().iterator().next();
                        notifyProviderChanged(providers.get(activeProviderId));
                    }
                    saveProvidersToDisk();
                }
                writeJson(out, 200, new JSONObject().put("ok", true));
                return;
            }
        }

        if ("/api/settings/connection".equals(path)) {
            if ("PUT".equals(method)) {
                writeJson(out, 200, req.getJsonBody());
            } else {
                JSONObject conn = new JSONObject()
                        .put("providerRetryCount", 3)
                        .put("wsRetryCount", 10)
                        .put("reconnectInitialDelayMs", 500)
                        .put("reconnectMaxDelayMs", 10000)
                        .put("maxConcurrentTasks", 5);
                writeJson(out, 200, conn);
            }
            return;
        }

        if ("/api/settings/subagents".equals(path)) {
            if ("PUT".equals(method)) {
                writeJson(out, 200, req.getJsonBody());
            } else {
                JSONObject subagents = new JSONObject()
                        .put("agents", new JSONArray())
                        .put("fallbackId", JSONObject.NULL)
                        .put("maxAgents", 20);
                writeJson(out, 200, subagents);
            }
            return;
        }

        if ("/api/settings/collaboration".equals(path)) {
            if ("PUT".equals(method)) {
                writeJson(out, 200, req.getJsonBody());
            } else {
                JSONObject collab = new JSONObject()
                        .put("coderSelector", "")
                        .put("reviewerSelector", "")
                        .put("coderPrompt", "")
                        .put("reviewerPrompt", "")
                        .put("maxCycles", 2)
                        .put("reviewTests", true);
                writeJson(out, 200, collab);
            }
            return;
        }

        if ("/api/runtime/global-memory".equals(path)) {
            writeJson(out, 200, new JSONObject().put("enabled", true));
            return;
        }

        if ("/api/runtime/custom-prompt".equals(path)) {
            writeJson(out, 200, new JSONObject().put("text", ""));
            return;
        }

        if ("/api/life/unread".equals(path)) {
            writeJson(out, 200, new JSONObject().put("pending", JSONObject.NULL));
            return;
        }

        if ("/api/studios".equals(path)) {
            writeJson(out, 200, new JSONObject().put("studios", new JSONArray()));
            return;
        }

        if ("/api/workflows".equals(path)) {
            writeJson(out, 200, new JSONObject().put("workflows", new JSONArray()));
            return;
        }

        if ("/api/workflows/templates".equals(path)) {
            writeJson(out, 200, new JSONObject().put("templates", new JSONArray()));
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
            JSONObject sessions = new JSONObject().put("sessions", new JSONArray());
            writeJson(out, 200, sessions);
            return;
        }

        if (path.startsWith("/api/sessions/")) {
            String sub = path.substring("/api/sessions/".length());
            if (sub.endsWith("/clear") && "POST".equals(method)) {
                String id = sub.substring(0, sub.length() - "/clear".length());
                sessionHistories.remove(id);
                writeJson(out, 200, new JSONObject().put("ok", true));
                return;
            }
            if ("DELETE".equals(method)) {
                sessionHistories.remove(sub);
                writeJson(out, 200, new JSONObject().put("ok", true));
                return;
            }
            JSONObject sessionMeta = new JSONObject()
                    .put("id", sub)
                    .put("title", "iFlow Session")
                    .put("cwd", "/root");
            writeJson(out, 200, sessionMeta);
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
        writeJson(out, 200, new JSONObject().put("ok", true));
    }

    private List<String> discoverModelsRemote(String baseUrl, String apiKey) {
        List<String> list = new ArrayList<>();
        try {
            String target = baseUrl != null ? baseUrl.trim() : "";
            while (target.endsWith("/")) target = target.substring(0, target.length() - 1);
            if (!target.endsWith("/models")) target += "/models";
            URL url = new URL(target);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    JSONObject json = new JSONObject(baos.toString(StandardCharsets.UTF_8.name()));
                    JSONArray data = json.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject item = data.optJSONObject(i);
                            if (item != null) {
                                String id = item.optString("id", "").trim();
                                if (!id.isEmpty() && !list.contains(id)) list.add(id);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
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

        int contentLength = 0;
        String cl = req.getHeader("content-length");
        if (cl != null) {
            try {
                contentLength = Integer.parseInt(cl.trim());
            } catch (Exception ignored) {}
        }
        if (contentLength > 0) {
            byte[] bodyBytes = new byte[contentLength];
            readFully(in, bodyBytes, 0, contentLength);
            req.body = new String(bodyBytes, StandardCharsets.UTF_8);
        } else {
            req.body = "";
        }

        return req;
    }

    public static final class HttpRequest {
        public final String method;
        public final String rawPath;
        public final String path;
        public final Map<String, String> headers = new HashMap<>();
        public String body = "";

        HttpRequest(String method, String rawPath) {
            this.method = method;
            this.rawPath = rawPath;
            int q = rawPath.indexOf('?');
            this.path = q >= 0 ? rawPath.substring(0, q) : rawPath;
        }

        public String getHeader(String name) {
            return headers.get(name != null ? name.toLowerCase() : "");
        }

        public JSONObject getJsonBody() {
            try {
                if (body == null || body.trim().isEmpty()) return new JSONObject();
                return new JSONObject(body);
            } catch (Exception e) {
                return new JSONObject();
            }
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
