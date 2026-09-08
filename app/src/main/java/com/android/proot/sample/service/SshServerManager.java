package com.android.proot.sample.service;

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
 * Manages OpenSSH Server (sshd) & Dropbear SSH server inside PRoot container:
 * Environment verification, one-click installation for Debian (apt) & Alpine (apk),
 * hostkey generation, password setup, foreground daemon lifecycle management, and client connection strings.
 */
public final class SshServerManager {
    private static final String TAG = "SshServerManager";

    public interface SshCallback {
        void onResult(boolean success, String message);
    }

    private static volatile SshServerManager sInstance;

    private int port = 2222;
    private String password = "proot";
    private volatile boolean isRunning = false;
    private volatile PRootProcess runningProcess;

    private final ExecutorService executor = Executors.newCachedThreadPool();

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
        if (port > 0 && port <= 65535) {
            this.port = port;
        }
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password != null ? password : "proot";
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Checks whether OpenSSH server (sshd) binary exists.
     */
    public boolean isSshdInstalled(File rootfsDir) {
        if (rootfsDir == null || !rootfsDir.exists()) return false;
        return new File(rootfsDir, "usr/sbin/sshd").exists();
    }

    /**
     * Checks whether Dropbear SSH daemon binary exists.
     */
    public boolean isDropbearInstalled(File rootfsDir) {
        if (rootfsDir == null || !rootfsDir.exists()) return false;
        return new File(rootfsDir, "usr/sbin/dropbear").exists();
    }

    /**
     * Checks whether any supported SSH daemon binary exists inside container.
     */
    public boolean isInstalled(File rootfsDir) {
        return isSshdInstalled(rootfsDir) || isDropbearInstalled(rootfsDir);
    }

    /**
     * Returns the detected SSH daemon binary type name.
     */
    public String getSshBinaryType(File rootfsDir) {
        if (isSshdInstalled(rootfsDir)) return "OpenSSH (sshd)";
        if (isDropbearInstalled(rootfsDir)) return "Dropbear";
        return "Not Installed";
    }

