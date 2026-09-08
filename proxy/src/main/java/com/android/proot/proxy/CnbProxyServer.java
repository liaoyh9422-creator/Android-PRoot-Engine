package com.android.proot.proxy;

import org.json.JSONArray;

import java.io.IOException;
import java.net.ServerSocket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Singleton facade gateway for managing CNB OpenAI-compatible local proxy service.
 */
public final class CnbProxyServer {
    public interface StateListener {
        void onStarting();
        void onStarted(int port, String baseUrl);
        void onStopped();
        void onError(String message, Throwable error);
    }

    public static final int DEFAULT_PORT = 7863;
    private static final int MAX_LOG_BUFFER = 250;
    private static volatile CnbProxyServer sInstance;

    private EmbeddedProxyServer server;
    private WebStudioServer webServer;
    private ProxyConfig config;
    private WebStudioServer.ProviderChangeListener providerChangeListener;
    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final List<ProxyLogListener> logListeners = new CopyOnWriteArrayList<>();
    private final LinkedList<String> logBuffer = new LinkedList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean starting;

    public static CnbProxyServer getInstance() {
        if (sInstance == null) {
            synchronized (CnbProxyServer.class) {
                if (sInstance == null) {
                    sInstance = new CnbProxyServer();
                }
            }
        }
        return sInstance;
    }

    private CnbProxyServer() {}

    public void setLogListener(ProxyLogListener listener) {
        logListeners.clear();
        if (listener != null) {
            logListeners.add(listener);
        }
    }

    public void addLogListener(ProxyLogListener listener) {
        if (listener != null && !logListeners.contains(listener)) {
            logListeners.add(listener);
        }
    }

    public void removeLogListener(ProxyLogListener listener) {
        if (listener != null) {
            logListeners.remove(listener);
        }
    }

    public void addStateListener(StateListener listener) {
        if (listener != null && !stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }

    public void removeStateListener(StateListener listener) {
        if (listener != null) {
            stateListeners.remove(listener);
        }
    }

    public void setStateListener(StateListener listener) {
        stateListeners.clear();
        if (listener != null) {
            stateListeners.add(listener);
        }
    }

    public synchronized void setProviderChangeListener(WebStudioServer.ProviderChangeListener listener) {
        this.providerChangeListener = listener;
        if (webServer != null) {
            webServer.setProviderChangeListener(listener);
        }
    }

    public synchronized WebStudioServer getWebServer() {
        return webServer;
    }

    public synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public synchronized boolean isStarting() {
        return starting;
    }

    public synchronized int getActualPort() {
        return server != null ? server.getActualPort() : (config != null ? config.getPort() : DEFAULT_PORT);
    }

    public synchronized String getBaseUrl() {
        return "http://127.0.0.1:" + getActualPort() + "/v1";
    }

    public synchronized int getWebPort() {
        return webServer != null ? webServer.getActualPort() : (config != null ? config.getWebPort() : ProxyConfig.DEFAULT_WEB_PORT);
    }

    public synchronized String getWebUrl() {
        return "http://127.0.0.1:" + getWebPort();
    }

    public synchronized JSONArray getPoolStats() {
        if (server != null && server.getCsrfPool() != null) {
            return server.getCsrfPool().stats();
        }
        return new JSONArray();
    }

    public synchronized List<String> getRecentLogs() {
        synchronized (logBuffer) {
            return new ArrayList<>(logBuffer);
        }
    }

    public void clearLogs() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
    }

    public void logMessage(String tag, String message) {
        dispatchLog(tag, message);
    }

    private void dispatchLog(String tag, String message) {
        String timestamp;
        synchronized (timeFormat) {
            timestamp = timeFormat.format(new Date());
        }
        String entry = "[" + timestamp + "] [" + tag + "] " + message;
        synchronized (logBuffer) {
            logBuffer.add(entry);
            if (logBuffer.size() > MAX_LOG_BUFFER) {
                logBuffer.removeFirst();
            }
        }
        for (ProxyLogListener l : logListeners) {
            try {
                l.onLog(tag, entry);
            } catch (Exception ignored) {}
        }
    }

