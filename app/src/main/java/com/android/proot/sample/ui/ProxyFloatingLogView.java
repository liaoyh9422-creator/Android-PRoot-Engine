package com.android.proot.sample.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.proot.proxy.CnbProxyServer;
import com.android.proot.proxy.ProxyLogListener;
import com.android.proot.sample.I18n;
import com.android.proot.sample.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Movable in-app floating overlay providing real-time CNB proxy logs and live diagnostics.
 */
public final class ProxyFloatingLogView implements ProxyLogListener {
    private static ProxyFloatingLogView sInstance;

    private final Activity activity;
    private final Handler mainHandler;
    private final ExecutorService diagExecutor;

    private FrameLayout rootOverlay;
    private LinearLayout miniCapsule;
    private TextView tvCapsuleStatusDot;
    private TextView tvCapsuleLabel;

    private LinearLayout expandedWindow;
    private TextView tvWindowTitle;
    private TextView btnDiag;
    private TextView btnClear;
    private TextView btnMin;
    private TextView btnClose;
    private ScrollView scrollLog;
    private TextView tvLogBody;
    private TextView tvFooterStatus;

    private float dX, dY;
    private float startX, startY;

    public static synchronized ProxyFloatingLogView getInstance(Activity activity) {
        if (sInstance == null || sInstance.activity != activity) {
            if (sInstance != null) {
                sInstance.destroy();
            }
            sInstance = new ProxyFloatingLogView(activity);
        }
        return sInstance;
    }

    private ProxyFloatingLogView(Activity activity) {
        this.activity = activity;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.diagExecutor = Executors.newSingleThreadExecutor();
        initView();
        CnbProxyServer.getInstance().addLogListener(this);
    }

