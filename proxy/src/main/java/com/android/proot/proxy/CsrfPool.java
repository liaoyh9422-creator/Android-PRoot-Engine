package com.android.proot.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages anonymous CSRF tokens pool for Tencent CNB.
 * Scrapes window.csrftoken and csrfkey cookies without requiring user authentication or credentials.
 */
public final class CsrfPool {
    private static final String HOME = "https://cnb.cool/";
    private static final Pattern TOKEN = Pattern.compile("window\\.csrftoken\\s*=\\s*\"([0-9a-fA-F]{32,64})\"");
    private static final Pattern KEY = Pattern.compile("csrfkey=([0-9a-fA-F]{32,64})");
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36";

    public static final class CsrfToken {
        public final long created;
        public volatile int errCount;
        public volatile int inUse;
        public final String key;
        public final String token;
        public volatile boolean valid;

        public CsrfToken(String key, String token) {
            this.created = System.currentTimeMillis();
            this.valid = true;
            this.errCount = 0;
            this.inUse = 0;
            this.key = key;
            this.token = token;
        }
    }

    private final ProxyLogListener logger;
    private final ScheduledExecutorService maintenance;
    private final int minSize;
    private final int maxSize;
    private final long ttlMs;
    private final int timeoutMs;
    private final List<CsrfToken> tokens;
    private volatile boolean closed;

    public CsrfPool(ProxyConfig config, ProxyLogListener logger) {
        this.tokens = new ArrayList<>();
        this.maintenance = Executors.newSingleThreadScheduledExecutor();
        this.minSize = config.getPoolMin();
        this.maxSize = config.getPoolMax();
        this.ttlMs = config.getTtlMinutes() * 60_000L;
        this.timeoutMs = config.getTimeoutMs();
        this.logger = logger != null ? logger : (tag, msg) -> {};
    }

