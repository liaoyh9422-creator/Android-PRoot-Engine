package com.android.proot.sample;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.proot.PRootConfig;
import com.android.proot.PRootEngine;
import com.android.proot.PRootProcess;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private PRootEngine engine;
    private PRootProcess currentProcess;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView tvTitle;
    private TextView tvStatus;
    private TextView tvConsoleHeader;
    private TextView tvLog;
    private ScrollView scrollLog;
    private Button btnInit;
    private Button btnRunUname;
    private Button btnRunScript;
    private Button btnStop;
    private Button btnClear;
    private Button btnLangZh;
    private Button btnLangEn;
    private Button btnLangJa;

    // Status state tracking for dynamic language updates
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
        setContentView(R.layout.activity_main);

        // 1. Initialize Pure Java I18n
        I18n.init(this);

        // 2. Bind Views
        tvTitle = findViewById(R.id.tv_title);
        tvStatus = findViewById(R.id.tv_status);
        tvConsoleHeader = findViewById(R.id.tv_console_header);
        tvLog = findViewById(R.id.tv_log);
        scrollLog = findViewById(R.id.scroll_log);
        btnInit = findViewById(R.id.btn_init);
        btnRunUname = findViewById(R.id.btn_run_uname);
        btnRunScript = findViewById(R.id.btn_run_script);
        btnStop = findViewById(R.id.btn_stop);
        btnClear = findViewById(R.id.btn_clear);
        btnLangZh = findViewById(R.id.btn_lang_zh);
        btnLangEn = findViewById(R.id.btn_lang_en);
        btnLangJa = findViewById(R.id.btn_lang_ja);

        engine = new PRootEngine(this);

        // 3. Setup Listeners
        btnLangZh.setOnClickListener(v -> switchLanguage(I18n.Language.ZH_CN));
        btnLangEn.setOnClickListener(v -> switchLanguage(I18n.Language.EN));
        btnLangJa.setOnClickListener(v -> switchLanguage(I18n.Language.JA));

        btnInit.setOnClickListener(v -> initEngine());
        btnRunUname.setOnClickListener(v -> runCommand("/bin/sh", "-c", "uname -a"));
        btnRunScript.setOnClickListener(v -> runCommand("/bin/sh", "-c",
                "echo '=== PRoot Virtual Sandbox ==='; id; echo '--- /lib ---'; ls -l /lib; echo '--- /etc ---'; ls -l /etc; echo '--- resolv.conf ---'; cat /etc/resolv.conf 2>/dev/null"));
        btnStop.setOnClickListener(v -> stopCurrentProcess());
        btnClear.setOnClickListener(v -> tvLog.setText(""));

        // 4. Render UI Texts
        updateUiTexts();
    }

    private void switchLanguage(I18n.Language language) {
        I18n.setLanguage(this, language);
        updateUiTexts();
        appendLog(I18n.get(I18n.Key.LOG_LANG_SWITCHED));
    }

    private void updateUiTexts() {
        tvTitle.setText(I18n.get(I18n.Key.APP_TITLE));
        btnInit.setText(I18n.get(I18n.Key.BTN_INIT));
        btnRunUname.setText(I18n.get(I18n.Key.BTN_RUN_UNAME));
        btnRunScript.setText(I18n.get(I18n.Key.BTN_RUN_SCRIPT));
        btnStop.setText(I18n.get(I18n.Key.BTN_STOP));
        btnClear.setText(I18n.get(I18n.Key.BTN_CLEAR));
        tvConsoleHeader.setText(I18n.get(I18n.Key.CONSOLE_TITLE));

        // Highlight selected language button
        I18n.Language current = I18n.getLanguage();
        btnLangZh.setTypeface(null, current == I18n.Language.ZH_CN ? Typeface.BOLD : Typeface.NORMAL);
        btnLangZh.setAlpha(current == I18n.Language.ZH_CN ? 1.0f : 0.6f);

        btnLangEn.setTypeface(null, current == I18n.Language.EN ? Typeface.BOLD : Typeface.NORMAL);
        btnLangEn.setAlpha(current == I18n.Language.EN ? 1.0f : 0.6f);

        btnLangJa.setTypeface(null, current == I18n.Language.JA ? Typeface.BOLD : Typeface.NORMAL);
        btnLangJa.setAlpha(current == I18n.Language.JA ? 1.0f : 0.6f);

        // Refresh current status text with localized string
        renderStatus();
    }

    private void setStatus(State state, String detail, int exitCode) {
        this.currentState = state;
        this.stateDetail = detail;
        this.lastExitCode = exitCode;
        mainHandler.post(this::renderStatus);
    }

    private void renderStatus() {
        String statusText;
        switch (currentState) {
            case INITIALIZING:
                statusText = I18n.get(I18n.Key.STATUS_INITIALIZING);
                break;
            case READY:
                statusText = I18n.get(I18n.Key.STATUS_READY);
                break;
            case INIT_FAILED:
                statusText = I18n.get(I18n.Key.STATUS_INIT_FAILED);
                break;
            case RUNNING:
                statusText = I18n.format(I18n.Key.STATUS_RUNNING, stateDetail);
                break;
            case STOPPED:
                statusText = I18n.get(I18n.Key.STATUS_STOPPED);
                break;
            case IDLE:
                statusText = I18n.format(I18n.Key.STATUS_IDLE, lastExitCode);
                break;
            case ERROR:
                statusText = I18n.format(I18n.Key.STATUS_ERROR, stateDetail);
                break;
            case UNINITIALIZED:
            default:
                statusText = I18n.get(I18n.Key.STATUS_UNINITIALIZED);
                break;
        }
        tvStatus.setText(I18n.get(I18n.Key.STATUS_LABEL_PREFIX) + statusText);
    }

    private void appendLog(String line) {
        mainHandler.post(() -> {
            tvLog.append(line + "\n");
            scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void initEngine() {
        setStatus(State.INITIALIZING, "", 0);
        appendLog(I18n.get(I18n.Key.LOG_INIT_START));
        executor.execute(() -> {
            boolean success = engine.initialize();
            if (success) {
                setStatus(State.READY, "", 0);
                appendLog(I18n.get(I18n.Key.LOG_INIT_SUCCESS));
                appendLog("  proot:  " + engine.getProotPath());
                appendLog("  loader: " + engine.getLoaderPath());
                appendLog("  rootfs: " + engine.getRootfsDir().getAbsolutePath());
            } else {
                setStatus(State.INIT_FAILED, "", 0);
                appendLog(I18n.get(I18n.Key.LOG_INIT_FAIL));
            }
        });
    }

    private void runCommand(String executable, String... args) {
        executor.execute(() -> {
            try {
                if (currentProcess != null && currentProcess.isAlive()) {
                    appendLog(I18n.get(I18n.Key.LOG_STOPPING));
                    currentProcess.destroyProcessTree();
                }

                setStatus(State.RUNNING, executable, 0);
                appendLog("\n$ " + executable + " " + String.join(" ", args));

                PRootConfig config = new PRootConfig.Builder()
                        .setExecutable(executable)
                        .addArgs(args)
                        .setWorkDir("/app")
                        .setFakeRoot(true)
                        .build();

                currentProcess = engine.launch(config);
                appendLog(I18n.get(I18n.Key.LOG_STARTED) + currentProcess.getPid());

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendLog(line);
                    }
                }

                int exitCode = currentProcess.waitFor();
                appendLog(I18n.get(I18n.Key.LOG_EXITED) + exitCode);
                setStatus(State.IDLE, "", exitCode);
            } catch (Exception e) {
                Log.e(TAG, "Execution error", e);
                appendLog("[Error] " + e.getMessage());
                setStatus(State.ERROR, e.getMessage(), 0);
            }
        });
    }

    private void stopCurrentProcess() {
        if (currentProcess != null && currentProcess.isAlive()) {
            executor.execute(() -> {
                appendLog(I18n.get(I18n.Key.LOG_KILLING_TREE) + currentProcess.getPid() + "...");
                currentProcess.destroyProcessTree();
                setStatus(State.STOPPED, "", 0);
                appendLog(I18n.get(I18n.Key.LOG_TREE_KILLED));
            });
        } else {
            appendLog(I18n.get(I18n.Key.LOG_NO_PROCESS));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentProcess != null) {
            currentProcess.destroyProcessTree();
        }
        executor.shutdownNow();
    }
}
