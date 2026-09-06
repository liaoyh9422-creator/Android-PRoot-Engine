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

    // AI Configuration Constants
    private static final String PREFS_AI = "proot_ai_config";
    private static final String KEY_AI_PROVIDER = "ai_provider";
    private static final String KEY_AI_KEY = "ai_key";
    private static final String KEY_AI_URL = "ai_url";
    private static final String KEY_AI_MODEL = "ai_model";

    // Header & Badges
    private TextView tvTitle;
    private TextView badgeArch;
    private TextView badgeRoot;
    private TextView badgeDistro;
    private TextView badgeAgent;
    private TextView badgeSaf;
    private View viewStatusDot;
    private TextView tvStatus;

    // Language Buttons
    private TextView btnLangZh;
    private TextView btnLangEn;
    private TextView btnLangJa;

    // Control Actions
    private TextView tvSectionControl;
    private TextView btnConfigKey;
    private TextView btnInit;
    private TextView btnRunUname;
    private TextView btnRunScript;
    private TextView btnAgent;
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
        badgeAgent = findViewById(R.id.badge_agent);
        badgeSaf = findViewById(R.id.badge_saf);
        viewStatusDot = findViewById(R.id.view_status_dot);
        tvStatus = findViewById(R.id.tv_status);

        btnLangZh = findViewById(R.id.btn_lang_zh);
        btnLangEn = findViewById(R.id.btn_lang_en);
        btnLangJa = findViewById(R.id.btn_lang_ja);

        tvSectionControl = findViewById(R.id.tv_section_control);
        btnConfigKey = findViewById(R.id.btn_config_key);
        btnInit = findViewById(R.id.btn_init);
        btnRunUname = findViewById(R.id.btn_run_uname);
        btnRunScript = findViewById(R.id.btn_run_script);
        btnAgent = findViewById(R.id.btn_agent);
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
        styleBadge(badgeAgent, UiTheme.C_YELLOW, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW);
        styleBadge(badgeSaf, UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB);
        styleBadge(badgeLineCount, UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB);

        // Action Micro-capsules
        styleCapsule(btnConfigKey, UiTheme.C_YELLOW, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW);
        styleCapsule(btnInit, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(btnRunUname, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        styleCapsule(btnRunScript, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(btnAgent, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
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
        btnAgent.setOnClickListener(v -> launchAiAgent());
        btnConfigKey.setOnClickListener(v -> showApiKeyDialog());
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
        btnAgent.setText(I18n.get(I18n.Key.BTN_AGENT));
        btnConfigKey.setText(I18n.get(I18n.Key.BTN_CONFIG_KEY));
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

    private void launchAiAgent() {
        SharedPreferences sp = getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE);
        String apiKey = sp.getString(KEY_AI_KEY, "").trim();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Notice: Please configure AI API Key", Toast.LENGTH_SHORT).show();
            showApiKeyDialog();
            return;
        }

        syncAiChatConfig(
                sp.getString(KEY_AI_PROVIDER, "deepseek"),
                apiKey,
                sp.getString(KEY_AI_URL, ""),
                sp.getString(KEY_AI_MODEL, "deepseek-chat")
        );

        runTerminalSession("/usr/local/bin/aichat");
    }

    private void showApiKeyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(I18n.get(I18n.Key.DIALOG_KEY_TITLE));

        SharedPreferences sp = getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE);
        String savedProvider = sp.getString(KEY_AI_PROVIDER, "deepseek");
        String savedKey = sp.getString(KEY_AI_KEY, "");
        String savedUrl = sp.getString(KEY_AI_URL, "");
        String savedModel = sp.getString(KEY_AI_MODEL, "deepseek-chat");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(UiTheme.dp(this, 16), UiTheme.dp(this, 10), UiTheme.dp(this, 16), UiTheme.dp(this, 10));

        TextView tvDesc = new TextView(this);
        tvDesc.setText(I18n.get(I18n.Key.DIALOG_KEY_DESC));
        tvDesc.setTextSize(12f);
        tvDesc.setTextColor(Color.parseColor(UiTheme.C_DIM));
        container.addView(tvDesc);

        // Provider RadioGroup
        TextView tvProvLabel = new TextView(this);
        tvProvLabel.setText(I18n.get(I18n.Key.DIALOG_KEY_PROVIDER));
        tvProvLabel.setTextSize(12f);
        tvProvLabel.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvProvLabel.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 2));
        container.addView(tvProvLabel);

        RadioGroup rgProvider = new RadioGroup(this);
        rgProvider.setOrientation(RadioGroup.HORIZONTAL);

        RadioButton rbDeepSeek = new RadioButton(this);
        rbDeepSeek.setText("DeepSeek");
        rbDeepSeek.setTextColor(Color.parseColor(UiTheme.C_TEXT));

        RadioButton rbOpenAI = new RadioButton(this);
        rbOpenAI.setText("OpenAI");
        rbOpenAI.setTextColor(Color.parseColor(UiTheme.C_TEXT));

        RadioButton rbCustom = new RadioButton(this);
        rbCustom.setText("Custom");
        rbCustom.setTextColor(Color.parseColor(UiTheme.C_TEXT));

        rgProvider.addView(rbDeepSeek);
        rgProvider.addView(rbOpenAI);
        rgProvider.addView(rbCustom);

        if ("openai".equalsIgnoreCase(savedProvider)) {
            rbOpenAI.setChecked(true);
        } else if ("custom".equalsIgnoreCase(savedProvider)) {
            rbCustom.setChecked(true);
        } else {
            rbDeepSeek.setChecked(true);
        }
        container.addView(rgProvider);

        // API Key Field
        TextView tvKeyLabel = new TextView(this);
        tvKeyLabel.setText(I18n.get(I18n.Key.DIALOG_KEY_API_KEY));
        tvKeyLabel.setTextSize(12f);
        tvKeyLabel.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvKeyLabel.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 2));
        container.addView(tvKeyLabel);

        EditText etKey = new EditText(this);
        etKey.setHint("sk-...");
        etKey.setText(savedKey);
        etKey.setTextSize(13f);
        etKey.setTypeface(Typeface.MONOSPACE);
        etKey.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etKey.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etKey.setHintTextColor(Color.parseColor(UiTheme.C_DIM));
        etKey.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 8), UiTheme.dp(this, 10), UiTheme.dp(this, 8));
        etKey.setSingleLine(true);
        container.addView(etKey);

        // API Base URL Field
        TextView tvUrlLabel = new TextView(this);
        tvUrlLabel.setText(I18n.get(I18n.Key.DIALOG_KEY_URL));
        tvUrlLabel.setTextSize(12f);
        tvUrlLabel.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvUrlLabel.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 2));
        container.addView(tvUrlLabel);

        EditText etUrl = new EditText(this);
        etUrl.setHint("https://api.deepseek.com");
        etUrl.setText(savedUrl);
        etUrl.setTextSize(13f);
        etUrl.setTypeface(Typeface.MONOSPACE);
        etUrl.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etUrl.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etUrl.setHintTextColor(Color.parseColor(UiTheme.C_DIM));
        etUrl.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 8), UiTheme.dp(this, 10), UiTheme.dp(this, 8));
        etUrl.setSingleLine(true);
        container.addView(etUrl);

        // Model Field
        TextView tvModelLabel = new TextView(this);
        tvModelLabel.setText(I18n.get(I18n.Key.DIALOG_KEY_MODEL));
        tvModelLabel.setTextSize(12f);
        tvModelLabel.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvModelLabel.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 2));
        container.addView(tvModelLabel);

        EditText etModel = new EditText(this);
        etModel.setHint("deepseek-chat");
        etModel.setText(savedModel);
        etModel.setTextSize(13f);
        etModel.setTypeface(Typeface.MONOSPACE);
        etModel.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etModel.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etModel.setHintTextColor(Color.parseColor(UiTheme.C_DIM));
        etModel.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 8), UiTheme.dp(this, 10), UiTheme.dp(this, 8));
        etModel.setSingleLine(true);
        container.addView(etModel);

        rgProvider.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == rbDeepSeek.getId()) {
                if (etUrl.getText().toString().isEmpty() || etUrl.getText().toString().contains("openai.com")) {
                    etUrl.setText("https://api.deepseek.com");
                }
                if (etModel.getText().toString().isEmpty() || etModel.getText().toString().contains("gpt")) {
                    etModel.setText("deepseek-chat");
                }
            } else if (checkedId == rbOpenAI.getId()) {
                if (etUrl.getText().toString().contains("deepseek.com")) {
                    etUrl.setText("https://api.openai.com/v1");
                }
                if (etModel.getText().toString().contains("deepseek")) {
                    etModel.setText("gpt-4o-mini");
                }
            }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(container);
        builder.setView(sv);

        builder.setPositiveButton(I18n.get(I18n.Key.DIALOG_KEY_SAVE), (dialog, which) -> {
            String selectedProvider = rbDeepSeek.isChecked() ? "deepseek" : (rbOpenAI.isChecked() ? "openai" : "custom");
            String key = etKey.getText().toString().trim();
            String url = etUrl.getText().toString().trim();
            String model = etModel.getText().toString().trim();

            sp.edit()
                    .putString(KEY_AI_PROVIDER, selectedProvider)
                    .putString(KEY_AI_KEY, key)
                    .putString(KEY_AI_URL, url)
                    .putString(KEY_AI_MODEL, model)
                    .apply();

            syncAiChatConfig(selectedProvider, key, url, model);
            Toast.makeText(this, I18n.get(I18n.Key.TOAST_KEY_SAVED), Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton(I18n.get(I18n.Key.DIALOG_KEY_CANCEL), null);
        builder.setNeutralButton("REPL", (dialog, which) -> {
            runTerminalSession("/usr/local/bin/aichat");
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void syncAiChatConfig(String provider, String apiKey, String apiUrl, String model) {
        if (engine == null) return;
        File rootfsDir = engine.getRootfsDir();
        if (rootfsDir == null || !rootfsDir.exists()) return;

        File configDir = new File(rootfsDir, "root/.config/aichat");
        configDir.mkdirs();
        File configFile = new File(configDir, "config.yaml");

        if (model == null || model.trim().isEmpty()) {
            model = "deepseek".equalsIgnoreCase(provider) ? "deepseek-chat" : ("openai".equalsIgnoreCase(provider) ? "gpt-4o-mini" : "default");
        }

        StringBuilder yaml = new StringBuilder();
        if ("openai".equalsIgnoreCase(provider)) {
            yaml.append("model: openai:").append(model).append("\n");
            yaml.append("stream: true\n\n");
            yaml.append("clients:\n");
            yaml.append("  - type: openai\n");
            if (apiUrl != null && !apiUrl.trim().isEmpty()) {
                yaml.append("    api_base: ").append(apiUrl.trim()).append("\n");
            }
            yaml.append("    api_key: ").append(apiKey != null ? apiKey.trim() : "").append("\n");
        } else if ("custom".equalsIgnoreCase(provider)) {
            yaml.append("model: custom:").append(model).append("\n");
            yaml.append("stream: true\n\n");
            yaml.append("clients:\n");
            yaml.append("  - type: openai-compatible\n");
            yaml.append("    name: custom\n");
            if (apiUrl != null && !apiUrl.trim().isEmpty()) {
                yaml.append("    api_base: ").append(apiUrl.trim()).append("\n");
            }
            yaml.append("    api_key: ").append(apiKey != null ? apiKey.trim() : "").append("\n");
            yaml.append("    models:\n");
            yaml.append("      - name: ").append(model).append("\n");
        } else {
            // Default DeepSeek
            yaml.append("model: deepseek:").append(model).append("\n");
            yaml.append("stream: true\n\n");
            yaml.append("clients:\n");
            yaml.append("  - type: openai-compatible\n");
            yaml.append("    name: deepseek\n");
            yaml.append("    api_base: ").append(apiUrl != null && !apiUrl.trim().isEmpty() ? apiUrl.trim() : "https://api.deepseek.com").append("\n");
            yaml.append("    api_key: ").append(apiKey != null ? apiKey.trim() : "").append("\n");
            yaml.append("    models:\n");
            yaml.append("      - name: ").append(model).append("\n");
            if (!"deepseek-reasoner".equals(model)) {
                yaml.append("      - name: deepseek-reasoner\n");
            }
        }

        try (FileWriter fw = new FileWriter(configFile)) {
            fw.write(yaml.toString());
            Log.i(TAG, "Synced aichat config.yaml successfully to: " + configFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to write aichat config.yaml", e);
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

                SharedPreferences sp = getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE);
                String aiKey = sp.getString(KEY_AI_KEY, "").trim();
                String aiProvider = sp.getString(KEY_AI_PROVIDER, "deepseek");
                String aiUrl = sp.getString(KEY_AI_URL, "").trim();
                String aiModel = sp.getString(KEY_AI_MODEL, "deepseek-chat").trim();

                if (!aiKey.isEmpty()) {
                    builder.addEnv("OPENAI_API_KEY", aiKey);
                    builder.addEnv("DEEPSEEK_API_KEY", aiKey);
                }
                if (!aiUrl.isEmpty()) {
                    builder.addEnv("OPENAI_BASE_URL", aiUrl);
                }

                syncAiChatConfig(aiProvider, aiKey, aiUrl, aiModel);

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
