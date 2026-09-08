package com.android.proot.sample.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.proot.sample.R;
import com.android.proot.sample.terminal.TerminalSessionItem;
import com.android.proot.sample.terminal.TerminalSessionManager;

import java.util.List;

/**
 * Controller for the Top Pull-Down Drawer (一体化大抽屉流, Scheme 2).
 * Integrates:
 * 1. Multi-session terminal hub (tabs, status, PID, switch, close, new session).
 * 2. AI Workflow actions (workspace, AI config, iFlow sessions, fullscreen).
 * 3. System services & tools (proxy, ADB, FTP, SSH, MCP, Health & Storage).
 * 
 * Excludes console preferences per user design decision.
 */
public final class TopDrawerController {

    public interface DrawerActionListener {
        void onWorkspaceClick();
        void onConfigClick();
        void onSessionsClick();
        void onFullscreenClick();
        void onProxyClick();
        void onAdbClick();
        void onFtpClick();
        void onSshClick();
        void onMcpClick();
        void onHealthClick();
        void onEnvironmentClick();
        void onNewProjectClick();
        void onNewSessionClick();
    }

    private final Context context;
    private final TerminalSessionManager sessionManager;
    private final DrawerActionListener actionListener;

    private View scrimView;
    private LinearLayout layoutDrawer;
    private ScrollView scrollContent;
    private TextView tvSessionHeader;
    private TextView btnNewSession;
    private LinearLayout containerSessionCards;
    private TextView btnCollapse;
    private View handlePill;

    // AI Workflow items
    private View itemWorkspace;
    private TextView tvWorkspaceLabel;
    private View itemAiConfig;
    private View itemIflowSessions;
    private View itemFullscreen;
    private View itemEnvironment;
    private View itemNewProject;

    // Services items
    private View itemProxy;
    private View itemAdb;
    private View itemFtp;
    private View itemSsh;
    private View itemMcp;
    private View itemHealth;

    private boolean isExpanded = false;
    private boolean isAnimating = false;

    public TopDrawerController(Context context, TerminalSessionManager sessionManager, DrawerActionListener actionListener) {
        this.context = context;
        this.sessionManager = sessionManager;
        this.actionListener = actionListener;
    }

    public void bindViews(View rootView) {
        if (rootView == null) return;

        scrimView = rootView.findViewById(R.id.top_drawer_scrim);
        layoutDrawer = rootView.findViewById(R.id.layout_top_drawer);
        scrollContent = rootView.findViewById(R.id.scroll_drawer_content);
        tvSessionHeader = rootView.findViewById(R.id.tv_drawer_session_header);
        btnNewSession = rootView.findViewById(R.id.btn_drawer_new_session);
        containerSessionCards = rootView.findViewById(R.id.container_session_cards);
        btnCollapse = rootView.findViewById(R.id.btn_drawer_collapse);
        handlePill = rootView.findViewById(R.id.drawer_handle_pill);

        // AI Workflow items
        itemWorkspace = rootView.findViewById(R.id.drawer_item_workspace);
        tvWorkspaceLabel = rootView.findViewById(R.id.tv_drawer_workspace_label);
        itemAiConfig = rootView.findViewById(R.id.drawer_item_ai_config);
        itemIflowSessions = rootView.findViewById(R.id.drawer_item_iflow_sessions);
        itemFullscreen = rootView.findViewById(R.id.drawer_item_fullscreen);
        itemEnvironment = rootView.findViewById(R.id.drawer_item_environment);
        itemNewProject = rootView.findViewById(R.id.drawer_item_new_project);

        // Service items
        itemProxy = rootView.findViewById(R.id.drawer_item_proxy);
        itemAdb = rootView.findViewById(R.id.drawer_item_adb);
        itemFtp = rootView.findViewById(R.id.drawer_item_ftp);
        itemSsh = rootView.findViewById(R.id.drawer_item_ssh);
        itemMcp = rootView.findViewById(R.id.drawer_item_mcp);
        itemHealth = rootView.findViewById(R.id.drawer_item_health);

        // Register session list listener
        if (sessionManager != null) {
            sessionManager.addSessionListChangeListener(new TerminalSessionManager.SessionListChangeListener() {
                @Override
                public void onSessionListChanged(List<TerminalSessionItem> sessions, int activeIndex) {
                    renderSessionCards(sessions, activeIndex);
                }

                @Override
                public void onActiveSessionChanged(TerminalSessionItem activeItem) {
                    if (sessionManager != null) {
                        renderSessionCards(sessionManager.getSessions(), sessionManager.getActiveSessionIndex());
                    }
                }
            });
        }
    }

