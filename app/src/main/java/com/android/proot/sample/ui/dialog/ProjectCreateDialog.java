package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.sample.R;
import com.android.proot.sample.tool.ProjectScaffoldManager;
import com.android.proot.sample.ui.UiTheme;

import java.io.File;

/**
 * Dialog for creating demo starter projects (Android, C++, Rust, Go) in the active workspace.
 */
public final class ProjectCreateDialog {

    public interface OnProjectCreatedListener {
        void onProjectCreated(String guestProjectPath, boolean autoBuild);
    }

    private ProjectCreateDialog() {}

    public static void show(Activity activity, File rootfsDir, String currentWorkspace, OnProjectCreatedListener listener) {
        if (activity == null || activity.isFinishing()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_project_create, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        UiTheme.configureDialogWindow(dialog);

        View container = dialogView.findViewById(R.id.dialog_create_container);
        TextView btnClose = dialogView.findViewById(R.id.btn_create_close);
        TextView tvWs = dialogView.findViewById(R.id.tv_target_workspace);
        EditText etName = dialogView.findViewById(R.id.et_project_name);
        RadioButton rbAndroid = dialogView.findViewById(R.id.rb_tmpl_android);
        RadioButton rbCpp = dialogView.findViewById(R.id.rb_tmpl_cpp);
        RadioButton rbRust = dialogView.findViewById(R.id.rb_tmpl_rust);
        RadioButton rbGo = dialogView.findViewById(R.id.rb_tmpl_go);
        CheckBox cbAutoBuild = dialogView.findViewById(R.id.cb_auto_build);
        TextView btnCancel = dialogView.findViewById(R.id.btn_cancel_create);
        TextView btnSubmit = dialogView.findViewById(R.id.btn_submit_create);

        container.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));
        tvWs.setText(currentWorkspace != null ? currentWorkspace : "/workspace");
        etName.setText("demo_app");
        etName.setSelection(etName.getText().length());

        btnCancel.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        btnSubmit.setBackground(UiTheme.roundRect(activity, UiTheme.C_GREEN_BG, UiTheme.C_GREEN, 1, 4));
        UiTheme.applyTactileFeedback(btnCancel);
        UiTheme.applyTactileFeedback(btnSubmit);

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSubmit.setOnClickListener(v -> {
            String projectName = etName.getText().toString().trim();
            if (projectName.isEmpty()) {
                Toast.makeText(activity, "请输入项目名称", Toast.LENGTH_SHORT).show();
                return;
            }

            // Sanitize project name
            projectName = projectName.replaceAll("[^a-zA-Z0-9_-]", "_");

            ProjectScaffoldManager.ProjectType type = ProjectScaffoldManager.ProjectType.ANDROID_APK;
            if (rbCpp.isChecked()) {
                type = ProjectScaffoldManager.ProjectType.CPP_CMAKE;
            } else if (rbRust.isChecked()) {
                type = ProjectScaffoldManager.ProjectType.RUST_JNI;
            } else if (rbGo.isChecked()) {
                type = ProjectScaffoldManager.ProjectType.GO_JNI;
            }

            try {
                String guestPath = ProjectScaffoldManager.createProject(rootfsDir, tvWs.getText().toString(), projectName, type);
                dialog.dismiss();
                Toast.makeText(activity, "项目创建成功: " + guestPath, Toast.LENGTH_SHORT).show();
                if (listener != null) {
                    listener.onProjectCreated(guestPath, cbAutoBuild.isChecked());
                }
            } catch (Exception e) {
                Toast.makeText(activity, "创建失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        dialog.show();
    }
}
