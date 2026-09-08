package com.android.proot.sample.helper;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Robust network probe & proxy penetration helper.
 * Uses strict HTTPS www.google.com/generate_204 to ensure genuine international proxy connectivity
 * without false positives from domestic CDNs or captive portals.
 */
public final class NetworkProbeHelper {

    public interface ProbeCallback {
        void onSuccess(long latencyMs, String channelInfo);
        void onError(String message);
    }

    // Strict gold-standard probe target: 100% blocked in mainland China without proxy
    private static final String GOOGLE_STRICT_HTTPS_204 = "https://www.google.com/generate_204";
    private static final int TIMEOUT_DIRECT_MS = 3000;
    private static final int TIMEOUT_LOOPBACK_MS = 2500;

    // Common local proxy ports for Clash, v2rayNG, Sing-box
    private static final int[] LOCAL_PROXY_PORTS = { 7890, 10809, 2080 };

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static Handler sMainHandler;

    private static volatile Proxy sActiveProxy = null;
    private static volatile String sActiveProxyDescription = null;

    static {
        try {
            // Disable JVM DNS negative caching to avoid temporary failures locking up the process
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
        } catch (Throwable ignored) {}
    }

    private static void postToMain(Runnable r) {
        if (sMainHandler == null) {
            try {
                if (Looper.getMainLooper() != null) {
                    sMainHandler = new Handler(Looper.getMainLooper());
                }
            } catch (Throwable ignored) {}
        }
        if (sMainHandler != null) {
            sMainHandler.post(r);
        } else {
            r.run();
        }
    }

    private NetworkProbeHelper() {}

    /**
     * Gets the currently active proxy found during probe penetration (or null if direct).
     */
    public static Proxy getActiveProxy() {
        return sActiveProxy;
    }

    /**
     * Gets a human-readable description of active proxy channel.
     */
    public static String getActiveProxyDescription() {
        return sActiveProxyDescription;
    }

    public static void setExplicitProxy(String host, int port) {
        sActiveProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        sActiveProxyDescription = host + ":" + port;
    }

    public static void clearActiveProxy() {
        sActiveProxy = null;
        sActiveProxyDescription = null;
    }

    /**
     * Tests connectivity with waterfall penetration:
     * 1. Direct HTTPS google.com probe
     * 2. Active VPN interface process binding
     * 3. Local loopback proxy penetration (127.0.0.1:7890 / 10809 / 2080)
     */
    public static void testGoogleConnectivity(Context context, ProbeCallback callback) {
        EXECUTOR.execute(() -> {
            long overallStart = System.currentTimeMillis();

            // Stage 1: Try direct strict HTTPS probe
            ProbeResult directResult = tryProbe(null, TIMEOUT_DIRECT_MS);
            if (directResult.success) {
                clearActiveProxy();
                final long latency = System.currentTimeMillis() - overallStart;
                postToMain(() -> callback.onSuccess(latency, ""));
                return;
            }

            // Stage 2: Bind to VPN network if active
            if (context != null) {
                try {
                    ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
                            .getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm != null) {
                        for (Network net : cm.getAllNetworks()) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    cm.bindProcessToNetwork(net);
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    ConnectivityManager.setProcessDefaultNetwork(net);
                                }
                                ProbeResult vpnResult = tryProbe(null, TIMEOUT_DIRECT_MS);
                                if (vpnResult.success) {
                                    clearActiveProxy();
                                    final long latency = System.currentTimeMillis() - overallStart;
                                    postToMain(() -> callback.onSuccess(latency, "VPN 通道"));
                                    return;
                                }
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // Stage 3: Local Loopback Penetration (bypasses Android split-tunneling restrictions)
            for (int port : LOCAL_PROXY_PORTS) {
                Proxy localProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
                ProbeResult proxyResult = tryProbe(localProxy, TIMEOUT_LOOPBACK_MS);
                if (proxyResult.success) {
                    sActiveProxy = localProxy;
                    sActiveProxyDescription = "127.0.0.1:" + port;
                    final long latency = System.currentTimeMillis() - overallStart;
                    postToMain(() -> callback.onSuccess(latency, "本地代理穿透 :" + port));
                    return;
                }
            }

            final String errorMsg = directResult.errorMessage != null ? directResult.errorMessage : "连接超时";
            postToMain(() -> callback.onError(errorMsg));
        });
    }

    public static void testGoogleConnectivity(ProbeCallback callback) {
        testGoogleConnectivity(null, callback);
    }

    private static class ProbeResult {
        final boolean success;
        final String errorMessage;

        ProbeResult(boolean success, String errorMessage) {
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }

    private static ProbeResult tryProbe(Proxy proxy, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(GOOGLE_STRICT_HTTPS_204);
            conn = (HttpURLConnection) (proxy != null ? url.openConnection(proxy) : url.openConnection());
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) PRoot-Probe/2.0");

            int code = conn.getResponseCode();
            if (code == 204 || code == 200) {
                return new ProbeResult(true, null);
            }
            return new ProbeResult(false, "HTTP " + code);
        } catch (Exception e) {
            return new ProbeResult(false, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }
}
