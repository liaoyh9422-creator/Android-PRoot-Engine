package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.sample.I18n;
import com.android.proot.sample.terminal.TerminalBridge;
import com.android.proot.sample.ui.ProxyFloatingLogView;
import com.android.proot.sample.ui.UiTheme;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

/**
 * Unified contextual modal dialog absorbing secondary settings and diagnostics:
 * Font size +/- scale, terminal clipboard copy/clear, i18n language switch,
 * system environment badges, and proxy diagnostics trigger.
 */
public final class MoreMenuDialog {

    public interface ActionListener {
        void onFontSizeChanged(int newSize);
        void onLanguageChanged();
        void onOpenAdb();
        void onOpenMcpSkills();
        void onOpenHealthStorage();
        void onOpenFtp();
        void onOpenSsh();
        void onShowGestureGuide();
        TerminalSession getCurrentSession();
        int getCurrentFontSize();
    }

    private MoreMenuDialog() {}

    public static void show(Activity activity, TerminalBridge terminalBridge, TerminalView terminalView, ActionListener listener) {
        if (activity == null || activity.isFinishing()) return;

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

        // 1. Header Row
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, UiTheme.dp(activity, 10));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("🛠 终端与系统控制 (Console Hub)");
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

        // 2. Font Size Scaling Section
        addSectionTitle(activity, root, "终端字号缩放 (FONT SIZE)");
        LinearLayout fontRow = new LinearLayout(activity);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnFontMinus = UiTheme.createButton(activity, "A- 缩小", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);
        TextView tvFontSize = new TextView(activity);
        tvFontSize.setTextColor(Color.parseColor(UiTheme.C_CYAN));
        tvFontSize.setTextSize(13f);
        tvFontSize.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvFontSize.setGravity(Gravity.CENTER);
        tvFontSize.setText((listener != null ? listener.getCurrentFontSize() : 12) + " sp");

