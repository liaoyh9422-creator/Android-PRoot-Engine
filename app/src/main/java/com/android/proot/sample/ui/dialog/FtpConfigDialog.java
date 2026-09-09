package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.sample.service.FtpServerManager;
import com.android.proot.sample.ui.UiTheme;

import java.io.File;

/**
 * Modern modal dialog for configuring and controlling the built-in FTP file server:
 * Server toggle, IP:Port display, credentials, root folder binding, and one-click URL copy.
 */
public final class FtpConfigDialog {

    private FtpConfigDialog() {}

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        FtpServerManager ftp = FtpServerManager.getInstance();

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
        UiTheme.configureDialogWindow(dialog);

        // 1. Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, UiTheme.dp(activity, 10));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("📁 FTP 文件传输服务 (FTP Server)");
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

        // 2. Live Status Card
        LinearLayout statusCard = new LinearLayout(activity);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 8));
        statusCard.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 10), UiTheme.dp(activity, 12), UiTheme.dp(activity, 10));

        LinearLayout statusLine = new LinearLayout(activity);
        statusLine.setOrientation(LinearLayout.HORIZONTAL);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);

        View statusDot = UiTheme.createDot(activity, ftp.isRunning() ? UiTheme.C_GREEN : UiTheme.C_DIM, 8);
        statusLine.addView(statusDot);

        TextView tvStatus = new TextView(activity);
        tvStatus.setText(ftp.isRunning() ? " 服务运行中" : " 服务已停止");
        tvStatus.setTextColor(Color.parseColor(ftp.isRunning() ? UiTheme.C_GREEN : UiTheme.C_DIM));
        tvStatus.setTextSize(12.5f);
        tvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        tvStatus.setPadding(UiTheme.dp(activity, 6), 0, 0, 0);
        statusLine.addView(tvStatus);
        statusCard.addView(statusLine);

        String ip = FtpServerManager.getLocalIpAddress();
        int port = ftp.getPort();
        String ftpUrl = "ftp://" + ip + ":" + port;

        TextView tvUrl = new TextView(activity);
        tvUrl.setText(ftpUrl);
        tvUrl.setTextColor(Color.parseColor(UiTheme.C_CYAN));
        tvUrl.setTextSize(14f);
        tvUrl.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvUrl.setPadding(0, UiTheme.dp(activity, 6), 0, UiTheme.dp(activity, 6));
        statusCard.addView(tvUrl);

        TextView btnCopyUrl = UiTheme.createButton(activity, "📋 复制完整 FTP 连接地址", UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 5);
        btnCopyUrl.setOnClickListener(v -> {
            String full = "ftp://" + ftp.getUsername() + ":" + ftp.getPassword() + "@" + ip + ":" + port;
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("FTP URL", full));
                Toast.makeText(activity, "已复制: " + full, Toast.LENGTH_SHORT).show();
            }
        });
        UiTheme.applyTactileFeedback(btnCopyUrl);
        statusCard.addView(btnCopyUrl);
        root.addView(statusCard);

        addDivider(activity, root);

        // 3. Service Toggle Action
        TextView btnToggle = UiTheme.createButton(activity,
                ftp.isRunning() ? "⏹ 停止 FTP 服务" : "▶ 启动 FTP 服务",
                ftp.isRunning() ? UiTheme.C_RED : UiTheme.C_GREEN,
                ftp.isRunning() ? UiTheme.C_RED_BG : UiTheme.C_GREEN_BG,
                ftp.isRunning() ? UiTheme.C_RED : UiTheme.C_GREEN, 6);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 38));
        btnToggle.setLayoutParams(tLp);
        UiTheme.applyTactileFeedback(btnToggle);
        root.addView(btnToggle);

        addDivider(activity, root);

        // 4. Configuration Inputs
        addSectionTitle(activity, root, "网络与认证参数 (SETTINGS)");

        EditText etPort = createInput(activity, "监听端口 (默认 2121)");
        etPort.setText(String.valueOf(ftp.getPort()));
        etPort.setEnabled(!ftp.isRunning());

        EditText etUser = createInput(activity, "用户名 (默认 proot)");
        etUser.setText(ftp.getUsername());
        etUser.setEnabled(!ftp.isRunning());

        EditText etPass = createInput(activity, "密码 (默认 proot)");
        etPass.setText(ftp.getPassword());
        etPass.setEnabled(!ftp.isRunning());

        CheckBox cbAnon = new CheckBox(activity);
        cbAnon.setText("允许匿名登录 (无需密码)");
        cbAnon.setTextColor(Color.parseColor(UiTheme.C_DIM));
        cbAnon.setTextSize(11.5f);
        cbAnon.setChecked(ftp.isAnonymous());
        cbAnon.setEnabled(!ftp.isRunning());

        root.addView(etPort);
        root.addView(etUser);
        root.addView(etPass);
        root.addView(cbAnon);

        addDivider(activity, root);

        // 5. Root Directory Selector
        addSectionTitle(activity, root, "FTP 根目录绑定 (ROOT DIRECTORY)");
        RadioGroup rg = new RadioGroup(activity);
        rg.setEnabled(!ftp.isRunning());

        RadioButton rbWorkspace = new RadioButton(activity);
        rbWorkspace.setText("📁 默认工作区 (files/workspace) [推荐]");
        rbWorkspace.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        rbWorkspace.setTextSize(11.5f);

        RadioButton rbFiles = new RadioButton(activity);
        rbFiles.setText("📦 应用全部私有文件目录 (filesDir)");
        rbFiles.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        rbFiles.setTextSize(11.5f);

        RadioButton rbSdcard = new RadioButton(activity);
        rbSdcard.setText("📱 手机外部存储 (/sdcard)");
        rbSdcard.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        rbSdcard.setTextSize(11.5f);

        rg.addView(rbWorkspace);
        rg.addView(rbFiles);
        rg.addView(rbSdcard);

        File currentRoot = ftp.getRootDir();
        if (currentRoot != null && currentRoot.getAbsolutePath().equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            rbSdcard.setChecked(true);
        } else if (currentRoot != null && currentRoot.getAbsolutePath().equals(activity.getFilesDir().getAbsolutePath())) {
            rbFiles.setChecked(true);
        } else {
            rbWorkspace.setChecked(true);
        }

        root.addView(rg);

        // Toggle Click Listener
        btnToggle.setOnClickListener(v -> {
            if (ftp.isRunning()) {
                ftp.stop();
                dialog.dismiss();
                Toast.makeText(activity, "FTP 服务已停止", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    int p = Integer.parseInt(etPort.getText().toString().trim());
                    ftp.setPort(p);
                } catch (Exception ignored) {}

                ftp.setUsername(etUser.getText().toString().trim());
                ftp.setPassword(etPass.getText().toString().trim());
                ftp.setAnonymous(cbAnon.isChecked());

                if (rbSdcard.isChecked()) {
                    ftp.setRootDir(Environment.getExternalStorageDirectory());
                } else if (rbFiles.isChecked()) {
                    ftp.setRootDir(activity.getFilesDir());
                } else {
                    File ws = new File(activity.getFilesDir(), "workspace");
                    if (!ws.exists()) ws.mkdirs();
                    ftp.setRootDir(ws);
                }

                ftp.start(activity, (running, msg) -> {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                });
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    private static EditText createInput(Activity a, String hint) {
        EditText et = new EditText(a);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor(UiTheme.C_DIM));
        et.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        et.setTextSize(12f);
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
        tv.setPadding(0, UiTheme.dp(a, 4), 0, UiTheme.dp(a, 4));
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
