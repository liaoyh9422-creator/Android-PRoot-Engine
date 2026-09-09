package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.PRootEngine;
import com.android.proot.sample.ai.IFlowSessionManager;
import com.android.proot.sample.tool.HealthStorageManager;
import com.android.proot.sample.ui.UiTheme;

import java.util.List;

/**
 * Modern modal dialog for categorized environment health check
 * and fine-grained storage breakdown and cleanup.
 */
public final class HealthStorageDialog {

    private HealthStorageDialog() {}

    public static void show(Activity activity, PRootEngine engine) {
        if (activity == null || activity.isFinishing() || engine == null) return;

        HealthStorageManager manager = HealthStorageManager.getInstance();

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
        tvTitle.setText("🩺 环境体检与存储管理");
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

        // 2. Tab Row
        LinearLayout tabRow = new LinearLayout(activity);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView tabHealth = UiTheme.createButton(activity, "🩺 环境全息体检", UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 6);
        TextView tabStorage = UiTheme.createButton(activity, "💾 细化存储空间", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 6);

        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 34), 1);
        tabHealth.setLayoutParams(t1);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 34), 1);
        t2.setMarginStart(UiTheme.dp(activity, 8));
        tabStorage.setLayoutParams(t2);

        tabRow.addView(tabHealth);
        tabRow.addView(tabStorage);
        root.addView(tabRow);

        addDivider(activity, root);

        // 3. Containers
        LinearLayout layoutHealth = new LinearLayout(activity);
        layoutHealth.setOrientation(LinearLayout.VERTICAL);

        LinearLayout layoutStorage = new LinearLayout(activity);
        layoutStorage.setOrientation(LinearLayout.VERTICAL);
        layoutStorage.setVisibility(View.GONE);

        root.addView(layoutHealth);
        root.addView(layoutStorage);

        tabHealth.setOnClickListener(v -> {
            tabHealth.setTextColor(Color.parseColor(UiTheme.C_CYAN));
            tabHealth.setBackground(UiTheme.roundRect(activity, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 1, 6));
            tabStorage.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tabStorage.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 6));
            layoutHealth.setVisibility(View.VISIBLE);
            layoutStorage.setVisibility(View.GONE);
        });

        tabStorage.setOnClickListener(v -> {
            tabStorage.setTextColor(Color.parseColor(UiTheme.C_GREEN));
            tabStorage.setBackground(UiTheme.roundRect(activity, UiTheme.C_GREEN_BG, UiTheme.C_GREEN, 1, 6));
            tabHealth.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tabHealth.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 6));
            layoutHealth.setVisibility(View.GONE);
            layoutStorage.setVisibility(View.VISIBLE);
        });

        // 4. Render Health Check
        renderHealthTab(activity, engine, manager, layoutHealth);

        // 5. Render Storage Tab
        renderStorageTab(activity, engine, manager, layoutStorage);

        dialog.show();
    }

    private static void renderHealthTab(Activity a, PRootEngine engine, HealthStorageManager manager, LinearLayout container) {
        container.removeAllViews();

        TextView tvLoading = new TextView(a);
        tvLoading.setText("正在执行系统与运行时全维度体检...");
        tvLoading.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvLoading.setTextSize(12f);
        tvLoading.setPadding(0, UiTheme.dp(a, 8), 0, UiTheme.dp(a, 8));
        container.addView(tvLoading);

        manager.runHealthCheck(a, engine, items -> {
            container.removeAllViews();
            String lastCat = "";

            for (HealthStorageManager.HealthItem it : items) {
                if (!it.category.equals(lastCat)) {
                    lastCat = it.category;
                    TextView tvCat = new TextView(a);
                    tvCat.setText(it.category);
                    tvCat.setTextColor(Color.parseColor(UiTheme.C_CYAN));
                    tvCat.setTextSize(11f);
                    tvCat.setTypeface(Typeface.DEFAULT_BOLD);
                    tvCat.setPadding(0, UiTheme.dp(a, 8), 0, UiTheme.dp(a, 4));
                    container.addView(tvCat);
                }

                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 5));
                row.setPadding(UiTheme.dp(a, 10), UiTheme.dp(a, 7), UiTheme.dp(a, 10), UiTheme.dp(a, 7));

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = UiTheme.dp(a, 4);
                row.setLayoutParams(lp);

                TextView tvIcon = new TextView(a);
                tvIcon.setText(it.passed ? "✔" : "⚠");
                tvIcon.setTextColor(Color.parseColor(it.passed ? UiTheme.C_GREEN : UiTheme.C_YELLOW));
                tvIcon.setTextSize(12f);
                tvIcon.setTypeface(Typeface.DEFAULT_BOLD);
                row.addView(tvIcon);

                LinearLayout textCol = new LinearLayout(a);
                textCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                tcLp.setMarginStart(UiTheme.dp(a, 8));
                textCol.setLayoutParams(tcLp);

                TextView tvItemTitle = new TextView(a);
                tvItemTitle.setText(it.title);
                tvItemTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
                tvItemTitle.setTextSize(12f);
                textCol.addView(tvItemTitle);

                TextView tvItemDetail = new TextView(a);
                tvItemDetail.setText(it.detail);
                tvItemDetail.setTextColor(Color.parseColor(UiTheme.C_DIM));
                tvItemDetail.setTextSize(10.5f);
                textCol.addView(tvItemDetail);

                row.addView(textCol);
                container.addView(row);
            }

            // Re-check Button
            TextView btnRecheck = UiTheme.createButton(a, "🔄 重新检测", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 6);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiTheme.dp(a, 36));
            rLp.topMargin = UiTheme.dp(a, 8);
            btnRecheck.setLayoutParams(rLp);
            btnRecheck.setOnClickListener(v -> renderHealthTab(a, engine, manager, container));
            container.addView(btnRecheck);
        });
    }

    private static void renderStorageTab(Activity a, PRootEngine engine, HealthStorageManager manager, LinearLayout container) {
        container.removeAllViews();

        TextView tvLoading = new TextView(a);
        tvLoading.setText("正在计算各模块存储占用...");
        tvLoading.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvLoading.setTextSize(12f);
        tvLoading.setPadding(0, UiTheme.dp(a, 8), 0, UiTheme.dp(a, 8));
        container.addView(tvLoading);

        manager.calculateStorage(a, engine, sb -> {
            container.removeAllViews();

            // Cards
            addStorageCard(a, container, "📦 Alpine 基础 Rootfs", IFlowSessionManager.formatFileSize(sb.rootfsBytes), UiTheme.C_CYAN);
            addStorageCard(a, container, "🤖 iFlow 会话与上下文 (~/.iflow)", IFlowSessionManager.formatFileSize(sb.iflowBytes), UiTheme.C_PURPLE);
            addStorageCard(a, container, "📁 应用私有工作区 (files/workspace)", IFlowSessionManager.formatFileSize(sb.workspaceBytes), UiTheme.C_BLUE);
            addStorageCard(a, container, "🗑 临时文件与缓存 (/tmp & cache)", IFlowSessionManager.formatFileSize(sb.cacheAndTmpBytes), UiTheme.C_YELLOW);

            if (sb.deviceTotalBytes > 0) {
                long used = sb.deviceTotalBytes - sb.deviceAvailableBytes;
                int percent = (int) ((used * 100.0) / sb.deviceTotalBytes);
                addStorageCard(a, container, "📱 设备内部存储总览",
                        "已用 " + IFlowSessionManager.formatFileSize(used) + " / 可用 " + IFlowSessionManager.formatFileSize(sb.deviceAvailableBytes) + " (" + percent + "%)",
                        UiTheme.C_GREEN);
            }

            // Cleanup Actions
            TextView tvActionTitle = new TextView(a);
            tvActionTitle.setText("存储优化与清理 (CLEANUP)");
            tvActionTitle.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tvActionTitle.setTextSize(10.5f);
            tvActionTitle.setTypeface(Typeface.DEFAULT_BOLD);
            tvActionTitle.setPadding(0, UiTheme.dp(a, 10), 0, UiTheme.dp(a, 6));
            container.addView(tvActionTitle);

            LinearLayout actRow = new LinearLayout(a);
            actRow.setOrientation(LinearLayout.HORIZONTAL);

            TextView btnCleanCache = UiTheme.createButton(a, "🧹 清理缓存与临时文件", UiTheme.C_YELLOW, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW, 5);
            TextView btnCleanSessions = UiTheme.createButton(a, "📦 归档清理旧会话", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 5);

            LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, UiTheme.dp(a, 36), 1);
            btnCleanCache.setLayoutParams(a1);
            LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, UiTheme.dp(a, 36), 1);
            a2.setMarginStart(UiTheme.dp(a, 8));
            btnCleanSessions.setLayoutParams(a2);

            btnCleanCache.setOnClickListener(v -> {
                btnCleanCache.setText("清理中...");
                manager.cleanCacheAndTmp(a, engine, () -> {
                    Toast.makeText(a, "临时文件与缓存已清空", Toast.LENGTH_SHORT).show();
                    renderStorageTab(a, engine, manager, container);
                });
            });

            btnCleanSessions.setOnClickListener(v -> {
                btnCleanSessions.setText("清理中...");
                manager.cleanExpiredSessions(engine, () -> {
                    Toast.makeText(a, "已清理超期历史会话", Toast.LENGTH_SHORT).show();
                    renderStorageTab(a, engine, manager, container);
                });
            });

            actRow.addView(btnCleanCache);
            actRow.addView(btnCleanSessions);
            container.addView(actRow);
        });
    }

    private static void addStorageCard(Activity a, LinearLayout container, String title, String value, String colorHex) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));
        row.setPadding(UiTheme.dp(a, 12), UiTheme.dp(a, 8), UiTheme.dp(a, 12), UiTheme.dp(a, 8));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiTheme.dp(a, 5);
        row.setLayoutParams(lp);

        TextView tvTitle = new TextView(a);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvTitle.setTextSize(11.5f);
        row.addView(tvTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView tvVal = new TextView(a);
        tvVal.setText(value);
        tvVal.setTextColor(Color.parseColor(colorHex));
        tvVal.setTextSize(11.5f);
        tvVal.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(tvVal);

        container.addView(row);
    }

    private static void addDivider(Activity a, LinearLayout root) {
        View div = new View(a);
        div.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, UiTheme.dp(a, 8), 0, UiTheme.dp(a, 8));
        div.setLayoutParams(lp);
        root.addView(div);
    }
}