        TextView btnFontPlus = UiTheme.createButton(activity, "A+ 放大", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 36), 1);
        btnFontMinus.setLayoutParams(btnLp);
        btnFontPlus.setLayoutParams(btnLp);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tvFontSize.setLayoutParams(labelLp);

        btnFontMinus.setOnClickListener(v -> {
            if (listener != null) {
                int cur = listener.getCurrentFontSize();
                int next = Math.max(cur - 1, 8);
                listener.onFontSizeChanged(next);
                tvFontSize.setText(next + " sp");
            }
        });
        btnFontPlus.setOnClickListener(v -> {
            if (listener != null) {
                int cur = listener.getCurrentFontSize();
                int next = Math.min(cur + 1, 32);
                listener.onFontSizeChanged(next);
                tvFontSize.setText(next + " sp");
            }
        });
        UiTheme.applyTactileFeedback(btnFontMinus);
        UiTheme.applyTactileFeedback(btnFontPlus);

        fontRow.addView(btnFontMinus);
        fontRow.addView(tvFontSize);
        fontRow.addView(btnFontPlus);
        root.addView(fontRow);

        addDivider(activity, root);

        // 3. Terminal Fast Actions
        addSectionTitle(activity, root, "终端缓冲区管理 (BUFFER ACTIONS)");
        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnCopy = UiTheme.createButton(activity, "📋 " + I18n.get(I18n.Key.BTN_COPY), UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE, 5);
        TextView btnClear = UiTheme.createButton(activity, "🗑 " + I18n.get(I18n.Key.BTN_CLEAR), UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);

        LinearLayout.LayoutParams actLp1 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 36), 1);
        btnCopy.setLayoutParams(actLp1);
        LinearLayout.LayoutParams actLp2 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 36), 1);
        actLp2.setMarginStart(UiTheme.dp(activity, 8));
        btnClear.setLayoutParams(actLp2);

        btnCopy.setOnClickListener(v -> {
            if (terminalBridge != null && listener != null) {
                terminalBridge.onCopyAllTextToClipboard(listener.getCurrentSession());
            }
            dialog.dismiss();
        });

        btnClear.setOnClickListener(v -> {
            if (listener != null && listener.getCurrentSession() != null) {
                listener.getCurrentSession().reset();
                Toast.makeText(activity, I18n.get(I18n.Key.LOG_TREE_KILLED), Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });
        UiTheme.applyTactileFeedback(btnCopy);
        UiTheme.applyTactileFeedback(btnClear);

        actionRow.addView(btnCopy);
        actionRow.addView(btnClear);
        root.addView(actionRow);

        addDivider(activity, root);

        // 4. Language Selection
        addSectionTitle(activity, root, "界面多语言 (LANGUAGE)");
        LinearLayout langRow = new LinearLayout(activity);
        langRow.setOrientation(LinearLayout.HORIZONTAL);

        I18n.Language currentLang = I18n.getLanguage();
        I18n.Language[] langs = new I18n.Language[]{I18n.Language.ZH_CN, I18n.Language.EN, I18n.Language.JA};
        for (int i = 0; i < langs.length; i++) {
            final I18n.Language l = langs[i];
            boolean isSelected = (l == currentLang);
            TextView btnLang = UiTheme.createButton(
                    activity,
                    l.getDisplayName(),
                    isSelected ? UiTheme.C_CYAN : UiTheme.C_DIM,
                    isSelected ? UiTheme.C_CYAN_BG : UiTheme.C_SURFACE_ALT,
                    isSelected ? UiTheme.C_CYAN : UiTheme.C_BORDER_SUB,
                    5
            );
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 34), 1);
            if (i > 0) lp.setMarginStart(UiTheme.dp(activity, 6));
            btnLang.setLayoutParams(lp);
            btnLang.setTextSize(10.5f);
            btnLang.setOnClickListener(v -> {
                I18n.setLanguage(activity, l);
                if (listener != null) listener.onLanguageChanged();
                dialog.dismiss();
            });
            UiTheme.applyTactileFeedback(btnLang);
            langRow.addView(btnLang);
        }
        root.addView(langRow);

        addDivider(activity, root);

        // 5. System Environment Badges
        addSectionTitle(activity, root, "系统环境与技术栈 (ENVIRONMENT)");
        LinearLayout badgeRow = new LinearLayout(activity);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);

        badgeRow.addView(UiTheme.createBadge(activity, "ARM64", UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN));
        View space1 = new View(activity);
        space1.setLayoutParams(new LinearLayout.LayoutParams(UiTheme.dp(activity, 6), 1));
        badgeRow.addView(space1);

        badgeRow.addView(UiTheme.createBadge(activity, "Zero-Root", UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE));
        View space2 = new View(activity);
        space2.setLayoutParams(new LinearLayout.LayoutParams(UiTheme.dp(activity, 6), 1));
        badgeRow.addView(space2);

        badgeRow.addView(UiTheme.createBadge(activity, "Alpine 3.20", UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN));
        View space3 = new View(activity);
        space3.setLayoutParams(new LinearLayout.LayoutParams(UiTheme.dp(activity, 6), 1));
        badgeRow.addView(space3);

        badgeRow.addView(UiTheme.createBadge(activity, "iFlow v0.5.19", UiTheme.C_YELLOW, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW));

        root.addView(badgeRow);

        addDivider(activity, root);

        // 6. Advanced Extensions & Services
        addSectionTitle(activity, root, "系统扩展与高级服务 (SERVICES & TOOLS)");

        // Row 1: ADB & MCP
        LinearLayout servRow1 = new LinearLayout(activity);
        servRow1.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnAdb = UiTheme.createButton(activity, "📱 ADB 无线调试", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);
        TextView btnMcp = UiTheme.createButton(activity, "🧩 MCP & Skill 扩展", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);

        LinearLayout.LayoutParams srLp1 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 36), 1);
        btnAdb.setLayoutParams(srLp1);
        LinearLayout.LayoutParams srLp2 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 36), 1);
        srLp2.setMarginStart(UiTheme.dp(activity, 8));
        btnMcp.setLayoutParams(srLp2);

        btnAdb.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onOpenAdb();
        });
        btnMcp.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onOpenMcpSkills();
        });
        UiTheme.applyTactileFeedback(btnAdb);
        UiTheme.applyTactileFeedback(btnMcp);

        servRow1.addView(btnAdb);
        servRow1.addView(btnMcp);
        root.addView(servRow1);

        // Row 2: Health & Storage, FTP
        LinearLayout servRow2 = new LinearLayout(activity);
        servRow2.setOrientation(LinearLayout.HORIZONTAL);
        servRow2.setPadding(0, UiTheme.dp(activity, 6), 0, 0);

        TextView btnHealth = UiTheme.createButton(activity, "🩺 体检与存储管理", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);
        TextView btnFtp = UiTheme.createButton(activity, "📁 FTP 文件服务", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);

        LinearLayout.LayoutParams srLp3 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 36), 1);
        btnHealth.setLayoutParams(srLp3);
        LinearLayout.LayoutParams srLp4 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 36), 1);
        srLp4.setMarginStart(UiTheme.dp(activity, 8));
        btnFtp.setLayoutParams(srLp4);

        btnHealth.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onOpenHealthStorage();
        });
        btnFtp.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onOpenFtp();
        });
        UiTheme.applyTactileFeedback(btnHealth);
        UiTheme.applyTactileFeedback(btnFtp);

        servRow2.addView(btnHealth);
        servRow2.addView(btnFtp);
        root.addView(servRow2);

        // Row 3: SSH & Gesture Guide
        LinearLayout servRow3 = new LinearLayout(activity);
        servRow3.setOrientation(LinearLayout.HORIZONTAL);
        servRow3.setPadding(0, UiTheme.dp(activity, 6), 0, 0);

        TextView btnSsh = UiTheme.createButton(activity, "🔒 SSH 远程终端", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);
        TextView btnGuide = UiTheme.createButton(activity, "⚡ 顶部控制中心", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 5);

        LinearLayout.LayoutParams srLp5 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 36), 1);
        btnSsh.setLayoutParams(srLp5);
        LinearLayout.LayoutParams srLp6 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 36), 1);
        srLp6.setMarginStart(UiTheme.dp(activity, 8));
        btnGuide.setLayoutParams(srLp6);

        btnSsh.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onOpenSsh();
        });
        btnGuide.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onShowGestureGuide();
        });
        UiTheme.applyTactileFeedback(btnSsh);
        UiTheme.applyTactileFeedback(btnGuide);

        servRow3.addView(btnSsh);
        servRow3.addView(btnGuide);
        root.addView(servRow3);

        dialog.show();
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
        lp.setMargins(0, UiTheme.dp(a, 10), 0, UiTheme.dp(a, 6));
        div.setLayoutParams(lp);
        root.addView(div);
    }
}
