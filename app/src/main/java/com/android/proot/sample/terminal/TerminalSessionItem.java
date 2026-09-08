package com.android.proot.sample.terminal;

import com.termux.terminal.TerminalSession;

/**
 * Encapsulates a single PTY terminal session within the multi-session terminal management system.
 * Tracks process ID, running status, working directory, command parameters, and Termux TerminalSession instance.
 */
public final class TerminalSessionItem {

    private final int id;
    private final int number;
    private String title;
    private final String workDir;
    private final String[] cmdArgs;
    private final TerminalSession session;
    private final int pid;
    private boolean isRunning;
    private int exitCode;
    private final long createdAt;

    public TerminalSessionItem(int id, int number, String title, String workDir,
                               String[] cmdArgs, TerminalSession session, int pid) {
        this.id = id;
        this.number = number;
        this.title = (title != null && !title.trim().isEmpty()) ? title.trim() : "bash";
        this.workDir = (workDir != null && !workDir.trim().isEmpty()) ? workDir.trim() : "/root";
        this.cmdArgs = cmdArgs != null ? cmdArgs : new String[0];
        this.session = session;
        this.pid = pid;
        this.isRunning = true;
        this.exitCode = 0;
        this.createdAt = System.currentTimeMillis();
    }

    public int getId() {
        return id;
    }

    public int getNumber() {
        return number;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        if (title != null && !title.trim().isEmpty()) {
            this.title = title.trim();
        }
    }

    public String getWorkDir() {
        return workDir;
    }

    public String[] getCmdArgs() {
        return cmdArgs;
    }

    public TerminalSession getSession() {
        return session;
    }

    public int getPid() {
        return pid;
    }

    public boolean isRunning() {
        return isRunning && session != null && session.isRunning();
    }

    public void setRunning(boolean running) {
        this.isRunning = running;
    }

    public int getExitCode() {
        return exitCode;
    }

    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * Short display label for compact chips and badges (e.g. "#1:bash").
     */
    public String getShortLabel() {
        return "#" + number + ":" + title;
    }

    /**
     * Extended descriptor including PID and working directory.
     */
    public String getDetailSummary() {
        return "PID " + pid + " • " + workDir;
    }
}
