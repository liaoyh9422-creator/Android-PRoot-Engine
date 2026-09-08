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
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    public interface ShellRunner {
        String run(String command, String cwd, int timeoutMs) throws Exception;
    }

    public static class ToolCall {
        public final String id;
        public final String name;
        public final JSONObject arguments;

        public ToolCall(String id, String name, JSONObject arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments != null ? arguments : new JSONObject();
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("type", "function");
                JSONObject fn = new JSONObject();
                fn.put("name", name);
                fn.put("arguments", arguments.toString());
                obj.put("function", fn);
            } catch (Exception ignored) {}
            return obj;
        }
    }

    public static class ToolResult {
        public final String output;
        public final boolean isError;

        public ToolResult(String output, boolean isError) {
            this.output = output != null ? output : "";
            this.isError = isError;
        }
    }

    private static class ToolCallBuilder {
        String id = "";
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
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
    private ShellRunner shellRunner;
    private File rootfsDir;
    private final Map<String, String> sessionCwds = new ConcurrentHashMap<>();
    private final Set<String> cancelledSessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
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

    public void setShellRunner(ShellRunner runner) {
        this.shellRunner = runner;
    }

    public void setRootfsDir(File rootfsDir) {
        this.rootfsDir = rootfsDir;
    }

    public void setCwd(String cwd) {
        if (cwd != null && !cwd.isEmpty()) sessionCwds.put("default", cwd);
    }

    public String getSessionCwd(String sessionId) {
        String s = (sessionId != null) ? sessionCwds.get(sessionId) : null;
        if (s != null && !s.isEmpty()) return s;
        s = sessionCwds.get("default");
        return (s != null && !s.isEmpty()) ? s : "/root";
    }

    public void setSessionCwd(String sessionId, String cwd) {
        if (sessionId != null && cwd != null) {
            sessionCwds.put(sessionId, cwd);
        }
    }

    public synchronized void selectProviderModel(String id, String model) {
        if (id == null || id.isEmpty()) id = activeProviderId;
        ProviderData p = providers.get(id);
        if (p != null && model != null && !model.isEmpty()) {
            p.model = model;
            if (!p.models.contains(model)) p.models.add(0, model);
            if (id.equals(activeProviderId)) {
                notifyProviderChanged(p);
            }
            saveProvidersToDisk();
        }
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
        String activeCwd = getSessionCwd(sessionId);
        JSONObject loaded = new JSONObject()
                .put("v", 1)
                .put("type", "event")
                .put("ts", System.currentTimeMillis())
                .put("payload", new JSONObject()
                        .put("event_type", "session_loaded")
                        .put("session_id", sessionId)
                        .put("cwd", activeCwd)
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
                        // Immediate ack for any command
                        JSONObject ack = new JSONObject()
                                .put("v", 1)
                                .put("type", "ack")
                                .put("id", id)
                                .put("ts", System.currentTimeMillis())
                                .put("payload", new JSONObject().put("ok", true));
                        sendWsTextFrame(out, ack.toString());

                        if ("send_message".equals(cmd)) {
                            String userText = payload.optString("text", "").trim();
                            dispatchAiTurn(out, sessionId, userText);
                        } else if ("cancel".equals(cmd)) {
                            cancelledSessions.add(sessionId);
                            sendAgentEvent(out, new JSONObject().put("event_type", "agent_cancelled"));
                        } else if ("select_model".equals(cmd)) {
                            String provId = payload.optString("provider_id");
                            String m = payload.optString("model");
                            if (!m.isEmpty()) selectProviderModel(provId, m);
                        }
                    }
                }
            } catch (Exception e) {
                logger.onLog("WS", "Error processing command: " + e.getMessage());
            }
        }
        try { socket.close(); } catch (Exception ignored) {}
    }

    private void sendAgentEvent(BufferedOutputStream out, JSONObject payload) {
        try {
            JSONObject event = new JSONObject()
                    .put("v", 1)
                    .put("type", "event")
                    .put("ts", System.currentTimeMillis())
                    .put("payload", payload);
            sendWsTextFrame(out, event.toString());
        } catch (Exception e) {
            logger.onLog("WS", "Failed to send agent event: " + e.getMessage());
        }
    }

    private void sendUsageUpdate(BufferedOutputStream out, int historyCount) {
        try {
            JSONObject usage = new JSONObject()
                    .put("input_tokens", 80 * historyCount)
                    .put("output_tokens", 40 * historyCount)
                    .put("total_tokens", 120 * historyCount);
            JSONObject categories = new JSONObject()
                    .put("system_tools", 9)
                    .put("messages", historyCount)
                    .put("skills", 0)
                    .put("mcp_tools", 0);
            JSONObject payload = new JSONObject()
                    .put("event_type", "usage_update")
                    .put("usage", usage)
                    .put("context_categories", categories);
            sendAgentEvent(out, payload);
        } catch (Exception ignored) {}
    }

    private void dispatchAiTurn(BufferedOutputStream out, String sessionId, String prompt) {
        clientPool.execute(() -> {
            try {
                cancelledSessions.remove(sessionId);
                List<JSONObject> history = sessionHistories.computeIfAbsent(sessionId != null ? sessionId : "default", k -> new ArrayList<>());
                JSONObject userMsg = new JSONObject().put("role", "user").put("content", prompt);
                synchronized (history) {
                    history.add(userMsg);
                }

                int maxRounds = 10;
                int currentRound = 0;

                while (currentRound < maxRounds && !cancelledSessions.contains(sessionId)) {
                    currentRound++;
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
                    body.put("tools", getAvailableTools());
                    body.put("tool_choice", "auto");

                    JSONArray messages = new JSONArray();
                    synchronized (history) {
                        for (JSONObject m : history) messages.put(m);
                    }
                    body.put("messages", messages);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        String err = "Upstream returned HTTP " + code;
                        sendAgentEvent(out, new JSONObject()
                                .put("event_type", "agent_error")
                                .put("message", err)
                                .put("is_fatal", false));
                        sendAgentEvent(out, new JSONObject().put("event_type", "turn_end"));
                        break;
                    }

                    StringBuilder reasoningBuilder = new StringBuilder();
                    StringBuilder contentBuilder = new StringBuilder();
                    Map<Integer, ToolCallBuilder> toolCallMap = new LinkedHashMap<>();

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (cancelledSessions.contains(sessionId)) break;
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if ("[DONE]".equals(data)) break;

                                try {
                                    JSONObject chunk = new JSONObject(data);
                                    JSONArray choices = chunk.optJSONArray("choices");
                                    if (choices != null && choices.length() > 0) {
                                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                        if (delta != null) {
                                            String r = delta.optString("reasoning_content", "");
                                            if (!r.isEmpty()) {
                                                reasoningBuilder.append(r);
                                                sendAgentEvent(out, new JSONObject()
                                                        .put("event_type", "reasoning_chunk")
                                                        .put("content", r));
                                            }

                                            JSONArray tcArray = delta.optJSONArray("tool_calls");
                                            if (tcArray != null) {
                                                for (int i = 0; i < tcArray.length(); i++) {
                                                    JSONObject tc = tcArray.getJSONObject(i);
                                                    int idx = tc.optInt("index", toolCallMap.size());
                                                    ToolCallBuilder tcb = toolCallMap.computeIfAbsent(idx, k -> new ToolCallBuilder());
                                                    if (tc.has("id")) tcb.id = tc.optString("id");
                                                    JSONObject fn = tc.optJSONObject("function");
                                                    if (fn != null) {
                                                        if (fn.has("name")) tcb.name.append(fn.optString("name"));
                                                        if (fn.has("arguments")) tcb.arguments.append(fn.optString("arguments"));
                                                    }
                                                }
                                            }

                                            String c = delta.optString("content", "");
                                            if (!c.isEmpty()) {
                                                contentBuilder.append(c);
                                                if (toolCallMap.isEmpty() && !contentBuilder.toString().contains("<|XYML|") && !contentBuilder.toString().contains("<tool_call")) {
                                                    sendAgentEvent(out, new JSONObject()
                                                            .put("event_type", "text_chunk")
                                                            .put("content", c));
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    if (cancelledSessions.contains(sessionId)) {
                        sendAgentEvent(out, new JSONObject().put("event_type", "agent_cancelled"));
                        break;
                    }

                    // Parse tool calls (Native or XYML/XML fallback)
                    List<ToolCall> calls = new ArrayList<>();
                    if (!toolCallMap.isEmpty()) {
                        for (ToolCallBuilder tcb : toolCallMap.values()) {
                            String name = tcb.name.toString().trim();
                            if (!name.isEmpty()) {
                                String id = (tcb.id != null && !tcb.id.isEmpty()) ? tcb.id : ("call_" + System.currentTimeMillis() + "_" + calls.size());
                                calls.add(new ToolCall(id, name, parseArguments(tcb.arguments.toString())));
                            }
                        }
                    } else {
                        JSONArray parsedXml = ToolForge.parseToolCalls(contentBuilder.toString(), getAvailableTools());
                        if (parsedXml != null && parsedXml.length() > 0) {
                            for (int i = 0; i < parsedXml.length(); i++) {
                                JSONObject c = parsedXml.getJSONObject(i);
                                JSONObject fn = c.optJSONObject("function");
                                String name = fn != null ? fn.optString("name") : c.optString("name");
                                Object argsObj = fn != null ? fn.opt("arguments") : c.opt("arguments");
                                String id = c.optString("id", "call_" + System.currentTimeMillis() + "_" + i);
                                calls.add(new ToolCall(id, name, parseArguments(argsObj)));
                            }
                        }
                    }

                    if (calls.isEmpty()) {
                        // No tool call in this round -> conversation turn finished
                        String cleanContent = ToolForge.stripProtocolMarkup(contentBuilder.toString());
                        if (contentBuilder.toString().contains("<|XYML|") || contentBuilder.toString().contains("<tool_call")) {
                            if (!cleanContent.isEmpty()) {
                                sendAgentEvent(out, new JSONObject()
                                        .put("event_type", "text_chunk")
                                        .put("content", cleanContent));
                            }
                        }
                        synchronized (history) {
                            history.add(new JSONObject().put("role", "assistant").put("content", cleanContent));
                            while (history.size() > 30) history.remove(0);
                        }
                        sendAgentEvent(out, new JSONObject().put("event_type", "turn_end"));
                        sendUsageUpdate(out, history.size());
                        break;
                    } else {
                        // Tools were called!
                        String rawContent = contentBuilder.toString();
                        String cleanContent = ToolForge.stripProtocolMarkup(rawContent);

                        JSONObject assistantMsg = new JSONObject();
                        assistantMsg.put("role", "assistant");
                        assistantMsg.put("content", cleanContent.isEmpty() ? JSONObject.NULL : cleanContent);
                        JSONArray tcArr = new JSONArray();
                        for (ToolCall tc : calls) {
                            tcArr.put(tc.toJson());
                        }
                        assistantMsg.put("tool_calls", tcArr);
                        synchronized (history) {
                            history.add(assistantMsg);
                        }

                        for (ToolCall tc : calls) {
                            if (cancelledSessions.contains(sessionId)) break;
                            String canonical = canonicalToolName(tc.name);
                            String uiName = getUiToolName(canonical);

                            // tool_start
                            sendAgentEvent(out, new JSONObject()
                                    .put("event_type", "tool_start")
                                    .put("call_id", tc.id)
                                    .put("tool_name", uiName)
                                    .put("arguments", tc.arguments));

                            // tool_running
                            sendAgentEvent(out, new JSONObject()
                                    .put("event_type", "tool_running")
                                    .put("call_id", tc.id)
                                    .put("tool_name", uiName));

                            // execute tool
                            long startMs = System.currentTimeMillis();
                            ToolResult result = executeTool(canonical, tc.arguments, sessionId);
                            double elapsed = (System.currentTimeMillis() - startMs) / 1000.0;

                            // tool_done
                            sendAgentEvent(out, new JSONObject()
                                    .put("event_type", "tool_done")
                                    .put("call_id", tc.id)
                                    .put("tool_name", uiName)
                                    .put("elapsed", elapsed)
                                    .put("result_preview", result.output)
                                    .put("is_error", result.isError));

                            // add tool result to history
                            JSONObject toolMsg = new JSONObject();
                            toolMsg.put("role", "tool");
                            toolMsg.put("tool_call_id", tc.id);
                            toolMsg.put("name", canonical);
                            toolMsg.put("content", result.output);
                            synchronized (history) {
                                history.add(toolMsg);
                            }
                        }

                        if (cancelledSessions.contains(sessionId)) {
                            sendAgentEvent(out, new JSONObject().put("event_type", "agent_cancelled"));
                            break;
                        }

                        // Loop back to next round to get LLM response after tool execution
                    }
                }
            } catch (Exception e) {
                logger.onLog("WS", "Error in AI turn: " + e.getMessage());
                try {
                    sendAgentEvent(out, new JSONObject()
                            .put("event_type", "agent_error")
                            .put("message", "执行错误: " + e.getMessage())
                            .put("is_fatal", false));
                    sendAgentEvent(out, new JSONObject().put("event_type", "turn_end"));
                } catch (Exception ignored) {}
            }
        });
    }

    private static JSONObject parseArguments(Object argsObj) {
        if (argsObj instanceof JSONObject) {
            return (JSONObject) argsObj;
        }
        if (argsObj instanceof String) {
            String s = ((String) argsObj).trim();
            if (s.startsWith("{")) {
                try {
                    return new JSONObject(s);
                } catch (Exception ignored) {}
            }
            JSONObject res = new JSONObject();
            try {
                res.put("command", s);
            } catch (Exception ignored) {}
            return res;
        }
        return new JSONObject();
    }

    public static String canonicalToolName(String name) {
        if (name == null) return "shell";
        String n = name.trim().toLowerCase();
        switch (n) {
            case "bash":
            case "sh":
            case "terminal":
            case "command":
            case "exec":
            case "run":
            case "local_shell":
                return "shell";
            case "read":
            case "cat":
            case "view":
            case "read_file":
                return "read_file";
            case "write":
            case "write_file":
                return "write_file";
            case "edit":
            case "replace":
            case "patch":
            case "apply_patch":
            case "edit_file":
                return "edit_file";
            case "ls":
            case "dir":
            case "list":
            case "ll":
            case "list_dir":
                return "list_dir";
            case "grep":
            case "search":
            case "grep_files":
                return "grep_files";
            case "web":
            case "websearch":
            case "web_search":
                return "web_search";
            case "browse":
            case "http":
            case "webfetch":
            case "fetch":
                return "fetch";
            case "doctor":
            case "runtime_doctor":
                return "runtime_doctor";
            default:
                return n;
        }
    }

    public static String getUiToolName(String canonical) {
        if (canonical == null) return "bash";
        switch (canonical) {
            case "shell": return "bash";
            case "read_file": return "read";
            case "write_file": return "write";
            case "edit_file": return "edit";
            case "list_dir": return "ls";
            case "grep_files": return "grep";
            case "fetch": return "webfetch";
            case "web_search": return "websearch";
            default: return canonical;
        }
    }

    public static JSONArray getAvailableTools() {
        JSONArray tools = new JSONArray();
        try {
            // 1. shell
            JSONObject shellTool = new JSONObject();
            shellTool.put("type", "function");
            JSONObject shellFn = new JSONObject();
            shellFn.put("name", "shell");
            shellFn.put("description", "Run a shell command in the Linux Debian guest environment.");
            JSONObject shellParams = new JSONObject();
            shellParams.put("type", "object");
            JSONObject shellProps = new JSONObject();
            shellProps.put("command", new JSONObject().put("type", "string").put("description", "The command line string to execute"));
            shellProps.put("timeout_ms", new JSONObject().put("type", "integer").put("description", "Timeout in milliseconds (optional, default 30000)"));
            shellParams.put("properties", shellProps);
            shellParams.put("required", new JSONArray().put("command"));
            shellFn.put("parameters", shellParams);
            shellTool.put("function", shellFn);
            tools.put(shellTool);

            // 2. read_file
            JSONObject readTool = new JSONObject();
            readTool.put("type", "function");
            JSONObject readFn = new JSONObject();
            readFn.put("name", "read_file");
            readFn.put("description", "Read contents of a text file from the workspace.");
            JSONObject readParams = new JSONObject();
            readParams.put("type", "object");
            JSONObject readProps = new JSONObject();
            readProps.put("path", new JSONObject().put("type", "string").put("description", "Target file path"));
            readProps.put("offset", new JSONObject().put("type", "integer").put("description", "1-based line number to start reading (optional)"));
            readProps.put("limit", new JSONObject().put("type", "integer").put("description", "Maximum lines to read (optional)"));
            readParams.put("properties", readProps);
            readParams.put("required", new JSONArray().put("path"));
            readFn.put("parameters", readParams);
            readTool.put("function", readFn);
            tools.put(readTool);

            // 3. write_file
            JSONObject writeTool = new JSONObject();
            writeTool.put("type", "function");
            JSONObject writeFn = new JSONObject();
            writeFn.put("name", "write_file");
            writeFn.put("description", "Create or overwrite a file with UTF-8 text content.");
            JSONObject writeParams = new JSONObject();
            writeParams.put("type", "object");
            JSONObject writeProps = new JSONObject();
            writeProps.put("path", new JSONObject().put("type", "string").put("description", "Target file path"));
            writeProps.put("content", new JSONObject().put("type", "string").put("description", "UTF-8 content to write"));
            writeParams.put("properties", writeProps);
            writeParams.put("required", new JSONArray().put("path").put("content"));
            writeFn.put("parameters", writeParams);
            writeTool.put("function", writeFn);
            tools.put(writeTool);

            // 4. edit_file
            JSONObject editTool = new JSONObject();
            editTool.put("type", "function");
            JSONObject editFn = new JSONObject();
            editFn.put("name", "edit_file");
            editFn.put("description", "Replace exact text fragment in a file with replacement content.");
            JSONObject editParams = new JSONObject();
            editParams.put("type", "object");
            JSONObject editProps = new JSONObject();
            editProps.put("path", new JSONObject().put("type", "string").put("description", "Target file path"));
            editProps.put("old_string", new JSONObject().put("type", "string").put("description", "Exact text to replace"));
            editProps.put("new_string", new JSONObject().put("type", "string").put("description", "Replacement text"));
            editProps.put("replace_all", new JSONObject().put("type", "boolean").put("description", "Whether to replace all occurrences"));
            editParams.put("properties", editProps);
            editParams.put("required", new JSONArray().put("path").put("old_string").put("new_string"));
            editFn.put("parameters", editParams);
            editTool.put("function", editFn);
            tools.put(editTool);

            // 5. list_dir
            JSONObject listTool = new JSONObject();
            listTool.put("type", "function");
            JSONObject listFn = new JSONObject();
            listFn.put("name", "list_dir");
            listFn.put("description", "List files and subdirectories in a directory path.");
            JSONObject listParams = new JSONObject();
            listParams.put("type", "object");
            JSONObject listProps = new JSONObject();
            listProps.put("path", new JSONObject().put("type", "string").put("description", "Directory path (default current working directory)"));
            listProps.put("depth", new JSONObject().put("type", "integer").put("description", "Max directory depth (default 1)"));
            listParams.put("properties", listProps);
            listFn.put("parameters", listParams);
            listTool.put("function", listFn);
            tools.put(listTool);

            // 6. grep_files
            JSONObject grepTool = new JSONObject();
            grepTool.put("type", "function");
            JSONObject grepFn = new JSONObject();
            grepFn.put("name", "grep_files");
            grepFn.put("description", "Search text pattern or regex in workspace files.");
            JSONObject grepParams = new JSONObject();
            grepParams.put("type", "object");
            JSONObject grepProps = new JSONObject();
            grepProps.put("query", new JSONObject().put("type", "string").put("description", "Search query or regex"));
            grepProps.put("path", new JSONObject().put("type", "string").put("description", "Directory or file to search"));
            grepProps.put("max_results", new JSONObject().put("type", "integer").put("description", "Max results to return (default 50)"));
            grepParams.put("properties", grepProps);
            grepParams.put("required", new JSONArray().put("query"));
            grepFn.put("parameters", grepParams);
            grepTool.put("function", grepFn);
            tools.put(grepTool);

            // 7. web_search
            JSONObject searchTool = new JSONObject();
            searchTool.put("type", "function");
            JSONObject searchFn = new JSONObject();
            searchFn.put("name", "web_search");
            searchFn.put("description", "Search the web for up-to-date documentation and information.");
            JSONObject searchParams = new JSONObject();
            searchParams.put("type", "object");
            JSONObject searchProps = new JSONObject();
            searchProps.put("query", new JSONObject().put("type", "string").put("description", "Search keywords"));
            searchParams.put("properties", searchProps);
            searchParams.put("required", new JSONArray().put("query"));
            searchFn.put("parameters", searchParams);
            searchTool.put("function", searchFn);
            tools.put(searchTool);

            // 8. fetch
            JSONObject fetchTool = new JSONObject();
            fetchTool.put("type", "function");
            JSONObject fetchFn = new JSONObject();
            fetchFn.put("name", "fetch");
            fetchFn.put("description", "Fetch and extract text content from a web URL.");
            JSONObject fetchParams = new JSONObject();
            fetchParams.put("type", "object");
            JSONObject fetchProps = new JSONObject();
            fetchProps.put("url", new JSONObject().put("type", "string").put("description", "URL to fetch"));
            fetchParams.put("properties", fetchProps);
            fetchParams.put("required", new JSONArray().put("url"));
            fetchFn.put("parameters", fetchProps);
            fetchTool.put("function", fetchFn);
            tools.put(fetchTool);

            // 9. runtime_doctor
            JSONObject docTool = new JSONObject();
            docTool.put("type", "function");
            JSONObject docFn = new JSONObject();
            docFn.put("name", "runtime_doctor");
            docFn.put("description", "Report environment health and Debian PRoot facts.");
            docFn.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
            docTool.put("function", docFn);
            tools.put(docTool);
        } catch (Exception ignored) {}
        return tools;
    }

    public File resolvePath(String pathStr, String sessionId) {
        if (pathStr == null || pathStr.trim().isEmpty()) {
            pathStr = getSessionCwd(sessionId);
        }
        pathStr = pathStr.trim();
        if (!pathStr.startsWith("/")) {
            String base = getSessionCwd(sessionId);
            if (!base.endsWith("/")) base += "/";
            pathStr = base + pathStr;
        }
        if (rootfsDir != null && rootfsDir.exists()) {
            if (pathStr.startsWith("/storage") || pathStr.startsWith("/sdcard")) {
                return new File(pathStr);
            }
            return new File(rootfsDir, pathStr.substring(1));
        }
        return new File(pathStr);
    }

    public ToolResult executeTool(String canonical, JSONObject args, String sessionId) {
        try {
            switch (canonical) {
                case "shell": {
                    String cmd = args.optString("command", args.optString("cmd", "")).trim();
                    if (cmd.isEmpty()) return new ToolResult("Error: Missing command argument", true);
                    int timeoutMs = args.optInt("timeout_ms", 30000);
                    String cwd = getSessionCwd(sessionId);
                    String out;
                    if (shellRunner != null) {
                        out = shellRunner.run(cmd, cwd, timeoutMs);
                    } else {
                        out = executeHostProcess(cmd, cwd, timeoutMs);
                    }
                    return new ToolResult(out, false);
                }
                case "read_file": {
                    String path = args.optString("path", args.optString("file_path", "")).trim();
                    if (path.isEmpty()) return new ToolResult("Error: Missing path argument", true);
                    int offset = args.optInt("offset", 1);
                    int limit = args.optInt("limit", 2000);
                    File file = resolvePath(path, sessionId);
                    if (!file.exists()) return new ToolResult("Error: File not found: " + path, true);
                    if (file.isDirectory()) return new ToolResult("Error: " + path + " is a directory, use list_dir instead", true);
                    List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                    int start = Math.max(1, offset) - 1;
                    int end = limit > 0 ? Math.min(lines.size(), start + limit) : lines.size();
                    StringBuilder sb = new StringBuilder();
                    for (int i = start; i < end; i++) {
                        sb.append(String.format("%4d | %s\n", i + 1, lines.get(i)));
                    }
                    return new ToolResult(sb.toString(), false);
                }
                case "write_file": {
                    String path = args.optString("path", args.optString("file_path", "")).trim();
                    if (path.isEmpty()) return new ToolResult("Error: Missing path argument", true);
                    String content = args.optString("content", "");
                    File file = resolvePath(path, sessionId);
                    if (file.getParentFile() != null) file.getParentFile().mkdirs();
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    Files.write(file.toPath(), bytes);
                    return new ToolResult("Successfully wrote " + bytes.length + " bytes to " + path, false);
                }
                case "edit_file": {
                    String path = args.optString("path", args.optString("file_path", "")).trim();
                    String oldStr = args.optString("old_string", "");
                    String newStr = args.optString("new_string", "");
                    boolean replaceAll = args.optBoolean("replace_all", false);
                    if (path.isEmpty() || oldStr.isEmpty()) {
                        return new ToolResult("Error: Missing path or old_string argument", true);
                    }
                    File file = resolvePath(path, sessionId);
                    if (!file.exists()) return new ToolResult("Error: File not found: " + path, true);
                    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    if (!text.contains(oldStr)) {
                        return new ToolResult("Error: old_string not found in " + path, true);
                    }
                    String replaced = replaceAll ? text.replace(oldStr, newStr) : text.replaceFirst(Pattern.quote(oldStr), Matcher.quoteReplacement(newStr));
                    Files.write(file.toPath(), replaced.getBytes(StandardCharsets.UTF_8));
                    return new ToolResult("Successfully edited " + path, false);
                }
                case "list_dir": {
                    String path = args.optString("path", getSessionCwd(sessionId)).trim();
                    File dir = resolvePath(path, sessionId);
                    if (!dir.exists()) return new ToolResult("Error: Directory not found: " + path, true);
                    if (!dir.isDirectory()) return new ToolResult("Error: " + path + " is not a directory", true);
                    File[] children = dir.listFiles();
                    if (children == null || children.length == 0) return new ToolResult("(Directory is empty)", false);
                    StringBuilder sb = new StringBuilder();
                    for (File f : children) {
                        sb.append(f.isDirectory() ? "[DIR]  " : "[FILE] ");
                        sb.append(String.format("%10s  ", f.isDirectory() ? "-" : formatSize(f.length())));
                        sb.append(f.getName()).append("\n");
                    }
                    return new ToolResult(sb.toString(), false);
                }
                case "grep_files": {
                    String query = args.optString("query", "").trim();
                    if (query.isEmpty()) return new ToolResult("Error: Missing query argument", true);
                    String path = args.optString("path", getSessionCwd(sessionId)).trim();
                    File root = resolvePath(path, sessionId);
                    if (!root.exists()) return new ToolResult("Error: Path not found: " + path, true);
                    int maxResults = args.optInt("max_results", 50);
                    StringBuilder sb = new StringBuilder();
                    int count = grepRecursive(root, query, sb, maxResults);
                    if (count == 0) return new ToolResult("No matches found for: " + query, false);
                    return new ToolResult(sb.toString(), false);
                }
                case "web_search": {
                    String q = args.optString("query", "").trim();
                    if (q.isEmpty()) return new ToolResult("Error: Missing query argument", true);
                    return new ToolResult(performWebSearch(q), false);
                }
                case "fetch": {
                    String url = args.optString("url", "").trim();
                    if (url.isEmpty()) return new ToolResult("Error: Missing url argument", true);
                    return new ToolResult(performHttpFetch(url), false);
                }
                case "runtime_doctor": {
                    JSONObject doc = new JSONObject();
                    doc.put("status", "ready");
                    doc.put("os", System.getProperty("os.name"));
                    doc.put("arch", System.getProperty("os.arch"));
                    doc.put("backend", "proot_linux");
                    doc.put("distro", "Debian 12 Bookworm");
                    doc.put("cwd", getSessionCwd(sessionId));
                    doc.put("rootfs_mounted", rootfsDir != null && rootfsDir.exists());
                    return new ToolResult(doc.toString(2), false);
                }
                default:
                    return new ToolResult("Error: Unknown tool '" + canonical + "'", true);
            }
        } catch (Exception e) {
            return new ToolResult("Error executing " + canonical + ": " + e.getMessage(), true);
        }
    }

    private String executeHostProcess(String command, String cwd, int timeoutMs) {
        try {
            ProcessBuilder pb;
            if (new File("/bin/bash").exists()) {
                pb = new ProcessBuilder("/bin/bash", "-c", command);
            } else if (new File("/bin/sh").exists()) {
                pb = new ProcessBuilder("/bin/sh", "-c", command);
            } else {
                pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            }
            File dir = resolvePath(cwd, null);
            if (dir.isDirectory()) pb.directory(dir);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + (timeoutMs > 0 ? timeoutMs : 30000);
            InputStream is = proc.getInputStream();
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                int avail = is.available();
                if (avail > 0) {
                    int r = is.read(buf, 0, Math.min(avail, buf.length));
                    if (r > 0) sb.append(new String(buf, 0, r, StandardCharsets.UTF_8));
                } else {
                    try {
                        int exit = proc.exitValue();
                        while (is.available() > 0) {
                            int r = is.read(buf);
                            if (r > 0) sb.append(new String(buf, 0, r, StandardCharsets.UTF_8));
                        }
                        String out = sb.toString().trim();
                        return out.isEmpty() ? "(Exit code " + exit + ")" : out;
                    } catch (IllegalThreadStateException running) {
                        Thread.sleep(40);
                    }
                }
            }
            proc.destroy();
            return sb.toString().trim() + "\n(Command timed out after " + (timeoutMs / 1000) + "s)";
        } catch (Exception e) {
            return "Process execution failed: " + e.getMessage();
        }
    }

    private String performWebSearch(String query) {
        try {
            String u = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8");
            URL url = new URL(u);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() == 200) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] b = new byte[1024];
                int n;
                InputStream is = conn.getInputStream();
                while ((n = is.read(b)) != -1 && baos.size() < 64000) baos.write(b, 0, n);
                String html = baos.toString("UTF-8");
                Matcher m = Pattern.compile("(?is)<a class=\"result__snippet[^>]*>(.*?)</a>").matcher(html);
                StringBuilder sb = new StringBuilder();
                int count = 0;
                while (m.find() && count < 5) {
                    count++;
                    String snippet = m.group(1).replaceAll("<[^>]+>", "").trim();
                    if (!snippet.isEmpty()) sb.append(count).append(". ").append(snippet).append("\n\n");
                }
                if (sb.length() > 0) return sb.toString().trim();
            }
        } catch (Exception ignored) {}
        return "Web search query executed for '" + query + "'. (No external search hits reachable or network restricted)";
    }

    private String performHttpFetch(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] b = new byte[1024];
                int n;
                InputStream is = conn.getInputStream();
                while ((n = is.read(b)) != -1 && baos.size() < 64000) baos.write(b, 0, n);
                String raw = baos.toString("UTF-8");
                String text = raw.replaceAll("(?is)<script.*?</script>", "")
                        .replaceAll("(?is)<style.*?</style>", "")
                        .replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
                if (text.length() > 2000) text = text.substring(0, 2000) + "... (truncated)";
                return text;
            } else {
                return "HTTP Error " + code;
            }
        } catch (Exception e) {
            return "Fetch failed: " + e.getMessage();
        }
    }

    private int grepRecursive(File dir, String query, StringBuilder sb, int maxResults) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (count >= maxResults) break;
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !f.getName().equals("node_modules")) {
                    count += grepRecursive(f, query, sb, maxResults - count);
                }
            } else if (f.isFile() && f.length() < 1024 * 1024) {
                try {
                    List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (line.contains(query)) {
                            count++;
                            sb.append(f.getPath()).append(":").append(i + 1).append(": ").append(line.trim()).append("\n");
                            if (count >= maxResults) break;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return count;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
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
            if (sub.endsWith("/cwd")) {
                String id = sub.substring(0, sub.length() - "/cwd".length());
                if ("POST".equals(method) || "PUT".equals(method)) {
                    JSONObject b = req.getJsonBody();
                    String newCwd = b.optString("cwd", "/root");
                    setSessionCwd(id, newCwd);
                    writeJson(out, 200, new JSONObject().put("ok", true).put("cwd", newCwd));
                } else {
                    writeJson(out, 200, new JSONObject().put("cwd", getSessionCwd(id)));
                }
                return;
            }
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
                    .put("cwd", getSessionCwd(sub));
            writeJson(out, 200, sessionMeta);
            return;
        }

        if ("/api/fs/list".equals(path)) {
            String dirPath = req.getQueryParam("path");
            if (dirPath == null || dirPath.isEmpty()) dirPath = "/root";
            File dir = resolvePath(dirPath, null);
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
                File file = resolvePath(filePath, null);
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
