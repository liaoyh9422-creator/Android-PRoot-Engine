package com.android.proot.sample;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.PRootEngine;
import com.android.proot.sample.ai.IFlowConfigManager;
import com.android.proot.sample.ai.WorkspaceManager;
import com.android.proot.sample.helper.StoragePermissionHelper;
import com.android.proot.sample.proxy.LocalProxyController;
import com.android.proot.sample.terminal.ExtraKeysBarController;
import com.android.proot.sample.terminal.TerminalBridge;
import com.android.proot.sample.terminal.TerminalSessionItem;
import com.android.proot.sample.terminal.TerminalSessionManager;
import com.android.proot.sample.ui.TopDrawerController;
import com.android.proot.sample.ui.UiTheme;
import com.android.proot.sample.ui.dialog.AdbOpsDialog;
import com.android.proot.sample.ui.dialog.AiConfigDialog;
import com.android.proot.sample.ui.dialog.FtpConfigDialog;
import com.android.proot.sample.ui.dialog.HealthStorageDialog;
import com.android.proot.sample.ui.dialog.IFlowSessionsDialog;
import com.android.proot.sample.ui.dialog.McpSkillDialog;
import com.android.proot.sample.ui.dialog.MoreMenuDialog;
import com.android.proot.sample.ui.dialog.ProxyOpsDialog;
import com.android.proot.sample.ui.dialog.SshConfigDialog;
import com.android.proot.sample.ui.dialog.WorkspaceDialog;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.List;

/**
 * Main Activity featuring Scheme 2 (一体化大抽屉流) + 方案 A (极客纯净顶栏 32dp):
 * - 32dp Immersive Micro Status Bar (Status dot, Active session, Workspace chip, Proxy badge, Session count, Dropdown toggle, More)
 * - Maximized Terminal Console Viewport with Multi-Session switching & background persistence
 * - Two-Row Standard PC Keyboard (with Inverted-T Directional Keys & HOME/END)
 * - Top Pull-Down Drawer with Multi-Session Management, AI Workflow, and Core Tools & Services
 */
public class MainActivity extends Activity {

    private PRootEngine engine;
    private WorkspaceManager workspaceManager;
    private IFlowConfigManager configManager;
    private TerminalBridge terminalBridge;
    private TerminalSessionManager sessionManager;
    private ExtraKeysBarController extraKeysController;
    private TopDrawerController topDrawerController;
    private LocalProxyController proxyController;

    // Root & Top Micro Bar
    private FrameLayout rootLayout;
    private LinearLayout layoutTopBar;
    private View viewTopStatusDot;
    private TextView tvActiveSessionBadge;
    private TextView tvActiveWorkspaceChip;
    private TextView tvTopProxyBadge;
    private TextView badgeSessionCount;
    private TextView btnDropdownToggle;
    private TextView btnMoreMenu;

    // Terminal View
    private View frameTerminal;
    private TerminalView terminalView;

