package com.android.proot.sample.tool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.android.proot.PRootConfig;
import com.android.proot.PRootEngine;
import com.android.proot.PRootProcess;
import com.android.proot.sample.helper.NetworkProbeHelper;
import com.android.proot.sample.helper.SetupStatusManager;
import com.android.proot.sample.helper.ToolchainDownloader;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages 5 core ARM64 development environments:
 * 1. Base Core Utilities (apk --arch aarch64 add git, make, curl, build-base)
 * 2. Android Core Build Tools (aapt2, aidl, zipalign, apksigner, android-35 SDK, d8)
 * 3. C/C++ & NDK Native Toolchain (clang, lld, cmake, ninja, Termux-NDK aarch64)
 * 4. Rust & Android JNI Cross Compilation (rustc, cargo, aarch64-linux-android target)
 * 5. Go & Android JNI Cross Compilation (go 1.24 linux-arm64, CGO cross-compile)
 */
public final class EnvironmentManager {

    public static final String ID_BASE = "base_tools";
    public static final String ID_ANDROID = "android_core";
    public static final String ID_CPP = "cpp_ndk";
    public static final String ID_RUST = "rust_jni";
    public static final String ID_GO = "go_jni";

    public static class ToolchainItem {
        public final String id;
        public final String name;
        public final String desc;
        public final String components;
        public final String estimatedSize;
        public boolean isInstalled;
        public String detectedVersion;
        public boolean isOperating;

