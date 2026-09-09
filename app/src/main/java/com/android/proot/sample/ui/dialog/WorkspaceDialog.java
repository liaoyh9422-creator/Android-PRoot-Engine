package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.sample.I18n;
import com.android.proot.sample.R;
import com.android.proot.sample.ai.WorkspaceManager;
import com.android.proot.sample.ui.UiTheme;

import java.io.File;
import java.util.List;

/**
 * Modern modal dialog for switching active workspace, creating directories, and viewing workspace history.
 */
public final class WorkspaceDialog {

    public interface OnWorkspaceSelectedListener {
        void onWorkspaceSelected(String guestPath);
    }

    private WorkspaceDialog() {}

    public static void show(Activity activity, File rootfsDir, OnWorkspaceSelectedListener listener) {
        if (activity == null || activity.isFinishing()) return;

        WorkspaceManager wm = WorkspaceManager.getInstance(activity);
        String currentActive = wm.getActiveWorkspace();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_workspaces, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        UiTheme.configureDialogWindow(dialog);

        TextView badgeActive = dialogView.findViewById(R.id.badge_active_workspace);
        TextView btnClose = dialogView.findViewById(R.id.btn_dialog_close);
        TextView btnPresetRoot = dialogView.findViewById(R.id.btn_preset_root);
        TextView btnPresetProjects = dialogView.findViewById(R.id.btn_preset_projects);
        TextView btnPresetSdcard = dialogView.findViewById(R.id.btn_preset_sdcard);
        EditText etCustom = dialogView.findViewById(R.id.et_custom_workspace);
        CheckBox cbAutoCreate = dialogView.findViewById(R.id.cb_auto_create_dir);
        TextView btnSwitchCustom = dialogView.findViewById(R.id.btn_switch_custom);
        LinearLayout layoutRecent = dialogView.findViewById(R.id.layout_recent_workspaces);

        // Styling
        dialogView.findViewById(R.id.dialog_container).setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));
        styleBadge(activity, badgeActive, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        badgeActive.setText(currentActive);

        styleCapsule(activity, btnPresetRoot, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleCapsule(activity, btnPresetProjects, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        styleCapsule(activity, btnPresetSdcard, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(activity, btnSwitchCustom, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);

        etCustom.setBackground(UiTheme.roundRect(activity, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));

        btnClose.setOnClickListener(v -> dialog.dismiss());

        View.OnClickListener presetListener = v -> {
            String path = WorkspaceManager.DEFAULT_ROOTFS_HOME;
            if (v.getId() == R.id.btn_preset_projects) path = WorkspaceManager.DEFAULT_SDCARD_PROJECTS;
            else if (v.getId() == R.id.btn_preset_sdcard) path = WorkspaceManager.DEFAULT_SDCARD_ROOT;

            selectWorkspace(activity, dialog, rootfsDir, wm, path, true, listener);
        };

        btnPresetRoot.setOnClickListener(presetListener);
        btnPresetProjects.setOnClickListener(presetListener);
        btnPresetSdcard.setOnClickListener(presetListener);

        btnSwitchCustom.setOnClickListener(v -> {
            String input = etCustom.getText().toString().trim();
            String sanitized = WorkspaceManager.sanitizePath(input);
            if (sanitized == null) {
                Toast.makeText(activity, "请输入以 / 开头的合法绝对路径", Toast.LENGTH_SHORT).show();
                return;
            }
            selectWorkspace(activity, dialog, rootfsDir, wm, sanitized, cbAutoCreate.isChecked(), listener);
        });

        populateRecentList(activity, dialog, rootfsDir, wm, layoutRecent, listener);

        dialog.show();
    }

    private static void selectWorkspace(Activity activity, AlertDialog dialog, File rootfsDir,
                                        WorkspaceManager wm, String path, boolean autoCreate,
                                        OnWorkspaceSelectedListener listener) {
        if (autoCreate) {
            WorkspaceManager.ensureDirectoryExists(rootfsDir, path);
        }
        wm.setActiveWorkspace(path);
        dialog.dismiss();
        Toast.makeText(activity, "工作区已切换至: " + path, Toast.LENGTH_SHORT).show();
        if (listener != null) {
            listener.onWorkspaceSelected(path);
        }
    }

    private static void populateRecentList(Activity activity, AlertDialog dialog, File rootfsDir,
                                           WorkspaceManager wm, LinearLayout container,
                                           OnWorkspaceSelectedListener listener) {
        container.removeAllViews();
        List<String> list = wm.getRecentWorkspaces();
        String active = wm.getActiveWorkspace();

        for (String wsPath : list) {
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));
            item.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 8), UiTheme.dp(activity, 10), UiTheme.dp(activity, 8));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UiTheme.dp(activity, 6);
            item.setLayoutParams(lp);

            TextView tvPath = new TextView(activity);
            tvPath.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            String displayText = wsPath.equals(WorkspaceManager.DEFAULT_WORKSPACE) ? wsPath + " (files/workspace)" : wsPath;
            tvPath.setText(displayText);
            tvPath.setTextColor(Color.parseColor(wsPath.equals(active) ? UiTheme.C_CYAN : UiTheme.C_TEXT));
            tvPath.setTextSize(12f);
            tvPath.setTypeface(android.graphics.Typeface.MONOSPACE);

            if (wsPath.equals(WorkspaceManager.DEFAULT_WORKSPACE)) {
                TextView defBadge = new TextView(activity);
                styleBadge(activity, defBadge, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
                defBadge.setText("默认");
                defBadge.setTextSize(10f);
                defBadge.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 2), UiTheme.dp(activity, 6), UiTheme.dp(activity, 2));
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                badgeLp.rightMargin = UiTheme.dp(activity, 6);
                defBadge.setLayoutParams(badgeLp);
                item.addView(defBadge);
            }

            if (wsPath.equals(active)) {
                TextView activeBadge = new TextView(activity);
                styleBadge(activity, activeBadge, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);
                activeBadge.setText("当前");
                activeBadge.setTextSize(10f);
                activeBadge.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 2), UiTheme.dp(activity, 6), UiTheme.dp(activity, 2));
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                badgeLp.rightMargin = UiTheme.dp(activity, 8);
                activeBadge.setLayoutParams(badgeLp);
                item.addView(activeBadge);
            }

            item.addView(tvPath);

            if (!WorkspaceManager.DEFAULT_WORKSPACE.equals(wsPath) && !WorkspaceManager.DEFAULT_ROOTFS_HOME.equals(wsPath)) {
                TextView btnDel = new TextView(activity);
                btnDel.setText("✕");
                btnDel.setTextColor(Color.parseColor(UiTheme.C_DIM));
                btnDel.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 2), UiTheme.dp(activity, 6), UiTheme.dp(activity, 2));
                btnDel.setOnClickListener(v -> {
                    wm.removeRecentWorkspace(wsPath);
                    populateRecentList(activity, dialog, rootfsDir, wm, container, listener);
                });
                item.addView(btnDel);
            }

            item.setOnClickListener(v -> selectWorkspace(activity, dialog, rootfsDir, wm, wsPath, true, listener));

            container.addView(item);
        }
    }

    private static void styleBadge(Activity a, TextView view, String textColor, String bgColor, String strokeColor) {
        if (view == null) return;
        view.setTextColor(Color.parseColor(textColor));
        view.setBackground(UiTheme.roundRect(a, bgColor, strokeColor, 1, 4));
        view.setPadding(UiTheme.dp(a, 6), UiTheme.dp(a, 2), UiTheme.dp(a, 6), UiTheme.dp(a, 2));
    }

    private static void styleCapsule(Activity a, TextView btn, String textColor, String bgColor, String strokeColor) {
        if (btn == null) return;
        btn.setTextColor(Color.parseColor(textColor));
        btn.setBackground(UiTheme.roundRect(a, bgColor, strokeColor, 1, 6));
        btn.setPadding(UiTheme.dp(a, 10), UiTheme.dp(a, 6), UiTheme.dp(a, 10), UiTheme.dp(a, 6));
    }
}
