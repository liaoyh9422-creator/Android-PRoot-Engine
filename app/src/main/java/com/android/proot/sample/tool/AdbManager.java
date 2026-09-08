package com.android.proot.sample.tool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.proot.PRootConfig;
import com.android.proot.PRootEngine;
import com.android.proot.PRootProcess;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages ADB installation inside PRoot Alpine container, Wireless Debugging pairing,
 * connection establishment, device status polling, and iFlow CLI interoperability.
 */
public final class AdbManager {
    private static final String TAG = "AdbManager";

    public interface AdbCallback {
        void onResult(boolean success, String output);
    }

    private static volatile AdbManager sInstance;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static AdbManager getInstance() {
        if (sInstance == null) {
            synchronized (AdbManager.class) {
                if (sInstance == null) {
                    sInstance = new AdbManager();
                }
            }
        }
        return sInstance;
    }

    private AdbManager() {}

    /**
     * Checks if /usr/bin/adb exists within Alpine rootfs.
     */
    public boolean isInstalled(File rootfsDir) {
        if (rootfsDir == null || !rootfsDir.exists()) return false;
        File adbBin = new File(rootfsDir, "usr/bin/adb");
        return adbBin.exists();
    }

    /**
     * One-click installs android-tools via apk inside the PRoot container.
     */
    public void installAdb(PRootEngine engine, AdbCallback callback) {
        executor.execute(() -> {
            try {
                if (!engine.isInitialized()) {
                    engine.initialize();
                }
                PRootConfig config = new PRootConfig.Builder()
                        .setExecutable("/sbin/apk")
                        .addArgs("add", "--no-cache", "android-tools")
                        .setWorkDir("/root")
                        .setFakeRoot(true)
                        .build();

                PRootProcess proc = engine.launch(config);
                String output = drainProcessOutput(proc);
                int exit = proc.waitFor();

                boolean ok = (exit == 0 && isInstalled(engine.getRootfsDir()));
                postCallback(callback, ok, output);
            } catch (Exception e) {
                Log.e(TAG, "Failed installing android-tools", e);
                postCallback(callback, false, "安装失败: " + e.getMessage());
            }
        });
    }

    /**
     * Android 11+ adb pair <ip:port> <code>
     */
    public void pair(PRootEngine engine, String hostPort, String pairCode, AdbCallback callback) {
        if (hostPort == null || hostPort.trim().isEmpty() || pairCode == null || pairCode.trim().isEmpty()) {
            postCallback(callback, false, "配对参数不完整");
            return;
        }
        executeAdbCmd(engine, callback, "pair", hostPort.trim(), pairCode.trim());
    }

    /**
     * adb connect <ip:port>
     */
    public void connect(PRootEngine engine, String hostPort, AdbCallback callback) {
        if (hostPort == null || hostPort.trim().isEmpty()) {
            postCallback(callback, false, "请输入有效的 IP:端口");
            return;
        }
        executeAdbCmd(engine, callback, "connect", hostPort.trim());
    }

    /**
     * adb devices -l
     */
    public void listDevices(PRootEngine engine, AdbCallback callback) {
        executeAdbCmd(engine, callback, "devices", "-l");
    }

    /**
     * adb disconnect
     */
    public void disconnectAll(PRootEngine engine, AdbCallback callback) {
        executeAdbCmd(engine, callback, "disconnect");
    }

    /**
     * adb kill-server
     */
    public void killServer(PRootEngine engine, AdbCallback callback) {
        executeAdbCmd(engine, callback, "kill-server");
    }

    private void executeAdbCmd(PRootEngine engine, AdbCallback callback, String... args) {
        executor.execute(() -> {
            try {
                if (!isInstalled(engine.getRootfsDir())) {
                    postCallback(callback, false, "未检测到 ADB 调试工具，请先点击安装 android-tools。");
                    return;
                }
                if (!engine.isInitialized()) {
                    engine.initialize();
                }

                PRootConfig.Builder builder = new PRootConfig.Builder()
                        .setExecutable("/usr/bin/adb")
                        .setWorkDir("/root")
                        .setFakeRoot(true);

                for (String arg : args) {
                    builder.addArg(arg);
                }

                PRootProcess proc = engine.launch(builder.build());
                String output = drainProcessOutput(proc);
                int exit = proc.waitFor();
                postCallback(callback, exit == 0, output);
            } catch (Exception e) {
                Log.e(TAG, "Error executing adb command", e);
                postCallback(callback, false, "执行失败: " + e.getMessage());
            }
        });
    }

    private String drainProcessOutput(PRootProcess proc) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception ignored) {}
        return sb.toString().trim();
    }

    private void postCallback(AdbCallback callback, boolean success, String output) {
        if (callback != null) {
            mainHandler.post(() -> callback.onResult(success, output));
        }
    }
}
