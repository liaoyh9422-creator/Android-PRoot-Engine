package com.android.proot.sample;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.PRootConfig;
import com.android.proot.PRootEngine;
import com.android.proot.sample.terminal.TerminalBridge;
import com.android.proot.sample.ui.UiTheme;
import com.android.proot.proxy.CnbProxyServer;
import com.android.proot.proxy.ProxyConfig;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity: Android-PRoot-Engine host console interface.
 * Equipped with full-fledged Termux TerminalView & PTY backend (supporting vi/top/ANSI colors),
 * virtual accessory keybar, and CLIProxyAPI-inspired dark geek theme.
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private PRootEngine engine;
    private TerminalBridge terminalBridge;
    private TerminalSession currentSession;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // UI Cards & Containers
    private LinearLayout rootLayout;
    private View cardHeader;
    private View cardActions;
    private View cardTerminal;
    private FrameLayout frameTerminal;
    private HorizontalScrollView scrollKeybar;

    // Header & Badges
    private TextView tvTitle;
    private TextView badgeArch;
    private TextView badgeRoot;
    private TextView badgeDistro;
    private TextView badgeSaf;
    private View viewStatusDot;
    private TextView tvStatus;

    // Language Buttons
    private TextView btnLangZh;
    private TextView btnLangEn;
    private TextView btnLangJa;

    // Control Actions
    private TextView tvSectionControl;
    private TextView btnSessionsCtrl;
    private TextView btnAiConfig;
    private TextView btnInit;
    private TextView btnRunUname;
    private TextView btnRunScript;
    private TextView btnStop;
    private EditText etCommand;
    private TextView btnExec;

    // Terminal Console & Keybar
    private TextView tvSectionTerminal;
    private TextView badgeLineCount;
    private TextView btnSessions;
    private TextView btnFullscreen;
    private TextView btnFontMinus;
    private TextView btnFontPlus;
    private TextView btnCopy;
    private TextView btnClear;
    private TerminalView terminalView;
    private int terminalFontSize = 12;
    private boolean isFullScreen = true;
    private static final int REQUEST_CODE_STORAGE_PERMS = 1001;
    private static final int REQUEST_CODE_MANAGE_STORAGE = 1002;

    // Keybar Buttons
    private TextView keyEsc;
    private TextView keyTab;
    private TextView keyCtrl;
    private TextView keyAlt;
    private TextView keySigint;
    private TextView keyEof;
    private TextView keyUp;
    private TextView keyDown;
    private TextView keyLeft;
    private TextView keyRight;
    private TextView keyPaste;

    private enum State {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        INIT_FAILED,
        RUNNING,
        STOPPED,
        IDLE,
        ERROR
    }

    private State currentState = State.UNINITIALIZED;
    private String stateDetail = "";
    private int lastExitCode = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiTheme.setupImmersiveStatusBar(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        setContentView(R.layout.activity_main);

        // 1. Initialize Pure Java I18n
        I18n.init(this);

        // 2. Initialize Terminal Bridge
        terminalBridge = new TerminalBridge(this);

        // 3. Bind Views
        bindViews();

        // 4. Connect TerminalView to Bridge
        terminalBridge.setTerminalView(terminalView);
        terminalBridge.setCallback(new TerminalBridge.SessionCallback() {
            @Override
            public void onTitleChanged(String title) {
                if (badgeLineCount != null && title != null && !title.isEmpty()) {
                    badgeLineCount.setText(title);
                }
            }

            @Override
            public void onSessionFinished(int exitCode) {
                setStatus(State.IDLE, "", exitCode);
                badgeLineCount.setText("Exit: " + exitCode);
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

        // 5. Apply CLIProxyAPI-style Themes & Drawables
        applyUiTheme();

        // 6. Initialize PRoot Engine instance
        engine = new PRootEngine(this);

        // 7. Setup Action Listeners
        setupListeners();

        // 8. Initial Localization Render
        updateUiTexts();

        // 9. Apply Default Fullscreen (Immersive Status Bar, no top line)
        applyFullScreen(true);

        // 10. Request Storage Permissions (Non-blocking)
        requestStoragePermissionIfNeeded();

        // 11. Auto-initialize PRoot Engine & Launch Interactive Shell
        if (currentState == State.UNINITIALIZED) {
            initEngine();
        }
    }

    private void bindViews() {
        rootLayout = findViewById(R.id.root_layout);
        cardHeader = findViewById(R.id.card_header);
        cardActions = findViewById(R.id.card_actions);
        cardTerminal = findViewById(R.id.card_terminal);
        frameTerminal = findViewById(R.id.frame_terminal);
        scrollKeybar = findViewById(R.id.scroll_keybar);

        tvTitle = findViewById(R.id.tv_title);
        badgeArch = findViewById(R.id.badge_arch);
        badgeRoot = findViewById(R.id.badge_root);
        badgeDistro = findViewById(R.id.badge_distro);
        badgeSaf = findViewById(R.id.badge_saf);
        viewStatusDot = findViewById(R.id.view_status_dot);
        tvStatus = findViewById(R.id.tv_status);

        btnLangZh = findViewById(R.id.btn_lang_zh);
        btnLangEn = findViewById(R.id.btn_lang_en);
        btnLangJa = findViewById(R.id.btn_lang_ja);

        tvSectionControl = findViewById(R.id.tv_section_control);
        btnSessionsCtrl = findViewById(R.id.btn_sessions_ctrl);
        btnAiConfig = findViewById(R.id.btn_ai_config);
        btnInit = findViewById(R.id.btn_init);
        btnRunUname = findViewById(R.id.btn_run_uname);
        btnRunScript = findViewById(R.id.btn_run_script);
        btnStop = findViewById(R.id.btn_stop);
        etCommand = findViewById(R.id.et_command);
        btnExec = findViewById(R.id.btn_exec);

        tvSectionTerminal = findViewById(R.id.tv_section_terminal);
        badgeLineCount = findViewById(R.id.badge_line_count);
        btnSessions = findViewById(R.id.btn_sessions);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        btnFontMinus = findViewById(R.id.btn_font_minus);
        btnFontPlus = findViewById(R.id.btn_font_plus);
        btnCopy = findViewById(R.id.btn_copy);
        btnClear = findViewById(R.id.btn_clear);
        terminalView = findViewById(R.id.terminal_view);

        // Keybar
        keyEsc = findViewById(R.id.key_esc);
        keyTab = findViewById(R.id.key_tab);
        keyCtrl = findViewById(R.id.key_ctrl);
        keyAlt = findViewById(R.id.key_alt);
        keySigint = findViewById(R.id.key_sigint);
        keyEof = findViewById(R.id.key_eof);
        keyUp = findViewById(R.id.key_up);
        keyDown = findViewById(R.id.key_down);
        keyLeft = findViewById(R.id.key_left);
        keyRight = findViewById(R.id.key_right);
        keyPaste = findViewById(R.id.key_paste);
    }

    private void applyUiTheme() {
        // Surface Cards
        cardHeader.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));
        cardActions.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));
        cardTerminal.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));

        // Terminal frame
        frameTerminal.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));
        etCommand.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER, 1, 6));

        // Badges
        styleBadge(badgeArch, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        styleBadge(badgeRoot, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleBadge(badgeDistro, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);
        styleBadge(badgeSaf, UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB);
        styleBadge(badgeLineCount, UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB);

        // Action Micro-capsules
        styleCapsule(btnSessionsCtrl, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleCapsule(btnAiConfig, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleCapsule(btnInit, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(btnRunUname, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        styleCapsule(btnRunScript, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(btnStop, UiTheme.C_RED, UiTheme.C_RED_BG, UiTheme.C_RED);
        styleCapsule(btnExec, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);

        // Terminal Top Action Buttons
        styleCapsule(btnSessions, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleCapsule(btnFullscreen, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(btnFontMinus, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(btnFontPlus, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(btnCopy, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(btnClear, UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);

        // Style Keybar Buttons
        styleKeyButton(keyEsc);
        styleKeyButton(keyTab);
        styleKeyButton(keyCtrl);
        styleKeyButton(keyAlt);
        styleKeyButton(keySigint);
        styleKeyButton(keyEof);
        styleKeyButton(keyUp);
        styleKeyButton(keyDown);
        styleKeyButton(keyLeft);
        styleKeyButton(keyRight);
        styleKeyButton(keyPaste);

        // Status Indicator Dot Base
        updateStatusDot(UiTheme.C_DIM);
    }

    private void styleKeyButton(TextView keyView) {
        keyView.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        keyView.setTextSize(10f);
        keyView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        keyView.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        keyView.setPadding(UiTheme.dp(this, 9), UiTheme.dp(this, 5), UiTheme.dp(this, 9), UiTheme.dp(this, 5));
        keyView.setGravity(Gravity.CENTER);
        keyView.setClickable(true);
        keyView.setFocusable(true);
        keyView.setIncludeFontPadding(false);
    }

    private void updateKeyModifierStyles() {
        if (terminalBridge.isControlKeyActive()) {
            keyCtrl.setBackground(UiTheme.roundRect(this, UiTheme.C_BLUE_BG, UiTheme.C_BLUE, 1, 4));
            keyCtrl.setTextColor(Color.parseColor(UiTheme.C_BLUE));
        } else {
            keyCtrl.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
            keyCtrl.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        }

        if (terminalBridge.isAltKeyActive()) {
            keyAlt.setBackground(UiTheme.roundRect(this, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE, 1, 4));
            keyAlt.setTextColor(Color.parseColor(UiTheme.C_PURPLE));
        } else {
            keyAlt.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
            keyAlt.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        }
    }

    private void styleBadge(TextView view, String textColor, String bgColor, String strokeColor) {
        view.setTextColor(Color.parseColor(textColor));
        view.setTextSize(9.5f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setBackground(UiTheme.roundRect(this, bgColor, strokeColor, 1, 3));
        view.setPadding(UiTheme.dp(this, 5), UiTheme.dp(this, 2), UiTheme.dp(this, 5), UiTheme.dp(this, 2));
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
    }

    private void styleCapsule(TextView btn, String textColor, String bgColor, String strokeColor) {
        btn.setTextColor(Color.parseColor(textColor));
        btn.setTextSize(11.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setBackground(UiTheme.roundRect(this, bgColor, strokeColor, 1, 5));
        btn.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 6), UiTheme.dp(this, 10), UiTheme.dp(this, 6));
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setIncludeFontPadding(false);
    }

    private void updateStatusDot(String colorHex) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Color.parseColor(colorHex));
        viewStatusDot.setBackground(dot);
    }

    private void setupListeners() {
        // Language Switcher
        btnLangZh.setOnClickListener(v -> switchLanguage(I18n.Language.ZH_CN));
        btnLangEn.setOnClickListener(v -> switchLanguage(I18n.Language.EN));
        btnLangJa.setOnClickListener(v -> switchLanguage(I18n.Language.JA));

        // Preset Actions
        btnSessionsCtrl.setOnClickListener(v -> showPigoSessionsDialog());
        btnAiConfig.setOnClickListener(v -> showAiConfigDialog());
        btnInit.setOnClickListener(v -> initEngine());
        btnRunUname.setOnClickListener(v -> runTerminalSession(getDefaultShell(), "-c", "uname -a"));
        btnRunScript.setOnClickListener(v -> runTerminalSession(getDefaultShell(), "-l")); // Launch interactive shell!
        btnStop.setOnClickListener(v -> stopCurrentSession());

        // Custom Command Execution
        btnExec.setOnClickListener(v -> executeCustomCommand());
        etCommand.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                executeCustomCommand();
                return true;
            }
            return false;
        });

        // Fullscreen and Font scaling
        btnSessions.setOnClickListener(v -> showPigoSessionsDialog());
        btnFullscreen.setOnClickListener(v -> toggleFullScreen());
        btnFontPlus.setOnClickListener(v -> {
            terminalFontSize = Math.min(terminalFontSize + 1, 32);
            terminalView.setTextSize(terminalFontSize);
        });
        btnFontMinus.setOnClickListener(v -> {
            terminalFontSize = Math.max(terminalFontSize - 1, 8);
            terminalView.setTextSize(terminalFontSize);
        });

        // Copy and Clear
        btnCopy.setOnClickListener(v -> {
            if (currentSession != null && currentSession.getEmulator() != null) {
                String transcript = currentSession.getEmulator().getScreen().getTranscriptText();
                terminalBridge.onCopyTextToClipboard(currentSession, transcript);
            }
        });
        btnClear.setOnClickListener(v -> {
            if (currentSession != null) {
                currentSession.reset();
            }
        });

        // Keybar Handlers
        keyEsc.setOnClickListener(v -> terminalBridge.sendEscape(currentSession));
        keyTab.setOnClickListener(v -> terminalBridge.sendTab(currentSession));
        keyCtrl.setOnClickListener(v -> {
            terminalBridge.toggleControlKey();
            updateKeyModifierStyles();
        });
        keyAlt.setOnClickListener(v -> {
            terminalBridge.toggleAltKey();
            updateKeyModifierStyles();
        });
        keySigint.setOnClickListener(v -> terminalBridge.sendSigInt(currentSession));
        keyEof.setOnClickListener(v -> terminalBridge.sendEof(currentSession));
        keyUp.setOnClickListener(v -> terminalBridge.sendArrowUp(currentSession));
        keyDown.setOnClickListener(v -> terminalBridge.sendArrowDown(currentSession));
        keyLeft.setOnClickListener(v -> terminalBridge.sendArrowLeft(currentSession));
        keyRight.setOnClickListener(v -> terminalBridge.sendArrowRight(currentSession));
        keyPaste.setOnClickListener(v -> terminalBridge.onPasteTextFromClipboard(currentSession));
    }

    private void executeCustomCommand() {
        String cmd = etCommand.getText().toString().trim();
        if (cmd.isEmpty()) return;

        etCommand.setText("");
        if (currentSession != null && currentSession.isRunning()) {
            terminalBridge.sendString(currentSession, cmd + "\n");
        } else {
            runTerminalSession(getDefaultShell(), "-c", cmd);
        }
    }

    private void switchLanguage(I18n.Language language) {
        I18n.setLanguage(this, language);
        updateUiTexts();
    }

    private void updateUiTexts() {
        tvTitle.setText(I18n.get(I18n.Key.APP_TITLE));
        tvSectionControl.setText(I18n.get(I18n.Key.SECTION_CONTROL));
        tvSectionTerminal.setText(I18n.get(I18n.Key.SECTION_TERMINAL));

        btnSessionsCtrl.setText(I18n.get(I18n.Key.BTN_SESSIONS));
        btnAiConfig.setText(I18n.get(I18n.Key.BTN_AI_CONFIG));
        btnSessions.setText(I18n.get(I18n.Key.BTN_SESSIONS));
        btnFullscreen.setText(isFullScreen ? I18n.get(I18n.Key.BTN_EXIT_FULLSCREEN) : I18n.get(I18n.Key.BTN_FULLSCREEN));
        btnInit.setText(I18n.get(I18n.Key.BTN_INIT));
        btnRunUname.setText(I18n.get(I18n.Key.BTN_RUN_UNAME));
        btnRunScript.setText("Shell (PTY)");
        btnStop.setText(I18n.get(I18n.Key.BTN_STOP));
        btnExec.setText(I18n.get(I18n.Key.BTN_EXEC));
        btnCopy.setText(I18n.get(I18n.Key.BTN_COPY));
        btnClear.setText(I18n.get(I18n.Key.BTN_CLEAR));
        etCommand.setHint(I18n.get(I18n.Key.HINT_CUSTOM_CMD));

        // Language Buttons Highlight
        I18n.Language current = I18n.getLanguage();
        styleLangButton(btnLangZh, current == I18n.Language.ZH_CN);
        styleLangButton(btnLangEn, current == I18n.Language.EN);
        styleLangButton(btnLangJa, current == I18n.Language.JA);

        // Status & Line count
        renderStatus();
    }

    private void styleLangButton(TextView btn, boolean active) {
        if (active) {
            btn.setTextColor(Color.parseColor(UiTheme.C_BLUE));
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setBackground(UiTheme.roundRect(this, UiTheme.C_BLUE_BG, UiTheme.C_BLUE, 1, 4));
        } else {
            btn.setTextColor(Color.parseColor(UiTheme.C_DIM));
            btn.setTypeface(Typeface.DEFAULT);
            btn.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        }
        btn.setPadding(UiTheme.dp(this, 7), UiTheme.dp(this, 3), UiTheme.dp(this, 7), UiTheme.dp(this, 3));
        btn.setGravity(Gravity.CENTER);
        btn.setIncludeFontPadding(false);
    }

    private void setStatus(State state, String detail, int exitCode) {
        this.currentState = state;
        this.stateDetail = detail;
        this.lastExitCode = exitCode;
        mainHandler.post(this::renderStatus);
    }

    private void renderStatus() {
        String statusText;
        String dotColor;

        switch (currentState) {
            case INITIALIZING:
                statusText = I18n.get(I18n.Key.STATUS_INITIALIZING);
                dotColor = UiTheme.C_YELLOW;
                break;
            case READY:
                statusText = I18n.get(I18n.Key.STATUS_READY);
                dotColor = UiTheme.C_GREEN;
                break;
            case INIT_FAILED:
                statusText = I18n.format(I18n.Key.STATUS_INIT_FAILED, stateDetail);
                dotColor = UiTheme.C_RED;
                break;
            case RUNNING:
                statusText = I18n.format(I18n.Key.STATUS_RUNNING, stateDetail);
                dotColor = UiTheme.C_BLUE;
                break;
            case STOPPED:
                statusText = I18n.get(I18n.Key.STATUS_STOPPED);
                dotColor = UiTheme.C_RED;
                break;
            case IDLE:
                statusText = I18n.format(I18n.Key.STATUS_IDLE, lastExitCode);
                dotColor = UiTheme.C_GREEN;
                break;
            case ERROR:
                statusText = I18n.format(I18n.Key.STATUS_ERROR, stateDetail);
                dotColor = UiTheme.C_RED;
                break;
            case UNINITIALIZED:
            default:
                statusText = I18n.get(I18n.Key.STATUS_UNINITIALIZED);
                dotColor = UiTheme.C_DIM;
                break;
        }

        tvStatus.setText(I18n.get(I18n.Key.STATUS_LABEL_PREFIX) + statusText);
        updateStatusDot(dotColor);
    }

    private String getDefaultShell() {
        File rootfs = engine != null ? engine.getRootfsDir() : null;
        if (rootfs != null && new File(rootfs, "bin/bash").exists()) {
            return "/bin/bash";
        }
        return "/bin/sh";
    }

    private void initEngine() {
        setStatus(State.INITIALIZING, "", 0);

        executor.execute(() -> {
            try {
                boolean ok = engine.initialize();
                if (ok) {
                    syncPigoConfigToRootfs();
                    setStatus(State.READY, "", 0);
                    // Automatically launch interactive shell on ready!
                    mainHandler.post(() -> runTerminalSession(getDefaultShell(), "-l"));
                } else {
                    setStatus(State.INIT_FAILED, "Native libraries missing", 0);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize PRootEngine", e);
                setStatus(State.INIT_FAILED, e.getMessage(), 0);
            }
        });
    }

    private void stopCurrentSession() {
        if (currentSession != null && currentSession.isRunning()) {
            executor.execute(() -> {
                currentSession.finishIfRunning();
                currentSession = null;
                setStatus(State.STOPPED, "", -1);
            });
        }
    }

    private void runTerminalSession(String... cmdArgs) {
        if (cmdArgs == null || cmdArgs.length == 0) return;

        executor.execute(() -> {
            stopCurrentSession();

            try {
                if (!engine.isInitialized()) {
                    setStatus(State.INITIALIZING, "Initializing PRootEngine...", 0);
                    if (!engine.initialize()) {
                        setStatus(State.INIT_FAILED, "PRoot init failed", -1);
                        return;
                    }
                }

                PRootConfig.Builder builder = new PRootConfig.Builder()
                        .setWorkDir("/root")
                        .setFakeRoot(true)
                        .setExecutable(cmdArgs[0]);

                for (int i = 1; i < cmdArgs.length; i++) {
                    builder.addArg(cmdArgs[i]);
                }

                PRootConfig config = builder.build();
                List<String> cmd = engine.buildCommandLine(config);
                Map<String, String> envMap = engine.buildEnvironment(config);

                String shellPath = cmd.get(0);
                String[] args = cmd.toArray(new String[0]);
                String[] envArray = new String[envMap.size()];
                int idx = 0;
                for (Map.Entry<String, String> entry : envMap.entrySet()) {
                    envArray[idx++] = entry.getKey() + "=" + entry.getValue();
                }

                String cwd = engine.getFilesDir().getAbsolutePath();

                mainHandler.post(() -> {
                    try {
                        currentSession = new TerminalSession(
                                shellPath,
                                cwd,
                                args,
                                envArray,
                                2000,
                                terminalBridge
                        );

                        terminalView.attachSession(currentSession);
                        int pid = currentSession.getPid();
                        String displayCmd = (cmdArgs.length > 2) ? cmdArgs[2] : cmdArgs[0];
                        setStatus(State.RUNNING, displayCmd + " [PID " + pid + "]", 0);
                        badgeLineCount.setText("PID: " + pid);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to attach TerminalSession", e);
                        setStatus(State.ERROR, e.getMessage(), -1);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error running terminal session", e);
                setStatus(State.ERROR, e.getMessage(), -1);
            }
        });
    }

    // ==========================================
    // Fullscreen Mode Handling (Status Bar Immersive)
    // ==========================================

    private void toggleFullScreen() {
        isFullScreen = !isFullScreen;
        applyFullScreen(isFullScreen);
    }

    private void applyFullScreen(boolean fullScreen) {
        UiTheme.setupImmersiveStatusBar(this);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (fullScreen) {
            cardHeader.setVisibility(View.GONE);
            cardActions.setVisibility(View.GONE);

            if (rootLayout != null) {
                rootLayout.setFitsSystemWindows(true);
                rootLayout.setPadding(0, 0, 0, 0);
            }

            if (cardTerminal != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) cardTerminal.getLayoutParams();
                if (lp != null) {
                    lp.topMargin = 0;
                    cardTerminal.setLayoutParams(lp);
                }
                cardTerminal.setBackgroundColor(Color.parseColor(UiTheme.C_BG));
                cardTerminal.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 4), UiTheme.dp(this, 8), UiTheme.dp(this, 4));
            }

            if (frameTerminal != null) {
                frameTerminal.setBackgroundColor(Color.parseColor(UiTheme.C_BG));
            }

            btnFullscreen.setText(I18n.get(I18n.Key.BTN_EXIT_FULLSCREEN));
            styleCapsule(btnFullscreen, UiTheme.C_YELLOW, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW);
        } else {
            cardHeader.setVisibility(View.VISIBLE);
            cardActions.setVisibility(View.VISIBLE);

            int pad12 = UiTheme.dp(this, 12);
            if (rootLayout != null) {
                rootLayout.setFitsSystemWindows(true);
                rootLayout.setPadding(pad12, pad12, pad12, pad12);
            }

            if (cardTerminal != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) cardTerminal.getLayoutParams();
                if (lp != null) {
                    lp.topMargin = UiTheme.dp(this, 8);
                    cardTerminal.setLayoutParams(lp);
                }
                cardTerminal.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));
                cardTerminal.setPadding(pad12, pad12, pad12, pad12);
            }

            if (frameTerminal != null) {
                frameTerminal.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));
            }

            btnFullscreen.setText(I18n.get(I18n.Key.BTN_FULLSCREEN));
            styleCapsule(btnFullscreen, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            UiTheme.setupImmersiveStatusBar(this);
            if (isFullScreen) {
                applyFullScreen(true);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (isFullScreen) {
            toggleFullScreen();
            return;
        }
        super.onBackPressed();
    }

    // ==========================================
    // Storage Permission Handling
    // ==========================================

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestStoragePermissionIfNeeded() {
        if (checkStoragePermission()) {
            return;
        }

        SharedPreferences sp = getSharedPreferences("proot_permissions", Context.MODE_PRIVATE);
        boolean askedBefore = sp.getBoolean("storage_asked", false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!askedBefore) {
                sp.edit().putBoolean("storage_asked", true).apply();
                new AlertDialog.Builder(this)
                        .setTitle("存储访问权限")
                        .setMessage("为使 PRoot 虚拟化环境能够访问手机外部存储（/sdcard），建议授予“所有文件访问权限”。\n\n即使暂不授予，内置 Linux 终端环境仍可完全正常使用。")
                        .setPositiveButton("前往授权", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                            } catch (Exception e1) {
                                try {
                                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                                    startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                                } catch (Exception e2) {
                                    try {
                                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                        intent.setData(Uri.parse("package:" + getPackageName()));
                                        startActivity(intent);
                                    } catch (Exception ignored) {}
                                }
                            }
                        })
                        .setNegativeButton("稍后再说", null)
                        .show();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!askedBefore) {
                sp.edit().putBoolean("storage_asked", true).apply();
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_CODE_STORAGE_PERMS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_STORAGE_PERMS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "存储权限未授予，/sdcard 访问受限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "所有文件访问权限已授予", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "所有文件访问权限未授予，/sdcard 访问受限", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // ==========================================
    // Pigo AI Agent Quick Configuration & Launch
    // ==========================================

    private void showAiConfigDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_pigo_config, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        TextView badgePigo = dialogView.findViewById(R.id.badge_dialog_pigo);
        TextView btnPresetCnb = dialogView.findViewById(R.id.btn_preset_cnb);
        TextView tvProxyStatus = dialogView.findViewById(R.id.tv_proxy_status);
        TextView btnPresetOpenRouter = dialogView.findViewById(R.id.btn_preset_openrouter);
        TextView btnPresetDeepSeek = dialogView.findViewById(R.id.btn_preset_deepseek);
        EditText etBaseUrl = dialogView.findViewById(R.id.et_pigo_base_url);
        EditText etApiKey = dialogView.findViewById(R.id.et_pigo_api_key);
        CheckBox cbShowKey = dialogView.findViewById(R.id.cb_show_key);
        EditText etModel = dialogView.findViewById(R.id.et_pigo_model);
        TextView btnFetchModels = dialogView.findViewById(R.id.btn_fetch_models);
        CheckBox cbApprove = dialogView.findViewById(R.id.cb_pigo_approve);
        TextView btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        TextView btnSave = dialogView.findViewById(R.id.btn_dialog_save);
        TextView btnSaveRun = dialogView.findViewById(R.id.btn_dialog_save_and_run);

        // Styling dialog components
        dialogView.findViewById(R.id.dialog_container).setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));
        styleBadge(badgePigo, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        styleCapsule(btnPresetCnb, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        btnPresetCnb.setText(I18n.get(I18n.Key.BTN_PRESET_CNB));
        styleCapsule(btnPresetOpenRouter, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(btnPresetDeepSeek, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleCapsule(btnFetchModels, UiTheme.C_YELLOW, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW);
        styleCapsule(btnCancel, UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(btnSave, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(btnSaveRun, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);

        etBaseUrl.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));
        etApiKey.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));
        etModel.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));

        // Load saved values (Default empty, NEVER hardcode user credentials!)
        SharedPreferences sp = getSharedPreferences("pigo_config", Context.MODE_PRIVATE);
        String defUrl = sp.getString("base_url", "");
        String defKey = sp.getString("api_key", "");
        String defModel = sp.getString("model", "");
        boolean defApprove = sp.getBoolean("approve", true);

        etBaseUrl.setText(defUrl);
        etApiKey.setText(defKey);
        etModel.setText(defModel);
        cbApprove.setChecked(defApprove);

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
            if (tvProxyStatus == null) return;
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
                if (currentUrl.contains("127.0.0.1") || currentUrl.contains("localhost")) {
                    tvProxyStatus.setVisibility(View.VISIBLE);
                    tvProxyStatus.setText(I18n.get(I18n.Key.STATUS_PROXY_STOPPED));
                    tvProxyStatus.setTextColor(Color.parseColor(UiTheme.C_DIM));
                } else {
                    tvProxyStatus.setVisibility(View.GONE);
                }
            }
        };

        CnbProxyServer.StateListener stateListener = new CnbProxyServer.StateListener() {
            @Override
            public void onStarting() {
                mainHandler.post(updateProxyStatus);
            }

            @Override
            public void onStarted(int port, String baseUrl) {
                mainHandler.post(() -> {
                    updateProxyStatus.run();
                    if (etBaseUrl.getText().toString().contains("127.0.0.1")) {
                        etBaseUrl.setText(baseUrl);
                    }
                });
            }

            @Override
            public void onStopped() {
                mainHandler.post(updateProxyStatus);
            }

            @Override
            public void onError(String message, Throwable error) {
                mainHandler.post(() -> {
                    updateProxyStatus.run();
                    Toast.makeText(MainActivity.this, "Proxy error: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        };
        CnbProxyServer.getInstance().setStateListener(stateListener);
        dialog.setOnDismissListener(d -> CnbProxyServer.getInstance().setStateListener(null));

        // Preset buttons
        btnPresetCnb.setOnClickListener(v -> {
            String baseUrl = CnbProxyServer.getInstance().getBaseUrl();
            etBaseUrl.setText(baseUrl);
            etModel.setText("deepseek-v4-flash");
            etApiKey.setText("");
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
                Toast.makeText(this, "请先填写 Base URL", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, I18n.get(I18n.Key.TOAST_FETCHING_MODELS), Toast.LENGTH_SHORT).show();
            fetchModels(url, key, models -> {
                if (models.isEmpty()) {
                    Toast.makeText(this, "未从该端点找到模型", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(this, String.format(I18n.get(I18n.Key.TOAST_FETCH_SUCCESS), models.size()), Toast.LENGTH_SHORT).show();
                new AlertDialog.Builder(this)
                        .setTitle("选择模型 (" + models.size() + ")")
                        .setItems(models.toArray(new String[0]), (d, which) -> {
                            etModel.setText(models.get(which));
                        })
                        .show();
            }, err -> {
                Toast.makeText(this, String.format(I18n.get(I18n.Key.TOAST_FETCH_FAIL), err), Toast.LENGTH_LONG).show();
            });
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            savePigoConfig(
                    etBaseUrl.getText().toString().trim(),
                    etApiKey.getText().toString().trim(),
                    etModel.getText().toString().trim(),
                    cbApprove.isChecked()
            );
            Toast.makeText(this, I18n.get(I18n.Key.TOAST_CONFIG_SAVED), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        btnSaveRun.setOnClickListener(v -> {
            savePigoConfig(
                    etBaseUrl.getText().toString().trim(),
                    etApiKey.getText().toString().trim(),
                    etModel.getText().toString().trim(),
                    cbApprove.isChecked()
            );
            dialog.dismiss();
            launchPigoSession();
        });

        dialog.show();
    }

    private void ensureProxyRunningIfNeeded(String url) {
        if (url != null && (url.contains("127.0.0.1") || url.contains("localhost:7863"))) {
            if (!CnbProxyServer.getInstance().isRunning() && !CnbProxyServer.getInstance().isStarting()) {
                CnbProxyServer.getInstance().startAsync(new ProxyConfig.Builder().build());
            }
        }
    }

    private void launchPigoSession() {
        SharedPreferences sp = getSharedPreferences("pigo_config", Context.MODE_PRIVATE);
        ensureProxyRunningIfNeeded(sp.getString("base_url", ""));
        startNewPigoSession();
    }

    private void startNewPigoSession() {
        SharedPreferences sp = getSharedPreferences("pigo_config", Context.MODE_PRIVATE);
        ensureProxyRunningIfNeeded(sp.getString("base_url", ""));
        if (currentSession != null && currentSession.isRunning()) {
            terminalBridge.sendString(currentSession, "pigo\n");
            Toast.makeText(this, "已在当前终端启动 pigo", Toast.LENGTH_SHORT).show();
        } else {
            runTerminalSession("/usr/local/bin/pigo");
        }
    }

    private void continueRecentPigoSession() {
        SharedPreferences sp = getSharedPreferences("pigo_config", Context.MODE_PRIVATE);
        ensureProxyRunningIfNeeded(sp.getString("base_url", ""));
        if (currentSession != null && currentSession.isRunning()) {
            terminalBridge.sendString(currentSession, "pigo -c\n");
            Toast.makeText(this, "正在恢复最近的 Pigo 会话", Toast.LENGTH_SHORT).show();
        } else {
            runTerminalSession("/usr/local/bin/pigo", "-c");
        }
    }

    private void resumePigoSession(String sessionId) {
        SharedPreferences sp = getSharedPreferences("pigo_config", Context.MODE_PRIVATE);
        ensureProxyRunningIfNeeded(sp.getString("base_url", ""));
        String cmd = "pigo -r " + sessionId + "\n";
        if (currentSession != null && currentSession.isRunning()) {
            terminalBridge.sendString(currentSession, cmd);
            Toast.makeText(this, String.format(I18n.get(I18n.Key.TOAST_SESSION_RESUMED), sessionId), Toast.LENGTH_SHORT).show();
        } else {
            runTerminalSession("/usr/local/bin/pigo", "-r", sessionId);
        }
    }

    // ==========================================
    // Pigo Session Management Dialog & Logic
    // ==========================================

    public static class PigoSession {
        public final String id;
        public final String model;
        public final String cwd;
        public final String title;
        public final long lastModified;
        public final long fileSize;
        public final File file;

        public PigoSession(String id, String model, String cwd, String title, long lastModified, long fileSize, File file) {
            this.id = id;
            this.model = model;
            this.cwd = cwd;
            this.title = title;
            this.lastModified = lastModified;
            this.fileSize = fileSize;
            this.file = file;
        }
    }

    private void showPigoSessionsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_pigo_sessions, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

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
        dialogView.findViewById(R.id.dialog_container).setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));
        styleBadge(badgeCount, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleCapsule(btnNewSession, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);
        styleCapsule(btnResumeRecent, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(btnRefresh, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);

        tvTitle.setText(I18n.get(I18n.Key.TITLE_SESSIONS));
        btnNewSession.setText(I18n.get(I18n.Key.BTN_NEW_SESSION));
        btnResumeRecent.setText(I18n.get(I18n.Key.BTN_RESUME_RECENT));
        btnRefresh.setText(I18n.get(I18n.Key.BTN_REFRESH));
        tvEmptyTitle.setText(I18n.get(I18n.Key.SESSIONS_EMPTY));
        tvEmptyDesc.setText(I18n.get(I18n.Key.SESSIONS_EMPTY_DESC));

        btnClose.setOnClickListener(v -> dialog.dismiss());

        btnNewSession.setOnClickListener(v -> {
            dialog.dismiss();
            startNewPigoSession();
        });

        btnResumeRecent.setOnClickListener(v -> {
            dialog.dismiss();
            continueRecentPigoSession();
        });

        btnRefresh.setOnClickListener(v -> {
            populateSessionsList(dialog, layoutSessionsList, layoutEmptyState, badgeCount);
        });

        populateSessionsList(dialog, layoutSessionsList, layoutEmptyState, badgeCount);

        dialog.show();
    }

    private void populateSessionsList(AlertDialog dialog, LinearLayout container, LinearLayout emptyState, TextView badgeCount) {
        List<PigoSession> sessions = loadPigoSessions();
        badgeCount.setText(String.format(I18n.get(I18n.Key.BADGE_TOTAL_SESSIONS), sessions.size()));

        // Keep emptyState view at index 0, remove previous item cards
        if (container.getChildCount() > 1) {
            container.removeViews(1, container.getChildCount() - 1);
        }

        if (sessions.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            return;
        }

        emptyState.setVisibility(View.GONE);

        for (PigoSession session : sessions) {
            View itemView = getLayoutInflater().inflate(R.layout.item_pigo_session, container, false);

            itemView.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 6));

            TextView tvTime = itemView.findViewById(R.id.tv_session_time);
            TextView badgeModel = itemView.findViewById(R.id.badge_session_model);
            TextView badgeSize = itemView.findViewById(R.id.badge_session_size);
            TextView tvPreview = itemView.findViewById(R.id.tv_session_preview);
            TextView tvId = itemView.findViewById(R.id.tv_session_id);
            TextView tvCwd = itemView.findViewById(R.id.tv_session_cwd);
            TextView btnResume = itemView.findViewById(R.id.btn_item_resume);
            TextView btnDelete = itemView.findViewById(R.id.btn_item_delete);

            styleBadge(badgeModel, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
            styleBadge(badgeSize, UiTheme.C_DIM, UiTheme.C_SURFACE, UiTheme.C_BORDER_SUB);
            styleCapsule(btnResume, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
            styleCapsule(btnDelete, UiTheme.C_RED, UiTheme.C_RED_BG, UiTheme.C_RED);

            tvTime.setText(formatTime(session.lastModified));
            badgeModel.setText(session.model != null && !session.model.isEmpty() ? session.model : "default");
            badgeSize.setText(formatFileSize(session.fileSize));
            tvPreview.setText(session.title);
            tvId.setText("ID: " + session.id);
            tvCwd.setText("📁 " + session.cwd);
            btnResume.setText(I18n.get(I18n.Key.BTN_RESUME));
            btnDelete.setText(I18n.get(I18n.Key.BTN_DELETE));

            // Clicking card or clicking Resume button resumes this session
            View.OnClickListener resumeListener = v -> {
                dialog.dismiss();
                resumePigoSession(session.id);
            };
            itemView.setOnClickListener(resumeListener);
            btnResume.setOnClickListener(resumeListener);

            // Delete session button
            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle(I18n.get(I18n.Key.CONFIRM_DELETE_TITLE))
                        .setMessage(String.format(I18n.get(I18n.Key.CONFIRM_DELETE_SESSION), session.id))
                        .setPositiveButton(I18n.get(I18n.Key.BTN_DELETE), (d, which) -> {
                            if (session.file != null && session.file.exists()) {
                                boolean ok = session.file.delete();
                                if (ok) {
                                    Toast.makeText(this, I18n.get(I18n.Key.TOAST_SESSION_DELETED), Toast.LENGTH_SHORT).show();
                                    populateSessionsList(dialog, container, emptyState, badgeCount);
                                }
                            }
                        })
                        .setNegativeButton(I18n.get(I18n.Key.BTN_CANCEL), null)
                        .show();
            });

            container.addView(itemView);
        }
    }

    private List<PigoSession> loadPigoSessions() {
        File rootfs = engine != null ? engine.getRootfsDir() : null;
        if (rootfs == null || !rootfs.exists()) {
            return Collections.emptyList();
        }

        File sessionsDir = new File(rootfs, "root/.pigo/sessions");
        if (!sessionsDir.exists() || !sessionsDir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] files = sessionsDir.listFiles((dir, name) -> name != null && name.endsWith(".jsonl"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<PigoSession> list = new ArrayList<>();
        for (File f : files) {
            try {
                PigoSession s = parseSessionFile(f);
                if (s != null) {
                    list.add(s);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed parsing session file: " + f.getName(), e);
            }
        }

        // Sort descending by last modified time (newest sessions first)
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return list;
    }

    private PigoSession parseSessionFile(File file) {
        String fallbackId = file.getName().replace(".jsonl", "");
        String id = fallbackId;
        String model = "default";
        String cwd = "/root";
        String title = null;
        long lastModified = file.lastModified();
        long fileSize = file.length();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line1 = reader.readLine();
            if (line1 != null && !line1.trim().isEmpty()) {
                try {
                    JSONObject j1 = new JSONObject(line1);
                    if (j1.has("id")) id = j1.getString("id");
                    if (j1.has("model")) model = j1.getString("model");
                    if (j1.has("cwd")) cwd = j1.getString("cwd");
                    if (j1.has("title")) title = j1.getString("title");
                } catch (Exception ignored) {}
            }

            // If title not yet found, look for first user message in subsequent lines (up to 15 lines)
            String line;
            int linesChecked = 0;
            while ((line = reader.readLine()) != null && linesChecked < 15) {
                linesChecked++;
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject j = new JSONObject(line);
                    String extracted = extractUserPrompt(j);
                    if (extracted != null && !extracted.isEmpty()) {
                        title = extracted;
                        break;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading session file: " + file.getName(), e);
        }

        if (title == null || title.trim().isEmpty()) {
            title = "(无对话记录)";
        } else {
            title = title.replace("\r", " ").replace("\n", " ").trim();
            if (title.length() > 100) {
                title = title.substring(0, 97) + "...";
            }
        }

        return new PigoSession(id, model, cwd, title, lastModified, fileSize, file);
    }

    private String extractUserPrompt(JSONObject j) {
        if (j.has("message")) {
            Object mObj = j.opt("message");
            if (mObj instanceof JSONObject) {
                JSONObject msg = (JSONObject) mObj;
                String role = msg.optString("role", "");
                if ("user".equalsIgnoreCase(role) || role.isEmpty()) {
                    String c = extractContent(msg.opt("content"));
                    if (c != null && !c.isEmpty()) return c;
                }
            }
        }
        if (j.has("role")) {
            String role = j.optString("role", "");
            if ("user".equalsIgnoreCase(role)) {
                String c = extractContent(j.opt("content"));
                if (c != null && !c.isEmpty()) return c;
            }
        }
        if (j.has("prompt")) {
            return j.optString("prompt");
        }
        return null;
    }

    private String extractContent(Object contentObj) {
        if (contentObj == null) return null;
        if (contentObj instanceof String) {
            return (String) contentObj;
        }
        if (contentObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) contentObj;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null) {
                    String text = item.optString("text", "");
                    if (!text.isEmpty()) return text;
                }
            }
        }
        return null;
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }

    private String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    private void savePigoConfig(String baseUrl, String apiKey, String model, boolean approve) {
        SharedPreferences sp = getSharedPreferences("pigo_config", Context.MODE_PRIVATE);
        sp.edit()
                .putString("base_url", baseUrl)
                .putString("api_key", apiKey)
                .putString("model", model)
                .putBoolean("approve", approve)
                .apply();

        syncPigoConfigToRootfs();
    }

    private void syncPigoConfigToRootfs() {
        SharedPreferences sp = getSharedPreferences("pigo_config", Context.MODE_PRIVATE);
        String baseUrl = sp.getString("base_url", "").trim();
        String apiKey = sp.getString("api_key", "").trim();
        String model = sp.getString("model", "").trim();
        boolean approve = sp.getBoolean("approve", true);

        File rootfs = engine != null ? engine.getRootfsDir() : null;
        if (rootfs == null || !rootfs.exists()) {
            return;
        }

        File pigoDir = new File(rootfs, "root/.config/pigo");
        pigoDir.mkdirs();
        File configFile = new File(pigoDir, "config.toml");
        StringBuilder sb = new StringBuilder();
        if (!model.isEmpty()) {
            sb.append("model = \"").append(escapeToml(model)).append("\"\n");
        }
        sb.append("protocol = \"openai\"\n");
        if (!baseUrl.isEmpty()) {
            sb.append("base_url = \"").append(escapeToml(baseUrl)).append("\"\n");
        }
        if (!apiKey.isEmpty()) {
            sb.append("api_key = \"").append(escapeToml(apiKey)).append("\"\n");
        }
        sb.append("approve = ").append(approve).append("\n");

        try (FileWriter fw = new FileWriter(configFile)) {
            fw.write(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to write config.toml", e);
        }

        File envFile = new File(rootfs, "root/.pigo.env");
        try (FileWriter fw = new FileWriter(envFile)) {
            if (!apiKey.isEmpty()) {
                fw.write("export OPENCODE_API_KEY=\"" + escapeToml(apiKey) + "\"\n");
            }
            if (!baseUrl.isEmpty()) {
                fw.write("export OPENCODE_GO_BASE_URL=\"" + escapeToml(baseUrl) + "\"\n");
            }
        } catch (Exception ignored) {}
    }

    private String escapeToml(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    interface ModelSuccessCallback {
        void onSuccess(List<String> models);
    }

    interface ModelErrorCallback {
        void onError(String error);
    }

    private void fetchModels(String baseUrl, String apiKey, ModelSuccessCallback onSuccess, ModelErrorCallback onError) {
        executor.execute(() -> {
            try {
                String target = baseUrl.trim();
                while (target.endsWith("/")) {
                    target = target.substring(0, target.length() - 1);
                }
                if (!target.endsWith("/models")) {
                    target = target + "/models";
                }
                URL url = new URL(target);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                if (!apiKey.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                }
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    String resp = baos.toString("UTF-8");
                    JSONObject json = new JSONObject(resp);
                    JSONArray data = json.optJSONArray("data");
                    List<String> models = new ArrayList<>();
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject m = data.getJSONObject(i);
                            String id = m.optString("id");
                            if (!id.isEmpty()) models.add(id);
                        }
                    }
                    mainHandler.post(() -> onSuccess.onSuccess(models));
                } else {
                    InputStream es = conn.getErrorStream();
                    String errStr = "";
                    if (es != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[2048];
                        int n;
                        while ((n = es.read(buf)) != -1) baos.write(buf, 0, n);
                        errStr = baos.toString("UTF-8");
                    }
                    final String finalErr = "HTTP " + code + (errStr.isEmpty() ? "" : ": " + errStr);
                    mainHandler.post(() -> onError.onError(finalErr));
                }
            } catch (Exception e) {
                mainHandler.post(() -> onError.onError(e.getMessage()));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCurrentSession();
        CnbProxyServer.getInstance().stop();
        executor.shutdownNow();
    }
}