    public void applyUiTheme() {
        if (btnNewSession != null) {
            btnNewSession.setBackground(UiTheme.roundRect(context, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 1, 4));
            UiTheme.applyTactileFeedback(btnNewSession);
        }

        if (btnCollapse != null) {
            UiTheme.applyTactileFeedback(btnCollapse);
        }

        if (handlePill != null) {
            handlePill.setBackground(UiTheme.roundRect(context, UiTheme.C_BORDER, null, 0, 2));
        }

        // Style AI workflow chips
        styleActionCard(itemWorkspace);
        styleActionCard(itemAiConfig);
        styleActionCard(itemIflowSessions);
        styleActionCard(itemFullscreen);
        styleActionCard(itemEnvironment);
        styleActionCard(itemNewProject);

        // Style Service chips
        styleActionCard(itemProxy);
        styleActionCard(itemAdb);
        styleActionCard(itemFtp);
        styleActionCard(itemSsh);
        styleActionCard(itemMcp);
        styleActionCard(itemHealth);
    }

    private void styleActionCard(View v) {
        if (v != null) {
            v.setBackground(UiTheme.roundRect(context, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));
            UiTheme.applyTactileFeedback(v);
        }
    }

    public void setupListeners() {
        if (scrimView != null) {
            scrimView.setOnClickListener(v -> collapse());
        }

        if (btnCollapse != null) {
            btnCollapse.setOnClickListener(v -> collapse());
        }

        if (btnNewSession != null) {
            btnNewSession.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) {
                    actionListener.onNewSessionClick();
                }
            });
        }

        // AI Workflow items
        if (itemWorkspace != null) {
            itemWorkspace.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onWorkspaceClick();
            });
        }
        if (itemAiConfig != null) {
            itemAiConfig.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onConfigClick();
            });
        }
        if (itemIflowSessions != null) {
            itemIflowSessions.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onSessionsClick();
            });
        }
        if (itemFullscreen != null) {
            itemFullscreen.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onFullscreenClick();
            });
        }
        if (itemEnvironment != null) {
            itemEnvironment.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onEnvironmentClick();
            });
        }
        if (itemNewProject != null) {
            itemNewProject.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onNewProjectClick();
            });
        }

        // Service items
        if (itemProxy != null) {
            itemProxy.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onProxyClick();
            });
        }
        if (itemAdb != null) {
            itemAdb.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onAdbClick();
            });
        }
        if (itemFtp != null) {
            itemFtp.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onFtpClick();
            });
        }
        if (itemSsh != null) {
            itemSsh.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onSshClick();
            });
        }
        if (itemMcp != null) {
            itemMcp.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onMcpClick();
            });
        }
        if (itemHealth != null) {
            itemHealth.setOnClickListener(v -> {
                collapse();
                if (actionListener != null) actionListener.onHealthClick();
            });
        }

        // Drag up to collapse gesture
        if (layoutDrawer != null) {
            setupDrawerDragUp(layoutDrawer);
        }
    }

    private void setupDrawerDragUp(View drawer) {
        drawer.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            private float startX;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        startX = event.getRawX();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float diffY = event.getRawY() - startY;
                        float diffX = Math.abs(event.getRawX() - startX);
                        if (diffY < -UiTheme.dp(context, 30) && diffX < Math.abs(diffY)) {
                            collapse();
                            return true;
                        }
                        break;
                }
                return false;
            }
        });
    }

    /**
     * Attaches drag-down touch gesture and click toggle to the top bar.
     */
    public void attachTopBarTrigger(View topBarView, View dropdownToggleBtn) {
        if (dropdownToggleBtn != null) {
            dropdownToggleBtn.setOnClickListener(v -> toggle());
            UiTheme.applyTactileFeedback(dropdownToggleBtn);
        }

        if (topBarView != null) {
            topBarView.setOnTouchListener(new View.OnTouchListener() {
                private float startY;
                private float startX;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startY = event.getRawY();
                            startX = event.getRawX();
                            return false;
                        case MotionEvent.ACTION_MOVE:
                            float diffY = event.getRawY() - startY;
                            float diffX = Math.abs(event.getRawX() - startX);
                            if (!isExpanded && diffY > UiTheme.dp(context, 20) && diffX < diffY * 1.5f) {
                                expand();
                                return true;
                            }
                            break;
                    }
                    return false;
                }
            });
        }
    }

    public void updateWorkspaceDisplay(String activeWorkspace) {
        if (tvWorkspaceLabel != null && activeWorkspace != null) {
            tvWorkspaceLabel.setText("工作区: " + activeWorkspace);
        }
    }

    public boolean isExpanded() {
        return isExpanded;
    }

    public void toggle() {
        if (isExpanded) {
            collapse();
        } else {
            expand();
        }
    }

    public void expand() {
        if (isExpanded || isAnimating || layoutDrawer == null) return;
        isAnimating = true;

        if (sessionManager != null) {
            renderSessionCards(sessionManager.getSessions(), sessionManager.getActiveSessionIndex());
        }

        layoutDrawer.setVisibility(View.VISIBLE);
        if (scrimView != null) {
            scrimView.setVisibility(View.VISIBLE);
            scrimView.setAlpha(0f);
            scrimView.animate().alpha(1f).setDuration(220).start();
        }

        layoutDrawer.post(() -> {
            int height = layoutDrawer.getHeight();
            if (height <= 0) height = UiTheme.dp(context, 380);
            layoutDrawer.setTranslationY(-height);
            layoutDrawer.animate()
                    .translationY(0)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator())
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            isExpanded = true;
                            isAnimating = false;
                        }
                    })
                    .start();
        });
    }

    public void collapse() {
        if (!isExpanded || isAnimating || layoutDrawer == null) return;
        isAnimating = true;

        int height = layoutDrawer.getHeight();
        if (height <= 0) height = UiTheme.dp(context, 380);

        if (scrimView != null) {
            scrimView.animate().alpha(0f).setDuration(200).start();
        }

        layoutDrawer.animate()
                .translationY(-height)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        layoutDrawer.setVisibility(View.GONE);
                        if (scrimView != null) {
                            scrimView.setVisibility(View.GONE);
                        }
                        isExpanded = false;
                        isAnimating = false;
                    }
                })
                .start();
    }

    /**
     * Renders terminal multi-session cards inside Section 1 of the drawer.
     */
    public void renderSessionCards(List<TerminalSessionItem> sessions, int activeIndex) {
        if (containerSessionCards == null) return;
        containerSessionCards.removeAllViews();

        int count = sessions != null ? sessions.size() : 0;
        if (tvSessionHeader != null) {
            tvSessionHeader.setText("💻 终端会话 (SESSIONS: " + count + ")");
        }

        if (sessions == null || sessions.isEmpty()) {
            TextView emptyTv = new TextView(context);
            emptyTv.setText("暂无活动终端会话，点击右上角新建");
            emptyTv.setTextColor(Color.parseColor(UiTheme.C_DIM));
            emptyTv.setTextSize(11f);
            emptyTv.setPadding(0, UiTheme.dp(context, 8), 0, UiTheme.dp(context, 8));
            containerSessionCards.addView(emptyTv);
            return;
        }

        for (int i = 0; i < sessions.size(); i++) {
            final int sessionIndex = i;
            TerminalSessionItem item = sessions.get(i);
            boolean isActive = (i == activeIndex);

            // Card container
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(UiTheme.dp(context, 10), UiTheme.dp(context, 8), UiTheme.dp(context, 8), UiTheme.dp(context, 8));

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, UiTheme.dp(context, 44));
            if (i > 0) {
                cardLp.topMargin = UiTheme.dp(context, 6);
            }
            card.setLayoutParams(cardLp);

            // Active vs Inactive card styling
            if (isActive) {
                card.setBackground(UiTheme.roundRect(context, "#0E2428", UiTheme.C_CYAN, 1, 6));
            } else {
                card.setBackground(UiTheme.roundRect(context, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));
            }

            // Status dot (green if running, red/dim if dead)
            View dot = new View(context);
            int dotSize = UiTheme.dp(context, 7);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotLp.setMarginEnd(UiTheme.dp(context, 8));
            dot.setLayoutParams(dotLp);
            String dotColor = item.isRunning() ? UiTheme.C_GREEN : UiTheme.C_RED;
            dot.setBackground(UiTheme.roundRect(context, dotColor, null, 0, 4));
            card.addView(dot);

            // Session Info (Title + CWD & PID)
            LinearLayout textBlock = new LinearLayout(context);
            textBlock.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            textBlock.setLayoutParams(tbLp);

            // Title line
            TextView tvTitle = new TextView(context);
            tvTitle.setText(item.getShortLabel());
            tvTitle.setTextSize(12f);
            tvTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tvTitle.setTextColor(Color.parseColor(isActive ? UiTheme.C_CYAN : UiTheme.C_TEXT));
            tvTitle.setSingleLine(true);
            textBlock.addView(tvTitle);

            // Details line
            TextView tvDetails = new TextView(context);
            tvDetails.setText(item.getDetailSummary());
            tvDetails.setTextSize(9.5f);
            tvDetails.setTypeface(Typeface.MONOSPACE);
            tvDetails.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tvDetails.setSingleLine(true);
            textBlock.addView(tvDetails);

            card.addView(textBlock);

            // Active Badge or Switch button
            if (isActive) {
                TextView tvActiveBadge = new TextView(context);
                tvActiveBadge.setText("当前");
                tvActiveBadge.setTextSize(9.5f);
                tvActiveBadge.setTypeface(Typeface.DEFAULT_BOLD);
                tvActiveBadge.setTextColor(Color.parseColor(UiTheme.C_CYAN));
                tvActiveBadge.setBackground(UiTheme.roundRect(context, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 1, 4));
                tvActiveBadge.setPadding(UiTheme.dp(context, 6), UiTheme.dp(context, 2), UiTheme.dp(context, 6), UiTheme.dp(context, 2));
                card.addView(tvActiveBadge);
            } else {
                TextView tvSwitch = new TextView(context);
                tvSwitch.setText("切换");
                tvSwitch.setTextSize(9.5f);
                tvSwitch.setTextColor(Color.parseColor(UiTheme.C_DIM));
                tvSwitch.setBackground(UiTheme.roundRect(context, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 4));
                tvSwitch.setPadding(UiTheme.dp(context, 6), UiTheme.dp(context, 2), UiTheme.dp(context, 6), UiTheme.dp(context, 2));
                card.addView(tvSwitch);
            }

            // Close session button (✕)
            TextView btnClose = new TextView(context);
            btnClose.setText("✕");
            btnClose.setTextSize(13f);
            btnClose.setTextColor(Color.parseColor(UiTheme.C_DIM));
            btnClose.setPadding(UiTheme.dp(context, 10), UiTheme.dp(context, 6), UiTheme.dp(context, 6), UiTheme.dp(context, 6));
            btnClose.setOnClickListener(v -> {
                if (sessionManager != null) {
                    sessionManager.closeSession(sessionIndex);
                }
            });
            UiTheme.applyTactileFeedback(btnClose);
            card.addView(btnClose);

            // Click card to switch to this session
            card.setOnClickListener(v -> {
                if (!isActive && sessionManager != null) {
                    sessionManager.switchToSession(sessionIndex);
                }
                collapse();
            });
            UiTheme.applyTactileFeedback(card);

            containerSessionCards.addView(card);
        }
    }
}
