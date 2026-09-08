package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.proxy.CnbProxyServer;
import com.android.proot.proxy.ProxyConfig;
import com.android.proot.sample.I18n;
import com.android.proot.sample.R;
import com.android.proot.sample.ui.ProxyFloatingLogView;
import com.android.proot.sample.ui.UiTheme;

import org.json.JSONArray;

/**
 * Quick operations dialog for managing CNB Local Proxy gateway service, pool stats, and live diagnostics.
 */
public final class ProxyOpsDialog {

    public interface OnOpenConfigListener {
        void onOpenConfig();
    }

    private ProxyOpsDialog() {}

    public static void show(Activity activity, OnOpenConfigListener configListener) {
        if (activity == null || activity.isFinishing()) return;

        CnbProxyServer server = CnbProxyServer.getInstance();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiTheme.dp(activity, 16), UiTheme.dp(activity, 16), UiTheme.dp(activity, 16), UiTheme.dp(activity, 16));
        root.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));

        builder.setView(root);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        // Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("🌐 本地代理运维 (Local Proxy)");
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(tvTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView btnClose = new TextView(activity);
        btnClose.setText("✕");
        btnClose.setTextColor(Color.parseColor(UiTheme.C_DIM));
        btnClose.setTextSize(18f);
        btnClose.setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 4), UiTheme.dp(activity, 4), UiTheme.dp(activity, 4));
        btnClose.setOnClickListener(v -> dialog.dismiss());
        header.addView(btnClose);
        root.addView(header);

        // Status Card
        LinearLayout cardStatus = new LinearLayout(activity);
        cardStatus.setOrientation(LinearLayout.VERTICAL);
        cardStatus.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));
        cardStatus.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 10), UiTheme.dp(activity, 12), UiTheme.dp(activity, 10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = UiTheme.dp(activity, 12);
        cardStatus.setLayoutParams(cardLp);

        TextView tvStateDot = new TextView(activity);
        tvStateDot.setTextSize(13f);
        tvStateDot.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        TextView tvEndpoint = new TextView(activity);
        tvEndpoint.setTextSize(11f);
        tvEndpoint.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvEndpoint.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams endLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        endLp.topMargin = UiTheme.dp(activity, 4);
        tvEndpoint.setLayoutParams(endLp);

        TextView tvPool = new TextView(activity);
        tvPool.setTextSize(11f);
        tvPool.setTextColor(Color.parseColor(UiTheme.C_PURPLE));
        LinearLayout.LayoutParams poolLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        poolLp.topMargin = UiTheme.dp(activity, 4);
        tvPool.setLayoutParams(poolLp);

        cardStatus.addView(tvStateDot);
        cardStatus.addView(tvEndpoint);
        cardStatus.addView(tvPool);
        root.addView(cardStatus);

        // Operation Buttons Row 1: Start/Restart & Stop
        LinearLayout row1 = new LinearLayout(activity);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row1Lp.topMargin = UiTheme.dp(activity, 12);
        row1.setLayoutParams(row1Lp);

        TextView btnToggle = new TextView(activity);
        btnToggle.setGravity(Gravity.CENTER);
        btnToggle.setTextSize(12f);
        btnToggle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView btnStop = new TextView(activity);
        btnStop.setGravity(Gravity.CENTER);
        btnStop.setTextSize(12f);
        btnStop.setText("停止服务");
        styleCapsule(activity, btnStop, UiTheme.C_RED, UiTheme.C_RED_BG, UiTheme.C_RED);
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        stopLp.leftMargin = UiTheme.dp(activity, 8);
        btnStop.setLayoutParams(stopLp);

        row1.addView(btnToggle);
        row1.addView(btnStop);
        root.addView(row1);

        // Operation Buttons Row 2: Live Diag & Floating Log
        LinearLayout row2 = new LinearLayout(activity);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = UiTheme.dp(activity, 8);
        row2.setLayoutParams(row2Lp);

        TextView btnDiag = new TextView(activity);
        btnDiag.setGravity(Gravity.CENTER);
        btnDiag.setTextSize(12f);
        btnDiag.setText("⚡ 一键验活 (Diag)");
        styleCapsule(activity, btnDiag, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);
        btnDiag.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView btnLogs = new TextView(activity);
        btnLogs.setGravity(Gravity.CENTER);
        btnLogs.setTextSize(12f);
        btnLogs.setText("📜 打开日志 HUD");
        styleCapsule(activity, btnLogs, UiTheme.C_YELLOW, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW);
        LinearLayout.LayoutParams logsLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        logsLp.leftMargin = UiTheme.dp(activity, 8);
        btnLogs.setLayoutParams(logsLp);

        row2.addView(btnDiag);
        row2.addView(btnLogs);
        root.addView(row2);

        // Operation Buttons Row 3: Go to AI Config
        TextView btnConfig = new TextView(activity);
        btnConfig.setGravity(Gravity.CENTER);
        btnConfig.setTextSize(12f);
        btnConfig.setText("⚙ 配置凭证与端点 (AI Config)");
        styleCapsule(activity, btnConfig, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        LinearLayout.LayoutParams cfgLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cfgLp.topMargin = UiTheme.dp(activity, 8);
        btnConfig.setLayoutParams(cfgLp);
        root.addView(btnConfig);

        Runnable refreshUi = () -> {
            if (activity.isFinishing()) return;
            activity.runOnUiThread(() -> {
                boolean running = server.isRunning();
                boolean starting = server.isStarting();

                if (running) {
                    tvStateDot.setText("● 运行中 (RUNNING)");
                    tvStateDot.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                    tvEndpoint.setText("监听端点: " + server.getBaseUrl());
                    JSONArray pool = server.getPoolStats();
                    tvPool.setText("Cookie 凭证池: " + (pool != null ? pool.length() : 0) + " 个有效凭证");
                    btnToggle.setText("重启代理 (Restart)");
                    styleCapsule(activity, btnToggle, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
                    btnStop.setVisibility(View.VISIBLE);
                } else if (starting) {
                    tvStateDot.setText("⏳ 启动中 (STARTING...)");
                    tvStateDot.setTextColor(Color.parseColor(UiTheme.C_YELLOW));
                    tvEndpoint.setText("正在绑定端口...");
                    tvPool.setText("");
                    btnToggle.setText("正在启动...");
                    styleCapsule(activity, btnToggle, UiTheme.C_DIM, UiTheme.C_SURFACE, UiTheme.C_BORDER_SUB);
                    btnStop.setVisibility(View.GONE);
                } else {
                    tvStateDot.setText("○ 已停止 (STOPPED)");
                    tvStateDot.setTextColor(Color.parseColor(UiTheme.C_DIM));
                    tvEndpoint.setText("默认端点: " + server.getBaseUrl());
                    tvPool.setText("服务未运行");
                    btnToggle.setText("启动代理 (Start)");
                    styleCapsule(activity, btnToggle, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);
                    btnStop.setVisibility(View.GONE);
                }
            });
        };

        CnbProxyServer.StateListener listener = new CnbProxyServer.StateListener() {
            @Override public void onStarting() { refreshUi.run(); }
            @Override public void onStarted(int port, String baseUrl) { refreshUi.run(); }
            @Override public void onStopped() { refreshUi.run(); }
            @Override public void onError(String message, Throwable error) { refreshUi.run(); }
        };

        server.addStateListener(listener);
        dialog.setOnDismissListener(d -> server.removeStateListener(listener));

        btnToggle.setOnClickListener(v -> {
            if (server.isRunning()) {
                server.stop();
                server.startAsync(new ProxyConfig.Builder().build());
                Toast.makeText(activity, "正在重启本地代理...", Toast.LENGTH_SHORT).show();
            } else if (!server.isStarting()) {
                server.startAsync(new ProxyConfig.Builder().build());
                Toast.makeText(activity, "正在启动本地代理...", Toast.LENGTH_SHORT).show();
            }
        });

        btnStop.setOnClickListener(v -> {
            server.stop();
            Toast.makeText(activity, "本地代理服务已停止", Toast.LENGTH_SHORT).show();
        });

        btnDiag.setOnClickListener(v -> {
            dialog.dismiss();
            ProxyFloatingLogView logView = ProxyFloatingLogView.getInstance(activity);
            logView.showExpanded();
            logView.runDiagnostics();
            Toast.makeText(activity, "已展开悬浮诊断窗并开始测活", Toast.LENGTH_SHORT).show();
        });

        btnLogs.setOnClickListener(v -> {
            dialog.dismiss();
            ProxyFloatingLogView.getInstance(activity).showExpanded();
        });

        btnConfig.setOnClickListener(v -> {
            dialog.dismiss();
            if (configListener != null) configListener.onOpenConfig();
        });

        refreshUi.run();
        dialog.show();
    }

    private static void styleCapsule(Activity a, TextView btn, String textColor, String bgColor, String strokeColor) {
        if (btn == null) return;
        btn.setTextColor(Color.parseColor(textColor));
        btn.setBackground(UiTheme.roundRect(a, bgColor, strokeColor, 1, 6));
        btn.setPadding(UiTheme.dp(a, 10), UiTheme.dp(a, 8), UiTheme.dp(a, 10), UiTheme.dp(a, 8));
    }
}
