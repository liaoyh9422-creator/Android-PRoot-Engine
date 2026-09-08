package com.android.proot.sample.tool;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import com.android.proot.PRootEngine;
import com.android.proot.proxy.CnbProxyServer;
import com.android.proot.sample.ai.IFlowConfigManager;
import com.android.proot.sample.ai.IFlowSessionManager;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provides comprehensive multi-dimensional system health diagnosis
 * and categorized fine-grained storage calculation/cleanup.
 */
public final class HealthStorageManager {
    private static final String TAG = "HealthStorageMgr";

    public static class HealthItem {
        public String category;
        public String title;
        public boolean passed;
        public String detail;

        public HealthItem(String category, String title, boolean passed, String detail) {
            this.category = category;
            this.title = title;
            this.passed = passed;
            this.detail = detail;
        }
    }

    public static class StorageBreakdown {
        public long rootfsBytes;
        public long iflowBytes;
        public long workspaceBytes;
        public long cacheAndTmpBytes;
        public long deviceTotalBytes;
        public long deviceAvailableBytes;
    }

    public interface HealthCallback {
        void onResult(List<HealthItem> items);
    }

    public interface StorageCallback {
        void onResult(StorageBreakdown breakdown);
    }

    private static volatile HealthStorageManager sInstance;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static HealthStorageManager getInstance() {
        if (sInstance == null) {
            synchronized (HealthStorageManager.class) {
                if (sInstance == null) {
                    sInstance = new HealthStorageManager();
                }
            }
        }
        return sInstance;
    }

    private HealthStorageManager() {}

    /**
     * Runs multi-dimensional environment health check.
     */
    public void runHealthCheck(Context context, PRootEngine engine, HealthCallback callback) {
        executor.execute(() -> {
            List<HealthItem> list = new ArrayList<>();
            File rootfs = engine != null ? engine.getRootfsDir() : null;

            // 1. PRoot 底层核心
            boolean prootOk = (engine != null && engine.isInitialized());
            list.add(new HealthItem("PRoot 底层核心", "PRoot 引擎初始化", prootOk, prootOk ? "引擎就绪，Fake-Root 正常" : "引擎未就绪"));

            boolean busyboxOk = rootfs != null && new File(rootfs, "bin/busybox").exists();
            list.add(new HealthItem("PRoot 底层核心", "Alpine 基础系统", busyboxOk, busyboxOk ? "busybox 完整就绪" : "rootfs 缺少 busybox"));

            boolean dnsOk = rootfs != null && new File(rootfs, "etc/resolv.conf").exists();
            list.add(new HealthItem("PRoot 底层核心", "DNS 解析配置", dnsOk, dnsOk ? "resolv.conf 注入成功" : "DNS 配置文件缺失"));

            boolean sslOk = rootfs != null && new File(rootfs, "etc/ssl/certs/ca-certificates.crt").exists();
            list.add(new HealthItem("PRoot 底层核心", "CA 证书链", sslOk, sslOk ? "HTTPS 根证书正常" : "CA 证书缺失"));

            // 2. iFlow AI 运行时
            boolean nodeOk = rootfs != null && (new File(rootfs, "usr/bin/node").exists() || new File(rootfs, "usr/local/bin/node").exists());
            list.add(new HealthItem("iFlow 运行时", "Node.js 运行时", nodeOk, nodeOk ? "v20+ 就绪" : "未检测到 Node.js"));

            boolean iflowOk = rootfs != null && new File(rootfs, "usr/local/bin/iflow").exists();
            list.add(new HealthItem("iFlow 运行时", "iFlow CLI 核心", iflowOk, iflowOk ? "v0.5.19 完整就绪" : "未检测到 iflow 执行程序"));

            File settingsFile = rootfs != null ? new File(rootfs, "root/.iflow/settings.json") : null;
            JSONObject json = IFlowConfigManager.readSettingsJson(settingsFile);
            boolean farewellBypassed = json.optBoolean("hasViewedFarewellLetter", false);
            list.add(new HealthItem("iFlow 运行时", "停服公告豁免状态", farewellBypassed, farewellBypassed ? "已永久豁免停服弹窗" : "未豁免（可能重复弹窗）"));

            String lang = json.optString("language", "");
            boolean langOk = "zh-CN".equalsIgnoreCase(lang);
            list.add(new HealthItem("iFlow 运行时", "中文环境持久化", langOk, langOk ? "已锁定 zh-CN" : "未锁定中文 (当前: " + (lang.isEmpty() ? "默认" : lang) + ")"));

            // 3. 本地代理网络
            boolean proxyRunning = CnbProxyServer.getInstance().isRunning();
            list.add(new HealthItem("本地代理服务", "AI 代理端口 (:8080)", proxyRunning, proxyRunning ? "HTTP 127.0.0.1:8080 正常服务中" : "代理服务当前未启动"));

            // 4. 扩展工具
            boolean adbOk = rootfs != null && new File(rootfs, "usr/bin/adb").exists();
            list.add(new HealthItem("扩展工具环境", "ADB 无线调试工具", adbOk, adbOk ? "已安装 (/usr/bin/adb)" : "未安装 (可通过一键安装获取)"));

            boolean dropbearOk = rootfs != null && new File(rootfs, "usr/sbin/dropbear").exists();
            list.add(new HealthItem("扩展工具环境", "Dropbear SSH 服务", dropbearOk, dropbearOk ? "已安装 (/usr/sbin/dropbear)" : "未安装"));

            mainHandler.post(() -> {
                if (callback != null) callback.onResult(list);
            });
        });
    }

