package com.android.proot.sample;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.PRootConfig;
import com.android.proot.PRootEngine;
import com.android.proot.sample.terminal.TerminalBridge;
import com.android.proot.sample.ui.UiTheme;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.File;
import java.io.FileWriter;
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
    private TextView btnInit;
    private TextView btnRunUname;
    private TextView btnRunScript;
    private TextView btnStop;
    private EditText etCommand;
    private TextView btnExec;

    // Terminal Console & Keybar
    private TextView tvSectionTerminal;
    private TextView badgeLineCount;
    private TextView btnFontMinus;
    private TextView btnFontPlus;
    private TextView btnCopy;
    private TextView btnClear;
    private TerminalView terminalView;
    private int terminalFontSize = 12;

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

        // 5. Apply CLIProxyAPI-style Themes & Drawables
        applyUiTheme();

        // 6. Initialize PRoot Engine instance
        engine = new PRootEngine(this);

        // 7. Setup Action Listeners
        setupListeners();

        // 8. Initial Localization Render
        updateUiTexts();
    }

    private void bindViews() {
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
        btnInit = findViewById(R.id.btn_init);
        btnRunUname = findViewById(R.id.btn_run_uname);
        btnRunScript = findViewById(R.id.btn_run_script);
        btnStop = findViewById(R.id.btn_stop);
        etCommand = findViewById(R.id.et_command);
        btnExec = findViewById(R.id.btn_exec);

        tvSectionTerminal = findViewById(R.id.tv_section_terminal);
        badgeLineCount = findViewById(R.id.badge_line_count);
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
        styleCapsule(btnInit, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(btnRunUname, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        styleCapsule(btnRunScript, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(btnStop, UiTheme.C_RED, UiTheme.C_RED_BG, UiTheme.C_RED);
        styleCapsule(btnExec, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);

        // Terminal Top Action Buttons
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
        btnInit.setOnClickListener(v -> initEngine());
        btnRunUname.setOnClickListener(v -> runTerminalSession("/bin/sh", "-c", "uname -a"));
        btnRunScript.setOnClickListener(v -> runTerminalSession("/bin/sh")); // Launch interactive shell!
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

        // Font scaling
        btnFontPlus.setOnClickListener(v -> {
            terminalFontSize = Math.min(terminalFontSize + 1, 28);
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
            runTerminalSession("/bin/sh", "-c", cmd);
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

    private void initEngine() {
        setStatus(State.INITIALIZING, "", 0);

        executor.execute(() -> {
            try {
                boolean ok = engine.initialize();
                if (ok) {
                    setStatus(State.READY, "", 0);
                    // Automatically launch interactive shell on ready!
                    mainHandler.post(() -> runTerminalSession("/bin/sh"));
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCurrentSession();
        executor.shutdownNow();
    }
}