    private void initView() {
        ViewGroup decor = (ViewGroup) activity.findViewById(android.R.id.content);
        View view = LayoutInflater.from(activity).inflate(R.layout.view_proxy_floating_log, decor, false);
        rootOverlay = (FrameLayout) view;
        decor.addView(rootOverlay);

        miniCapsule = rootOverlay.findViewById(R.id.layout_mini_capsule);
        tvCapsuleStatusDot = rootOverlay.findViewById(R.id.tv_capsule_status_dot);
        tvCapsuleLabel = rootOverlay.findViewById(R.id.tv_capsule_label);

        expandedWindow = rootOverlay.findViewById(R.id.layout_expanded_window);
        View windowHeader = rootOverlay.findViewById(R.id.layout_window_header);
        tvWindowTitle = rootOverlay.findViewById(R.id.tv_window_title);
        btnDiag = rootOverlay.findViewById(R.id.btn_diag);
        btnClear = rootOverlay.findViewById(R.id.btn_clear);
        btnMin = rootOverlay.findViewById(R.id.btn_min);
        btnClose = rootOverlay.findViewById(R.id.btn_close);
        scrollLog = rootOverlay.findViewById(R.id.scroll_log);
        tvLogBody = rootOverlay.findViewById(R.id.tv_log_body);
        tvFooterStatus = rootOverlay.findViewById(R.id.tv_footer_status);

        // Styling
        miniCapsule.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_CYAN, 1, 14));
        expandedWindow.setBackground(UiTheme.roundRect(activity, "#161B22F2", UiTheme.C_BORDER, 1, 10));

        UiTheme.styleCapsule(activity, btnDiag, UiTheme.C_YELLOW, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW);
        UiTheme.styleCapsule(activity, btnClear, UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        UiTheme.styleCapsule(activity, btnMin, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        UiTheme.styleCapsule(activity, btnClose, UiTheme.C_RED, UiTheme.C_RED_BG, UiTheme.C_RED);

        btnDiag.setText(I18n.get(I18n.Key.BTN_DIAGNOSTICS));
        btnClear.setText(I18n.get(I18n.Key.BTN_CLEAR_LOGS));
        btnMin.setText(I18n.get(I18n.Key.BTN_MINIMIZE));

        // Drag mini capsule
        miniCapsule.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    startX = event.getRawX();
                    startY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v.setX(event.getRawX() + dX);
                    v.setY(event.getRawY() + dY);
                    return true;
                case MotionEvent.ACTION_UP:
                    float diffX = Math.abs(event.getRawX() - startX);
                    float diffY = Math.abs(event.getRawY() - startY);
                    if (diffX < 10 && diffY < 10) {
                        showExpanded();
                    }
                    return true;
            }
            return false;
        });

        // Drag expanded window via header
        windowHeader.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX = expandedWindow.getX() - event.getRawX();
                    dY = expandedWindow.getY() - event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    expandedWindow.setX(event.getRawX() + dX);
                    expandedWindow.setY(event.getRawY() + dY);
                    return true;
            }
            return false;
        });

        btnMin.setOnClickListener(v -> showMiniCapsule());
        btnClose.setOnClickListener(v -> hideAll());
        btnClear.setOnClickListener(v -> {
            CnbProxyServer.getInstance().clearLogs();
            tvLogBody.setText("");
        });
        btnDiag.setOnClickListener(v -> runDiagnostics());

        // Populate recent logs
        loadRecentLogs();
        updateFooterStatus();
    }

    public void showMiniCapsule() {
        mainHandler.post(() -> {
            expandedWindow.setVisibility(View.GONE);
            miniCapsule.setVisibility(View.VISIBLE);
            updateFooterStatus();
        });
    }

    public void showExpanded() {
        mainHandler.post(() -> {
            miniCapsule.setVisibility(View.GONE);
            expandedWindow.setVisibility(View.VISIBLE);
            loadRecentLogs();
            updateFooterStatus();
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    public void hideAll() {
        mainHandler.post(() -> {
            miniCapsule.setVisibility(View.GONE);
            expandedWindow.setVisibility(View.GONE);
        });
    }

    public boolean isVisible() {
        return miniCapsule.getVisibility() == View.VISIBLE || expandedWindow.getVisibility() == View.VISIBLE;
    }

    private void loadRecentLogs() {
        List<String> logs = CnbProxyServer.getInstance().getRecentLogs();
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        for (String line : logs) {
            appendFormattedLine(ssb, line);
        }
        tvLogBody.setText(ssb);
    }

    @Override
    public void onLog(String tag, String message) {
        mainHandler.post(() -> {
            if (expandedWindow.getVisibility() == View.VISIBLE) {
                SpannableStringBuilder ssb = new SpannableStringBuilder();
                appendFormattedLine(ssb, message);
                tvLogBody.append(ssb);
                scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
            }
            updateFooterStatus();
        });
    }

    private void appendFormattedLine(SpannableStringBuilder ssb, String line) {
        int start = ssb.length();
        ssb.append(line).append("\n");
        int end = ssb.length();

        int color = Color.parseColor("#C9D1D9");
        if (line.contains("[POOL]")) color = Color.parseColor(UiTheme.C_CYAN);
        else if (line.contains("[REQ]")) color = Color.parseColor(UiTheme.C_BLUE);
        else if (line.contains("[UP]")) color = Color.parseColor(UiTheme.C_GREEN);
        else if (line.contains("[RESP]")) color = Color.parseColor(UiTheme.C_GREEN);
        else if (line.contains("[TOOL]")) color = Color.parseColor(UiTheme.C_PURPLE);
        else if (line.contains("[TEST]")) color = Color.parseColor(UiTheme.C_YELLOW);
        else if (line.contains("[ERROR]") || line.contains("[ERR]")) color = Color.parseColor(UiTheme.C_RED);
        else if (line.contains("[GATEWAY]") || line.contains("[HTTP]")) color = Color.parseColor(UiTheme.C_DIM);

        ssb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void updateFooterStatus() {
        boolean running = CnbProxyServer.getInstance().isRunning();
        boolean starting = CnbProxyServer.getInstance().isStarting();
        int port = CnbProxyServer.getInstance().getActualPort();
        JSONArray pool = CnbProxyServer.getInstance().getPoolStats();

        tvCapsuleLabel.setText("CNB :" + port + " 📜");
        if (running) {
            tvCapsuleStatusDot.setTextColor(Color.parseColor(UiTheme.C_GREEN));
            tvFooterStatus.setText(String.format("● 运行中 | 端口: %d | 凭证: %d个有效", port, pool.length()));
            tvFooterStatus.setTextColor(Color.parseColor(UiTheme.C_GREEN));
        } else if (starting) {
            tvCapsuleStatusDot.setTextColor(Color.parseColor(UiTheme.C_YELLOW));
            tvFooterStatus.setText(String.format("● 启动中... | 端口: %d", port));
            tvFooterStatus.setTextColor(Color.parseColor(UiTheme.C_YELLOW));
        } else {
            tvCapsuleStatusDot.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tvFooterStatus.setText("● 未运行");
            tvFooterStatus.setTextColor(Color.parseColor(UiTheme.C_DIM));
        }
    }

    public void runDiagnostics() {
        btnDiag.setEnabled(false);
        CnbProxyServer.getInstance().logMessage("TEST", "========================================");
        CnbProxyServer.getInstance().logMessage("TEST", "开始执行 CNB 代理全链路验活诊断...");

        diagExecutor.execute(() -> {
            int port = CnbProxyServer.getInstance().getActualPort();
            try {
                // 1. Check local port /healthz
                long t0 = System.currentTimeMillis();
                HttpURLConnection connHealth = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/healthz").openConnection();
                connHealth.setConnectTimeout(4000);
                connHealth.setReadTimeout(4000);
                int codeHealth = connHealth.getResponseCode();
                long latencyHealth = System.currentTimeMillis() - t0;
                connHealth.disconnect();

                if (codeHealth == 200) {
                    CnbProxyServer.getInstance().logMessage("TEST", "1/3 本地服务探测正常: 127.0.0.1:" + port + " (" + latencyHealth + "ms)");
                } else {
                    CnbProxyServer.getInstance().logMessage("TEST", "❌ 1/3 本地服务探测异常: HTTP " + codeHealth);
                    return;
                }

                // 2. Check token pool
                HttpURLConnection connPool = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/pool").openConnection();
                connPool.setConnectTimeout(4000);
                connPool.setReadTimeout(4000);
                int codePool = connPool.getResponseCode();
                if (codePool == 200) {
                    InputStream is = connPool.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    JSONObject pObj = new JSONObject(baos.toString(StandardCharsets.UTF_8.name()));
                    JSONArray pArr = pObj.optJSONArray("pool");
                    int validCount = pArr != null ? pArr.length() : 0;
                    String ttlStr = validCount > 0 ? pArr.getJSONObject(0).optString("ttl_left", "?") : "0s";
                    CnbProxyServer.getInstance().logMessage("TEST", "2/3 腾讯 CNB 会话凭证池: 有效凭证 " + validCount + " 个 (最新TTL: " + ttlStr + ")");
                } else {
                    CnbProxyServer.getInstance().logMessage("TEST", "❌ 2/3 凭证池获取异常: HTTP " + codePool);
                }
                connPool.disconnect();

                // 3. Live chat completion
                CnbProxyServer.getInstance().logMessage("TEST", "3/3 发起上游真机测试 (请求 Tencent CNB /v1/chat/completions)...");
                long tChat = System.currentTimeMillis();
                HttpURLConnection connChat = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/chat/completions").openConnection();
                connChat.setRequestMethod("POST");
                connChat.setRequestProperty("Content-Type", "application/json");
                connChat.setDoOutput(true);
                connChat.setConnectTimeout(15000);
                connChat.setReadTimeout(60000);

                JSONObject testReq = new JSONObject();
                testReq.put("model", "deepseek-v4-flash");
                testReq.put("stream", false);
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "user").put("content", "ping"));
                testReq.put("messages", msgs);

                byte[] reqBytes = testReq.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = connChat.getOutputStream()) {
                    os.write(reqBytes);
                }

                int codeChat = connChat.getResponseCode();
                long latencyChat = System.currentTimeMillis() - tChat;

                if (codeChat == 200) {
                    InputStream is = connChat.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    JSONObject resp = new JSONObject(baos.toString(StandardCharsets.UTF_8.name()));
                    JSONArray choices = resp.optJSONArray("choices");
                    String reply = "";
                    if (choices != null && choices.length() > 0) {
                        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                        if (msg != null) reply = msg.optString("content", "");
                    }
                    reply = reply.replace("\n", " ").trim();
                    if (reply.length() > 30) reply = reply.substring(0, 30) + "...";
                    CnbProxyServer.getInstance().logMessage("TEST", "上游响应成功: 耗时 " + latencyChat + "ms | 模型回复: \"" + reply + "\"");
                    CnbProxyServer.getInstance().logMessage("TEST", "✅ 验活成功: 本地代理与腾讯 CNB 真实链路 100% 畅通可用！");
                } else {
                    CnbProxyServer.getInstance().logMessage("TEST", "❌ 上游测试失败: HTTP " + codeChat);
                }
                connChat.disconnect();
            } catch (Exception e) {
                CnbProxyServer.getInstance().logMessage("TEST", "❌ 验活中断: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            } finally {
                CnbProxyServer.getInstance().logMessage("TEST", "========================================");
                mainHandler.post(() -> btnDiag.setEnabled(true));
            }
        });
    }

    public void destroy() {
        CnbProxyServer.getInstance().removeLogListener(this);
        diagExecutor.shutdownNow();
        if (rootOverlay != null && rootOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) rootOverlay.getParent()).removeView(rootOverlay);
        }
        if (sInstance == this) {
            sInstance = null;
        }
    }
}
