package com.android.proot.proxy;

import java.io.File;

/**
 * Immutable configuration entity for CNB OpenAI Proxy Server.
 */
public class ProxyConfig {
    public static final int DEFAULT_WEB_PORT = 7865;

    private final String listenHost;
    private final int port;
    private final int webPort;
    private final File webRoot;
    private final boolean enableWebStudio;
    private final String apiKey;
    private final String model;
    private final int poolMin;
    private final int poolMax;
    private final int ttlMinutes;
    private final boolean forcePromptTools;
    private final int timeoutMs;
    private final boolean enableThinking;
    private final String reasoningEffort;

    private ProxyConfig(Builder b) {
        this.listenHost = b.listenHost;
        this.port = b.port;
        this.webPort = b.webPort;
        this.webRoot = b.webRoot;
        this.enableWebStudio = b.enableWebStudio;
        this.apiKey = b.apiKey;
        this.model = b.model;
        this.poolMin = b.poolMin;
        this.poolMax = b.poolMax;
        this.ttlMinutes = b.ttlMinutes;
        this.forcePromptTools = b.forcePromptTools;
        this.timeoutMs = b.timeoutMs;
        this.enableThinking = b.enableThinking;
        this.reasoningEffort = b.reasoningEffort;
    }

    public String getListenHost() { return listenHost; }
    public int getPort() { return port; }
    public int getWebPort() { return webPort; }
    public File getWebRoot() { return webRoot; }
    public boolean isEnableWebStudio() { return enableWebStudio; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public int getPoolMin() { return poolMin; }
    public int getPoolMax() { return poolMax; }
    public int getTtlMinutes() { return ttlMinutes; }
    public boolean isForcePromptTools() { return forcePromptTools; }
    public int getTimeoutMs() { return timeoutMs; }
    public boolean isEnableThinking() { return enableThinking; }
    public String getReasoningEffort() { return reasoningEffort; }

    public static class Builder {
        private String listenHost = "0.0.0.0";
        private int port = 7863;
        private int webPort = DEFAULT_WEB_PORT;
        private File webRoot = null;
        private boolean enableWebStudio = true;
        private String apiKey = "";
        private String model = "deepseek-v4-flash";
        private int poolMin = 2;
        private int poolMax = 8;
        private int ttlMinutes = 30;
        private boolean forcePromptTools = true;
        private int timeoutMs = 15000;
        private boolean enableThinking = true;
        private String reasoningEffort = "high";

        public Builder setListenHost(String host) { this.listenHost = host; return this; }
        public Builder setPort(int port) { this.port = port; return this; }
        public Builder setWebPort(int port) { this.webPort = port; return this; }
        public Builder setWebRoot(File root) { this.webRoot = root; return this; }
        public Builder setEnableWebStudio(boolean enable) { this.enableWebStudio = enable; return this; }
        public Builder setApiKey(String key) { this.apiKey = key != null ? key : ""; return this; }
        public Builder setModel(String model) { this.model = model != null && !model.isEmpty() ? model : "deepseek-v4-flash"; return this; }
        public Builder setPoolMin(int min) { this.poolMin = Math.max(1, min); return this; }
        public Builder setPoolMax(int max) { this.poolMax = Math.max(this.poolMin, max); return this; }
        public Builder setTtlMinutes(int ttl) { this.ttlMinutes = Math.max(5, ttl); return this; }
        public Builder setForcePromptTools(boolean enable) { this.forcePromptTools = enable; return this; }
        public Builder setTimeoutMs(int ms) { this.timeoutMs = Math.max(3000, ms); return this; }
        public Builder setEnableThinking(boolean enable) { this.enableThinking = enable; return this; }
        public Builder setReasoningEffort(String effort) { this.reasoningEffort = effort != null ? effort : "high"; return this; }

        public ProxyConfig build() {
            return new ProxyConfig(this);
        }
    }
}
