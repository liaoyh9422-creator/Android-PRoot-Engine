package com.android.proot.proxy;

import org.json.JSONArray;

import java.io.IOException;
import java.net.ServerSocket;
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
    private static volatile CnbProxyServer sInstance;

    private EmbeddedProxyServer server;
    private ProxyConfig config;
    private ProxyLogListener logListener;
    private StateListener stateListener;
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
        this.logListener = listener;
    }

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
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

    public synchronized JSONArray getPoolStats() {
        if (server != null && server.getCsrfPool() != null) {
            return server.getCsrfPool().stats();
        }
        return new JSONArray();
    }

    public synchronized void startAsync(ProxyConfig baseConfig) {
        if (isRunning() || starting) return;
        starting = true;
        if (stateListener != null) stateListener.onStarting();

        executor.execute(() -> {
            try {
                int targetPort = baseConfig != null ? baseConfig.getPort() : DEFAULT_PORT;
                int freePort = findFreePort(targetPort);

                ProxyConfig.Builder builder = new ProxyConfig.Builder();
                if (baseConfig != null) {
                    builder.setListenHost(baseConfig.getListenHost())
                            .setApiKey(baseConfig.getApiKey())
                            .setModel(baseConfig.getModel())
                            .setPoolMin(baseConfig.getPoolMin())
                            .setPoolMax(baseConfig.getPoolMax())
                            .setTtlMinutes(baseConfig.getTtlMinutes())
                            .setForcePromptTools(baseConfig.isForcePromptTools())
                            .setTimeoutMs(baseConfig.getTimeoutMs());
                }
                builder.setPort(freePort);
                ProxyConfig cfg = builder.build();

                synchronized (CnbProxyServer.this) {
                    this.config = cfg;
                    this.server = new EmbeddedProxyServer(cfg, (tag, msg) -> {
                        if (logListener != null) logListener.onLog(tag, msg);
                    });
                }

                server.start();

                synchronized (CnbProxyServer.this) {
                    starting = false;
                }

                if (stateListener != null) {
                    stateListener.onStarted(freePort, getBaseUrl());
                }
            } catch (Exception e) {
                synchronized (CnbProxyServer.this) {
                    starting = false;
                    if (server != null) {
                        server.stop();
                        server = null;
                    }
                }
                if (stateListener != null) {
                    stateListener.onError(e.getMessage() != null ? e.getMessage() : "Failed to start proxy", e);
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
        if (stateListener != null) {
            stateListener.onStopped();
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