    public synchronized void startAsync(ProxyConfig baseConfig) {
        if (isRunning() || starting) return;
        starting = true;
        dispatchLog("GATEWAY", "Initiating proxy start sequence...");
        for (StateListener l : stateListeners) {
            try {
                l.onStarting();
            } catch (Exception ignored) {}
        }

        executor.execute(() -> {
            try {
                int targetPort = baseConfig != null ? baseConfig.getPort() : DEFAULT_PORT;
                int freePort = findFreePort(targetPort);
                if (freePort != targetPort) {
                    dispatchLog("GATEWAY", "Port " + targetPort + " in use; auto-migrated to free port " + freePort);
                }

                ProxyConfig.Builder builder = new ProxyConfig.Builder();
                if (baseConfig != null) {
                    builder.setListenHost(baseConfig.getListenHost())
                            .setApiKey(baseConfig.getApiKey())
                            .setModel(baseConfig.getModel())
                            .setPoolMin(baseConfig.getPoolMin())
                            .setPoolMax(baseConfig.getPoolMax())
                            .setTtlMinutes(baseConfig.getTtlMinutes())
                            .setForcePromptTools(baseConfig.isForcePromptTools())
                            .setTimeoutMs(baseConfig.getTimeoutMs())
                            .setEnableThinking(baseConfig.isEnableThinking())
                            .setReasoningEffort(baseConfig.getReasoningEffort())
                            .setWebPort(baseConfig.getWebPort())
                            .setWebRoot(baseConfig.getWebRoot())
                            .setEnableWebStudio(baseConfig.isEnableWebStudio());
                }
                builder.setPort(freePort);
                ProxyConfig cfg = builder.build();

                synchronized (CnbProxyServer.this) {
                    this.config = cfg;
                    this.server = new EmbeddedProxyServer(cfg, this::dispatchLog);
                }

                server.start();

                if (cfg.isEnableWebStudio()) {
                    int targetWebPort = cfg.getWebPort();
                    int freeWebPort = findFreePort(targetWebPort);
                    ProxyConfig.Builder webBuilder = new ProxyConfig.Builder()
                            .setListenHost(cfg.getListenHost())
                            .setPort(freePort)
                            .setWebPort(freeWebPort)
                            .setWebRoot(cfg.getWebRoot())
                            .setApiKey(cfg.getApiKey())
                            .setModel(cfg.getModel())
                            .setEnableThinking(cfg.isEnableThinking())
                            .setReasoningEffort(cfg.getReasoningEffort());
                    ProxyConfig webCfg = webBuilder.build();
                    synchronized (CnbProxyServer.this) {
                        this.webServer = new WebStudioServer(webCfg, this::dispatchLog);
                        if (providerChangeListener != null) {
                            this.webServer.setProviderChangeListener(providerChangeListener);
                        }
                    }
                    webServer.start();
                    dispatchLog("GATEWAY", "Web Studio active at " + getWebUrl());
                }

                synchronized (CnbProxyServer.this) {
                    starting = false;
                }

                dispatchLog("GATEWAY", "Proxy successfully started at " + getBaseUrl());
                for (StateListener l : stateListeners) {
                    try {
                        l.onStarted(freePort, getBaseUrl());
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                synchronized (CnbProxyServer.this) {
                    starting = false;
                    if (server != null) {
                        server.stop();
                        server = null;
                    }
                    if (webServer != null) {
                        webServer.stop();
                        webServer = null;
                    }
                }
                dispatchLog("ERROR", "Proxy start failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                for (StateListener l : stateListeners) {
                    try {
                        l.onError(e.getMessage() != null ? e.getMessage() : "Failed to start proxy", e);
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    public synchronized void stop() {
        starting = false;
        if (server != null) {
            server.stop();
            server = null;
        }
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        dispatchLog("GATEWAY", "Proxy server stopped");
        for (StateListener l : stateListeners) {
            try {
                l.onStopped();
            } catch (Exception ignored) {}
        }
    }

    private static int findFreePort(int startPort) {
        for (int p = startPort; p <= startPort + 10; p++) {
            try (ServerSocket s = new ServerSocket(p)) {
                s.setReuseAddress(true);
                return p;
            } catch (IOException ignored) {}
        }
        return startPort;
    }
}
