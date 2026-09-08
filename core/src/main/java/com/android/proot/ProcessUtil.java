package com.android.proot;

import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Process utility providing deep PID reflection and recursive process tree termination.
 */
public class ProcessUtil {
    private static final String TAG = "PRoot:ProcessUtil";

    /**
     * Obtains the native Linux PID of a running java.lang.Process via multi-level reflection.
     * Compatible with various Android ART runtime versions.
     */
    public static int getPid(Process p) {
        if (p == null) return -1;

        // 1. Try Java 9+ standard pid() method
        try {
            Method m = p.getClass().getMethod("pid");
            m.setAccessible(true);
            Object res = m.invoke(p);
            if (res instanceof Number) {
                int pid = ((Number) res).intValue();
                if (pid > 0) return pid;
            }
        } catch (Throwable ignored) {}

        // 2. Reflectively access private 'pid' field
        Class<?> clazz = p.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField("pid");
                f.setAccessible(true);
                int pid = f.getInt(p);
                if (pid > 0) return pid;
            } catch (Throwable ignored) {}
            clazz = clazz.getSuperclass();
        }

        // 3. Fallback: Parse toString() output
        try {
            String s = p.toString();
            Matcher matcher = Pattern.compile("(?i)pid[=\\s](\\d+)").matcher(s);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (Throwable ignored) {}

        return -1;
    }

    /**
     * Recursively terminates the process and all of its spawned child processes.
     */
    public static void killProcessTree(Process process) {
        if (process == null) return;
        int pid = getPid(process);
        if (pid > 0) {
            Log.d(TAG, "killProcessTree PID=" + pid);
            try {
                // Kill process group
                ProcessBuilder pb1 = new ProcessBuilder("/system/bin/sh", "-c",
                        "kill -9 -" + pid + " 2>/dev/null; kill -9 " + pid + " 2>/dev/null");
                pb1.redirectErrorStream(true);
                Process p1 = pb1.start();
                p1.waitFor(1, TimeUnit.SECONDS);
                p1.destroyForcibly();

                // Recursively kill child processes
                ProcessBuilder pb2 = new ProcessBuilder("/system/bin/sh", "-c",
                        "pgrep -P " + pid + " 2>/dev/null | xargs kill -9 2>/dev/null");
                pb2.redirectErrorStream(true);
                Process p2 = pb2.start();
                p2.waitFor(1, TimeUnit.SECONDS);
                p2.destroyForcibly();

                // Android OS process kill
                android.os.Process.killProcess(pid);
            } catch (Exception e) {
                Log.w(TAG, "killProcessTree error: " + e.getMessage());
            }
        }
        process.destroyForcibly();
    }

    /**
     * Scans /proc to kill any orphaned processes matching the given keywords in their cmdline.
     */
    public static void killProcessesMatching(String... keywords) {
        if (keywords == null || keywords.length == 0) return;
        try {
            File proc = new File("/proc");
            File[] pids = proc.listFiles();
            if (pids == null) return;
            int myPid = android.os.Process.myPid();

            for (File p : pids) {
                if (!p.isDirectory()) continue;
                String name = p.getName();
                try {
                    int pid = Integer.parseInt(name);
                    if (pid == myPid || pid <= 1) continue;

                    File cmdlineFile = new File(p, "cmdline");
                    if (!cmdlineFile.exists() || !cmdlineFile.canRead()) continue;

                    byte[] buf = new byte[2048];
                    int len;
                    try (FileInputStream fis = new FileInputStream(cmdlineFile)) {
                        len = fis.read(buf);
                    }
                    if (len <= 0) continue;

                    String cmdline = new String(buf, 0, len).replace('\0', ' ').trim();
                    for (String kw : keywords) {
                        if (cmdline.contains(kw)) {
                            Log.i(TAG, "Terminating orphaned process PID=" + pid + ": " + cmdline);
                            android.os.Process.killProcess(pid);
                            try {
                                Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "kill -9 " + pid}).waitFor(500, TimeUnit.MILLISECONDS);
                            } catch (Exception ignored) {}
                            break;
                        }
                    }
                } catch (NumberFormatException ignored) {
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "killProcessesMatching error: " + e.getMessage());
        }
    }
}
