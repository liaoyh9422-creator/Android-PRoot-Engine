package com.android.proot.sample.proxy;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;

import com.android.proot.proxy.CnbProxyServer;
import com.android.proot.sample.ui.UiTheme;
import com.android.proot.sample.ui.dialog.ProxyOpsDialog;

import org.json.JSONArray;

/**
 * Coordinates Local Proxy server state observation with UI controls in the AI Control Hub.
 */
public final class LocalProxyController {

    private final Activity activity;
    private final CnbProxyServer server;
    private TextView btnProxyBadge;

    private final CnbProxyServer.StateListener stateListener = new CnbProxyServer.StateListener() {
        @Override public void onStarting() { updateUi(); }
        @Override public void onStarted(int port, String baseUrl) { updateUi(); }
        @Override public void onStopped() { updateUi(); }
        @Override public void onError(String message, Throwable error) { updateUi(); }
    };

    public LocalProxyController(Activity activity) {
        this.activity = activity;
        this.server = CnbProxyServer.getInstance();
        this.server.addStateListener(stateListener);
    }

    public void bindControl(TextView btnProxy) {
        this.btnProxyBadge = btnProxy;
        if (btnProxyBadge != null) {
            btnProxyBadge.setOnClickListener(v -> ProxyOpsDialog.show(activity, null));
        }
        updateUi();
    }

    public void updateUi() {
        if (btnProxyBadge == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            if (server.isRunning()) {
                String text = "🌐 ● :" + server.getActualPort();
                btnProxyBadge.setText(text);
                btnProxyBadge.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                btnProxyBadge.setBackground(UiTheme.roundRect(activity, UiTheme.C_GREEN_BG, UiTheme.C_GREEN, 1, 4));
                btnProxyBadge.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 2), UiTheme.dp(activity, 6), UiTheme.dp(activity, 2));
            } else if (server.isStarting()) {
                btnProxyBadge.setText("🌐 ⏳启动中");
                btnProxyBadge.setTextColor(Color.parseColor(UiTheme.C_YELLOW));
                btnProxyBadge.setBackground(UiTheme.roundRect(activity, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW, 1, 4));
                btnProxyBadge.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 2), UiTheme.dp(activity, 6), UiTheme.dp(activity, 2));
            } else {
                btnProxyBadge.setText("🌐 :未启动");
                btnProxyBadge.setTextColor(Color.parseColor(UiTheme.C_DIM));
                btnProxyBadge.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
                btnProxyBadge.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 2), UiTheme.dp(activity, 6), UiTheme.dp(activity, 2));
            }
        });
    }

    public void destroy() {
        server.removeStateListener(stateListener);
    }
}
