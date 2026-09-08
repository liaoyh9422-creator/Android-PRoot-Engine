package com.android.proot.sample.service;

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
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages Dropbear SSH server inside PRoot Alpine container:
 * Environment verification, one-click installation, hostkey generation,
 * password setup, daemon lifecycle management, and client connection strings.
 */
public final class SshServerManager {
    private static final String TAG = "SshServerManager";

    public interface SshCallback {
        void onResult(boolean success, String message);
    }

    private static volatile SshServerManager sInstance;

    private int port = 2222;
    private String password = "proot";
    private boolean isRunning = false;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static SshServerManager getInstance() {
        if (sInstance == null) {
            synchronized (SshServerManager.class) {
                if (sInstance == null) {
                    sInstance = new SshServerManager();
                }
            }
        }
        return sInstance;
    }

    private SshServerManager() {}

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Checks whether Dropbear SSH daemon binary exists inside container.
     */
    public boolean isInstalled(File rootfsDir) {
        if (rootfsDir == null || !rootfsDir.exists()) return false;
        File bin = new File(rootfsDir, "usr/sbin/dropbear");
        return bin.exists();
    }

    /**
     * One-click installs dropbear and generates host keys.
     */
    public void installSsh(PRootEngine engine, SshCallback callback) {
        executor.execute(() -> {
            try {
                if (!engine.isInitialized()) engine.initialize();

                // 1. apk add dropbear
                PRootConfig config = new PRootConfig.Builder()
                        .setExecutable("/sbin/apk")
                        .addArgs("add", "--no-cache", "dropbear")
                        .setWorkDir("/root")
                        .setFakeRoot(true)
                        .build();

                PRootProcess proc = engine.launch(config);
                String out = drainProcessOutput(proc);
                int exit = proc.waitFor();

                if (exit != 0 || !isInstalled(engine.getRootfsDir())) {
                    postCallback(callback, false, "Dropbear 安装失败:\n" + out);
                    return;
                }

                // 2. Generate host keys and set default password
                String script = "mkdir -p /etc/dropbear && " +
                        "[ -f /etc/dropbear/dropbear_rsa_host_key ] || dropbearkey -t rsa -f /etc/dropbear/dropbear_rsa_host_key && " +
                        "[ -f /etc/dropbear/dropbear_ed25519_host_key ] || dropbearkey -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key && " +
                        "echo 'root:" + escapePassword(password) + "' | chpasswd";

                PRootConfig initConfig = new PRootConfig.Builder()
                        .setExecutable("/bin/sh")
                        .addArgs("-c", script)
                        .setWorkDir("/root")
                        .setFakeRoot(true)
                        .build();

                PRootProcess initProc = engine.launch(initConfig);
                initProc.waitFor();

                postCallback(callback, true, "Dropbear SSH 服务与主机密钥安装配置完成！");
            } catch (Exception e) {
                Log.e(TAG, "Install SSH error", e);
                postCallback(callback, false, "安装异常: " + e.getMessage());
            }
        });
    }

    /**
     * Updates root password for SSH logins.
     */
    public void updatePassword(PRootEngine engine, String newPass, SshCallback callback) {
        if (newPass == null || newPass.trim().isEmpty()) {
            postCallback(callback, false, "密码不能为空");
            return;
        }
        this.password = newPass.trim();
        executor.execute(() -> {
            try {
                PRootConfig config = new PRootConfig.Builder()
                        .setExecutable("/bin/sh")
                        .addArgs("-c", "echo 'root:" + escapePassword(password) + "' | chpasswd")
                        .setWorkDir("/root")
                        .setFakeRoot(true)
                        .build();

                PRootProcess proc = engine.launch(config);
                int exit = proc.waitFor();
                postCallback(callback, exit == 0, exit == 0 ? "SSH root 密码已更新为: " + password : "密码更新失败");
            } catch (Exception e) {
                postCallback(callback, false, "密码更新异常: " + e.getMessage());
            }
        });
    }

    /**
     * Starts Dropbear SSH daemon inside PRoot container.
     */
    public void startServer(PRootEngine engine, SshCallback callback) {
        executor.execute(() -> {
            try {
                if (!isInstalled(engine.getRootfsDir())) {
                    postCallback(callback, false, "未检测到 Dropbear SSH 服务，请先执行一键安装。");
                    return;
                }

                // Kill existing dropbear if any
                stopDaemonProcess(engine);

                // Update root password before starting
                PRootConfig passConfig = new PRootConfig.Builder()
                        .setExecutable("/bin/sh")
                        .addArgs("-c", "echo 'root:" + escapePassword(password) + "' | chpasswd")
                        .setWorkDir("/root")
                        .setFakeRoot(true)
                        .build();
                engine.launch(passConfig).waitFor();

                // Launch dropbear daemon: -p <port> -R (generate hostkey if missing)
                PRootConfig startConfig = new PRootConfig.Builder()
                        .setExecutable("/usr/sbin/dropbear")
                        .addArgs("-p", String.valueOf(port), "-R")
                        .setWorkDir("/root")
                        .setFakeRoot(true)
                        .build();

                PRootProcess proc = engine.launch(startConfig);
                Thread.sleep(600); // Wait for daemon binding

                boolean active = checkPortActive(port);
                isRunning = active;
                postCallback(callback, active, active
                        ? "SSH 服务启动成功！监听端口: " + port
                        : "SSH 启动未在目标端口建立监听，请检查端口冲突。");
            } catch (Exception e) {
                Log.e(TAG, "Failed starting dropbear", e);
                isRunning = false;
                postCallback(callback, false, "启动失败: " + e.getMessage());
            }
        });
    }

    /**
     * Stops Dropbear SSH daemon.
     */
    public void stopServer(PRootEngine engine, SshCallback callback) {
        executor.execute(() -> {
            stopDaemonProcess(engine);
            isRunning = false;
            postCallback(callback, true, "SSH 服务已停止");
        });
    }

    private void stopDaemonProcess(PRootEngine engine) {
        try {
            PRootConfig killConfig = new PRootConfig.Builder()
                    .setExecutable("/bin/sh")
                    .addArgs("-c", "killall dropbear || pkill dropbear || true")
                    .setWorkDir("/root")
                    .setFakeRoot(true)
                    .build();
            engine.launch(killConfig).waitFor();
        } catch (Exception ignored) {}
    }

    private boolean checkPortActive(int testPort) {
        try (Socket s = new Socket("127.0.0.1", testPort)) {
            return true;
        } catch (Exception e) {
            return false;
        }
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

    private String escapePassword(String pass) {
        return pass.replace("'", "'\\''");
    }

    private void postCallback(SshCallback callback, boolean success, String msg) {
        if (callback != null) {
            mainHandler.post(() -> callback.onResult(success, msg));
        }
    }
}
