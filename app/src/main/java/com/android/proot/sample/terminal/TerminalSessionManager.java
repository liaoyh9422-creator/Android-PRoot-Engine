package com.android.proot.sample.terminal;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.proot.PRootConfig;
import com.android.proot.PRootEngine;
import com.android.proot.sample.I18n;
import com.android.proot.sample.ai.IFlowConfigManager;
import com.android.proot.sample.ui.UiTheme;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages PRootEngine lifecycle, PTY terminal multi-session execution, process states,
 * and environment variable injection. Supports switching tabs, creating and closing sessions,
 * with resilient fallback to prevent empty/dead terminal states.
 */
public final class TerminalSessionManager {
    private static final String TAG = "TerminalSessionMgr";

    public enum State {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        INIT_FAILED,
        RUNNING,
        STOPPED,
        IDLE,
        ERROR
    }

    public interface StateChangeListener {
        void onStateChanged(State state, String detail, int exitCode);
    }

    public interface SessionListChangeListener {
        void onSessionListChanged(List<TerminalSessionItem> sessions, int activeIndex);
        void onActiveSessionChanged(TerminalSessionItem activeItem);
    }

    private final Activity activity;
    private final PRootEngine engine;
    private final TerminalBridge terminalBridge;
    private final TerminalView terminalView;
    private final TextView tvStatus;
    private final View statusDot;
    private final TextView badgeLineCount;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<TerminalSessionItem> sessionList = new CopyOnWriteArrayList<>();
    private final List<SessionListChangeListener> sessionListeners = new CopyOnWriteArrayList<>();

    private int activeSessionIndex = -1;
    private int nextSessionNumber = 1;

    private State currentState = State.UNINITIALIZED;
    private String stateDetail = "";
    private int lastExitCode = 0;
    private StateChangeListener stateChangeListener;

    public TerminalSessionManager(Activity activity, PRootEngine engine,
                                  TerminalBridge terminalBridge, TerminalView terminalView,
                                  TextView tvStatus, View statusDot, TextView badgeLineCount) {
        this.activity = activity;
        this.engine = engine;
        this.terminalBridge = terminalBridge;
        this.terminalView = terminalView;
        this.tvStatus = tvStatus;
        this.statusDot = statusDot;
        this.badgeLineCount = badgeLineCount;
    }

    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    public void addSessionListChangeListener(SessionListChangeListener listener) {
        if (listener != null && !sessionListeners.contains(listener)) {
            sessionListeners.add(listener);
        }
    }

    public void removeSessionListChangeListener(SessionListChangeListener listener) {
        if (listener != null) {
            sessionListeners.remove(listener);
        }
    }

    public List<TerminalSessionItem> getSessions() {
        return Collections.unmodifiableList(sessionList);
    }

    public int getActiveSessionIndex() {
        return activeSessionIndex;
    }

    public TerminalSessionItem getActiveSessionItem() {
        if (activeSessionIndex >= 0 && activeSessionIndex < sessionList.size()) {
            return sessionList.get(activeSessionIndex);
        }
        return null;
    }

    public TerminalSession getCurrentSession() {
        TerminalSessionItem item = getActiveSessionItem();
        return item != null ? item.getSession() : null;
    }

    public PRootEngine getEngine() {
        return engine;
    }

    public State getCurrentState() {
        return currentState;
    }

    public void setStatus(State state, String detail, int exitCode) {
        this.currentState = state;
        this.stateDetail = detail;
        this.lastExitCode = exitCode;
        mainHandler.post(this::renderStatus);
        if (stateChangeListener != null) {
            stateChangeListener.onStateChanged(state, detail, exitCode);
        }
    }

    public void renderStatus() {
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

        if (tvStatus != null) {
            tvStatus.setText(I18n.get(I18n.Key.STATUS_LABEL_PREFIX) + statusText);
        }
        if (statusDot != null) {
            statusDot.setBackground(UiTheme.roundRect(activity, dotColor, dotColor, 0, 4));
        }
    }

    public String getDefaultShell() {
        File rootfs = engine != null ? engine.getRootfsDir() : null;
        if (rootfs != null && new File(rootfs, "bin/bash").exists()) {
            return "/bin/bash";
        }
        return "/bin/sh";
    }

