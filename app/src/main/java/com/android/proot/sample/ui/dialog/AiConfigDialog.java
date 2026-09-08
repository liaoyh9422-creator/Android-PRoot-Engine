package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.proxy.CnbProxyServer;
import com.android.proot.proxy.ProxyConfig;
import com.android.proot.sample.I18n;
import com.android.proot.sample.R;
import com.android.proot.sample.ai.IFlowConfigManager;
import com.android.proot.sample.ui.ProxyFloatingLogView;
import com.android.proot.sample.ui.UiTheme;

import java.io.File;

/**
 * Modern modal dialog for configuring iFlow AI endpoints, API keys, models, and presets.
 */
public final class AiConfigDialog {

    public interface OnSaveAndRunListener {
        void onSaveAndRun();
    }

    private AiConfigDialog() {}

    public static void show(Activity activity, File rootfsDir, OnSaveAndRunListener runListener) {
        if (activity == null || activity.isFinishing()) return;

        IFlowConfigManager configManager = IFlowConfigManager.getInstance(activity);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_iflow_config, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        TextView badgeIflow = dialogView.findViewById(R.id.badge_dialog_iflow);
        TextView btnPresetCnb = dialogView.findViewById(R.id.btn_preset_cnb);
        TextView tvProxyStatus = dialogView.findViewById(R.id.tv_proxy_status);
        TextView btnPresetOpenRouter = dialogView.findViewById(R.id.btn_preset_openrouter);
        TextView btnPresetDeepSeek = dialogView.findViewById(R.id.btn_preset_deepseek);
        EditText etBaseUrl = dialogView.findViewById(R.id.et_iflow_base_url);
        EditText etApiKey = dialogView.findViewById(R.id.et_iflow_api_key);
        CheckBox cbShowKey = dialogView.findViewById(R.id.cb_show_key);
        EditText etModel = dialogView.findViewById(R.id.et_iflow_model);
        TextView btnFetchModels = dialogView.findViewById(R.id.btn_fetch_models);
        CheckBox cbApprove = dialogView.findViewById(R.id.cb_iflow_yolo);
        TextView btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        TextView btnSave = dialogView.findViewById(R.id.btn_dialog_save);
        TextView btnSaveRun = dialogView.findViewById(R.id.btn_dialog_save_and_run);

        // Styling dialog components
        dialogView.findViewById(R.id.dialog_container).setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));
        styleBadge(activity, badgeIflow, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        styleCapsule(activity, btnPresetCnb, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        btnPresetCnb.setText(I18n.get(I18n.Key.BTN_PRESET_CNB));
        styleCapsule(activity, btnPresetOpenRouter, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(activity, btnPresetDeepSeek, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        tvProxyStatus.setOnClickListener(v -> ProxyFloatingLogView.getInstance(activity).showExpanded());
        styleCapsule(activity, btnFetchModels, UiTheme.C_YELLOW, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW);
        styleCapsule(activity, btnCancel, UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(activity, btnSave, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(activity, btnSaveRun, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);

        etBaseUrl.setBackground(UiTheme.roundRect(activity, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));
        etApiKey.setBackground(UiTheme.roundRect(activity, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));
        etModel.setBackground(UiTheme.roundRect(activity, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));

        // Load saved values
        etBaseUrl.setText(configManager.getBaseUrl());
        etApiKey.setText(configManager.getApiKey());
        etModel.setText(configManager.getModel());
        cbApprove.setChecked(configManager.isAutoApprove());

        // Password visibility toggle
        cbShowKey.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            etApiKey.setSelection(etApiKey.getText().length());
        });

        Runnable updateProxyStatus = () -> {
            if (tvProxyStatus == null || activity.isFinishing()) return;
            activity.runOnUiThread(() -> {
                if (CnbProxyServer.getInstance().isRunning()) {
                    tvProxyStatus.setVisibility(View.VISIBLE);
                    tvProxyStatus.setText(String.format(I18n.get(I18n.Key.STATUS_PROXY_RUNNING), CnbProxyServer.getInstance().getBaseUrl()));
                    tvProxyStatus.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                } else if (CnbProxyServer.getInstance().isStarting()) {
                    tvProxyStatus.setVisibility(View.VISIBLE);
                    tvProxyStatus.setText(I18n.get(I18n.Key.STATUS_PROXY_STARTING));
                    tvProxyStatus.setTextColor(Color.parseColor(UiTheme.C_YELLOW));
                } else {
                    String currentUrl = etBaseUrl.getText().toString().trim();
                    if (IFlowConfigManager.isLocalProxy(currentUrl)) {
                        tvProxyStatus.setVisibility(View.VISIBLE);
                        tvProxyStatus.setText(I18n.get(I18n.Key.STATUS_PROXY_STOPPED));
                        tvProxyStatus.setTextColor(Color.parseColor(UiTheme.C_DIM));
                    } else {
                        tvProxyStatus.setVisibility(View.GONE);
                    }
                }
            });
        };

        CnbProxyServer.StateListener stateListener = new CnbProxyServer.StateListener() {
            @Override
            public void onStarting() {
                updateProxyStatus.run();
            }

            @Override
            public void onStarted(int port, String baseUrl) {
                activity.runOnUiThread(() -> {
                    updateProxyStatus.run();
                    if (etBaseUrl.getText().toString().contains("127.0.0.1")) {
                        etBaseUrl.setText(baseUrl);
                    }
                });
            }

            @Override
            public void onStopped() {
                updateProxyStatus.run();
            }

            @Override
            public void onError(String message, Throwable error) {
                activity.runOnUiThread(() -> {
                    updateProxyStatus.run();
                    Toast.makeText(activity, "Proxy error: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        };

        CnbProxyServer.getInstance().addStateListener(stateListener);
        dialog.setOnDismissListener(d -> CnbProxyServer.getInstance().removeStateListener(stateListener));

        // Preset buttons
        btnPresetCnb.setOnClickListener(v -> {
            ProxyFloatingLogView.getInstance(activity).showMiniCapsule();
            String baseUrl = CnbProxyServer.getInstance().getBaseUrl();
            etBaseUrl.setText(baseUrl);
            etModel.setText("deepseek-v4-flash");
            etApiKey.setText("cnb-free");
            cbApprove.setChecked(true);
            if (!CnbProxyServer.getInstance().isRunning() && !CnbProxyServer.getInstance().isStarting()) {
                tvProxyStatus.setVisibility(View.VISIBLE);
                tvProxyStatus.setText(I18n.get(I18n.Key.STATUS_PROXY_STARTING));
                tvProxyStatus.setTextColor(Color.parseColor(UiTheme.C_YELLOW));
                CnbProxyServer.getInstance().startAsync(new ProxyConfig.Builder().build());
            } else {
                updateProxyStatus.run();
            }
        });

        btnPresetOpenRouter.setOnClickListener(v -> {
            etBaseUrl.setText("https://openrouter.ai/api/v1");
            etModel.setText("openrouter/free");
            updateProxyStatus.run();
        });

        btnPresetDeepSeek.setOnClickListener(v -> {
            etBaseUrl.setText("https://api.deepseek.com/v1");
            etModel.setText("deepseek-chat");
            updateProxyStatus.run();
        });

        // Fetch models button
        btnFetchModels.setOnClickListener(v -> {
            String url = etBaseUrl.getText().toString().trim();
            String key = etApiKey.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(activity, "请先填写 Base URL", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(activity, I18n.get(I18n.Key.TOAST_FETCHING_MODELS), Toast.LENGTH_SHORT).show();
            configManager.fetchModels(url, key, models -> {
                if (models.isEmpty()) {
                    Toast.makeText(activity, "未从该端点找到模型", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(activity, String.format(I18n.get(I18n.Key.TOAST_FETCH_SUCCESS), models.size()), Toast.LENGTH_SHORT).show();
                new AlertDialog.Builder(activity)
                        .setTitle("选择模型 (" + models.size() + ")")
                        .setItems(models.toArray(new String[0]), (d, which) -> {
                            etModel.setText(models.get(which));
                        })
                        .show();
            }, err -> {
                Toast.makeText(activity, String.format(I18n.get(I18n.Key.TOAST_FETCH_FAIL), err), Toast.LENGTH_LONG).show();
            });
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String url = etBaseUrl.getText().toString().trim();
            String key = etApiKey.getText().toString().trim();
            String mdl = etModel.getText().toString().trim();
            if (key.isEmpty() && !IFlowConfigManager.isLocalProxy(url) && !url.isEmpty()) {
                Toast.makeText(activity, "提示: 外部端点未填写 API Key，可能会报错", Toast.LENGTH_SHORT).show();
            }
            configManager.saveConfig(url, key, mdl, cbApprove.isChecked(), rootfsDir);
            Toast.makeText(activity, I18n.get(I18n.Key.TOAST_CONFIG_SAVED), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        btnSaveRun.setOnClickListener(v -> {
            String url = etBaseUrl.getText().toString().trim();
            String key = etApiKey.getText().toString().trim();
            String mdl = etModel.getText().toString().trim();
            if (key.isEmpty() && !IFlowConfigManager.isLocalProxy(url) && !url.isEmpty()) {
                Toast.makeText(activity, "提示: 外部端点未填写 API Key，可能会报错", Toast.LENGTH_SHORT).show();
            }
            configManager.saveConfig(url, key, mdl, cbApprove.isChecked(), rootfsDir);
            dialog.dismiss();
            if (runListener != null) {
                runListener.onSaveAndRun();
            }
        });

        updateProxyStatus.run();
        dialog.show();
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
