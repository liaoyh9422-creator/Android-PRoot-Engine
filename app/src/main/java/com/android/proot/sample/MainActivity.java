package com.android.proot.sample;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.proot.PRootConfig;
import com.android.proot.PRootEngine;
import com.android.proot.PRootProcess;
import com.android.proot.sample.ui.UiTheme;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity: Android-PRoot-Engine host console interface.
 * Redesigned referencing CLIProxyAPI's dark geek aesthetics (GitHub Dark palette, micro-capsule buttons, badges, terminal viewer).
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private PRootEngine engine;
    private PRootProcess currentProcess;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // UI Cards & Containers
    private View cardHeader;
    private View cardActions;
    private View cardTerminal;

    // Header & Badges
    private TextView tvTitle;
    private TextView badgeArch;
    private TextView badgeRoot;
    private TextView badgeGlibc;
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

    // Terminal Console
    private TextView tvSectionTerminal;
    private TextView badgeLineCount;
    private TextView btnCopy;
    private TextView btnClear;
    private ScrollView scrollLog;
    private TextView tvLog;

    private int logLineCount = 1;

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

        // 2. Bind Views
        bindViews();

        // 3. Apply CLIProxyAPI-style Themes & Drawables
        applyUiTheme();

        // 4. Initialize PRoot Engine instance
        engine = new PRootEngine(this);

        // 5. Setup Action Listeners
        setupListeners();

        // 6. Initial Localization Render
        updateUiTexts();
    }

    private void bindViews() {
        cardHeader = findViewById(R.id.card_header);
        cardActions = findViewById(R.id.card_actions);
        cardTerminal = findViewById(R.id.card_terminal);

        tvTitle = findViewById(R.id.tv_title);
        badgeArch = findViewById(R.id.badge_arch);
        badgeRoot = findViewById(R.id.badge_root);
        badgeGlibc = findViewById(R.id.badge_glibc);
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
        btnCopy = findViewById(R.id.btn_copy);
        btnClear = findViewById(R.id.btn_clear);
        scrollLog = findViewById(R.id.scroll_log);
        tvLog = findViewById(R.id.tv_log);
    }

    private void applyUiTheme() {
        // Surface Cards
        cardHeader.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));
        cardActions.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));
        cardTerminal.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));

        // Inner Viewports
        scrollLog.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER_SUB, 1, 6));
        etCommand.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER, 1, 6));

        // Badges
        styleBadge(badgeArch, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        styleBadge(badgeRoot, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleBadge(badgeGlibc, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);
        styleBadge(badgeSaf, UiTheme.C_YELLOW, UiTheme.C_YELLOW_BG, UiTheme.C_YELLOW);
        styleBadge(badgeLineCount, UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER_SUB);

        // Action Micro-capsules
        styleCapsule(btnInit, UiTheme.C_BLUE, UiTheme.C_BLUE_BG, UiTheme.C_BLUE);
        styleCapsule(btnRunUname, UiTheme.C_CYAN, UiTheme.C_CYAN_BG, UiTheme.C_CYAN);
        styleCapsule(btnRunScript, UiTheme.C_PURPLE, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE);
        styleCapsule(btnStop, UiTheme.C_RED, UiTheme.C_RED_BG, UiTheme.C_RED);
        styleCapsule(btnExec, UiTheme.C_GREEN, UiTheme.C_GREEN_BG, UiTheme.C_GREEN);

        // Terminal Top Action Buttons
        styleCapsule(btnCopy, UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);
        styleCapsule(btnClear, UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER);

        // Status Indicator Dot Base
        updateStatusDot(UiTheme.C_DIM);
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
        btnRunUname.setOnClickListener(v -> runCommand("/bin/sh", "-c", "uname -a"));
        btnRunScript.setOnClickListener(v -> runCommand("/bin/sh", "-c",
                "echo '=== PRoot Virtual Sandbox ==='; id; echo '--- /lib ---'; ls -l /lib; echo '--- /etc ---'; ls -l /etc; echo '--- resolv.conf ---'; cat /etc/resolv.conf 2>/dev/null"));
        btnStop.setOnClickListener(v -> stopCurrentProcess());

        // Custom Command Execution
        btnExec.setOnClickListener(v -> executeCustomCommand());
        etCommand.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                executeCustomCommand();
                return true;
            }
            return false;
        });

        // Copy and Clear
        btnCopy.setOnClickListener(v -> copyLogToClipboard());
        btnClear.setOnClickListener(v -> {
            tvLog.setText("");
            logLineCount = 0;
            updateLineCountBadge();
        });
    }

    private void executeCustomCommand() {
        String cmd = etCommand.getText().toString().trim();
        if (cmd.isEmpty()) {
            return;
        }
        runCommand("/bin/sh", "-c", cmd);
    }

    private void copyLogToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("PRoot Logs", tvLog.getText().toString());
            cm.setPrimaryClip(clip);
            Toast.makeText(this, I18n.get(I18n.Key.TOAST_LOG_COPIED), Toast.LENGTH_SHORT).show();
        }
    }

    private void switchLanguage(I18n.Language language) {
        I18n.setLanguage(this, language);
        updateUiTexts();
        appendLog(I18n.get(I18n.Key.LOG_LANG_SWITCHED));
    }

    private void updateUiTexts() {
        tvTitle.setText(I18n.get(I18n.Key.APP_TITLE));
        tvSectionControl.setText(I18n.get(I18n.Key.SECTION_CONTROL));
        tvSectionTerminal.setText(I18n.get(I18n.Key.SECTION_TERMINAL));

        btnInit.setText(I18n.get(I18n.Key.BTN_INIT));
        btnRunUname.setText(I18n.get(I18n.Key.BTN_RUN_UNAME));
        btnRunScript.setText(I18n.get(I18n.Key.BTN_RUN_SCRIPT));
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
        updateLineCountBadge();
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
                statusText = I18n.get(I18n.Key.STATUS_INIT_FAILED);
                dotColor = UiTheme.C_RED;
                break;
            case RUNNING:
                statusText = I18n.format(I18n.Key.STATUS_RUNNING, stateDetail);
                dotColor = UiTheme.C_CYAN;
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

    private void updateLineCountBadge() {
        badgeLineCount.setText(I18n.format(I18n.Key.BADGE_LINES, logLineCount));
    }

    private void initEngine() {
        setStatus(State.INITIALIZING, "", 0);
        appendLog(I18n.get(I18n.Key.LOG_INIT_START));

        executor.execute(() -> {
            try {
                engine.initialize();
                setStatus(State.READY, "", 0);
                appendLog(I18n.get(I18n.Key.LOG_INIT_SUCCESS));
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize PRootEngine", e);
                setStatus(State.INIT_FAILED, e.getMessage(), 0);
                appendLog(I18n.get(I18n.Key.LOG_INIT_FAIL) + " " + e.getMessage());
            }
        });
    }

    private void stopCurrentProcess() {
        if (currentProcess != null && currentProcess.isAlive()) {
            appendLog(I18n.get(I18n.Key.LOG_STOPPING));
            executor.execute(() -> {
                int pid = currentProcess.getPid();
                appendLog(I18n.get(I18n.Key.LOG_KILLING_TREE) + pid);
                currentProcess.destroyProcessTree();
                currentProcess = null;
                setStatus(State.STOPPED, "", -1);
                appendLog(I18n.get(I18n.Key.LOG_TREE_KILLED));
            });
        } else {
            appendLog(I18n.get(I18n.Key.LOG_NO_PROCESS));
        }
    }

    private void runCommand(String... cmdArgs) {
        if (cmdArgs == null || cmdArgs.length == 0) return;

        executor.execute(() -> {
            stopCurrentProcess();

            try {
                PRootConfig.Builder builder = new PRootConfig.Builder()
                        .setWorkDir("/app")
                        .setFakeRoot(true)
                        .setExecutable(cmdArgs[0]);

                for (int i = 1; i < cmdArgs.length; i++) {
                    builder.addArg(cmdArgs[i]);
                }

                builder.addEnv("HOME", "/app");
                builder.addEnv("USER", "root");
                builder.addEnv("PATH", "/bin:/usr/bin:/sbin:/usr/sbin:/usr/local/bin");
                builder.addEnv("TERM", "xterm-256color");

                PRootConfig config = builder.build();
                currentProcess = engine.launch(config);

                int pid = currentProcess.getPid();
                String displayCmd = (cmdArgs.length > 2) ? cmdArgs[2] : cmdArgs[0];
                setStatus(State.RUNNING, displayCmd + " [PID " + pid + "]", 0);
                appendLog(I18n.get(I18n.Key.LOG_STARTED) + pid + " (" + displayCmd + ")");

                // Read stdout & stderr concurrently
                executor.execute(() -> readStream(currentProcess.getInputStream()));
                executor.execute(() -> readStream(currentProcess.getErrorStream()));

                int exitCode = currentProcess.waitFor();
                setStatus(State.IDLE, "", exitCode);
                appendLog(I18n.get(I18n.Key.LOG_EXITED) + exitCode);
            } catch (Exception e) {
                Log.e(TAG, "Error running command", e);
                setStatus(State.ERROR, e.getMessage(), -1);
                appendLog("[Exception] " + e.getMessage());
            }
        });
    }

    private void readStream(java.io.InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(line);
            }
        } catch (Exception ignored) {
        }
    }

    private void appendLog(String line) {
        mainHandler.post(() -> {
            tvLog.append(line + "\n");
            logLineCount++;
            updateLineCountBadge();
            scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCurrentProcess();
        executor.shutdownNow();
    }
}