    public void initEngine(Runnable onReady) {
        setStatus(State.INITIALIZING, "", 0);

        executor.execute(() -> {
            try {
                boolean ok = engine.initialize();
                if (ok) {
                    IFlowConfigManager.getInstance(activity).syncIFlowConfigToRootfs(engine.getRootfsDir());
                    setStatus(State.READY, "", 0);
                    mainHandler.post(() -> {
                        if (onReady != null) {
                            onReady.run();
                        } else {
                            createNewSession("/root", getDefaultShell(), "-l");
                        }
                    });
                } else {
                    setStatus(State.INIT_FAILED, "Native libraries missing", 0);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize PRootEngine", e);
                setStatus(State.INIT_FAILED, e.getMessage(), 0);
            }
        });
    }

    /**
     * Backward-compatible alias for running a terminal session (creates a new session).
     */
    public void runTerminalSession(String workDir, String... cmdArgs) {
        createNewSession(workDir, cmdArgs);
    }

    /**
     * Spawns a new concurrent PTY terminal session with PRoot engine isolation.
     */
    public void createNewSession(String workDir, String... cmdArgs) {
        if (cmdArgs == null || cmdArgs.length == 0) {
            cmdArgs = new String[]{getDefaultShell(), "-l"};
        }
        final String[] effectiveArgs = cmdArgs;

        executor.execute(() -> {
            try {
                if (!engine.isInitialized()) {
                    setStatus(State.INITIALIZING, "Initializing PRootEngine...", 0);
                    if (!engine.initialize()) {
                        setStatus(State.INIT_FAILED, "PRoot init failed", -1);
                        return;
                    }
                }

                String effectiveWorkDir = (workDir != null && !workDir.trim().isEmpty()) ? workDir.trim() : "/root";

                PRootConfig.Builder builder = new PRootConfig.Builder()
                        .setWorkDir(effectiveWorkDir)
                        .setFakeRoot(true)
                        .setExecutable(effectiveArgs[0]);

                for (int i = 1; i < effectiveArgs.length; i++) {
                    builder.addArg(effectiveArgs[i]);
                }

                // Inject OpenAI / iFlow environment variables into PRoot environment
                IFlowConfigManager iflowConfig = IFlowConfigManager.getInstance(activity);
                String iflowBaseUrl = iflowConfig.getBaseUrl();
                String iflowApiKey = iflowConfig.getApiKey();
                String iflowModel = iflowConfig.getModel();

                if (!iflowApiKey.isEmpty()) {
                    builder.addEnv("IFLOW_API_KEY", iflowApiKey);
                    builder.addEnv("OPENAI_API_KEY", iflowApiKey);
                }
                if (!iflowBaseUrl.isEmpty()) {
                    builder.addEnv("IFLOW_BASE_URL", iflowBaseUrl);
                    builder.addEnv("OPENAI_BASE_URL", iflowBaseUrl);
                }
                if (!iflowModel.isEmpty()) {
                    builder.addEnv("IFLOW_MODEL_NAME", iflowModel);
                }

                // Inject clean UTF-8 environment
                builder.addEnv("LANG", "zh_CN.UTF-8");
                builder.addEnv("LC_ALL", "zh_CN.UTF-8");
                builder.addEnv("LANGUAGE", "zh_CN:zh");

                // Inject TrueColor (24-bit) terminal environment
                builder.addEnv("TERM", "xterm-256color");
                builder.addEnv("COLORTERM", "truecolor");
                builder.addEnv("FORCE_COLOR", "3");

                // Inject Thinking mode environment
                String effort = iflowConfig.getReasoningEffort();
                builder.addEnv("DEFAULT_REASONING_EFFORT", effort);
                builder.addEnv("THINKING_DISPLAY_MODE", "visible");
                builder.addEnv("MAX_THINKING_TOKENS", "31999");

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
                        TerminalSession session = new TerminalSession(
                                shellPath,
                                cwd,
                                args,
                                envArray,
                                2000,
                                terminalBridge
                        );

                        int pid = session.getPid();
                        int sessionNum = nextSessionNumber++;

                        String initialTitle;
                        if (effectiveArgs.length > 0 && effectiveArgs[0].contains("iflow")) {
                            initialTitle = "iflow";
                        } else if (effectiveArgs.length > 2 && effectiveArgs[2].contains("iflow")) {
                            initialTitle = "iflow";
                        } else {
                            String binName = new File(effectiveArgs[0]).getName();
                            initialTitle = binName.isEmpty() ? "bash" : binName;
                        }

                        TerminalSessionItem newItem = new TerminalSessionItem(
                                sessionNum, sessionNum, initialTitle, effectiveWorkDir, effectiveArgs, session, pid
                        );

                        sessionList.add(newItem);
                        int newIndex = sessionList.size() - 1;
                        switchToSession(newIndex);
                        notifySessionListChanged();

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

    /**
     * Switches the active session attached to the TerminalView console.
     */
    public synchronized void switchToSession(int index) {
        if (index < 0 || index >= sessionList.size()) return;

        activeSessionIndex = index;
        TerminalSessionItem item = sessionList.get(index);
        TerminalSession session = item.getSession();

        if (terminalView != null && session != null) {
            terminalView.attachSession(session);
            terminalView.onScreenUpdated();
        }

        int pid = item.getPid();
        String displayCmd = item.getTitle();
        if (item.isRunning()) {
            setStatus(State.RUNNING, displayCmd + " [PID " + pid + "]", 0);
        } else {
            setStatus(State.IDLE, displayCmd, item.getExitCode());
        }

        if (badgeLineCount != null) {
            badgeLineCount.setText("PID: " + pid);
        }

        notifyActiveSessionChanged(item);
    }

    /**
     * Closes the terminal session at the given index.
     * If the closed session is the only session remaining, automatically respawns a clean
     * default shell session so the terminal viewport is never left dead.
     */
    public synchronized void closeSession(int index) {
        if (index < 0 || index >= sessionList.size()) return;

        TerminalSessionItem item = sessionList.get(index);
        if (item != null && item.getSession() != null && item.getSession().isRunning()) {
            try {
                item.getSession().finishIfRunning();
            } catch (Exception e) {
                Log.w(TAG, "Error finishing session", e);
            }
        }

        sessionList.remove(index);

        if (sessionList.isEmpty()) {
            // Safety fallback: spawn a new clean session in current working directory
            String fallbackWorkDir = (item != null && item.getWorkDir() != null) ? item.getWorkDir() : "/workspace";
            createNewSession(fallbackWorkDir, getDefaultShell(), "-l");
        } else {
            if (activeSessionIndex == index) {
                int newActiveIndex = Math.min(index, sessionList.size() - 1);
                switchToSession(newActiveIndex);
            } else if (activeSessionIndex > index) {
                activeSessionIndex--;
            }
            notifySessionListChanged();
        }
    }

    public void stopCurrentSession() {
        if (activeSessionIndex >= 0 && activeSessionIndex < sessionList.size()) {
            closeSession(activeSessionIndex);
        }
    }

    public void onSessionFinished(TerminalSession finishedSession, int exitCode) {
        for (TerminalSessionItem item : sessionList) {
            if (item.getSession() == finishedSession) {
                item.setRunning(false);
                item.setExitCode(exitCode);
                break;
            }
        }

        TerminalSessionItem current = getActiveSessionItem();
        if (current != null && current.getSession() == finishedSession) {
            setStatus(State.IDLE, current.getTitle(), exitCode);
            mainHandler.post(() -> {
                if (badgeLineCount != null) {
                    badgeLineCount.setText("Exit: " + exitCode);
                }
            });
        }
        notifySessionListChanged();
    }

    public void onSessionFinished(int exitCode) {
        TerminalSession current = getCurrentSession();
        if (current != null) {
            onSessionFinished(current, exitCode);
        }
    }

    public void onSessionTitleChanged(TerminalSession session, String title) {
        for (TerminalSessionItem item : sessionList) {
            if (item.getSession() == session) {
                item.setTitle(title);
                break;
            }
        }

        TerminalSessionItem current = getActiveSessionItem();
        if (current != null && current.getSession() == session) {
            mainHandler.post(() -> {
                if (badgeLineCount != null && title != null && !title.isEmpty()) {
                    badgeLineCount.setText(title);
                }
            });
        }
        notifySessionListChanged();
    }

    private void notifySessionListChanged() {
        mainHandler.post(() -> {
            for (SessionListChangeListener listener : sessionListeners) {
                listener.onSessionListChanged(sessionList, activeSessionIndex);
            }
        });
    }

    private void notifyActiveSessionChanged(TerminalSessionItem activeItem) {
        mainHandler.post(() -> {
            for (SessionListChangeListener listener : sessionListeners) {
                listener.onActiveSessionChanged(activeItem);
            }
        });
    }

    public void destroy() {
        for (TerminalSessionItem item : sessionList) {
            if (item.getSession() != null && item.getSession().isRunning()) {
                try {
                    item.getSession().finishIfRunning();
                } catch (Exception ignored) {}
            }
        }
        sessionList.clear();
        executor.shutdownNow();
    }
}
