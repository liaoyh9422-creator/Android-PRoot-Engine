package com.android.proot;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Handle to a running process inside the PRoot sandbox.
 * Wraps java.lang.Process with native PID resolution and recursive tree termination.
 */
public class PRootProcess {
    private final Process process;
    private final int pid;

    public PRootProcess(Process process) {
        this.process = process;
        this.pid = ProcessUtil.getPid(process);
    }

    /**
     * Returns the native Linux PID of the underlying process.
     */
    public int getPid() {
        return pid;
    }

    /**
     * Gets the standard output stream of the process.
     */
    public InputStream getInputStream() {
        return process.getInputStream();
    }

    /**
     * Gets the error stream of the process.
     */
    public InputStream getErrorStream() {
        return process.getErrorStream();
    }

    /**
     * Gets the standard input stream of the process.
     */
    public OutputStream getOutputStream() {
        return process.getOutputStream();
    }

    /**
     * Waits for the process to exit.
     */
    public int waitFor() throws InterruptedException {
        return process.waitFor();
    }

    /**
     * Waits for the process to exit with a specified timeout.
     */
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return process.waitFor(timeout, unit);
    }

    /**
     * Returns the exit value of the process.
     */
    public int exitValue() {
        return process.exitValue();
    }

    /**
     * Tests whether the process is alive.
     */
    public boolean isAlive() {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /**
     * Standard graceful termination request.
     */
    public void destroy() {
        process.destroy();
    }

    /**
     * Forcibly destroys the immediate process.
     */
    public void destroyForcibly() {
        process.destroyForcibly();
    }

    /**
     * Recursively terminates the process and all spawned child processes in its process group.
     */
    public void destroyProcessTree() {
        ProcessUtil.killProcessTree(process);
    }

    /**
     * Returns the underlying java.lang.Process instance.
     */
    public Process getUnderlyingProcess() {
        return process;
    }
}