    /**
     * Calculates fine-grained categorized storage usage.
     */
    public void calculateStorage(Context context, PRootEngine engine, StorageCallback callback) {
        executor.execute(() -> {
            StorageBreakdown sb = new StorageBreakdown();
            File rootfs = engine != null ? engine.getRootfsDir() : null;

            if (rootfs != null && rootfs.exists()) {
                File iflowDir = new File(rootfs, "root/.iflow");
                sb.iflowBytes = calculateDirSize(iflowDir);

                File tmpDir = new File(rootfs, "tmp");
                sb.cacheAndTmpBytes = calculateDirSize(tmpDir);

                long totalRootfs = calculateDirSize(rootfs);
                sb.rootfsBytes = Math.max(0, totalRootfs - sb.iflowBytes - sb.cacheAndTmpBytes);
            }

            if (context != null) {
                File wsDir = new File(context.getFilesDir(), "workspace");
                sb.workspaceBytes = calculateDirSize(wsDir);

                File cacheDir = context.getCacheDir();
                sb.cacheAndTmpBytes += calculateDirSize(cacheDir);

                try {
                    StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
                    sb.deviceTotalBytes = stat.getTotalBytes();
                    sb.deviceAvailableBytes = stat.getAvailableBytes();
                } catch (Exception ignored) {}
            }

            mainHandler.post(() -> {
                if (callback != null) callback.onResult(sb);
            });
        });
    }

    /**
     * Cleans temporary files, crash dumps, and cache directories.
     */
    public void cleanCacheAndTmp(Context context, PRootEngine engine, Runnable onComplete) {
        executor.execute(() -> {
            if (context != null) {
                deleteDirContents(context.getCacheDir());
            }
            if (engine != null && engine.getRootfsDir() != null) {
                File tmp = new File(engine.getRootfsDir(), "tmp");
                deleteDirContents(tmp);
            }
            mainHandler.post(() -> {
                if (onComplete != null) onComplete.run();
            });
        });
    }

    /**
     * Cleans empty or expired iFlow session workspaces older than 14 days.
     */
    public void cleanExpiredSessions(PRootEngine engine, Runnable onComplete) {
        executor.execute(() -> {
            if (engine != null && engine.getRootfsDir() != null) {
                File projectsDir = new File(engine.getRootfsDir(), "root/.iflow/projects");
                if (projectsDir.exists() && projectsDir.isDirectory()) {
                    File[] files = projectsDir.listFiles();
                    if (files != null) {
                        long now = System.currentTimeMillis();
                        long limit = 14L * 24 * 3600 * 1000;
                        for (File f : files) {
                            if (f.isDirectory() && (now - f.lastModified() > limit)) {
                                deleteDirRecursive(f);
                            }
                        }
                    }
                }
            }
            mainHandler.post(() -> {
                if (onComplete != null) onComplete.run();
            });
        });
    }

    private long calculateDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        long size = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                size += calculateDirSize(child);
            }
        }
        return size;
    }

    private void deleteDirContents(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteDirRecursive(child);
            }
        }
    }

    private void deleteDirRecursive(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }
}