    public boolean start() {
        logger.onLog("POOL", "Fetching " + minSize + " independent CNB session token(s)...");
        CountDownLatch done = new CountDownLatch(minSize);
        int workerThreads = Math.max(1, Math.min(minSize, 4));
        ExecutorService workers = Executors.newFixedThreadPool(workerThreads);

        try {
            for (int i = 0; i < minSize; i++) {
                workers.execute(() -> {
                    try {
                        CsrfToken token = fetch();
                        if (token != null) {
                            synchronized (tokens) {
                                tokens.add(token);
                            }
                            logger.onLog("POOL", "Token acquired (pool size=" + size() + ")");
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }

            try {
                done.await(45, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            workers.shutdownNow();
        }

        if (size() == 0) {
            logger.onLog("POOL", "Failed: no usable CSRF token acquired from CNB");
            return false;
        }

        maintenance.scheduleAtFixedRate(this::maintain, 60, 60, TimeUnit.SECONDS);
        logger.onLog("POOL", "Ready: " + size() + " token(s) initialized (max=" + maxSize + ")");
        return true;
    }

    public void close() {
        closed = true;
        maintenance.shutdownNow();
        synchronized (tokens) {
            tokens.clear();
        }
    }

    public int size() {
        synchronized (tokens) {
            return tokens.size();
        }
    }

    public CsrfToken acquire() throws IOException {
        synchronized (tokens) {
            CsrfToken best = null;
            for (CsrfToken candidate : tokens) {
                if (usable(candidate)) {
                    if (best == null || candidate.inUse < best.inUse) {
                        best = candidate;
                    }
                }
            }

            if (best != null) {
                if (best.inUse == 0 || tokens.size() >= maxSize) {
                    best.inUse++;
                    return best;
                }
            }
        }

        // Try expanding pool if under maxSize
        if (size() < maxSize) {
            logger.onLog("POOL", "Expanding token pool for concurrent request...");
            CsrfToken created = fetch();
            if (created != null) {
                synchronized (tokens) {
                    created.inUse = 1;
                    tokens.add(created);
                    return created;
                }
            }
        }

        synchronized (tokens) {
            CsrfToken best = null;
            for (CsrfToken candidate : tokens) {
                if (usable(candidate)) {
                    if (best == null || candidate.inUse < best.inUse) {
                        best = candidate;
                    }
                }
            }
            if (best != null) {
                best.inUse++;
                return best;
            }
        }

        throw new IOException("no valid csrf token available");
    }

    public void report(CsrfToken token, boolean ok) {
        if (token == null) return;
        synchronized (tokens) {
            if (ok) {
                token.errCount = 0;
                token.valid = true;
            } else {
                token.errCount++;
                if (token.errCount >= 3) {
                    token.valid = false;
                }
            }
            token.inUse = Math.max(0, token.inUse - 1);
        }
    }

    private boolean usable(CsrfToken token) {
        return token != null && token.valid && token.errCount < 3 && (System.currentTimeMillis() - token.created < ttlMs);
    }

    private void maintain() {
        if (closed) return;
        synchronized (tokens) {
            tokens.removeIf(t -> !usable(t));
        }

        while (!closed && size() < minSize) {
            CsrfToken token = fetch();
            if (token == null) break;
            synchronized (tokens) {
                if (tokens.size() < maxSize) {
                    tokens.add(token);
                }
            }
        }
        logger.onLog("POOL", "Maintenance complete: size=" + size());
    }

    private CsrfToken fetch() {
        HttpURLConnection conn = null;
        try {
            URL current = new URL(HOME);
            List<String> cookieHeaders = new ArrayList<>();
            int status = 0;
            String body = "";

            for (int redirect = 0; redirect < 10; redirect++) {
                conn = (HttpURLConnection) current.openConnection();
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

                if (!cookieHeaders.isEmpty()) {
                    conn.setRequestProperty("Cookie", joinCookies(cookieHeaders));
                }

                status = conn.getResponseCode();
                addCookies(cookieHeaders, conn.getHeaderFields().get("Set-Cookie"));
                addCookies(cookieHeaders, conn.getHeaderFields().get("set-cookie"));

                if (status >= 300 && status < 400) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (location != null && !location.isEmpty()) {
                        current = new URL(current, location);
                        continue;
                    }
                    break;
                } else {
                    try (InputStream is = conn.getInputStream()) {
                        body = read(is, 0x800000); // 8MB
                    }
                    break;
                }
            }

            String token = "";
            Matcher tokenMatch = TOKEN.matcher(body);
            if (tokenMatch.find()) {
                token = tokenMatch.group(1);
            }

            String key = findCookie(cookieHeaders);

            if (status == 200 && !token.isEmpty() && !key.isEmpty()) {
                return new CsrfToken(key, token);
            }

            logger.onLog("POOL", "Token fetch rejected: http=" + status + " token=" + !token.isEmpty() + " cookie=" + !key.isEmpty());
            return null;
        } catch (Exception e) {
            logger.onLog("POOL", "Token fetch error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public JSONArray stats() {
        JSONArray result = new JSONArray();
        synchronized (tokens) {
            for (CsrfToken t : tokens) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("csrfkey", prefix(t.key));
                    obj.put("token", prefix(t.token));
                    obj.put("valid", t.valid);
                    obj.put("err_cnt", t.errCount);
                    obj.put("in_use", t.inUse);
                    long leftSec = Math.max(0, (ttlMs - (System.currentTimeMillis() - t.created)) / 1000);
                    obj.put("ttl_left", leftSec + "s");
                    result.put(obj);
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    private static void addCookies(List<String> target, List<String> source) {
        if (source != null && !source.isEmpty()) {
            target.addAll(source);
        }
    }

    private static String findCookie(List<String> cookies) {
        if (cookies == null) return "";
        for (String c : cookies) {
            if (c == null) continue;
            Matcher m = KEY.matcher(c);
            if (m.find()) {
                return m.group(1);
            }
        }
        return "";
    }

    private static String joinCookies(List<String> cookies) {
        if (cookies == null) return "";
        StringBuilder out = new StringBuilder();
        for (String c : cookies) {
            if (c == null) continue;
            Matcher m = KEY.matcher(c);
            if (m.find()) {
                if (out.length() > 0) {
                    out.append("; ");
                }
                out.append("csrfkey=").append(m.group(1));
            }
        }
        return out.toString();
    }

    private static String read(InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
            total += n;
            if (total >= maxBytes) break;
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    private static String prefix(String value) {
        if (value == null) return "";
        return value.length() <= 8 ? value : value.substring(0, 8);
    }
}
