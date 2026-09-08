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
import com.android.proot.sample.R;
import com.android.proot.sample.tool.EnvironmentManager;
import com.android.proot.sample.ui.UiTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Modern modal dialog for inspecting and installing 5 core ARM64 development toolchains online.
 */
public final class EnvironmentHubDialog {

    private EnvironmentHubDialog() {}

    public static void show(Activity activity, PRootEngine engine) {
        if (activity == null || activity.isFinishing()) return;

        EnvironmentManager manager = EnvironmentManager.getInstance();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_environment_hub, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        View container = dialogView.findViewById(R.id.dialog_env_container);
        TextView btnClose = dialogView.findViewById(R.id.btn_env_close);
        TextView btnScanAll = dialogView.findViewById(R.id.btn_env_scan_all);
        TextView btnInstallAll = dialogView.findViewById(R.id.btn_env_install_all);
        ProgressBar pbOperation = dialogView.findViewById(R.id.pb_env_operation);
        LinearLayout layoutCards = dialogView.findViewById(R.id.layout_toolchain_cards);
        ScrollView scrollLog = dialogView.findViewById(R.id.scroll_env_log);
        TextView tvLog = dialogView.findViewById(R.id.tv_env_log);

        container.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));
        btnScanAll.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        btnInstallAll.setBackground(UiTheme.roundRect(activity, UiTheme.C_GREEN_BG, UiTheme.C_GREEN, 1, 4));
        UiTheme.applyTactileFeedback(btnScanAll);
        UiTheme.applyTactileFeedback(btnInstallAll);

        btnClose.setOnClickListener(v -> dialog.dismiss());

        final Runnable[] populateCardsRef = new Runnable[1];
        populateCardsRef[0] = () -> {
            layoutCards.removeAllViews();
            List<EnvironmentManager.ToolchainItem> items = manager.getToolchains();
            for (EnvironmentManager.ToolchainItem item : items) {
                LinearLayout card = new LinearLayout(activity);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));
                card.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 8), UiTheme.dp(activity, 10), UiTheme.dp(activity, 8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, UiTheme.dp(activity, 8));
                card.setLayoutParams(lp);

                // Row 1: Title & Badge
                LinearLayout row1 = new LinearLayout(activity);
                row1.setOrientation(LinearLayout.HORIZONTAL);
                row1.setGravity(Gravity.CENTER_VERTICAL);

                TextView tvName = new TextView(activity);
                tvName.setText(item.name);
                tvName.setTextColor(Color.parseColor(UiTheme.C_TEXT));
                tvName.setTextSize(13f);
                tvName.setTypeface(Typeface.DEFAULT_BOLD);
                row1.addView(tvName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

                TextView badge = new TextView(activity);
                if (item.isOperating) {
                    badge.setText("⏳ 装配中");
                    badge.setTextColor(Color.parseColor(UiTheme.C_YELLOW));
                    badge.setBackground(UiTheme.roundRect(activity, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW, 1, 4));
                } else if (item.isInstalled) {
                    badge.setText("🟢 已就绪");
                    badge.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                    badge.setBackground(UiTheme.roundRect(activity, UiTheme.C_GREEN_BG, UiTheme.C_GREEN, 1, 4));
                } else {
                    badge.setText("⚪ 未安装");
                    badge.setTextColor(Color.parseColor(UiTheme.C_DIM));
                    badge.setBackground(UiTheme.roundRect(activity, UiTheme.C_BG, UiTheme.C_BORDER, 1, 4));
                }
                badge.setTextSize(10f);
                badge.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 2), UiTheme.dp(activity, 6), UiTheme.dp(activity, 2));
                row1.addView(badge);
                card.addView(row1);

                // Row 2: Components & Desc
                TextView tvDesc = new TextView(activity);
                tvDesc.setText(item.components);
                tvDesc.setTextColor(Color.parseColor(UiTheme.C_DIM));
                tvDesc.setTextSize(11f);
                tvDesc.setPadding(0, UiTheme.dp(activity, 2), 0, 0);
                card.addView(tvDesc);

                // Row 3: Version, Size & Action Button
                LinearLayout row3 = new LinearLayout(activity);
                row3.setOrientation(LinearLayout.HORIZONTAL);
                row3.setGravity(Gravity.CENTER_VERTICAL);
                row3.setPadding(0, UiTheme.dp(activity, 4), 0, 0);

                TextView tvMeta = new TextView(activity);
                String metaStr = (item.isInstalled ? "版本: " + item.detectedVersion : "大小: " + item.estimatedSize);
                tvMeta.setText(metaStr);
                tvMeta.setTextColor(Color.parseColor(item.isInstalled ? UiTheme.C_CYAN : UiTheme.C_DIM));
                tvMeta.setTextSize(10.5f);
                row3.addView(tvMeta, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

                TextView btnAction = new TextView(activity);
                btnAction.setText(item.isInstalled ? "重新体检" : "立即安装");
                btnAction.setTextSize(11f);
                if (item.isInstalled) {
                    btnAction.setTextColor(Color.parseColor(UiTheme.C_TEXT));
                    btnAction.setBackground(UiTheme.roundRect(activity, UiTheme.C_BG, UiTheme.C_BORDER, 1, 4));
                } else {
                    btnAction.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                    btnAction.setBackground(UiTheme.roundRect(activity, UiTheme.C_GREEN_BG, UiTheme.C_GREEN, 1, 4));
                }
                btnAction.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 3), UiTheme.dp(activity, 8), UiTheme.dp(activity, 3));
                UiTheme.applyTactileFeedback(btnAction);

                btnAction.setOnClickListener(v -> {
                    if (item.isInstalled) {
                        manager.scanAll(activity, engine, list -> populateCardsRef[0].run());
                    } else {
                        pbOperation.setVisibility(View.VISIBLE);
                        manager.installToolchain(activity, engine, item.id, new EnvironmentManager.InstallCallback() {
                            @Override
                            public void onLog(String msg) {
                                tvLog.append(msg + "\n");
                                scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
                            }
                            @Override
                            public void onProgress(int percent, String label) {
                                pbOperation.setProgress(percent);
                            }
                            @Override
                            public void onItemFinished(String id, boolean success, String version) {
                                populateCardsRef[0].run();
                            }
                            @Override
                            public void onAllFinished(boolean allSuccess) {
                                pbOperation.setVisibility(View.GONE);
                                populateCardsRef[0].run();
                            }
                        });
                    }
                });

                row3.addView(btnAction);
                card.addView(row3);
                layoutCards.addView(card);
            }
        };

        // Populate initially
        populateCardsRef[0].run();

        // Auto scan on dialog open
        manager.scanAll(activity, engine, list -> populateCardsRef[0].run());

        btnScanAll.setOnClickListener(v -> {
            tvLog.append("> 正在执行全量环境体检...\n");
            manager.scanAll(activity, engine, list -> {
                populateCardsRef[0].run();
                tvLog.append("> 全量体检完成！\n");
            });
        });

        btnInstallAll.setOnClickListener(v -> {
            List<String> uninstalledIds = new ArrayList<>();
            for (EnvironmentManager.ToolchainItem it : manager.getToolchains()) {
                if (!it.isInstalled) {
                    uninstalledIds.add(it.id);
                }
            }
            if (uninstalledIds.isEmpty()) {
                Toast.makeText(activity, "全部开发环境均已就绪！", Toast.LENGTH_SHORT).show();
                return;
            }
            pbOperation.setVisibility(View.VISIBLE);
            tvLog.append("> 开始批量装配未就绪环境 (共 " + uninstalledIds.size() + " 项)...\n");
            manager.installBatch(activity, engine, uninstalledIds, new EnvironmentManager.InstallCallback() {
                @Override
                public void onLog(String msg) {
                    tvLog.append(msg + "\n");
                    scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
                }
                @Override
                public void onProgress(int percent, String label) {
                    pbOperation.setProgress(percent);
                }
                @Override
                public void onItemFinished(String id, boolean success, String version) {
                    populateCardsRef[0].run();
                }
                @Override
                public void onAllFinished(boolean allSuccess) {
                    pbOperation.setVisibility(View.GONE);
                    populateCardsRef[0].run();
                    Toast.makeText(activity, allSuccess ? "环境全部装配成功！" : "部分环境装配完成", Toast.LENGTH_SHORT).show();
                }
            });
        });

        dialog.show();
    }
}
