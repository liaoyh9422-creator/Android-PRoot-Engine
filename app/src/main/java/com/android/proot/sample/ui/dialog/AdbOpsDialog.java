package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
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
import com.android.proot.sample.tool.AdbManager;
import com.android.proot.sample.ui.UiTheme;

/**
 * Modern modal dialog managing Android Wireless Debugging (ADB):
 * Environmental health, Android 11+ wireless pairing, direct host:port connection,
 * device status polling, and iFlow CLI integration.
 */
public final class AdbOpsDialog {

    private AdbOpsDialog() {}

    public static void show(Activity activity, PRootEngine engine) {
        if (activity == null || activity.isFinishing() || engine == null) return;

        AdbManager adbManager = AdbManager.getInstance();
        boolean installed = adbManager.isInstalled(engine.getRootfsDir());

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
        tvTitle.setText("📱 ADB 无线调试 (Wireless Bridge)");
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

        TextView btnInstall = UiTheme.createButton(activity, "⚡ 一键安装 ADB", UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 5);
        btnInstall.setVisibility(installed ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams instLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, UiTheme.dp(activity, 32));
        instLp.setMarginStart(UiTheme.dp(activity, 10));
        btnInstall.setLayoutParams(instLp);
        statusRow.addView(btnInstall);

        root.addView(statusRow);
        addDivider(activity, root);

        // 3. Android 11+ Pairing Mode
        addSectionTitle(activity, root, "模式 A: 无线配对码连接 (ANDROID 11+)");
        TextView tvPairHint = new TextView(activity);
        tvPairHint.setText("在【开发者选项 → 无线调试 → 使用配对码配对设备】中查看");
        tvPairHint.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvPairHint.setTextSize(11f);
        tvPairHint.setPadding(0, 0, 0, UiTheme.dp(activity, 6));
        root.addView(tvPairHint);

        LinearLayout pairRow = new LinearLayout(activity);
        pairRow.setOrientation(LinearLayout.HORIZONTAL);

        EditText etPairHostPort = createInput(activity, "配对 IP:端口 (例 192.168.1.5:37123)");
        EditText etPairCode = createInput(activity, "6位配对码 (例 123456)");
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 38), 3);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 38), 2);
        p2.setMarginStart(UiTheme.dp(activity, 6));
        etPairHostPort.setLayoutParams(p1);
        etPairCode.setLayoutParams(p2);
        pairRow.addView(etPairHostPort);
        pairRow.addView(etPairCode);
        root.addView(pairRow);

        EditText etConnectPort = createInput(activity, "无线调试主端口 (例 41235，配对成功后连接)");
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 38));
        p3.topMargin = UiTheme.dp(activity, 6);
        etConnectPort.setLayoutParams(p3);
        root.addView(etConnectPort);

        TextView btnPairAndConnect = UiTheme.createButton(activity, "🔗 配对并建立连接", UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE, 6);
        LinearLayout.LayoutParams bpcLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 36));
        bpcLp.topMargin = UiTheme.dp(activity, 8);
        btnPairAndConnect.setLayoutParams(bpcLp);
        UiTheme.applyTactileFeedback(btnPairAndConnect);
        root.addView(btnPairAndConnect);

        addDivider(activity, root);

        // 4. Quick Direct Connect
        addSectionTitle(activity, root, "模式 B: IP/端口直连 (DIRECT CONNECT)");
        LinearLayout directRow = new LinearLayout(activity);
        directRow.setOrientation(LinearLayout.HORIZONTAL);

        EditText etDirectHostPort = createInput(activity, "目标地址 (默认 127.0.0.1:5555)");
        etDirectHostPort.setText("127.0.0.1:5555");
        TextView btnDirectConnect = UiTheme.createButton(activity, "⚡ 直连", UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN, 6);

        LinearLayout.LayoutParams d1 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 38), 3);
        LinearLayout.LayoutParams d2 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 38), 1);
        d2.setMarginStart(UiTheme.dp(activity, 6));
        etDirectHostPort.setLayoutParams(d1);
        btnDirectConnect.setLayoutParams(d2);
        UiTheme.applyTactileFeedback(btnDirectConnect);

        directRow.addView(etDirectHostPort);
        directRow.addView(btnDirectConnect);
        root.addView(directRow);

        addDivider(activity, root);

        // 5. Devices & Controls
        addSectionTitle(activity, root, "设备状态与操作 (DEVICES & ACTIONS)");
        LinearLayout actRow = new LinearLayout(activity);
        actRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnRefresh = UiTheme.createButton(activity, "🔄 刷新设备", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);
        TextView btnDisconnect = UiTheme.createButton(activity, "⏹ 断开所有", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);
        TextView btnKillServer = UiTheme.createButton(activity, "⚠ 重启服务", UiTheme.C_RED, UiTheme.C_RED_BG, UiTheme.C_RED, 5);

        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 34), 1);
        btnRefresh.setLayoutParams(actLp);
        LinearLayout.LayoutParams actLp2 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 34), 1);
        actLp2.setMarginStart(UiTheme.dp(activity, 6));
        btnDisconnect.setLayoutParams(actLp2);
        LinearLayout.LayoutParams actLp3 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 34), 1);
        actLp3.setMarginStart(UiTheme.dp(activity, 6));
        btnKillServer.setLayoutParams(actLp3);

        UiTheme.applyTactileFeedback(btnRefresh);
        UiTheme.applyTactileFeedback(btnDisconnect);
        UiTheme.applyTactileFeedback(btnKillServer);

        actRow.addView(btnRefresh);
        actRow.addView(btnDisconnect);
        actRow.addView(btnKillServer);
        root.addView(actRow);

        // 6. Terminal Console Log Output Box
        TextView tvConsoleOutput = new TextView(activity);
        tvConsoleOutput.setTextColor(Color.parseColor(UiTheme.C_CYAN));
        tvConsoleOutput.setTextSize(11f);
        tvConsoleOutput.setTypeface(Typeface.MONOSPACE);
        tvConsoleOutput.setBackground(UiTheme.roundRect(activity, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));
        tvConsoleOutput.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 8), UiTheme.dp(activity, 10), UiTheme.dp(activity, 8));
        tvConsoleOutput.setText("等待操作指令... (iFlow 终端中可直接执行 adb 命令)");
        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 100));
        outLp.topMargin = UiTheme.dp(activity, 10);
        tvConsoleOutput.setLayoutParams(outLp);
        root.addView(tvConsoleOutput);

        // Listeners
        btnInstall.setOnClickListener(v -> {
            btnInstall.setEnabled(false);
            btnInstall.setText("正在安装...");
            tvConsoleOutput.setText("正在从 Alpine 软件源拉取并安装 android-tools (包含 adb)...");
            adbManager.installAdb(engine, (ok, out) -> {
                btnInstall.setEnabled(true);
                btnInstall.setText("⚡ 一键安装 ADB");
                if (ok) {
                    btnInstall.setVisibility(View.GONE);
                    updateStatusBadge(activity, tvStatusBadge, true);
                    tvConsoleOutput.setText("✔ 安装成功！\n" + out);
                    Toast.makeText(activity, "ADB 工具已就绪", Toast.LENGTH_SHORT).show();
                } else {
                    tvConsoleOutput.setText("❌ 安装失败:\n" + out);
                }
            });
        });

        btnPairAndConnect.setOnClickListener(v -> {
            String hp = etPairHostPort.getText().toString().trim();
            String code = etPairCode.getText().toString().trim();
            String connectPortStr = etConnectPort.getText().toString().trim();
            if (hp.isEmpty() || code.isEmpty()) {
                Toast.makeText(activity, "请填写配对 IP:端口 和配对码", Toast.LENGTH_SHORT).show();
                return;
            }
            tvConsoleOutput.setText("正在执行 adb pair " + hp + " ...");
            adbManager.pair(engine, hp, code, (ok, out) -> {
                if (ok) {
                    tvConsoleOutput.setText("✔ 配对成功！\n" + out);
                    // Determine connect host and port
                    String host = hp.contains(":") ? hp.substring(0, hp.indexOf(":")) : "127.0.0.1";
                    String targetConnect = connectPortStr.isEmpty() ? hp : (host + ":" + connectPortStr);
                    tvConsoleOutput.append("\n正在连接至 " + targetConnect + " ...");
                    adbManager.connect(engine, targetConnect, (cOk, cOut) -> {
                        tvConsoleOutput.append("\n" + (cOk ? "✔ 连接成功:\n" : "❌ 连接失败:\n") + cOut);
                        adbManager.listDevices(engine, (dOk, dOut) -> tvConsoleOutput.append("\n\n" + dOut));
                    });
                } else {
                    tvConsoleOutput.setText("❌ 配对失败:\n" + out);
                }
            });
        });

        btnDirectConnect.setOnClickListener(v -> {
            String hp = etDirectHostPort.getText().toString().trim();
            if (hp.isEmpty()) {
                Toast.makeText(activity, "请输入有效的 IP:端口", Toast.LENGTH_SHORT).show();
                return;
            }
            tvConsoleOutput.setText("正在连接 " + hp + " ...");
            adbManager.connect(engine, hp, (ok, out) -> {
                tvConsoleOutput.setText((ok ? "✔ 连接结果:\n" : "❌ 连接失败:\n") + out);
                adbManager.listDevices(engine, (dOk, dOut) -> tvConsoleOutput.append("\n\n" + dOut));
            });
        });

        btnRefresh.setOnClickListener(v -> {
            tvConsoleOutput.setText("正在拉取设备列表 (adb devices -l) ...");
            adbManager.listDevices(engine, (ok, out) -> tvConsoleOutput.setText(out));
        });

        btnDisconnect.setOnClickListener(v -> {
            tvConsoleOutput.setText("正在断开所有 ADB 连接...");
            adbManager.disconnectAll(engine, (ok, out) -> {
                tvConsoleOutput.setText(out.isEmpty() ? "已断开连接" : out);
                adbManager.listDevices(engine, (dOk, dOut) -> tvConsoleOutput.append("\n\n" + dOut));
            });
        });

        btnKillServer.setOnClickListener(v -> {
            tvConsoleOutput.setText("正在重启 ADB 守护服务 (adb kill-server)...");
            adbManager.killServer(engine, (ok, out) -> tvConsoleOutput.setText("ADB 服务已终止，下次调用将自动重启。"));
        });

        dialog.show();

        // Initial polling if installed
        if (installed) {
            adbManager.listDevices(engine, (ok, out) -> {
                if (ok && !out.isEmpty()) {
                    tvConsoleOutput.setText("已连接设备:\n" + out);
                }
            });
        }
    }

    private static void updateStatusBadge(Activity a, TextView badge, boolean installed) {
        badge.setText(installed ? "✔ 已就绪 (/usr/bin/adb)" : "⚠ 未检测到 ADB 工具");
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
        et.setSingleLine(true);
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
