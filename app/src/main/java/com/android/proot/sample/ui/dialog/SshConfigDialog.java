package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.PRootEngine;
import com.android.proot.sample.service.FtpServerManager;
import com.android.proot.sample.service.SshServerManager;
import com.android.proot.sample.ui.UiTheme;

/**
 * Modern modal dialog for configuring and managing Dropbear SSH server:
 * Environment verification, 1-click installer, toggle, port, root password, and connection command copy.
 */
public final class SshConfigDialog {

    private SshConfigDialog() {}

    public static void show(Activity activity, PRootEngine engine) {
        if (activity == null || activity.isFinishing() || engine == null) return;

        SshServerManager ssh = SshServerManager.getInstance();
        boolean installed = ssh.isInstalled(engine.getRootfsDir());

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        ScrollView scrollRoot = new ScrollView(activity);
        scrollRoot.setFillViewport(true);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiTheme.dp(activity, 16), UiTheme.dp(activity, 16), UiTheme.dp(activity, 16), UiTheme.dp(activity, 16));
        root.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));
        scrollRoot.addView(root);

        builder.setView(scrollRoot);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        // 1. Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, UiTheme.dp(activity, 10));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("🔒 SSH 远程终端服务 (Dropbear)");
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView btnClose = new TextView(activity);
        btnClose.setText("✕");
        btnClose.setTextColor(Color.parseColor(UiTheme.C_DIM));
        btnClose.setTextSize(16f);
        btnClose.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 4), UiTheme.dp(activity, 6), UiTheme.dp(activity, 4));
        btnClose.setOnClickListener(v -> dialog.dismiss());
        UiTheme.applyTactileFeedback(btnClose);
        header.addView(btnClose);
        root.addView(header);

        addDivider(activity, root);

        // 2. Environment Status
        addSectionTitle(activity, root, "环境安装状态 (STATUS)");
        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvStatusBadge = new TextView(activity);
        updateStatusBadge(activity, tvStatusBadge, installed);
        statusRow.addView(tvStatusBadge);

        TextView btnInstall = UiTheme.createButton(activity, "⚡ 一键安装 SSH", UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 5);
        btnInstall.setVisibility(installed ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams instLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, UiTheme.dp(activity, 32));
        instLp.setMarginStart(UiTheme.dp(activity, 10));
        btnInstall.setLayoutParams(instLp);
        statusRow.addView(btnInstall);

        root.addView(statusRow);
        addDivider(activity, root);

        // 3. Live Server Status Card
        LinearLayout liveCard = new LinearLayout(activity);
        liveCard.setOrientation(LinearLayout.VERTICAL);
        liveCard.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 8));
        liveCard.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 10), UiTheme.dp(activity, 12), UiTheme.dp(activity, 10));

        LinearLayout liveLine = new LinearLayout(activity);
        liveLine.setOrientation(LinearLayout.HORIZONTAL);
        liveLine.setGravity(Gravity.CENTER_VERTICAL);

        View statusDot = UiTheme.createDot(activity, ssh.isRunning() ? UiTheme.C_GREEN : UiTheme.C_DIM, 8);
        liveLine.addView(statusDot);

        TextView tvRunningStatus = new TextView(activity);
        tvRunningStatus.setText(ssh.isRunning() ? " 守护进程运行中 (端口 " + ssh.getPort() + ")" : " 服务已停止");
        tvRunningStatus.setTextColor(Color.parseColor(ssh.isRunning() ? UiTheme.C_GREEN : UiTheme.C_DIM));
        tvRunningStatus.setTextSize(12f);
        tvRunningStatus.setTypeface(Typeface.DEFAULT_BOLD);
        tvRunningStatus.setPadding(UiTheme.dp(activity, 6), 0, 0, 0);
        liveLine.addView(tvRunningStatus);
        liveCard.addView(liveLine);

        String ip = FtpServerManager.getLocalIpAddress();
        String sshCmd = "ssh root@" + ip + " -p " + ssh.getPort();

        TextView tvCmd = new TextView(activity);
        tvCmd.setText(sshCmd);
        tvCmd.setTextColor(Color.parseColor(UiTheme.C_CYAN));
        tvCmd.setTextSize(13f);
        tvCmd.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvCmd.setPadding(0, UiTheme.dp(activity, 6), 0, UiTheme.dp(activity, 6));
        liveCard.addView(tvCmd);

        TextView btnCopyCmd = UiTheme.createButton(activity, "📋 复制终端连接指令", UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 5);
        btnCopyCmd.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("SSH Command", sshCmd));
                Toast.makeText(activity, "已复制: " + sshCmd, Toast.LENGTH_SHORT).show();
            }
        });
        UiTheme.applyTactileFeedback(btnCopyCmd);
        liveCard.addView(btnCopyCmd);
        root.addView(liveCard);

        addDivider(activity, root);

        // 4. Toggle Action
        TextView btnToggle = UiTheme.createButton(activity,
                ssh.isRunning() ? "⏹ 停止 SSH 服务" : "▶ 启动 SSH 服务",
                ssh.isRunning() ? UiTheme.C_RED : UiTheme.C_GREEN,
                ssh.isRunning() ? UiTheme.C_RED_BG : UiTheme.C_GREEN_BG,
                ssh.isRunning() ? UiTheme.C_RED : UiTheme.C_GREEN, 6);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 38));
        btnToggle.setLayoutParams(tLp);
        btnToggle.setEnabled(installed);
        UiTheme.applyTactileFeedback(btnToggle);
        root.addView(btnToggle);

        addDivider(activity, root);

        // 5. Configuration Settings
        addSectionTitle(activity, root, "参数与 root 密码配置 (SETTINGS)");

        EditText etPort = createInput(activity, "监听端口 (默认 2222)");
        etPort.setText(String.valueOf(ssh.getPort()));
        etPort.setEnabled(!ssh.isRunning());

        EditText etPass = createInput(activity, "root 用户密码 (默认 proot)");
        etPass.setText(ssh.getPassword());

        TextView btnSavePass = UiTheme.createButton(activity, "💾 更新 root 密码", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 34));
        pLp.topMargin = UiTheme.dp(activity, 6);
        btnSavePass.setLayoutParams(pLp);
        UiTheme.applyTactileFeedback(btnSavePass);

        root.addView(etPort);
        root.addView(etPass);
        root.addView(btnSavePass);

        // Listeners
        btnInstall.setOnClickListener(v -> {
            btnInstall.setEnabled(false);
            btnInstall.setText("正在安装 Dropbear...");
            ssh.installSsh(engine, (ok, msg) -> {
                btnInstall.setEnabled(true);
                btnInstall.setText("⚡ 一键安装 SSH");
                if (ok) {
                    btnInstall.setVisibility(View.GONE);
                    btnToggle.setEnabled(true);
                    updateStatusBadge(activity, tvStatusBadge, true);
                    Toast.makeText(activity, "Dropbear SSH 安装成功！", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                }
            });
        });

        btnSavePass.setOnClickListener(v -> {
            String p = etPass.getText().toString().trim();
            if (p.isEmpty()) {
                Toast.makeText(activity, "密码不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            ssh.updatePassword(engine, p, (ok, msg) -> {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            });
        });

        btnToggle.setOnClickListener(v -> {
            if (ssh.isRunning()) {
                ssh.stopServer(engine, (ok, msg) -> {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
            } else {
                try {
                    int p = Integer.parseInt(etPort.getText().toString().trim());
                    ssh.setPort(p);
                } catch (Exception ignored) {}

                ssh.setPassword(etPass.getText().toString().trim());
                ssh.startServer(engine, (ok, msg) -> {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
            }
        });

        dialog.show();
    }

    private static void updateStatusBadge(Activity a, TextView badge, boolean installed) {
        badge.setText(installed ? "✔ 已就绪 (/usr/sbin/dropbear)" : "⚠ 未检测到 SSH 服务");
        badge.setTextColor(Color.parseColor(installed ? UiTheme.C_GREEN : UiTheme.C_YELLOW));
        badge.setBackground(UiTheme.roundRect(a, installed ? UiTheme.C_GREEN_BG : UiTheme.C_YELLOW_BG,
                installed ? UiTheme.C_GREEN : UiTheme.C_YELLOW, 1, 5));
        badge.setPadding(UiTheme.dp(a, 8), UiTheme.dp(a, 4), UiTheme.dp(a, 8), UiTheme.dp(a, 4));
        badge.setTextSize(11f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private static EditText createInput(Activity a, String hint) {
        EditText et = new EditText(a);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor(UiTheme.C_DIM));
        et.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        et.setTextSize(12f);
        et.setTypeface(Typeface.MONOSPACE);
        et.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));
        et.setPadding(UiTheme.dp(a, 10), UiTheme.dp(a, 6), UiTheme.dp(a, 10), UiTheme.dp(a, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiTheme.dp(a, 6);
        et.setLayoutParams(lp);
        return et;
    }

    private static void addSectionTitle(Activity a, LinearLayout root, String title) {
        TextView tv = new TextView(a);
        tv.setText(title);
        tv.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tv.setTextSize(10f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, UiTheme.dp(a, 4), 0, UiTheme.dp(a, 6));
        root.addView(tv);
    }

    private static void addDivider(Activity a, LinearLayout root) {
        View div = new View(a);
        div.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, UiTheme.dp(a, 8), 0, UiTheme.dp(a, 6));
        div.setLayoutParams(lp);
        root.addView(div);
    }
}