    private int terminalFontSize = 12;
    private boolean isFullScreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!com.android.proot.sample.helper.SetupStatusManager.isSetupCompleted(this)) {
            startActivity(new android.content.Intent(this, com.android.proot.sample.ui.SetupWizardActivity.class));
            finish();
            return;
        }

        UiTheme.setupImmersiveStatusBar(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        setContentView(R.layout.activity_main);

        // 1. Initialize Localization & Managers
        I18n.init(this);
        engine = new PRootEngine(this);
        workspaceManager = WorkspaceManager.getInstance(this);
        configManager = IFlowConfigManager.getInstance(this);
        terminalBridge = new TerminalBridge(this);

        // 2. Bind View Elements
        bindViews();

        // 3. Connect TerminalView and SessionManager
        terminalBridge.setTerminalView(terminalView);
        sessionManager = new TerminalSessionManager(
                this,
                engine,
                terminalBridge,
                terminalView,
                null,
                viewTopStatusDot,
                null
        );

        terminalBridge.setCallback(new TerminalBridge.SessionCallback() {
            @Override
            public void onTitleChanged(TerminalSession session, String title) {
                sessionManager.onSessionTitleChanged(session, title);
            }

            @Override
            public void onSessionFinished(TerminalSession session, int exitCode) {
                sessionManager.onSessionFinished(session, exitCode);
            }
        });

        terminalBridge.setFontScaleListener(increase -> {
            if (increase) {
                terminalFontSize = Math.min(terminalFontSize + 1, 32);
            } else {
                terminalFontSize = Math.max(terminalFontSize - 1, 8);
            }
            terminalView.setTextSize(terminalFontSize);
        });

        // 4. Initialize Modular Controllers
        extraKeysController = new ExtraKeysBarController(this, terminalBridge, () -> sessionManager.getCurrentSession());
        extraKeysController.bindViews(findViewById(android.R.id.content));

        topDrawerController = new TopDrawerController(this, sessionManager, new TopDrawerController.DrawerActionListener() {
            @Override public void onWorkspaceClick() { showWorkspacesDialog(); }
            @Override public void onConfigClick() { showConfigDialog(); }
            @Override public void onSessionsClick() { showSessionsDialog(); }
            @Override public void onFullscreenClick() { toggleFullScreen(); }
            @Override public void onProxyClick() { showProxyOpsDialog(); }
            @Override public void onAdbClick() { showAdbDialog(); }
            @Override public void onFtpClick() { showFtpDialog(); }
            @Override public void onSshClick() { showSshDialog(); }
            @Override public void onMcpClick() { showMcpSkillsDialog(); }
            @Override public void onHealthClick() { showHealthStorageDialog(); }
            @Override public void onEnvironmentClick() { showEnvironmentHubDialog(); }
            @Override public void onNewProjectClick() { showProjectCreateDialog(); }
            @Override public void onNewSessionClick() { startNewTerminalSession(); }
        });
        topDrawerController.bindViews(findViewById(android.R.id.content));

        proxyController = new LocalProxyController(this);
        proxyController.bindControl(tvTopProxyBadge);

        // 5. Connect Multi-Session updates to Top Bar Badges
        sessionManager.addSessionListChangeListener(new TerminalSessionManager.SessionListChangeListener() {
            @Override
            public void onSessionListChanged(List<TerminalSessionItem> sessions, int activeIndex) {
                updateSessionBadges(sessions, activeIndex);
            }

            @Override
            public void onActiveSessionChanged(TerminalSessionItem activeItem) {
                if (activeItem != null && tvActiveSessionBadge != null) {
                    tvActiveSessionBadge.setText(activeItem.getShortLabel());
                }
            }
        });

        // 6. Apply UI Themes and Listeners
        applyUiTheme();
        extraKeysController.applyUiTheme();
        topDrawerController.applyUiTheme();

        setupListeners();
        extraKeysController.setupListeners();
        topDrawerController.setupListeners();
        topDrawerController.attachTopBarTrigger(layoutTopBar, btnDropdownToggle);

        updateUiTexts();

        // 7. Request Storage Permissions (Non-blocking)
        StoragePermissionHelper.requestStoragePermissionIfNeeded(this);

        // 8. Auto-initialize PRoot Engine & Launch Interactive Shell in Active Workspace
        sessionManager.initEngine(() -> {
            String activeWs = workspaceManager.getActiveWorkspace();
            WorkspaceManager.ensureDirectoryExists(engine.getRootfsDir(), activeWs);
            sessionManager.createNewSession(activeWs, sessionManager.getDefaultShell(), "-l");
        });
    }

    private void bindViews() {
        rootLayout = findViewById(R.id.root_layout);
        layoutTopBar = findViewById(R.id.layout_top_bar);
        viewTopStatusDot = findViewById(R.id.view_top_status_dot);
        tvActiveSessionBadge = findViewById(R.id.tv_active_session_badge);
        tvActiveWorkspaceChip = findViewById(R.id.tv_active_workspace_chip);
        tvTopProxyBadge = findViewById(R.id.tv_top_proxy_badge);
        badgeSessionCount = findViewById(R.id.badge_session_count);
        btnDropdownToggle = findViewById(R.id.btn_dropdown_toggle);
        btnMoreMenu = findViewById(R.id.btn_more_menu);

        frameTerminal = findViewById(R.id.frame_terminal);
        terminalView = findViewById(R.id.terminal_view);
    }

    private void applyUiTheme() {
        if (layoutTopBar != null) {
            layoutTopBar.setBackgroundColor(Color.parseColor(UiTheme.C_SURFACE));
        }

        if (tvActiveSessionBadge != null) {
            tvActiveSessionBadge.setBackground(UiTheme.roundRect(this, UiTheme.C_CYAN_BG, UiTheme.C_CYAN, 1, 4));
            tvActiveSessionBadge.setPadding(UiTheme.dp(this, 6), UiTheme.dp(this, 2), UiTheme.dp(this, 6), UiTheme.dp(this, 2));
            UiTheme.applyTactileFeedback(tvActiveSessionBadge);
        }

        if (tvActiveWorkspaceChip != null) {
            tvActiveWorkspaceChip.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
            tvActiveWorkspaceChip.setPadding(UiTheme.dp(this, 6), UiTheme.dp(this, 2), UiTheme.dp(this, 6), UiTheme.dp(this, 2));
            UiTheme.applyTactileFeedback(tvActiveWorkspaceChip);
        }

        if (badgeSessionCount != null) {
            badgeSessionCount.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB, 1, 4));
            badgeSessionCount.setPadding(UiTheme.dp(this, 6), UiTheme.dp(this, 2), UiTheme.dp(this, 6), UiTheme.dp(this, 2));
        }

        if (btnDropdownToggle != null) {
            btnDropdownToggle.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
            btnDropdownToggle.setPadding(UiTheme.dp(this, 6), UiTheme.dp(this, 2), UiTheme.dp(this, 6), UiTheme.dp(this, 2));
            UiTheme.applyTactileFeedback(btnDropdownToggle);
        }

        if (btnMoreMenu != null) {
            UiTheme.applyTactileFeedback(btnMoreMenu);
        }
    }

    private void setupListeners() {
        if (tvActiveSessionBadge != null) {
            tvActiveSessionBadge.setOnClickListener(v -> {
                if (topDrawerController != null) topDrawerController.toggle();
            });
        }

        if (tvActiveWorkspaceChip != null) {
            tvActiveWorkspaceChip.setOnClickListener(v -> showWorkspacesDialog());
        }

        if (tvTopProxyBadge != null) {
            tvTopProxyBadge.setOnClickListener(v -> showProxyOpsDialog());
        }

        if (btnDropdownToggle != null) {
            btnDropdownToggle.setOnClickListener(v -> {
                if (topDrawerController != null) topDrawerController.toggle();
            });
        }

        if (btnMoreMenu != null) {
            btnMoreMenu.setOnClickListener(v -> showMoreMenuDialog());
        }
    }

    private void updateSessionBadges(List<TerminalSessionItem> sessions, int activeIndex) {
        int count = sessions != null ? sessions.size() : 0;
        if (badgeSessionCount != null) {
            badgeSessionCount.setText("[" + count + "会话]");
        }
        if (sessions != null && activeIndex >= 0 && activeIndex < sessions.size()) {
            TerminalSessionItem active = sessions.get(activeIndex);
            if (tvActiveSessionBadge != null) {
                tvActiveSessionBadge.setText(active.getShortLabel());
            }
        }
    }

    private void updateUiTexts() {
        String activeWs = workspaceManager.getActiveWorkspace();
        if (tvActiveWorkspaceChip != null) {
            tvActiveWorkspaceChip.setText("📁 " + activeWs + " ▾");
        }
        if (topDrawerController != null) {
            topDrawerController.updateWorkspaceDisplay(activeWs);
        }
    }

    private void startNewTerminalSession() {
        String activeWs = workspaceManager.getActiveWorkspace();
        WorkspaceManager.ensureDirectoryExists(engine.getRootfsDir(), activeWs);
        sessionManager.createNewSession(activeWs, sessionManager.getDefaultShell(), "-l");
        Toast.makeText(this, "已创建新终端会话", Toast.LENGTH_SHORT).show();
    }

    // ==========================================
    // Dialogs & AI Session Actions
    // ==========================================

    private void showWorkspacesDialog() {
        WorkspaceDialog.show(this, engine.getRootfsDir(), path -> {
            updateUiTexts();
            if (sessionManager.getCurrentSession() != null && sessionManager.getCurrentSession().isRunning()) {
                terminalBridge.sendString(sessionManager.getCurrentSession(), WorkspaceManager.buildSafeCdCommand(path));
            } else {
                sessionManager.createNewSession(path, sessionManager.getDefaultShell(), "-l");
            }
        });
    }

    private void showSessionsDialog() {
        IFlowSessionsDialog.show(this, engine.getRootfsDir(), new IFlowSessionsDialog.SessionActionListener() {
            @Override
            public void onStartNewSession() {
                startNewAiSession();
            }

            @Override
            public void onResumeRecentSession() {
                continueRecentAiSession();
            }

            @Override
            public void onResumeSession(String sessionId) {
                resumeAiSession(sessionId);
            }
        });
    }

    private void showConfigDialog() {
        AiConfigDialog.show(this, engine.getRootfsDir(), this::launchAiSession);
    }

    private void showProxyOpsDialog() {
        ProxyOpsDialog.show(this, this::showConfigDialog);
    }

    private void showMoreMenuDialog() {
        MoreMenuDialog.show(this, terminalBridge, terminalView, new MoreMenuDialog.ActionListener() {
            @Override
            public void onFontSizeChanged(int newSize) {
                terminalFontSize = newSize;
                if (terminalView != null) {
                    terminalView.setTextSize(terminalFontSize);
                }
            }

            @Override
            public void onLanguageChanged() {
                updateUiTexts();
                Toast.makeText(MainActivity.this, I18n.get(I18n.Key.LOG_LANG_SWITCHED), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onOpenAdb() {
                showAdbDialog();
            }

            @Override
            public void onOpenMcpSkills() {
                showMcpSkillsDialog();
            }

            @Override
            public void onOpenHealthStorage() {
                showHealthStorageDialog();
            }

            @Override
            public void onOpenFtp() {
                showFtpDialog();
            }

            @Override
            public void onOpenSsh() {
                showSshDialog();
            }

            @Override
            public void onShowGestureGuide() {
                if (topDrawerController != null) {
                    topDrawerController.expand();
                }
                Toast.makeText(MainActivity.this, "从屏幕顶部向下滑动或点击 ▼ 即可唤出控制中心与终端会话管理", Toast.LENGTH_LONG).show();
            }

            @Override
            public TerminalSession getCurrentSession() {
                return sessionManager != null ? sessionManager.getCurrentSession() : null;
            }

            @Override
            public int getCurrentFontSize() {
                return terminalFontSize;
            }
        });
    }

    private void showAdbDialog() {
        AdbOpsDialog.show(this, engine);
    }

    private void showMcpSkillsDialog() {
        McpSkillDialog.show(this, engine.getRootfsDir());
    }

    private void showHealthStorageDialog() {
        HealthStorageDialog.show(this, engine);
    }

    private void showFtpDialog() {
        FtpConfigDialog.show(this);
    }

    private void showSshDialog() {
        SshConfigDialog.show(this, engine);
    }

    private void showEnvironmentHubDialog() {
        com.android.proot.sample.ui.dialog.EnvironmentHubDialog.show(this, engine);
    }

    private void showProjectCreateDialog() {
        String activeWs = workspaceManager.getActiveWorkspace();
        com.android.proot.sample.ui.dialog.ProjectCreateDialog.show(this, engine.getRootfsDir(), activeWs, (guestProjectPath, autoBuild) -> {
            if (autoBuild) {
                sessionManager.createNewSession(guestProjectPath, sessionManager.getDefaultShell(), "-c", "cd " + guestProjectPath + " && chmod +x build.sh && ./build.sh && exec " + sessionManager.getDefaultShell() + " -l");
            } else {
                sessionManager.createNewSession(guestProjectPath, sessionManager.getDefaultShell(), "-l");
            }
        });
    }

    private void launchAiSession() {
        configManager.ensureProxyRunningIfNeeded(configManager.getBaseUrl());
        configManager.syncIFlowConfigToRootfs(engine.getRootfsDir());

        String runCmd = configManager.isAutoApprove() ? "iflow -y" : "iflow";
        if (sessionManager.getCurrentSession() != null && sessionManager.getCurrentSession().isRunning()) {
            terminalBridge.sendString(sessionManager.getCurrentSession(), runCmd + "\n");
            Toast.makeText(this, "已在当前终端启动 iflow", Toast.LENGTH_SHORT).show();
        } else {
            String activeWs = workspaceManager.getActiveWorkspace();
            WorkspaceManager.ensureDirectoryExists(engine.getRootfsDir(), activeWs);
            if (configManager.isAutoApprove()) {
                sessionManager.createNewSession(activeWs, "/usr/local/bin/iflow", "-y");
            } else {
                sessionManager.createNewSession(activeWs, "/usr/local/bin/iflow");
            }
        }
    }

    private void startNewAiSession() {
        launchAiSession();
    }

    private void continueRecentAiSession() {
        configManager.ensureProxyRunningIfNeeded(configManager.getBaseUrl());
        configManager.syncIFlowConfigToRootfs(engine.getRootfsDir());

        String runCmd = configManager.isAutoApprove() ? "iflow -c -y" : "iflow -c";
        if (sessionManager.getCurrentSession() != null && sessionManager.getCurrentSession().isRunning()) {
            terminalBridge.sendString(sessionManager.getCurrentSession(), runCmd + "\n");
            Toast.makeText(this, "正在恢复最近的 iFlow 会话", Toast.LENGTH_SHORT).show();
        } else {
            String activeWs = workspaceManager.getActiveWorkspace();
            WorkspaceManager.ensureDirectoryExists(engine.getRootfsDir(), activeWs);
            if (configManager.isAutoApprove()) {
                sessionManager.createNewSession(activeWs, "/usr/local/bin/iflow", "-c", "-y");
            } else {
                sessionManager.createNewSession(activeWs, "/usr/local/bin/iflow", "-c");
            }
        }
    }

    private void resumeAiSession(String sessionId) {
        configManager.ensureProxyRunningIfNeeded(configManager.getBaseUrl());
        configManager.syncIFlowConfigToRootfs(engine.getRootfsDir());

        String cmd = configManager.isAutoApprove() ? "iflow -r " + sessionId + " -y\n" : "iflow -r " + sessionId + "\n";
        if (sessionManager.getCurrentSession() != null && sessionManager.getCurrentSession().isRunning()) {
            terminalBridge.sendString(sessionManager.getCurrentSession(), cmd);
            Toast.makeText(this, String.format(I18n.get(I18n.Key.TOAST_SESSION_RESUMED), sessionId), Toast.LENGTH_SHORT).show();
        } else {
            String activeWs = workspaceManager.getActiveWorkspace();
            WorkspaceManager.ensureDirectoryExists(engine.getRootfsDir(), activeWs);
            if (configManager.isAutoApprove()) {
                sessionManager.createNewSession(activeWs, "/usr/local/bin/iflow", "-r", sessionId, "-y");
            } else {
                sessionManager.createNewSession(activeWs, "/usr/local/bin/iflow", "-r", sessionId);
            }
        }
    }

    // ==========================================
    // Fullscreen Mode Handling
    // ==========================================

    private void toggleFullScreen() {
        isFullScreen = !isFullScreen;
        applyFullScreen(isFullScreen);
    }

    private void applyFullScreen(boolean fullScreen) {
        UiTheme.setupImmersiveStatusBar(this);
        if (layoutTopBar != null) {
            layoutTopBar.setVisibility(fullScreen ? View.GONE : View.VISIBLE);
        }
        Toast.makeText(this, fullScreen ? "已进入全屏沉浸模式" : "已退出全屏模式", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            UiTheme.setupImmersiveStatusBar(this);
        }
    }

    @Override
    public void onBackPressed() {
        if (topDrawerController != null && topDrawerController.isExpanded()) {
            topDrawerController.collapse();
            return;
        }
        if (isFullScreen) {
            toggleFullScreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        StoragePermissionHelper.handleRequestPermissionsResult(this, requestCode, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        StoragePermissionHelper.handleActivityResult(this, requestCode);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sessionManager != null) {
            sessionManager.destroy();
        }
        if (proxyController != null) {
            proxyController.destroy();
        }
    }
}
