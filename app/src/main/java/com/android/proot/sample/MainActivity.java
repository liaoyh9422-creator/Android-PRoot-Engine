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
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
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
        btnAiConfig = findViewById(R.id.btn_ai_config);
        btnInit = findViewById(R.id.btn_init);
        btnRunUname = findViewById(R.id.btn_run_uname);
        btnRunScript = findViewById(R.id.btn_run_script);
        btnStop = findViewById(R.id.btn_stop);
        etCommand = findViewById(R.id.et_command);
        btnExec = findViewById(R.id.btn_exec);

        tvSectionTerminal = findViewById(R.id.tv_section_terminal);
        badgeLineCount = findViewById(R.id.badge_line_count);
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
        styleCapsule(btnAiConfig, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleCapsule(btnInit, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(btnRunUname, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        styleCapsule(btnRunScript, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(btnStop, UiTheme.C_RED, UiTheme.C_RED_BG, UiTheme.C_RED);
        styleCapsule(btnExec, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);

        // Terminal Top Action Buttons
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

        btnAiConfig.setText(I18n.get(I18n.Key.BTN_AI_CONFIG));
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

        // Preset buttons
        btnPresetOpenRouter.setOnClickListener(v -> {
            etBaseUrl.setText("https://openrouter.ai/api/v1");
            etModel.setText("openrouter/free");
        });
        btnPresetDeepSeek.setOnClickListener(v -> {
            etBaseUrl.setText("https://api.deepseek.com/v1");
            etModel.setText("deepseek-chat");
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

    private void launchPigoSession() {
        if (currentSession != null && currentSession.isRunning()) {
            terminalBridge.sendString(currentSession, "pigo\n");
            Toast.makeText(this, "已在当前终端启动 pigo", Toast.LENGTH_SHORT).show();
        } else {
            runTerminalSession("/usr/local/bin/pigo");
        }
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
        executor.shutdownNow();
    }
}
