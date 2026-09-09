package com.android.proot.sample.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.sample.I18n;
import com.android.proot.sample.R;
import com.android.proot.sample.ai.IFlowSessionManager;
import com.android.proot.sample.ui.UiTheme;

import java.io.File;
import java.util.List;

/**
 * Modern modal dialog for viewing, resuming, creating, and deleting iFlow sessions.
 */
public final class IFlowSessionsDialog {

    public interface SessionActionListener {
        void onStartNewSession();
        void onResumeRecentSession();
        void onResumeSession(String sessionId);
    }

    private IFlowSessionsDialog() {}

    public static void show(Activity activity, File rootfsDir, SessionActionListener listener) {
        if (activity == null || activity.isFinishing()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_iflow_sessions, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        UiTheme.configureDialogWindow(dialog);

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        TextView badgeCount = dialogView.findViewById(R.id.badge_sessions_count);
        TextView btnClose = dialogView.findViewById(R.id.btn_dialog_close);
        TextView btnNewSession = dialogView.findViewById(R.id.btn_new_session);
        TextView btnResumeRecent = dialogView.findViewById(R.id.btn_resume_recent);
        TextView btnRefresh = dialogView.findViewById(R.id.btn_refresh_sessions);
        LinearLayout layoutSessionsList = dialogView.findViewById(R.id.layout_sessions_list);
        LinearLayout layoutEmptyState = dialogView.findViewById(R.id.layout_empty_state);
        TextView tvEmptyTitle = dialogView.findViewById(R.id.tv_empty_title);
        TextView tvEmptyDesc = dialogView.findViewById(R.id.tv_empty_desc);

        // Styling dialog container and buttons
        dialogView.findViewById(R.id.dialog_container).setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));
        styleBadge(activity, badgeCount, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleCapsule(activity, btnNewSession, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);
        styleCapsule(activity, btnResumeRecent, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(activity, btnRefresh, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);

        tvTitle.setText(I18n.get(I18n.Key.TITLE_SESSIONS));
        btnNewSession.setText(I18n.get(I18n.Key.BTN_NEW_SESSION));
        btnResumeRecent.setText(I18n.get(I18n.Key.BTN_RESUME_RECENT));
        btnRefresh.setText(I18n.get(I18n.Key.BTN_REFRESH));
        tvEmptyTitle.setText(I18n.get(I18n.Key.SESSIONS_EMPTY));
        tvEmptyDesc.setText(I18n.get(I18n.Key.SESSIONS_EMPTY_DESC));

        btnClose.setOnClickListener(v -> dialog.dismiss());

        btnNewSession.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onStartNewSession();
        });

        btnResumeRecent.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onResumeRecentSession();
        });

        btnRefresh.setOnClickListener(v -> {
            populateSessionsList(activity, dialog, rootfsDir, layoutSessionsList, layoutEmptyState, badgeCount, listener);
        });

        populateSessionsList(activity, dialog, rootfsDir, layoutSessionsList, layoutEmptyState, badgeCount, listener);

        dialog.show();
    }

    private static void populateSessionsList(Activity activity, AlertDialog dialog, File rootfsDir,
                                              LinearLayout container, LinearLayout emptyState,
                                              TextView badgeCount, SessionActionListener listener) {
        List<IFlowSessionManager.IFlowSession> sessions = IFlowSessionManager.loadSessions(rootfsDir);
        badgeCount.setText(String.format(I18n.get(I18n.Key.BADGE_TOTAL_SESSIONS), sessions.size()));

        if (container.getChildCount() > 1) {
            container.removeViews(1, container.getChildCount() - 1);
        }

        if (sessions.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            return;
        }

        emptyState.setVisibility(View.GONE);

        for (IFlowSessionManager.IFlowSession session : sessions) {
            View itemView = activity.getLayoutInflater().inflate(R.layout.item_iflow_session, container, false);

            itemView.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));

            TextView tvTime = itemView.findViewById(R.id.tv_session_time);
            TextView badgeModel = itemView.findViewById(R.id.badge_session_model);
            TextView badgeSize = itemView.findViewById(R.id.badge_session_size);
            TextView tvPreview = itemView.findViewById(R.id.tv_session_preview);
            TextView tvId = itemView.findViewById(R.id.tv_session_id);
            TextView tvCwd = itemView.findViewById(R.id.tv_session_cwd);
            TextView btnDelete = itemView.findViewById(R.id.btn_item_delete);

            styleBadge(activity, badgeModel, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
            styleBadge(activity, badgeSize, UiTheme.C_DIM, UiTheme.C_SURFACE, UiTheme.C_BORDER_SUB);
            styleCapsule(activity, btnDelete, UiTheme.C_RED, UiTheme.C_RED_BG, UiTheme.C_RED);

            tvTime.setText(IFlowSessionManager.formatTime(session.lastModified));
            badgeModel.setText(session.model != null && !session.model.isEmpty() ? session.model : "default");
            badgeSize.setText(IFlowSessionManager.formatFileSize(session.fileSize));
            tvPreview.setText(session.title);
            tvId.setText("ID: " + session.id);
            tvCwd.setText("📁 " + session.cwd);
            btnDelete.setText(I18n.get(I18n.Key.BTN_DELETE));

            UiTheme.applyTactileFeedback(itemView);
            itemView.setOnClickListener(v -> {
                dialog.dismiss();
                if (listener != null) listener.onResumeSession(session.id);
            });

            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(activity)
                        .setTitle(I18n.get(I18n.Key.CONFIRM_DELETE_TITLE))
                        .setMessage(String.format(I18n.get(I18n.Key.CONFIRM_DELETE_SESSION), session.id))
                        .setPositiveButton(I18n.get(I18n.Key.BTN_DELETE), (d, which) -> {
                            if (session.file != null && session.file.exists()) {
                                boolean ok = session.file.delete();
                                if (ok) {
                                    Toast.makeText(activity, I18n.get(I18n.Key.TOAST_SESSION_DELETED), Toast.LENGTH_SHORT).show();
                                    populateSessionsList(activity, dialog, rootfsDir, container, emptyState, badgeCount, listener);
                                }
                            }
                        })
                        .setNegativeButton(I18n.get(I18n.Key.BTN_CANCEL), null)
                        .show();
            });

            container.addView(itemView);
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
