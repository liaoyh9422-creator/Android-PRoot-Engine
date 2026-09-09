package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
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

import com.android.proot.sample.tool.McpSkillManager;
import com.android.proot.sample.ui.UiTheme;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modern modal dialog for managing Model Context Protocol (MCP) server configurations
 * and custom prompt/engineering Skills for iFlow CLI.
 */
public final class McpSkillDialog {

    private McpSkillDialog() {}

    public static void show(Activity activity, File rootfsDir) {
        if (activity == null || activity.isFinishing() || rootfsDir == null) return;

        McpSkillManager manager = McpSkillManager.getInstance();

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
        tvTitle.setText("🧩 MCP 与 Skill 扩展管理");
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

        // 2. Tab Bar (MCP Servers vs Skills)
        LinearLayout tabRow = new LinearLayout(activity);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView tabMcp = UiTheme.createButton(activity, "🧩 MCP 工具服务", UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 6);
        TextView tabSkill = UiTheme.createButton(activity, "⚡ Skill 技能库", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 6);

        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 34), 1);
        tabMcp.setLayoutParams(t1);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 34), 1);
        t2.setMarginStart(UiTheme.dp(activity, 8));
        tabSkill.setLayoutParams(t2);

        tabRow.addView(tabMcp);
        tabRow.addView(tabSkill);
        root.addView(tabRow);

        addDivider(activity, root);

        // 3. Content Containers
        LinearLayout layoutMcpContainer = new LinearLayout(activity);
        layoutMcpContainer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout layoutSkillContainer = new LinearLayout(activity);
        layoutSkillContainer.setOrientation(LinearLayout.VERTICAL);
        layoutSkillContainer.setVisibility(View.GONE);

        root.addView(layoutMcpContainer);
        root.addView(layoutSkillContainer);

        // Tab Switching Logic
        tabMcp.setOnClickListener(v -> {
            tabMcp.setTextColor(Color.parseColor(UiTheme.C_CYAN));
            tabMcp.setBackground(UiTheme.roundRect(activity, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 1, 6));
            tabSkill.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tabSkill.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 6));
            layoutMcpContainer.setVisibility(View.VISIBLE);
            layoutSkillContainer.setVisibility(View.GONE);
        });

        tabSkill.setOnClickListener(v -> {
            tabSkill.setTextColor(Color.parseColor(UiTheme.C_PURPLE));
            tabSkill.setBackground(UiTheme.roundRect(activity, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE, 1, 6));
            tabMcp.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tabMcp.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 6));
            layoutMcpContainer.setVisibility(View.GONE);
            layoutSkillContainer.setVisibility(View.VISIBLE);
        });

        // 4. Render MCP Servers Tab
        renderMcpTab(activity, rootfsDir, manager, layoutMcpContainer);

        // 5. Render Skills Tab
        renderSkillTab(activity, rootfsDir, manager, layoutSkillContainer);

        dialog.show();
    }

    private static void renderMcpTab(Activity a, File rootfsDir, McpSkillManager manager, LinearLayout container) {
        container.removeAllViews();

        List<McpSkillManager.McpServer> servers = manager.loadMcpServers(rootfsDir);

        TextView tvDesc = new TextView(a);
        tvDesc.setText("Model Context Protocol (MCP) 为 iFlow 提供外部工具调用（文件感知、网络、ADB）。支持本地 Stdio 进程与远程 SSE / httpStream 云端服务。");
        tvDesc.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvDesc.setTextSize(11f);
        tvDesc.setPadding(0, 0, 0, UiTheme.dp(a, 8));
        container.addView(tvDesc);

        LinearLayout listLayout = new LinearLayout(a);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        container.addView(listLayout);

        for (McpSkillManager.McpServer s : servers) {
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));
            item.setPadding(UiTheme.dp(a, 10), UiTheme.dp(a, 8), UiTheme.dp(a, 10), UiTheme.dp(a, 8));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UiTheme.dp(a, 6);
            item.setLayoutParams(lp);

            // Left Info
            LinearLayout infoCol = new LinearLayout(a);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            infoCol.setLayoutParams(infoLp);

            LinearLayout titleRow = new LinearLayout(a);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            String effType = s.getEffectiveType();
            String badgeColor = "sse".equalsIgnoreCase(effType) ? UiTheme.C_GREEN : ("httpstream".equalsIgnoreCase(effType) ? UiTheme.C_PURPLE : UiTheme.C_CYAN);
            String badgeBg = "sse".equalsIgnoreCase(effType) ? UiTheme.C_GREEN_BG : ("httpstream".equalsIgnoreCase(effType) ? UiTheme.C_PURPLE_BG : UiTheme.C_CYAN_BG);
            TextView tvBadge = UiTheme.createButton(a, effType.toUpperCase(), badgeColor, badgeBg, badgeColor, 3);
            tvBadge.setTextSize(9f);
            tvBadge.setPadding(UiTheme.dp(a, 4), UiTheme.dp(a, 1), UiTheme.dp(a, 4), UiTheme.dp(a, 1));
            titleRow.addView(tvBadge);

            TextView tvName = new TextView(a);
            tvName.setText("  " + s.name);
            tvName.setTextColor(Color.parseColor(s.enabled ? UiTheme.C_TEXT : UiTheme.C_DIM));
            tvName.setTextSize(12.5f);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            titleRow.addView(tvName);

            infoCol.addView(titleRow);

            TextView tvDetail = new TextView(a);
            if (s.isRemote()) {
                String detail = s.url;
                if (s.headers != null && !s.headers.isEmpty()) {
                    detail += " (" + s.headers.size() + " headers)";
                }
                tvDetail.setText(detail);
            } else {
                String fullCmd = s.command + (s.args.isEmpty() ? "" : " " + String.join(" ", s.args));
                tvDetail.setText(fullCmd);
            }
            tvDetail.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tvDetail.setTextSize(10.5f);
            tvDetail.setTypeface(Typeface.MONOSPACE);
            infoCol.addView(tvDetail);
            item.addView(infoCol);

            // Ping Button for Remote Servers
            if (s.isRemote()) {
                TextView btnPing = UiTheme.createButton(a, "⚡Ping", UiTheme.C_YELLOW, UiTheme.C_SURFACE, UiTheme.C_BORDER, 4);
                btnPing.setPadding(UiTheme.dp(a, 6), UiTheme.dp(a, 3), UiTheme.dp(a, 6), UiTheme.dp(a, 3));
                btnPing.setTextSize(10f);
                btnPing.setOnClickListener(v -> {
                    btnPing.setText("⏳...");
                    manager.pingRemoteServer(s.url, s.headers, (ok, code, lat, detail) -> {
                        a.runOnUiThread(() -> {
                            btnPing.setText(ok ? "🟢" + lat + "ms" : "🔴" + (code > 0 ? code : "Err"));
                            Toast.makeText(a, s.name + ": " + detail + " (" + lat + "ms)", Toast.LENGTH_SHORT).show();
                        });
                    });
                });
                LinearLayout.LayoutParams pingLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                pingLp.setMarginEnd(UiTheme.dp(a, 6));
                btnPing.setLayoutParams(pingLp);
                item.addView(btnPing);
            }

            // Edit Button for MCP Server
            TextView btnEdit = UiTheme.createButton(a, "✏️", UiTheme.C_CYAN, UiTheme.C_SURFACE, UiTheme.C_BORDER, 4);
            btnEdit.setPadding(UiTheme.dp(a, 6), UiTheme.dp(a, 3), UiTheme.dp(a, 6), UiTheme.dp(a, 3));
            btnEdit.setTextSize(10f);
            btnEdit.setOnClickListener(v -> showMcpDialog(a, rootfsDir, manager, servers, s, () -> renderMcpTab(a, rootfsDir, manager, container)));
            LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            editLp.setMarginEnd(UiTheme.dp(a, 6));
            btnEdit.setLayoutParams(editLp);
            item.addView(btnEdit);

            // Toggle Button
            TextView btnToggle = UiTheme.createButton(a, s.enabled ? "已启用" : "已停用",
                    s.enabled ? UiTheme.C_GREEN : UiTheme.C_DIM,
                    s.enabled ? UiTheme.C_GREEN_BG : UiTheme.C_SURFACE,
                    s.enabled ? UiTheme.C_GREEN : UiTheme.C_BORDER, 5);
            btnToggle.setPadding(UiTheme.dp(a, 8), UiTheme.dp(a, 4), UiTheme.dp(a, 8), UiTheme.dp(a, 4));
            btnToggle.setTextSize(10.5f);
            btnToggle.setOnClickListener(v -> {
                s.enabled = !s.enabled;
                manager.saveMcpServers(rootfsDir, servers);
                renderMcpTab(a, rootfsDir, manager, container);
            });
            item.addView(btnToggle);

            // Delete Button
            TextView btnDel = new TextView(a);
            btnDel.setText("✕");
            btnDel.setTextColor(Color.parseColor(UiTheme.C_DIM));
            btnDel.setPadding(UiTheme.dp(a, 8), UiTheme.dp(a, 4), UiTheme.dp(a, 8), UiTheme.dp(a, 4));
            btnDel.setOnClickListener(v -> {
                servers.remove(s);
                manager.saveMcpServers(rootfsDir, servers);
                renderMcpTab(a, rootfsDir, manager, container);
            });
            item.addView(btnDel);

            listLayout.addView(item);
        }

        // Action Buttons Row (Add Single + Import JSON)
        LinearLayout actionRow = new LinearLayout(a);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, UiTheme.dp(a, 6), 0, 0);

        TextView btnAdd = UiTheme.createButton(a, "➕ 添加单个服务", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 6);
        TextView btnImportJson = UiTheme.createButton(a, "📋 批量 JSON 导入", UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 6);

        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, UiTheme.dp(a, 36), 1);
        btnAdd.setLayoutParams(b1);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, UiTheme.dp(a, 36), 1);
        b2.setMarginStart(UiTheme.dp(a, 8));
        btnImportJson.setLayoutParams(b2);

        btnAdd.setOnClickListener(v -> showMcpDialog(a, rootfsDir, manager, servers, null, () -> renderMcpTab(a, rootfsDir, manager, container)));
        btnImportJson.setOnClickListener(v -> showImportJsonDialog(a, rootfsDir, manager, () -> renderMcpTab(a, rootfsDir, manager, container)));

        actionRow.addView(btnAdd);
        actionRow.addView(btnImportJson);
        container.addView(actionRow);
    }

    private static void renderSkillTab(Activity a, File rootfsDir, McpSkillManager manager, LinearLayout container) {
        container.removeAllViews();

        List<McpSkillManager.Skill> skills = manager.loadSkills(rootfsDir);

        TextView tvDesc = new TextView(a);
        tvDesc.setText("Skill 为 iFlow CLI 的场景化技能扩展，存储于 ~/.iflow/skills/ 目录下，在会话中自动识别生效。");
        tvDesc.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvDesc.setTextSize(11f);
        tvDesc.setPadding(0, 0, 0, UiTheme.dp(a, 8));
        container.addView(tvDesc);

        LinearLayout listLayout = new LinearLayout(a);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        container.addView(listLayout);

        for (McpSkillManager.Skill sk : skills) {
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));
            item.setPadding(UiTheme.dp(a, 10), UiTheme.dp(a, 8), UiTheme.dp(a, 10), UiTheme.dp(a, 8));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UiTheme.dp(a, 6);
            item.setLayoutParams(lp);

            LinearLayout rowTop = new LinearLayout(a);
            rowTop.setOrientation(LinearLayout.HORIZONTAL);
            rowTop.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvTitle = new TextView(a);
            tvTitle.setText(sk.title);
            tvTitle.setTextColor(Color.parseColor(UiTheme.C_PURPLE));
            tvTitle.setTextSize(12.5f);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            rowTop.addView(tvTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            // Edit Button for Skill
            TextView btnEditSkill = UiTheme.createButton(a, "✏️ 编辑", UiTheme.C_PURPLE, UiTheme.C_SURFACE, UiTheme.C_BORDER, 4);
            btnEditSkill.setPadding(UiTheme.dp(a, 6), UiTheme.dp(a, 2), UiTheme.dp(a, 6), UiTheme.dp(a, 2));
            btnEditSkill.setTextSize(10f);
            btnEditSkill.setOnClickListener(v -> showSkillDialog(a, rootfsDir, manager, sk, () -> renderSkillTab(a, rootfsDir, manager, container)));
            LinearLayout.LayoutParams editSkillLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            editSkillLp.setMarginEnd(UiTheme.dp(a, 6));
            btnEditSkill.setLayoutParams(editSkillLp);
            rowTop.addView(btnEditSkill);

            TextView btnDel = new TextView(a);
            btnDel.setText("✕");
            btnDel.setTextColor(Color.parseColor(UiTheme.C_DIM));
            btnDel.setPadding(UiTheme.dp(a, 6), UiTheme.dp(a, 2), UiTheme.dp(a, 6), UiTheme.dp(a, 2));
            btnDel.setOnClickListener(v -> {
                manager.deleteSkill(rootfsDir, sk.id);
                renderSkillTab(a, rootfsDir, manager, container);
            });
            rowTop.addView(btnDel);
            item.addView(rowTop);

            if (!sk.description.isEmpty()) {
                TextView tvDescText = new TextView(a);
                tvDescText.setText(sk.description);
                tvDescText.setTextColor(Color.parseColor(UiTheme.C_DIM));
                tvDescText.setTextSize(11f);
                tvDescText.setPadding(0, UiTheme.dp(a, 2), 0, 0);
                item.addView(tvDescText);
            }

            listLayout.addView(item);
        }

        // Action Buttons Row (Add & Reset)
        LinearLayout btnRow = new LinearLayout(a);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, UiTheme.dp(a, 6), 0, 0);

        TextView btnAddSkill = UiTheme.createButton(a, "➕ 新建 Skill", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 6);
        TextView btnReset = UiTheme.createButton(a, "🔄 重置预置技能", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 6);

        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, UiTheme.dp(a, 36), 1);
        btnAddSkill.setLayoutParams(b1);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, UiTheme.dp(a, 36), 1);
        b2.setMarginStart(UiTheme.dp(a, 8));
        btnReset.setLayoutParams(b2);

        btnAddSkill.setOnClickListener(v -> showSkillDialog(a, rootfsDir, manager, null, () -> renderSkillTab(a, rootfsDir, manager, container)));
        btnReset.setOnClickListener(v -> {
            manager.installPresetSkills(rootfsDir);
            renderSkillTab(a, rootfsDir, manager, container);
            Toast.makeText(a, "已恢复预置技能集", Toast.LENGTH_SHORT).show();
        });

        btnRow.addView(btnAddSkill);
        btnRow.addView(btnReset);
        container.addView(btnRow);
    }

    private static void showMcpDialog(Activity a, File rootfsDir, McpSkillManager manager, List<McpSkillManager.McpServer> servers, McpSkillManager.McpServer existing, Runnable onSaved) {
        final boolean isEdit = (existing != null);
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiTheme.dp(a, 16), UiTheme.dp(a, 16), UiTheme.dp(a, 16), UiTheme.dp(a, 16));
        root.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));

        TextView title = new TextView(a);
        title.setText(isEdit ? "✏️ 编辑 MCP 服务" : "➕ 添加自定义 MCP 服务");
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        String initialType = isEdit ? existing.getEffectiveType() : "sse";
        final String[] activeType = new String[]{initialType};

        // Protocol Mode Toggle (SSE vs httpStream vs Stdio)
        LinearLayout rowMode = new LinearLayout(a);
        rowMode.setOrientation(LinearLayout.HORIZONTAL);
        rowMode.setPadding(0, UiTheme.dp(a, 8), 0, UiTheme.dp(a, 8));

        TextView btnModeSse = UiTheme.createButton(a, "🌐 远程 SSE", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 5);
        TextView btnModeStream = UiTheme.createButton(a, "🌊 远程 Stream", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 5);
        TextView btnModeStdio = UiTheme.createButton(a, "💻 本地 Stdio", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 5);

        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, UiTheme.dp(a, 30), 1);
        btnModeSse.setLayoutParams(m1);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, UiTheme.dp(a, 30), 1);
        m2.setMarginStart(UiTheme.dp(a, 4));
        btnModeStream.setLayoutParams(m2);
        LinearLayout.LayoutParams m3 = new LinearLayout.LayoutParams(0, UiTheme.dp(a, 30), 1);
        m3.setMarginStart(UiTheme.dp(a, 4));
        btnModeStdio.setLayoutParams(m3);

        rowMode.addView(btnModeSse);
        rowMode.addView(btnModeStream);
        rowMode.addView(btnModeStdio);
        root.addView(rowMode);

        EditText etName = createInput(a, "服务标识 (如 cloud-db / fetch)");
        if (isEdit) {
            etName.setText(existing.name);
        } else {
            String defaultName = McpSkillManager.generateNextMcpName(servers);
            etName.setText(defaultName);
            etName.selectAll();
        }
        root.addView(etName);

        // Remote Inputs Container
        LinearLayout remoteLayout = new LinearLayout(a);
        remoteLayout.setOrientation(LinearLayout.VERTICAL);

        EditText etUrl = createInput(a, "服务 URL (如 https://api.domain.com/sse)");
        EditText etHeaders = createInput(a, "鉴权 Headers (可选，如 Authorization: Bearer xxx)");
        if (isEdit && existing.isRemote()) {
            etUrl.setText(existing.url);
            StringBuilder sbH = new StringBuilder();
            if (existing.headers != null) {
                for (Map.Entry<String, String> e : existing.headers.entrySet()) {
                    if (sbH.length() > 0) sbH.append("\n");
                    sbH.append(e.getKey()).append(": ").append(e.getValue());
                }
            }
            etHeaders.setText(sbH.toString());
        }
        remoteLayout.addView(etUrl);
        remoteLayout.addView(etHeaders);

        // Remote Ping Tester in Dialog
        LinearLayout pingRow = new LinearLayout(a);
        pingRow.setOrientation(LinearLayout.HORIZONTAL);
        pingRow.setGravity(Gravity.CENTER_VERTICAL);
        pingRow.setPadding(0, UiTheme.dp(a, 4), 0, UiTheme.dp(a, 4));

        TextView btnTestPing = UiTheme.createButton(a, "⚡ 测试连通性", UiTheme.C_YELLOW, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 4);
        btnTestPing.setPadding(UiTheme.dp(a, 8), UiTheme.dp(a, 4), UiTheme.dp(a, 8), UiTheme.dp(a, 4));
        btnTestPing.setTextSize(10.5f);

        TextView tvPingResult = new TextView(a);
        tvPingResult.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvPingResult.setTextSize(10f);
        tvPingResult.setPadding(UiTheme.dp(a, 8), 0, 0, 0);

        btnTestPing.setOnClickListener(v -> {
            String u = etUrl.getText().toString().trim();
            if (u.isEmpty()) {
                tvPingResult.setText("⚠️ 请先输入 URL");
                tvPingResult.setTextColor(Color.parseColor(UiTheme.C_YELLOW));
                return;
            }
            tvPingResult.setText("⏳ 正在探测连通性...");
            tvPingResult.setTextColor(Color.parseColor(UiTheme.C_DIM));
            Map<String, String> h = parseHeadersInput(etHeaders.getText().toString());
            manager.pingRemoteServer(u, h, (ok, code, lat, detail) -> {
                a.runOnUiThread(() -> {
                    tvPingResult.setText(detail + " (" + lat + "ms)");
                    tvPingResult.setTextColor(Color.parseColor(ok ? UiTheme.C_GREEN : UiTheme.C_RED));
                });
            });
        });

        pingRow.addView(btnTestPing);
        pingRow.addView(tvPingResult);
        remoteLayout.addView(pingRow);

        // Stdio Inputs Container
        LinearLayout stdioLayout = new LinearLayout(a);
        stdioLayout.setOrientation(LinearLayout.VERTICAL);

        EditText etCmd = createInput(a, "执行命令 (如 npx / node / python3)");
        EditText etArgs = createInput(a, "运行参数 (空格隔开，如 -y @mcp/server)");
        if (isEdit && !existing.isRemote()) {
            etCmd.setText(existing.command);
            etArgs.setText(existing.args != null ? String.join(" ", existing.args) : "");
        }
        stdioLayout.addView(etCmd);
        stdioLayout.addView(etArgs);

        root.addView(remoteLayout);
        root.addView(stdioLayout);

        // Style refresher
        Runnable refreshModeStyles = () -> {
            if ("httpstream".equalsIgnoreCase(activeType[0])) {
                btnModeStream.setTextColor(Color.parseColor(UiTheme.C_PURPLE));
                btnModeStream.setBackground(UiTheme.roundRect(a, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE, 1, 5));
                btnModeSse.setTextColor(Color.parseColor(UiTheme.C_DIM));
                btnModeSse.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 5));
                btnModeStdio.setTextColor(Color.parseColor(UiTheme.C_DIM));
                btnModeStdio.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 5));
                remoteLayout.setVisibility(View.VISIBLE);
                stdioLayout.setVisibility(View.GONE);
            } else if ("stdio".equalsIgnoreCase(activeType[0])) {
                btnModeStdio.setTextColor(Color.parseColor(UiTheme.C_CYAN));
                btnModeStdio.setBackground(UiTheme.roundRect(a, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 1, 5));
                btnModeSse.setTextColor(Color.parseColor(UiTheme.C_DIM));
                btnModeSse.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 5));
                btnModeStream.setTextColor(Color.parseColor(UiTheme.C_DIM));
                btnModeStream.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 5));
                remoteLayout.setVisibility(View.GONE);
                stdioLayout.setVisibility(View.VISIBLE);
            } else { // sse
                btnModeSse.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                btnModeSse.setBackground(UiTheme.roundRect(a, UiTheme.C_GREEN_BG, UiTheme.C_GREEN, 1, 5));
                btnModeStream.setTextColor(Color.parseColor(UiTheme.C_DIM));
                btnModeStream.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 5));
                btnModeStdio.setTextColor(Color.parseColor(UiTheme.C_DIM));
                btnModeStdio.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 5));
                remoteLayout.setVisibility(View.VISIBLE);
                stdioLayout.setVisibility(View.GONE);
            }
        };

        refreshModeStyles.run();

        btnModeSse.setOnClickListener(v -> {
            activeType[0] = "sse";
            refreshModeStyles.run();
        });
        btnModeStream.setOnClickListener(v -> {
            activeType[0] = "httpstream";
            refreshModeStyles.run();
        });
        btnModeStdio.setOnClickListener(v -> {
            activeType[0] = "stdio";
            refreshModeStyles.run();
        });

        ScrollView scrollRoot = new ScrollView(a);
        scrollRoot.setFillViewport(true);
        scrollRoot.addView(root);
        b.setView(scrollRoot);
        b.setPositiveButton(isEdit ? "保存修改" : "保存", (dialog, which) -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(a, "服务标识不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            // Check duplicate name with other servers
            for (McpSkillManager.McpServer s : servers) {
                if (s != existing && name.equalsIgnoreCase(s.name)) {
                    Toast.makeText(a, "服务标识 [" + name + "] 已存在", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            McpSkillManager.McpServer target = isEdit ? existing : new McpSkillManager.McpServer(name, "");
            target.name = name;
            target.type = activeType[0];

            if ("stdio".equals(activeType[0])) {
                target.command = etCmd.getText().toString().trim();
                target.args.clear();
                String argsStr = etArgs.getText().toString().trim();
                if (!argsStr.isEmpty()) {
                    for (String p : argsStr.split("\\s+")) target.args.add(p);
                }
                target.url = "";
                target.headers.clear();
            } else {
                target.url = etUrl.getText().toString().trim();
                target.headers.clear();
                target.headers.putAll(parseHeadersInput(etHeaders.getText().toString()));
                target.command = "";
                target.args.clear();
            }

            if (!isEdit) {
                servers.add(target);
            }
            manager.saveMcpServers(rootfsDir, servers);
            if (onSaved != null) onSaved.run();
            Toast.makeText(a, isEdit ? "已修改 MCP 服务: " + name : "已添加 MCP 服务: " + name, Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("取消", null);
        AlertDialog dialog = b.create();
        UiTheme.configureDialogWindow(dialog);
        dialog.show();
    }

    private static void showImportJsonDialog(Activity a, File rootfsDir, McpSkillManager manager, Runnable onImported) {
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiTheme.dp(a, 16), UiTheme.dp(a, 16), UiTheme.dp(a, 16), UiTheme.dp(a, 16));
        root.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));

        TextView title = new TextView(a);
        title.setText("📋 批量导入 MCP 配置 (JSON)");
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(a);
        sub.setText("支持粘贴 Claude Desktop、Cursor 配置或 mcpServers 字典");
        sub.setTextColor(Color.parseColor(UiTheme.C_DIM));
        sub.setTextSize(10.5f);
        sub.setPadding(0, UiTheme.dp(a, 2), 0, UiTheme.dp(a, 8));
        root.addView(sub);

        EditText etJson = new EditText(a);
        etJson.setHint("{\n  \"mcpServers\": {\n    \"example\": { \"type\": \"sse\", \"url\": \"...\" }\n  }\n}");
        etJson.setHintTextColor(Color.parseColor(UiTheme.C_DIM));
        etJson.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etJson.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));
        etJson.setTextSize(11f);
        etJson.setTypeface(Typeface.MONOSPACE);
        etJson.setMinLines(5);
        etJson.setMaxLines(10);
        etJson.setGravity(Gravity.TOP);
        etJson.setPadding(UiTheme.dp(a, 10), UiTheme.dp(a, 8), UiTheme.dp(a, 10), UiTheme.dp(a, 8));
        root.addView(etJson);

        // Buttons row (Paste & Clear)
        LinearLayout rowBtn = new LinearLayout(a);
        rowBtn.setOrientation(LinearLayout.HORIZONTAL);
        rowBtn.setPadding(0, UiTheme.dp(a, 8), 0, UiTheme.dp(a, 8));

        TextView btnPaste = UiTheme.createButton(a, "📋 从剪贴板粘贴", UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 4);
        btnPaste.setPadding(UiTheme.dp(a, 8), UiTheme.dp(a, 4), UiTheme.dp(a, 8), UiTheme.dp(a, 4));
        btnPaste.setTextSize(10.5f);
        btnPaste.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence clipText = cm.getPrimaryClip().getItemAt(0).getText();
                    if (clipText != null && clipText.length() > 0) {
                        etJson.setText(clipText.toString());
                        Toast.makeText(a, "已从剪贴板粘贴", Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception ignored) {}
        });

        TextView btnClear = UiTheme.createButton(a, "🔄 清空", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 4);
        btnClear.setPadding(UiTheme.dp(a, 8), UiTheme.dp(a, 4), UiTheme.dp(a, 8), UiTheme.dp(a, 4));
        btnClear.setTextSize(10.5f);
        btnClear.setOnClickListener(v -> etJson.setText(""));

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        btnPaste.setLayoutParams(p1);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        p2.setMarginStart(UiTheme.dp(a, 8));
        btnClear.setLayoutParams(p2);

        rowBtn.addView(btnPaste);
        rowBtn.addView(btnClear);
        root.addView(rowBtn);

        // Overwrite toggle button
        final boolean[] overwrite = new boolean[]{true};
        TextView btnStrategy = UiTheme.createButton(a, "冲突处理: 覆盖已有服务", UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN, 4);
        btnStrategy.setPadding(UiTheme.dp(a, 8), UiTheme.dp(a, 4), UiTheme.dp(a, 8), UiTheme.dp(a, 4));
        btnStrategy.setTextSize(10.5f);
        btnStrategy.setOnClickListener(v -> {
            overwrite[0] = !overwrite[0];
            btnStrategy.setText(overwrite[0] ? "冲突处理: 覆盖已有服务" : "冲突处理: 跳过重复保留原样");
            btnStrategy.setTextColor(Color.parseColor(overwrite[0] ? UiTheme.C_GREEN : UiTheme.C_DIM));
            btnStrategy.setBackground(UiTheme.roundRect(a, overwrite[0] ? UiTheme.C_GREEN_BG : UiTheme.C_SURFACE_ALT, overwrite[0] ? UiTheme.C_GREEN : UiTheme.C_BORDER, 1, 4));
        });
        root.addView(btnStrategy);

        ScrollView scrollRoot = new ScrollView(a);
        scrollRoot.setFillViewport(true);
        scrollRoot.addView(root);
        b.setView(scrollRoot);
        b.setPositiveButton("确认导入", (dialog, which) -> {
            String text = etJson.getText().toString().trim();
            McpSkillManager.ImportResult res = manager.importMcpServersFromJson(rootfsDir, text, overwrite[0]);
            Toast.makeText(a, res.message, Toast.LENGTH_LONG).show();
            if (res.success && onImported != null) {
                onImported.run();
            }
        });
        b.setNegativeButton("取消", null);
        AlertDialog dialog = b.create();
        UiTheme.configureDialogWindow(dialog);
        dialog.show();
    }

    private static Map<String, String> parseHeadersInput(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return map;
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(trimmed);
                java.util.Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    map.put(k, obj.optString(k));
                }
                return map;
            } catch (Exception ignored) {}
        }
        for (String line : trimmed.split("[\r\n;]+")) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                String k = line.substring(0, idx).trim();
                String v = line.substring(idx + 1).trim();
                if (!k.isEmpty()) map.put(k, v);
            }
        }
        return map;
    }

    private static void showSkillDialog(Activity a, File rootfsDir, McpSkillManager manager, McpSkillManager.Skill existing, Runnable onSaved) {
        final boolean isEdit = (existing != null);
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiTheme.dp(a, 16), UiTheme.dp(a, 16), UiTheme.dp(a, 16), UiTheme.dp(a, 16));
        root.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));

        TextView title = new TextView(a);
        title.setText(isEdit ? "✏️ 编辑 Skill 技能" : "新建自定义 Skill 技能");
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        EditText etId = createInput(a, "技能 ID (英文小写，如 git-commit-helper)");
        EditText etTitle = createInput(a, "技能名称 (如 Git 自动提交助手)");
        EditText etDesc = createInput(a, "功能描述 (如 分析 diff 生成语义化提交)");
        EditText etPrompt = createInput(a, "提示词指引 (System Instruction)");
        etPrompt.setLines(4);

        if (isEdit) {
            etId.setText(existing.id);
            etId.setEnabled(false);
            etId.setTextColor(Color.parseColor(UiTheme.C_DIM));
            etTitle.setText(existing.title);
            etDesc.setText(existing.description);
            etPrompt.setText(existing.prompt);
        }

        root.addView(etId);
        root.addView(etTitle);
        root.addView(etDesc);
        root.addView(etPrompt);

        ScrollView scrollRoot = new ScrollView(a);
        scrollRoot.setFillViewport(true);
        scrollRoot.addView(root);
        b.setView(scrollRoot);
        b.setPositiveButton(isEdit ? "保存修改" : "保存", (dialog, which) -> {
            String id = etId.getText().toString().trim();
            String t = etTitle.getText().toString().trim();
            String desc = etDesc.getText().toString().trim();
            String pr = etPrompt.getText().toString().trim();
            if (!id.isEmpty()) {
                manager.saveSkill(rootfsDir, id, t.isEmpty() ? id : t, desc, pr);
                if (onSaved != null) onSaved.run();
                Toast.makeText(a, isEdit ? "已更新 Skill: " + id : "已创建 Skill: " + id, Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("取消", null);
        AlertDialog dialog = b.create();
        UiTheme.configureDialogWindow(dialog);
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

    private static void addDivider(Activity a, LinearLayout root) {
        View div = new View(a);
        div.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, UiTheme.dp(a, 8), 0, UiTheme.dp(a, 8));
        div.setLayoutParams(lp);
        root.addView(div);
    }
}