    /**
     * One-click installs OpenSSH server (with smart fallback to Dropbear if apt/apk dictates) and generates host keys.
     */
    public void installSsh(PRootEngine engine, SshCallback callback) {
        executor.execute(() -> {
            try {
                if (!engine.isInitialized()) engine.initialize();
                File rootfs = engine.getRootfsDir();

                boolean isDebian = rootfs != null && new File(rootfs, "usr/bin/apt-get").exists();
                boolean isAlpine = rootfs != null && new File(rootfs, "sbin/apk").exists();

                StringBuilder installScript = new StringBuilder();
                installScript.append("mkdir -p /run/sshd /var/run /var/empty /etc/ssh /etc/ssh/sshd_config.d /tmp && ");

                if (isDebian) {
                    installScript.append("export DEBIAN_FRONTEND=noninteractive && ")
                            .append("apt-get update && ")
                            .append("apt-get install -y openssh-server && ");
                } else if (isAlpine) {
                    installScript.append("apk add --no-cache openssh && ");
                } else {
                    installScript.append("(apt-get update && apt-get install -y openssh-server || apk add --no-cache openssh || apk add --no-cache dropbear) && ");
                }

                // Generate host keys if missing
                installScript.append("([ -f /etc/ssh/ssh_host_rsa_key ] || [ -f /etc/ssh/ssh_host_ed25519_key ] || ssh-keygen -A || true) && ");

                // Configure sshd for PRoot compatibility
                installScript.append("printf 'PermitRootLogin yes\\nPasswordAuthentication yes\\nPermitEmptyPasswords no\\nUsePAM no\\nPidFile /tmp/sshd.pid\\n' > /etc/ssh/sshd_config.d/proot.conf 2>/dev/null || true && ");

                // Set root password
                installScript.append("echo 'root:").append(escapePassword(password)).append("' | chpasswd");

                PRootConfig config = new PRootConfig.Builder()
                        .setExecutable("/bin/sh")
                        .addArgs("-c", installScript.toString())
                        .setWorkDir("/root")
                        .setFakeRoot(true)
                        .build();

                PRootProcess proc = engine.launch(config);
                String out = drainProcessOutput(proc);
                int exit = proc.waitFor();

                if (exit != 0 && !isInstalled(engine.getRootfsDir())) {
                    postCallback(callback, false, "SSH 安装失败:\n" + out);
                    return;
                }

                String type = getSshBinaryType(engine.getRootfsDir());
                postCallback(callback, true, type + " 服务与主机密钥安装配置完成！");
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
     * Starts SSH daemon (preferring OpenSSH sshd, fallback to Dropbear) inside PRoot container.
     */
    public void startServer(PRootEngine engine, SshCallback callback) {
        executor.execute(() -> {
            try {
                File rootfs = engine.getRootfsDir();
                if (!isInstalled(rootfs)) {
                    postCallback(callback, false, "未检测到 SSH 服务 (sshd / dropbear)，请先执行一键安装。");
                    return;
                }

                // Stop any running daemon process first
                stopDaemonProcess(engine);

                // Prepare environment: directories, config, host keys, and password
                String prepScript = "mkdir -p /run/sshd /var/run /var/empty /etc/ssh /etc/ssh/sshd_config.d /tmp && " +
                        "chmod 755 /run/sshd /var/empty 2>/dev/null || true && " +
                        "([ -f /etc/ssh/ssh_host_rsa_key ] || [ -f /etc/ssh/ssh_host_ed25519_key ] || ssh-keygen -A || true) && " +
                        "printf 'PermitRootLogin yes\\nPasswordAuthentication yes\\nPermitEmptyPasswords no\\nUsePAM no\\nPidFile /tmp/sshd.pid\\n' > /etc/ssh/sshd_config.d/proot.conf 2>/dev/null || true && " +
                        "echo 'root:" + escapePassword(password) + "' | chpasswd";

                PRootConfig prepConfig = new PRootConfig.Builder()
                        .setExecutable("/bin/sh")
                        .addArgs("-c", prepScript)
                        .setWorkDir("/root")
                        .setFakeRoot(true)
                        .build();
                engine.launch(prepConfig).waitFor();

                boolean useSshd = isSshdInstalled(rootfs);
                PRootConfig startConfig;

                if (useSshd) {
                    // Launch OpenSSH sshd in foreground (-D) with stderr logging (-e)
                    // -D prevents detaching so PRoot keeps tracing child connections
                    startConfig = new PRootConfig.Builder()
                            .setExecutable("/usr/sbin/sshd")
                            .addArgs("-D", "-e", "-p", String.valueOf(port))
                            .setWorkDir("/root")
                            .setFakeRoot(true)
                            .build();
                } else {
                    // Fallback to Dropbear in foreground (-F) with stderr logging (-E)
                    startConfig = new PRootConfig.Builder()
                            .setExecutable("/usr/sbin/dropbear")
                            .addArgs("-F", "-E", "-p", String.valueOf(port), "-R")
                            .setWorkDir("/root")
                            .setFakeRoot(true)
                            .build();
                }

                PRootProcess proc = engine.launch(startConfig);
                this.runningProcess = proc;

                // Watch process death in background
                executor.execute(() -> {
                    try {
                        proc.waitFor();
                    } catch (Exception ignored) {
                    } finally {
                        if (runningProcess == proc) {
                            isRunning = false;
                            runningProcess = null;
                        }
                    }
                });

                // Wait for port binding (up to 3 seconds)
                boolean active = false;
                for (int i = 0; i < 15; i++) {
                    Thread.sleep(200);
                    if (checkPortActive(port)) {
                        active = true;
                        break;
                    }
                }

                isRunning = active;
                String typeName = useSshd ? "OpenSSH (sshd)" : "Dropbear";
                postCallback(callback, active, active
                        ? typeName + " 服务启动成功！监听端口: " + port
                        : "SSH 启动未在目标端口 " + port + " 建立监听，请检查端口冲突。");
            } catch (Exception e) {
                Log.e(TAG, "Failed starting SSH server", e);
                isRunning = false;
                runningProcess = null;
                postCallback(callback, false, "启动失败: " + e.getMessage());
            }
        });
    }

    /**
     * Stops SSH daemon cleanly.
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
            if (runningProcess != null) {
                runningProcess.destroy();
                runningProcess = null;
            }
        } catch (Exception ignored) {}

        if (engine != null) {
            try {
                PRootConfig killConfig = new PRootConfig.Builder()
                        .setExecutable("/bin/sh")
                        .addArgs("-c", "killall sshd || pkill sshd || killall dropbear || pkill dropbear || true")
                        .setWorkDir("/root")
                        .setFakeRoot(true)
                        .build();
                engine.launch(killConfig).waitFor();
            } catch (Exception ignored) {}
        }
    }

    public static String escapePassword(String pass) {
        if (pass == null) return "proot";
        return pass.replace("'", "'\\''");
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

    private void postCallback(SshCallback callback, boolean success, String msg) {
        if (callback == null) return;
        try {
            Looper mainLooper = Looper.getMainLooper();
            if (mainLooper != null) {
                new Handler(mainLooper).post(() -> callback.onResult(success, msg));
                return;
            }
        } catch (Throwable ignored) {}
        callback.onResult(success, msg);
    }
}
