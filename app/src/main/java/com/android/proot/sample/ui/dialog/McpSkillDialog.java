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

import com.android.proot.sample.tool.McpSkillManager;
import com.android.proot.sample.ui.UiTheme;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

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
        tvDesc.setText("Model Context Protocol (MCP) 为 iFlow 提供外部工具调用（文件感知、网络、ADB）。修改即时生效于 ~/.iflow/settings.json。");
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

            TextView tvName = new TextView(a);
            tvName.setText(s.name);
            tvName.setTextColor(Color.parseColor(s.enabled ? UiTheme.C_CYAN : UiTheme.C_DIM));
            tvName.setTextSize(12.5f);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            infoCol.addView(tvName);

            TextView tvCmd = new TextView(a);
            String fullCmd = s.command + " " + String.join(" ", s.args);
            tvCmd.setText(fullCmd);
            tvCmd.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tvCmd.setTextSize(10.5f);
            tvCmd.setTypeface(Typeface.MONOSPACE);
            infoCol.addView(tvCmd);
            item.addView(infoCol);

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
            btnDel.setPadding(UiTheme.dp(a, 8), UiTheme.dp(a, 4), UiTheme.dp(a, 4), UiTheme.dp(a, 4));
            btnDel.setOnClickListener(v -> {
                servers.remove(s);
                manager.saveMcpServers(rootfsDir, servers);
                renderMcpTab(a, rootfsDir, manager, container);
            });
            item.addView(btnDel);

            listLayout.addView(item);
        }

        // Add Custom MCP Button
        TextView btnAdd = UiTheme.createButton(a, "➕ 添加自定义 MCP 服务", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 6);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiTheme.dp(a, 36));
        addLp.topMargin = UiTheme.dp(a, 6);
        btnAdd.setLayoutParams(addLp);
        btnAdd.setOnClickListener(v -> showAddMcpDialog(a, rootfsDir, manager, servers, () -> renderMcpTab(a, rootfsDir, manager, container)));
        container.addView(btnAdd);
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

        btnAddSkill.setOnClickListener(v -> showAddSkillDialog(a, rootfsDir, manager, () -> renderSkillTab(a, rootfsDir, manager, container)));
        btnReset.setOnClickListener(v -> {
            manager.installPresetSkills(rootfsDir);
            renderSkillTab(a, rootfsDir, manager, container);
            Toast.makeText(a, "已恢复预置技能集", Toast.LENGTH_SHORT).show();
        });

        btnRow.addView(btnAddSkill);
        btnRow.addView(btnReset);
        container.addView(btnRow);
    }

    private static void showAddMcpDialog(Activity a, File rootfsDir, McpSkillManager manager, List<McpSkillManager.McpServer> servers, Runnable onSaved) {
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiTheme.dp(a, 16), UiTheme.dp(a, 16), UiTheme.dp(a, 16), UiTheme.dp(a, 16));
        root.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));

        TextView title = new TextView(a);
        title.setText("添加自定义 MCP 服务");
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        EditText etName = createInput(a, "服务标识 (如 sqlite / fetch)");
        EditText etCmd = createInput(a, "执行命令 (如 npx / node / python3)");
        EditText etArgs = createInput(a, "运行参数 (空格隔开，如 -y @mcp/server)");

        root.addView(etName);
        root.addView(etCmd);
        root.addView(etArgs);

        b.setView(root);
        b.setPositiveButton("保存", (dialog, which) -> {
            String name = etName.getText().toString().trim();
            String cmd = etCmd.getText().toString().trim();
            String argsStr = etArgs.getText().toString().trim();
            if (!name.isEmpty() && !cmd.isEmpty()) {
                McpSkillManager.McpServer s = new McpSkillManager.McpServer(name, cmd);
                if (!argsStr.isEmpty()) {
                    for (String p : argsStr.split("\\s+")) {
                        s.args.add(p);
                    }
                }
                servers.add(s);
                manager.saveMcpServers(rootfsDir, servers);
                if (onSaved != null) onSaved.run();
                Toast.makeText(a, "已添加 MCP 服务: " + name, Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private static void showAddSkillDialog(Activity a, File rootfsDir, McpSkillManager manager, Runnable onSaved) {
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiTheme.dp(a, 16), UiTheme.dp(a, 16), UiTheme.dp(a, 16), UiTheme.dp(a, 16));
        root.setBackground(UiTheme.roundRect(a, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));

        TextView title = new TextView(a);
        title.setText("新建自定义 Skill 技能");
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        EditText etId = createInput(a, "技能 ID (英文小写，如 git-commit-helper)");
        EditText etTitle = createInput(a, "技能名称 (如 Git 自动提交助手)");
        EditText etDesc = createInput(a, "功能描述 (如 分析 diff 生成语义化提交)");
        EditText etPrompt = createInput(a, "提示词指引 (System Instruction)");
        etPrompt.setLines(3);

        root.addView(etId);
        root.addView(etTitle);
        root.addView(etDesc);
        root.addView(etPrompt);

        b.setView(root);
        b.setPositiveButton("保存", (dialog, which) -> {
            String id = etId.getText().toString().trim();
            String t = etTitle.getText().toString().trim();
            String desc = etDesc.getText().toString().trim();
            String pr = etPrompt.getText().toString().trim();
            if (!id.isEmpty()) {
                manager.saveSkill(rootfsDir, id, t.isEmpty() ? id : t, desc, pr);
                if (onSaved != null) onSaved.run();
                Toast.makeText(a, "已创建 Skill: " + id, Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
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