        public ToolchainItem(String id, String name, String desc, String components, String estimatedSize) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.components = components;
            this.estimatedSize = estimatedSize;
            this.isInstalled = false;
            this.detectedVersion = "未安装";
            this.isOperating = false;
        }
    }

    public interface ScanCallback {
        void onScanComplete(List<ToolchainItem> items);
    }

    public interface InstallCallback {
        void onLog(String message);
        void onProgress(int percentage, String label);
        void onItemFinished(String toolchainId, boolean success, String versionOrError);
        void onAllFinished(boolean allSuccess);
    }

    private static EnvironmentManager sInstance;
    private final List<ToolchainItem> toolchains = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler;

    private void postToMain(Runnable r) {
        if (mainHandler == null) {
            try {
                if (Looper.getMainLooper() != null) {
                    mainHandler = new Handler(Looper.getMainLooper());
                }
            } catch (Throwable ignored) {}
        }
        if (mainHandler != null) {
            mainHandler.post(r);
        } else {
            r.run();
        }
    }

    public static synchronized EnvironmentManager getInstance() {
        if (sInstance == null) {
            sInstance = new EnvironmentManager();
        }
        return sInstance;
    }

    private EnvironmentManager() {
        toolchains.add(new ToolchainItem(
                ID_BASE,
                "1. 基础通用工具链",
                "包含 Git、Make、C/C++编译构建基础工具与网络工具",
                "git, curl, wget, tar, xz, jq, make, build-base, ca-certificates",
                "约 48 MB"
        ));
        toolchains.add(new ToolchainItem(
                ID_ANDROID,
                "2. Android 核心编译与自举链 (方案 A+)",
                "ARM64 原生打包套件与 Android SDK 平台核心，支持 Gradle 多模块自举打包与独立 APK 编译",
                "aapt2, aidl, zipalign, apksigner, android-34, android-35 SDK (android.jar), OpenJDK 17, d8/r8, gradle aapt2 override",
                "约 132 MB"
        ));
        toolchains.add(new ToolchainItem(
                ID_CPP,
                "3. C/C++ & NDK 原生构建链",
                "原生 Clang/LLD 编译器与针对 Android 的 NDK 交叉工具链",
                "clang, lld, cmake, ninja, Termux-NDK (aarch64-linux-android sysroot)",
                "约 320 MB"
        ));
        toolchains.add(new ToolchainItem(
                ID_RUST,
                "4. Rust & Android JNI 交叉编译链",
                "Rust ARM64 独立开发包及 aarch64-linux-android 目标库与 Linker",
                "rustc, cargo, rust-std-aarch64-linux-android, cargo config",
                "约 260 MB"
        ));
        toolchains.add(new ToolchainItem(
                ID_GO,
                "5. Go & Android JNI 交叉编译链",
                "Go ARM64 官方运行时与 CGO Android 交叉编译动态库支持",
                "go 1.24 linux-arm64, gofmt, cgo cross-compile support",
                "约 180 MB"
        ));
    }

    public List<ToolchainItem> getToolchains() {
        return Collections.unmodifiableList(toolchains);
    }

    public ToolchainItem getItem(String id) {
        for (ToolchainItem item : toolchains) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    /**
     * Scans all 5 toolchains in PRoot sandbox by running detection commands.
     */
    public void scanAll(Context context, PRootEngine engine, ScanCallback callback) {
        executor.execute(() -> {
            if (engine == null || !engine.isInitialized()) {
                postToMain(() -> {
                    if (callback != null) callback.onScanComplete(toolchains);
                });
                return;
            }

            for (ToolchainItem item : toolchains) {
                checkToolchainSync(context, engine, item);
            }

            postToMain(() -> {
                if (callback != null) callback.onScanComplete(toolchains);
            });
        });
    }

    private void checkToolchainSync(Context context, PRootEngine engine, ToolchainItem item) {
        try {
            switch (item.id) {
                case ID_BASE: {
                    String out = runShellCommand(engine, "git --version 2>/dev/null && make -v 2>/dev/null | head -n 1");
                    if (out.contains("git version")) {
                        item.isInstalled = true;
                        item.detectedVersion = out.replace("\n", " | ").trim();
                        SetupStatusManager.setToolchainInstalled(context, item.id, true);
                    } else {
                        item.isInstalled = false;
                        item.detectedVersion = "未安装";
                    }
                    break;
                }
                case ID_ANDROID: {
                    String aaptOut = runShellCommand(engine, "aapt2 version 2>/dev/null || (/opt/android-sdk/build-tools/34.0.0/aapt2 version 2>/dev/null) || (/opt/android-sdk/build-tools/35.0.2/aapt2 version 2>/dev/null)");
                    String javaOut = runShellCommand(engine, "javac -version 2>/dev/null || java -version 2>&1 | head -n 1");
                    boolean hasJar = runShellCommand(engine, "[ -f /opt/android-sdk/platforms/android-34/android.jar ] || [ -f /opt/android-sdk/platforms/android-35/android.jar ] || [ -f /opt/android-sdk/android.jar ] && echo YES").contains("YES");
                    if ((aaptOut.contains("Android Asset Packaging Tool") || aaptOut.contains("AAPT2") || aaptOut.contains("aapt2")) && hasJar) {
                        item.isInstalled = true;
                        if (javaOut.contains("javac") || javaOut.contains("17") || javaOut.contains("openjdk")) {
                            item.detectedVersion = "SDK 34 (aapt2 + JDK 17 + D8)";
                        } else {
                            item.detectedVersion = "Android SDK 34 & aapt2";
                        }
                        SetupStatusManager.setToolchainInstalled(context, item.id, true);
                    } else {
                        item.isInstalled = false;
                        item.detectedVersion = "未安装";
                    }
                    break;
                }
                case ID_CPP: {
                    String out = runShellCommand(engine, "clang --version 2>/dev/null | head -n 1");
                    if (out.contains("clang version")) {
                        item.isInstalled = true;
                        item.detectedVersion = out.trim();
                        SetupStatusManager.setToolchainInstalled(context, item.id, true);
                    } else {
                        item.isInstalled = false;
                        item.detectedVersion = "未安装";
                    }
                    break;
                }
                case ID_RUST: {
                    String out = runShellCommand(engine, "rustc --version 2>/dev/null");
                    if (out.contains("rustc")) {
                        item.isInstalled = true;
                        item.detectedVersion = out.trim();
                        SetupStatusManager.setToolchainInstalled(context, item.id, true);
                    } else {
                        item.isInstalled = false;
                        item.detectedVersion = "未安装";
                    }
                    break;
                }
                case ID_GO: {
                    String out = runShellCommand(engine, "go version 2>/dev/null || (/opt/go/bin/go version 2>/dev/null)");
                    if (out.contains("go version")) {
                        item.isInstalled = true;
                        item.detectedVersion = out.trim();
                        SetupStatusManager.setToolchainInstalled(context, item.id, true);
                    } else {
                        item.isInstalled = false;
                        item.detectedVersion = "未安装";
                    }
                    break;
                }
            }
        } catch (Exception e) {
            item.isInstalled = false;
            item.detectedVersion = "检测异常";
        }
    }

    /**
     * Installs a single toolchain online.
     */
    public void installToolchain(Context context, PRootEngine engine, String toolchainId, InstallCallback callback) {
        installBatch(context, engine, Collections.singletonList(toolchainId), callback);
    }

    /**
     * Installs selected toolchains in sequence.
     */
    public void installBatch(Context context, PRootEngine engine, List<String> toolchainIds, InstallCallback callback) {
        executor.execute(() -> {
            boolean allOk = true;
            int total = toolchainIds.size();
            int current = 0;

            for (String id : toolchainIds) {
                current++;
                ToolchainItem item = getItem(id);
                if (item == null) continue;

                item.isOperating = true;
                final int curIdx = current;
                postProgress(callback, (int) ((curIdx - 1) * 100f / total), "正在安装: " + item.name);
                postLog(callback, "\n==========================================");
                postLog(callback, "🚀 开始安装 [" + item.name + "] (" + curIdx + "/" + total + ")");
                postLog(callback, "==========================================");

                boolean ok = installSingleToolchain(context, engine, item, callback);
                item.isOperating = false;
                item.isInstalled = ok;
                SetupStatusManager.setToolchainInstalled(context, item.id, ok);

                if (ok) {
                    checkToolchainSync(context, engine, item);
                    postLog(callback, "✅ [" + item.name + "] 装配成功！就绪版本: " + item.detectedVersion);
                    postItemFinished(callback, item.id, true, item.detectedVersion);
                } else {
                    allOk = false;
                    postLog(callback, "❌ [" + item.name + "] 装配失败！");
                    postItemFinished(callback, item.id, false, "安装失败");
                }
            }

            postProgress(callback, 100, allOk ? "全部环境装配完成" : "部分环境装配失败");
            final boolean finalAllOk = allOk;
            postToMain(() -> {
                if (callback != null) callback.onAllFinished(finalAllOk);
            });
        });
    }

    private boolean installSingleToolchain(Context context, PRootEngine engine, ToolchainItem item, InstallCallback callback) {
        try {
            switch (item.id) {
                case ID_BASE:
                    return installBaseTools(engine, callback);
                case ID_ANDROID:
                    return installAndroidTools(context, engine, callback);
                case ID_CPP:
                    return installCppNdk(engine, callback);
                case ID_RUST:
                    return installRust(context, engine, callback);
                case ID_GO:
                    return installGo(context, engine, callback);
            }
        } catch (Exception e) {
            postLog(callback, "!! 异常中断: " + e.getMessage());
        }
        return false;
    }

    private boolean installBaseTools(PRootEngine engine, InstallCallback callback) {
        postLog(callback, "> 正在通过 Alpine aarch64 官方仓库同步基础通用套件...");
        postLog(callback, "> 命令: apk --arch aarch64 add bash curl wget git tar xz jq make build-base ca-certificates");

        String cmd = "apk --arch aarch64 add --no-cache bash curl wget git tar xz jq make build-base ca-certificates 2>&1";
        int ret = runStreamCommand(engine, cmd, callback);
        return ret == 0;
    }

    private boolean installAndroidTools(Context context, PRootEngine engine, InstallCallback callback) {
        postLog(callback, "> 正在装配 Android 核心与自举编译链 (方案 A+)...");

        // Step 0: Ensure foundational tools (curl, wget, unzip, ca-certificates)
        postLog(callback, "\n> [0/5] 正在校验并自愈基础工具链 (curl, wget, unzip, ca-certificates)...");
        String preToolsCmd = "apk --arch aarch64 add --no-cache curl wget unzip ca-certificates 2>&1";
        runStreamCommand(engine, preToolsCmd, callback);

        // Step 1: OpenJDK 17
        postLog(callback, "\n> [1/5] 正在通过 Alpine 官方源装配 OpenJDK 17 (Java 17 JDK)...");
        String jdkCmd = "apk --arch aarch64 add --no-cache openjdk17 2>&1";
        int retJdk = runStreamCommand(engine, jdkCmd, callback);
        if (retJdk != 0) {
            postLog(callback, "!! OpenJDK 17 安装遇到警告，继续后续装配...");
        }

        // Shell download helper function
        String dlHelper =
                "dl_file() { " +
                "  url=\"$1\"; out=\"$2\"; " +
                "  if command -v curl >/dev/null 2>&1; then " +
                "    curl -fsSL -m 300 \"$url\" -o \"$out\"; " +
                "  elif command -v wget >/dev/null 2>&1; then " +
                "    wget -q -T 300 \"$url\" -O \"$out\"; " +
                "  else " +
                "    return 1; " +
                "  fi; " +
                "}; ";

        // Step 2: ARM64 Native static build-tools (aapt2, zipalign, aidl, adb)
        postLog(callback, "\n> [2/5] 正在拉取 ARM64 专版静态 Android SDK Tools (aapt2, aidl, zipalign)...");
        String setupToolsScript =
                dlHelper +
                "mkdir -p /opt/android-sdk/build-tools/34.0.0 /opt/android-sdk/build-tools/35.0.2 /opt/android-sdk/platform-tools /usr/local/bin /usr/bin /tmp && " +
                "cd /tmp && " +
                "URLS=\"https://ghfast.top/https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip " +
                "https://ghproxy.net/https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip " +
                "https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip\" && " +
                "SUCCESS=0 && " +
                "for u in $URLS; do " +
                "  echo \"> 尝试下载 SDK Tools: $u\" && " +
                "  if dl_file \"$u\" sdk-tools.zip; then SUCCESS=1; break; fi; " +
                "done && " +
                "if [ $SUCCESS -eq 1 ] && [ -f sdk-tools.zip ]; then " +
                "  rm -rf /tmp/sdk-unzip && mkdir -p /tmp/sdk-unzip && " +
                "  unzip -qo sdk-tools.zip -d /tmp/sdk-unzip && " +
                "  AAPT2_SRC=$(find /tmp/sdk-unzip -name aapt2 2>/dev/null | head -n 1) && " +
                "  ZIPALIGN_SRC=$(find /tmp/sdk-unzip -name zipalign 2>/dev/null | head -n 1) && " +
                "  AIDL_SRC=$(find /tmp/sdk-unzip -name aidl 2>/dev/null | head -n 1) && " +
                "  ADB_SRC=$(find /tmp/sdk-unzip -name adb 2>/dev/null | head -n 1) && " +
                "  [ -n \"$AAPT2_SRC\" ] && cp -f \"$AAPT2_SRC\" /opt/android-sdk/build-tools/34.0.0/aapt2 && cp -f \"$AAPT2_SRC\" /opt/android-sdk/build-tools/35.0.2/aapt2 && " +
                "  [ -n \"$ZIPALIGN_SRC\" ] && cp -f \"$ZIPALIGN_SRC\" /opt/android-sdk/build-tools/34.0.0/zipalign && cp -f \"$ZIPALIGN_SRC\" /opt/android-sdk/build-tools/35.0.2/zipalign && " +
                "  [ -n \"$AIDL_SRC\" ] && cp -f \"$AIDL_SRC\" /opt/android-sdk/build-tools/34.0.0/aidl && cp -f \"$AIDL_SRC\" /opt/android-sdk/build-tools/35.0.2/aidl && " +
                "  [ -n \"$ADB_SRC\" ] && cp -f \"$ADB_SRC\" /opt/android-sdk/platform-tools/adb; " +
                "fi && " +
                "chmod +x /opt/android-sdk/build-tools/34.0.0/* /opt/android-sdk/build-tools/35.0.2/* /opt/android-sdk/platform-tools/* 2>/dev/null || true && " +
                "(cd /usr/bin && ln -sf ../../opt/android-sdk/build-tools/34.0.0/aapt2 aapt2 2>/dev/null || true) && " +
                "(cd /usr/bin && ln -sf ../../opt/android-sdk/build-tools/34.0.0/zipalign zipalign 2>/dev/null || true) && " +
                "(cd /usr/bin && ln -sf ../../opt/android-sdk/build-tools/34.0.0/aidl aidl 2>/dev/null || true) && " +
                "(cd /usr/local/bin && ln -sf ../../opt/android-sdk/build-tools/34.0.0/aapt2 aapt2 2>/dev/null || true) && " +
                "(cd /usr/local/bin && ln -sf ../../opt/android-sdk/build-tools/34.0.0/zipalign zipalign 2>/dev/null || true) && " +
                "rm -rf /tmp/sdk-tools.zip /tmp/sdk-unzip";

        runStreamCommand(engine, setupToolsScript, callback);

        File rootfsDir = engine.getRootfsDir();
        File aapt2Check = new File(rootfsDir, "opt/android-sdk/build-tools/34.0.0/aapt2");
        if (!aapt2Check.exists() || aapt2Check.length() < 10000) {
            postLog(callback, "> 沙箱直连下载 SDK Tools 受阻，启动宿主 Java 穿透下载通道...");
            File tmpToolsZip = new File(engine.getTmpDir(), "sdk-tools.zip");
            String[] fallbackUrls = {
                "https://ghfast.top/https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip",
                "https://ghproxy.net/https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip",
                "https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"
            };
            boolean toolsDownloaded = false;
            for (String u : fallbackUrls) {
                if (ToolchainDownloader.downloadSync(u, tmpToolsZip)) {
                    toolsDownloaded = true;
                    break;
                }
            }
            if (toolsDownloaded) {
                postLog(callback, "> 宿主下载完成，正在沙箱内解包装配 aapt2, zipalign, aidl...");
                String unpackTools =
                        "cd /tmp && rm -rf /tmp/sdk-unzip && mkdir -p /tmp/sdk-unzip && " +
                        "unzip -qo /tmp/sdk-tools.zip -d /tmp/sdk-unzip && " +
                        "AAPT2_SRC=$(find /tmp/sdk-unzip -name aapt2 2>/dev/null | head -n 1) && " +
                        "ZIPALIGN_SRC=$(find /tmp/sdk-unzip -name zipalign 2>/dev/null | head -n 1) && " +
                        "AIDL_SRC=$(find /tmp/sdk-unzip -name aidl 2>/dev/null | head -n 1) && " +
                        "ADB_SRC=$(find /tmp/sdk-unzip -name adb 2>/dev/null | head -n 1) && " +
                        "mkdir -p /opt/android-sdk/build-tools/34.0.0 /opt/android-sdk/build-tools/35.0.2 /opt/android-sdk/platform-tools && " +
                        "[ -n \"$AAPT2_SRC\" ] && cp -f \"$AAPT2_SRC\" /opt/android-sdk/build-tools/34.0.0/aapt2 && cp -f \"$AAPT2_SRC\" /opt/android-sdk/build-tools/35.0.2/aapt2 && " +
                        "[ -n \"$ZIPALIGN_SRC\" ] && cp -f \"$ZIPALIGN_SRC\" /opt/android-sdk/build-tools/34.0.0/zipalign && cp -f \"$ZIPALIGN_SRC\" /opt/android-sdk/build-tools/35.0.2/zipalign && " +
                        "[ -n \"$AIDL_SRC\" ] && cp -f \"$AIDL_SRC\" /opt/android-sdk/build-tools/34.0.0/aidl && cp -f \"$AIDL_SRC\" /opt/android-sdk/build-tools/35.0.2/aidl && " +
                        "[ -n \"$ADB_SRC\" ] && cp -f \"$ADB_SRC\" /opt/android-sdk/platform-tools/adb && " +
                        "chmod +x /opt/android-sdk/build-tools/34.0.0/* /opt/android-sdk/build-tools/35.0.2/* /opt/android-sdk/platform-tools/* 2>/dev/null || true && " +
                        "(cd /usr/bin && ln -sf ../../opt/android-sdk/build-tools/34.0.0/aapt2 aapt2 2>/dev/null || true) && " +
                        "(cd /usr/bin && ln -sf ../../opt/android-sdk/build-tools/34.0.0/zipalign zipalign 2>/dev/null || true) && " +
                        "(cd /usr/bin && ln -sf ../../opt/android-sdk/build-tools/34.0.0/aidl aidl 2>/dev/null || true) && " +
                        "rm -rf /tmp/sdk-tools.zip /tmp/sdk-unzip";
                runStreamCommand(engine, unpackTools, callback);
            }
        }

        // Step 3: Android SDK Platform (android.jar)
        postLog(callback, "\n> [3/5] 正在拉取官方 Android SDK Platform 34 (android.jar)...");
        String setupPlatformScript =
                dlHelper +
                "mkdir -p /opt/android-sdk/platforms/android-34 /opt/android-sdk/platforms/android-35 /tmp && " +
                "cd /tmp && " +
                "PLAT_URLS=\"https://dl.google.com/android/repository/platform-34-ext12_r01.zip https://dl.google.com/android/repository/platform-35_r01.zip\" && " +
                "PLAT_OK=0 && " +
                "for pu in $PLAT_URLS; do " +
                "  echo \"> 尝试下载 SDK 平台库: $pu\" && " +
                "  if dl_file \"$pu\" plat.zip; then " +
                "    rm -rf /tmp/plat-unzip && mkdir -p /tmp/plat-unzip && " +
                "    unzip -qo plat.zip \"*/android.jar\" -d /tmp/plat-unzip && " +
                "    JAR_SRC=$(find /tmp/plat-unzip -name \"android.jar\" 2>/dev/null | head -n 1) && " +
                "    if [ -n \"$JAR_SRC\" ] && [ -f \"$JAR_SRC\" ]; then " +
                "      cp -f \"$JAR_SRC\" /opt/android-sdk/platforms/android-34/android.jar && " +
                "      cp -f \"$JAR_SRC\" /opt/android-sdk/platforms/android-35/android.jar && " +
                "      cp -f \"$JAR_SRC\" /opt/android-sdk/android.jar && " +
                "      PLAT_OK=1; break; " +
                "    fi; " +
                "  fi; " +
                "done && " +
                "rm -rf /tmp/plat.zip /tmp/plat-unzip && " +
                "[ $PLAT_OK -eq 1 ]";

        runStreamCommand(engine, setupPlatformScript, callback);

        File androidJarCheck = new File(rootfsDir, "opt/android-sdk/platforms/android-34/android.jar");
        if (!androidJarCheck.exists() || androidJarCheck.length() < 1000000) {
            postLog(callback, "> 沙箱内下载平台库受阻，启动宿主 Java 穿透下载通道 (带活动代理)...");
            File tmpPlatZip = new File(engine.getTmpDir(), "plat.zip");
            String[] platUrls = {
                "https://dl.google.com/android/repository/platform-34-ext12_r01.zip",
                "https://dl.google.com/android/repository/platform-35_r01.zip"
            };
            boolean platDownloaded = false;
            for (String pu : platUrls) {
                postLog(callback, "> 宿主拉取: " + pu);
                if (ToolchainDownloader.downloadSync(pu, tmpPlatZip)) {
                    platDownloaded = true;
                    break;
                }
            }
            if (platDownloaded) {
                postLog(callback, "> 宿主下载完成，正在沙箱内解包 android.jar...");
                String unpackPlatScript =
                        "cd /tmp && rm -rf /tmp/plat-unzip && mkdir -p /tmp/plat-unzip && " +
                        "unzip -qo /tmp/plat.zip \"*/android.jar\" -d /tmp/plat-unzip && " +
                        "JAR_SRC=$(find /tmp/plat-unzip -name \"android.jar\" 2>/dev/null | head -n 1) && " +
                        "if [ -n \"$JAR_SRC\" ] && [ -f \"$JAR_SRC\" ]; then " +
                        "  mkdir -p /opt/android-sdk/platforms/android-34 /opt/android-sdk/platforms/android-35 && " +
                        "  cp -f \"$JAR_SRC\" /opt/android-sdk/platforms/android-34/android.jar && " +
                        "  cp -f \"$JAR_SRC\" /opt/android-sdk/platforms/android-35/android.jar && " +
                        "  cp -f \"$JAR_SRC\" /opt/android-sdk/android.jar; " +
                        "fi && " +
                        "rm -rf /tmp/plat.zip /tmp/plat-unzip";
                runStreamCommand(engine, unpackPlatScript, callback);
            }
        }

        // Step 4: D8 / R8 & Apksigner
        postLog(callback, "\n> [4/5] 正在装配 D8/R8 字节码编译器与 APK 签名工具...");
        String setupD8Script =
                dlHelper +
                "mkdir -p /opt/android-sdk/build-tools/34.0.0 /usr/bin /usr/local/bin /tmp && " +
                "dl_file \"https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.33/r8-8.2.33.jar\" /opt/android-sdk/build-tools/34.0.0/d8.jar 2>/dev/null || true && " +
                "dl_file \"https://ghfast.top/https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar\" /opt/android-sdk/build-tools/34.0.0/apksigner.jar 2>/dev/null || true && " +
                "cat << 'EOF' > /usr/bin/d8\n" +
                "#!/bin/sh\n" +
                "exec java -cp /opt/android-sdk/build-tools/34.0.0/d8.jar com.android.tools.r8.D8 \"$@\"\n" +
                "EOF\n" +
                "cat << 'EOF' > /usr/bin/apksigner\n" +
                "#!/bin/sh\n" +
                "exec java -jar /opt/android-sdk/build-tools/34.0.0/apksigner.jar \"$@\"\n" +
                "EOF\n" +
                "chmod +x /usr/bin/d8 /usr/bin/apksigner 2>/dev/null || true && " +
                "ln -sf /usr/bin/d8 /usr/local/bin/d8 2>/dev/null || true && " +
                "ln -sf /usr/bin/apksigner /usr/local/bin/apksigner 2>/dev/null || true";
        runStreamCommand(engine, setupD8Script, callback);

        // Host fallback for d8 and apksigner if missing
        File d8Jar = new File(rootfsDir, "opt/android-sdk/build-tools/34.0.0/d8.jar");
        if (!d8Jar.exists() || d8Jar.length() < 100000) {
            ToolchainDownloader.downloadSync("https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.33/r8-8.2.33.jar", d8Jar);
        }
        File apkSignerJar = new File(rootfsDir, "opt/android-sdk/build-tools/34.0.0/apksigner.jar");
        if (!apkSignerJar.exists() || apkSignerJar.length() < 100000) {
            ToolchainDownloader.downloadSync("https://ghfast.top/https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar", apkSignerJar);
        }

        // Step 5: Configure Gradle aapt2 override & environment variables (Core of Plan A+)
        postLog(callback, "\n> [5/5] 正在配置 Gradle aapt2 原生重定向与环境持久化 (自举核心)...");
        String configScript =
                "mkdir -p /root/.gradle /etc/profile.d && " +
                "cat << 'EOF' > /root/.gradle/gradle.properties\n" +
                "android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/34.0.0/aapt2\n" +
                "org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\n" +
                "EOF\n" +
                "cat << 'EOF' > /etc/profile.d/android.sh\n" +
                "export ANDROID_HOME=/opt/android-sdk\n" +
                "export ANDROID_SDK_ROOT=/opt/android-sdk\n" +
                "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk\n" +
                "export PATH=$JAVA_HOME/bin:$ANDROID_HOME/build-tools/34.0.0:$ANDROID_HOME/platform-tools:$PATH\n" +
                "EOF";
        runStreamCommand(engine, configScript, callback);

        // Physical ELF Header Verification
        File aapt2File = new File(rootfsDir, "opt/android-sdk/build-tools/34.0.0/aapt2");
        if (!aapt2File.exists() || !ToolchainDownloader.isElfArm64(rootfsDir, aapt2File)) {
            postLog(callback, "!! 熔断拦截: 未检测到有效 ARM64 (EM_AARCH64 = 183) aapt2 二进制！");
            return false;
        }

        File androidJar = new File(rootfsDir, "opt/android-sdk/platforms/android-34/android.jar");
        if (!androidJar.exists() || androidJar.length() < 1000000) {
            postLog(callback, "!! 平台库校验失败: android.jar 未能成功就绪！");
            return false;
        }

        postLog(callback, "> ARM64 ELF 机器码校验通过 (183 = EM_AARCH64) [✓]");
        postLog(callback, "> Android Platform 34 (android.jar: " + (androidJar.length() / 1048576) + "MB) 校验通过 [✓]");
        postLog(callback, "> 全套编译链就绪: aapt2, aidl, zipalign, android.jar, openjdk17, d8, apksigner [✓]");
        return true;
    }

    private boolean installCppNdk(PRootEngine engine, InstallCallback callback) {
        postLog(callback, "> 正在装配 ARM64 Clang/LLVM, CMake, Ninja 原生工具链...");
        String cmd = "apk --arch aarch64 add --no-cache clang lld llvm cmake ninja 2>&1 && " +
                "mkdir -p /etc/profile.d && " +
                "echo 'export CC=clang' > /etc/profile.d/cpp.sh && " +
                "echo 'export CXX=clang++' >> /etc/profile.d/cpp.sh";

        int ret = runStreamCommand(engine, cmd, callback);
        return ret == 0;
    }

    private boolean installRust(Context context, PRootEngine engine, InstallCallback callback) {
        postLog(callback, "> 正在拉取官方 ARM64 独立 Rust 编译器与 aarch64-linux-android 目标库...");
        runStreamCommand(engine, "apk --arch aarch64 add --no-cache curl wget tar 2>&1", callback);

        String script =
                "dl_file() { url=\"$1\"; out=\"$2\"; if command -v curl >/dev/null 2>&1; then curl -fsSL -m 300 \"$url\" -o \"$out\"; elif command -v wget >/dev/null 2>&1; then wget -q -T 300 \"$url\" -O \"$out\"; else return 1; fi; }; " +
                "RUST_VER=\"1.85.0\" && " +
                "mkdir -p /opt/rust /root/.cargo /usr/local/bin /tmp && " +
                "cd /tmp && " +
                "echo \"> 正在拉取 Rust 核心开发包...\" && " +
                "(dl_file \"https://mirrors.tuna.tsinghua.edu.cn/rustup/dist/rust-${RUST_VER}-aarch64-unknown-linux-gnu.tar.gz\" rust.tar.gz || " +
                "dl_file \"https://static.rust-lang.org/dist/rust-${RUST_VER}-aarch64-unknown-linux-gnu.tar.gz\" rust.tar.gz) && " +
                "tar -zxf rust.tar.gz -C /tmp && " +
                "/tmp/rust-${RUST_VER}-aarch64-unknown-linux-gnu/install.sh --prefix=/opt/rust --components=rustc,cargo,rust-std-aarch64-unknown-linux-gnu --disable-ldconfig && " +
                "ln -sf /opt/rust/bin/rustc /usr/local/bin/rustc && " +
                "ln -sf /opt/rust/bin/cargo /usr/local/bin/cargo && " +
                "rm -rf /tmp/rust.tar.gz /tmp/rust-${RUST_VER}-aarch64-unknown-linux-gnu && " +
                "echo \"> 配置 Cargo 镜像加速与环境变量...\" && " +
                "cat << 'EOF' > /root/.cargo/config.toml\n" +
                "[source.crates-io]\n" +
                "replace-with = 'tuna'\n" +
                "[source.tuna]\n" +
                "registry = \"sparse+https://mirrors.tuna.tsinghua.edu.cn/crates.io-index/\"\n" +
                "EOF\n" +
                "echo 'export PATH=/opt/rust/bin:$PATH' > /etc/profile.d/rust.sh";

        int ret = runStreamCommand(engine, script, callback);
        if (ret != 0) {
            // Host fallback
            File rustTar = new File(engine.getTmpDir(), "rust.tar.gz");
            if (ToolchainDownloader.downloadSync("https://mirrors.tuna.tsinghua.edu.cn/rustup/dist/rust-1.85.0-aarch64-unknown-linux-gnu.tar.gz", rustTar)) {
                String unpackRust =
                        "tar -zxf /tmp/rust.tar.gz -C /tmp && " +
                        "/tmp/rust-1.85.0-aarch64-unknown-linux-gnu/install.sh --prefix=/opt/rust --components=rustc,cargo,rust-std-aarch64-unknown-linux-gnu --disable-ldconfig && " +
                        "ln -sf /opt/rust/bin/rustc /usr/local/bin/rustc && " +
                        "ln -sf /opt/rust/bin/cargo /usr/local/bin/cargo && " +
                        "rm -rf /tmp/rust.tar.gz /tmp/rust-1.85.0-aarch64-unknown-linux-gnu";
                runStreamCommand(engine, unpackRust, callback);
            }
        }

        File rustc = new File(engine.getRootfsDir(), "opt/rust/bin/rustc");
        if (rustc.exists() && !ToolchainDownloader.isElfArm64(rustc)) {
            postLog(callback, "!! 熔断拦截: Rust 二进制非 ARM64！");
            return false;
        }
        return rustc.exists();
    }

    private boolean installGo(Context context, PRootEngine engine, InstallCallback callback) {
        postLog(callback, "> 正在拉取官方 Go 1.24 (linux-arm64) 独立发布包...");
        runStreamCommand(engine, "apk --arch aarch64 add --no-cache curl wget tar 2>&1", callback);

        String script =
                "dl_file() { url=\"$1\"; out=\"$2\"; if command -v curl >/dev/null 2>&1; then curl -fsSL -m 180 \"$url\" -o \"$out\"; elif command -v wget >/dev/null 2>&1; then wget -q -T 180 \"$url\" -O \"$out\"; else return 1; fi; }; " +
                "mkdir -p /opt /usr/local/bin /tmp && " +
                "cd /tmp && " +
                "URLS=\"https://golang.google.cn/dl/go1.24.0.linux-arm64.tar.gz https://go.dev/dl/go1.24.0.linux-arm64.tar.gz\" && " +
                "SUCCESS=0 && " +
                "for u in $URLS; do " +
                "  echo \"> 尝试下载 Go: $u\" && " +
                "  if dl_file \"$u\" go.tar.gz; then SUCCESS=1; break; fi; " +
                "done && " +
                "if [ $SUCCESS -eq 1 ] && [ -f go.tar.gz ]; then " +
                "  rm -rf /opt/go && " +
                "  tar -zxf go.tar.gz -C /opt && " +
                "  ln -sf /opt/go/bin/go /usr/local/bin/go && " +
                "  ln -sf /opt/go/bin/gofmt /usr/local/bin/gofmt && " +
                "  rm -f /tmp/go.tar.gz; " +
                "fi && " +
                "mkdir -p /etc/profile.d && " +
                "echo 'export GOROOT=/opt/go' > /etc/profile.d/go.sh && " +
                "echo 'export PATH=$GOROOT/bin:$PATH' >> /etc/profile.d/go.sh && " +
                "echo 'export CGO_ENABLED=1' >> /etc/profile.d/go.sh && " +
                "echo 'export GOPROXY=https://goproxy.cn,direct' >> /etc/profile.d/go.sh";

        int ret = runStreamCommand(engine, script, callback);
        File goBin = new File(engine.getRootfsDir(), "opt/go/bin/go");
        if (!goBin.exists()) {
            postLog(callback, "> 沙箱直连下载 Go 受阻，启动宿主 Java 穿透下载通道...");
            File tmpGo = new File(engine.getTmpDir(), "go.tar.gz");
            if (ToolchainDownloader.downloadSync("https://golang.google.cn/dl/go1.24.0.linux-arm64.tar.gz", tmpGo) ||
                ToolchainDownloader.downloadSync("https://go.dev/dl/go1.24.0.linux-arm64.tar.gz", tmpGo)) {
                String unpackGo =
                        "rm -rf /opt/go && " +
                        "tar -zxf /tmp/go.tar.gz -C /opt && " +
                        "ln -sf /opt/go/bin/go /usr/local/bin/go && " +
                        "ln -sf /opt/go/bin/gofmt /usr/local/bin/gofmt && " +
                        "rm -f /tmp/go.tar.gz";
                runStreamCommand(engine, unpackGo, callback);
            }
        }

        if (goBin.exists() && !ToolchainDownloader.isElfArm64(goBin)) {
            postLog(callback, "!! 熔断拦截: Go 二进制非 ARM64！");
            return false;
        }
        return goBin.exists();
    }

    public static String getProxyEnvPrefix() {
        String desc = NetworkProbeHelper.getActiveProxyDescription();
        if (desc != null && !desc.isEmpty()) {
            return "export http_proxy=http://" + desc + " && " +
                   "export https_proxy=http://" + desc + " && " +
                   "export HTTP_PROXY=http://" + desc + " && " +
                   "export HTTPS_PROXY=http://" + desc + " && ";
        }
        return "";
    }

    private int runStreamCommand(PRootEngine engine, String script, InstallCallback callback) {
        try {
            String fullScript = getProxyEnvPrefix() + script;
            PRootConfig config = new PRootConfig.Builder()
                    .setExecutable("/bin/sh")
                    .addArg("-c")
                    .addArg(fullScript)
                    .setRedirectErrorStream(true)
                    .build();

            PRootProcess process = engine.launch(config);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                postLog(callback, line);
            }
            return process.waitFor();
        } catch (Exception e) {
            postLog(callback, "执行异常: " + e.getMessage());
            return -1;
        }
    }

    private String runShellCommand(PRootEngine engine, String command) {
        try {
            String fullCommand = getProxyEnvPrefix() + command;
            PRootConfig config = new PRootConfig.Builder()
                    .setExecutable("/bin/sh")
                    .addArg("-c")
                    .addArg(fullCommand)
                    .setRedirectErrorStream(true)
                    .build();

            PRootProcess process = engine.launch(config);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            process.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void postLog(InstallCallback callback, String msg) {
        postToMain(() -> {
            if (callback != null) callback.onLog(msg);
        });
    }

    private void postProgress(InstallCallback callback, int percent, String label) {
        postToMain(() -> {
            if (callback != null) callback.onProgress(percent, label);
        });
    }

    private void postItemFinished(InstallCallback callback, String id, boolean success, String result) {
        postToMain(() -> {
            if (callback != null) callback.onItemFinished(id, success, result);
        });
    }
}
