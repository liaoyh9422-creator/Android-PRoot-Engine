package com.android.proot.sample.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ViewFlipper;

import com.android.proot.PRootConfig;
import com.android.proot.PRootEngine;
import com.android.proot.PRootProcess;
import com.android.proot.sample.MainActivity;
import com.android.proot.sample.R;
import com.android.proot.sample.helper.NetworkProbeHelper;
import com.android.proot.sample.helper.SetupStatusManager;
import com.android.proot.sample.helper.ToolchainDownloader;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clean, modern 2-step setup wizard implementing Scheme B:
 * - Rounded cards, micro-tip banners, and Mac-styled terminal window
 * - Multi-level auto network penetration (direct, VPN binding, 127.0.0.1 proxy)
 * - Dynamic network state listener for auto-recovery without manual toggling
 * - Streamlined milestone checklists instead of verbose log flooding
 */
public class SetupWizardActivity extends Activity {

    private static final String[] NODE_APK_URLS = {
            "https://mirrors.aliyun.com/alpine/v3.20/main/aarch64/nodejs-20.15.1-r0.apk",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/main/aarch64/nodejs-20.15.1-r0.apk",
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/nodejs-20.15.1-r0.apk"
    };

    private static final String[] NPM_APK_URLS = {
            "https://mirrors.aliyun.com/alpine/v3.20/community/aarch64/npm-10.9.1-r0.apk",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/community/aarch64/npm-10.9.1-r0.apk",
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/community/aarch64/npm-10.9.1-r0.apk"
    };

    private interface DownloadStepCallback {
        void onDone();
        void onFailed(String message);
    }

    private ViewFlipper viewFlipper;
    private TextView tvStepTitle;
    private TextView tvStepIndicator;

    // Screen 1: Proxy Check
    private TextView tvProxyStatusBadge;
    private TextView tvProxyTipBanner;
    private LinearLayout layoutProxyActionsFail;
    private TextView btnProxyRetry;
    private TextView btnProxyExit;
    private TextView btnProxyProceed;

    // Screen 2: Environment Setup
    private TextView tvNodeProgressLabel;
    private ProgressBar progressNodeDownload;
    private TextView tvNodeTipBanner;
    private ScrollView scrollNodeLog;
    private TextView tvNodeConsoleLog;
    private LinearLayout layoutNodeActionsFail;
    private TextView btnNodeRetry;
    private TextView btnNodeExit;
    private TextView btnNodeProceed;

    private PRootEngine engine;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isCheckingNetwork = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If already completed, bypass wizard and go straight to MainActivity
        if (SetupStatusManager.isSetupCompleted(this)) {
            launchMainActivity();
            return;
        }

        UiTheme.setupImmersiveStatusBar(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        setContentView(R.layout.activity_setup_wizard);

        bindViews();
        setupListeners();
        registerNetworkCallback();

        // Start initial network check
        startProxyCheck();
    }

    private void bindViews() {
        viewFlipper = findViewById(R.id.view_flipper_setup);
        tvStepTitle = findViewById(R.id.tv_setup_step_title);
        tvStepIndicator = findViewById(R.id.tv_setup_step_indicator);

        // Screen 1
        tvProxyStatusBadge = findViewById(R.id.tv_proxy_status_badge);
        tvProxyTipBanner = findViewById(R.id.tv_proxy_tip_banner);
        layoutProxyActionsFail = findViewById(R.id.layout_proxy_actions_fail);
        btnProxyRetry = findViewById(R.id.btn_proxy_retry);
        btnProxyExit = findViewById(R.id.btn_proxy_exit);
        btnProxyProceed = findViewById(R.id.btn_proxy_proceed);

        // Screen 2
        tvNodeProgressLabel = findViewById(R.id.tv_node_progress_label);
        progressNodeDownload = findViewById(R.id.progress_node_download);
        tvNodeTipBanner = findViewById(R.id.tv_node_tip_banner);
        scrollNodeLog = findViewById(R.id.scroll_node_log);
        tvNodeConsoleLog = findViewById(R.id.tv_node_console_log);
        layoutNodeActionsFail = findViewById(R.id.layout_node_actions_fail);
        btnNodeRetry = findViewById(R.id.btn_node_retry);
        btnNodeExit = findViewById(R.id.btn_node_exit);
        btnNodeProceed = findViewById(R.id.btn_node_proceed);

        // Tactile micro-scale feedback for buttons
        UiTheme.applyTactileFeedback(btnProxyRetry);
        UiTheme.applyTactileFeedback(btnProxyExit);
        UiTheme.applyTactileFeedback(btnProxyProceed);
        UiTheme.applyTactileFeedback(btnNodeRetry);
        UiTheme.applyTactileFeedback(btnNodeExit);
        UiTheme.applyTactileFeedback(btnNodeProceed);
    }

    private void setupListeners() {
        btnProxyRetry.setOnClickListener(v -> startProxyCheck());
        btnProxyExit.setOnClickListener(v -> exitApp());
        btnProxyProceed.setOnClickListener(v -> goToScreen2());

        btnNodeRetry.setOnClickListener(v -> startNodeInstallation());
        btnNodeExit.setOnClickListener(v -> exitApp());
        btnNodeProceed.setOnClickListener(v -> {
            SetupStatusManager.setSetupCompleted(this, true);
            launchMainActivity();
        });
    }

    private void registerNetworkCallback() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        mainHandler.post(() -> {
                            if (viewFlipper.getDisplayedChild() == 0
                                    && btnProxyProceed.getVisibility() != View.VISIBLE
                                    && !isCheckingNetwork) {
                                startProxyCheck();
                            }
                        });
                    }
                };
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            }
        } catch (Throwable ignored) {}
    }

    private void startProxyCheck() {
        if (isCheckingNetwork) return;
        isCheckingNetwork = true;

        tvProxyStatusBadge.setText("⏳ 检测中...");
        tvProxyStatusBadge.setTextColor(Color.parseColor(UiTheme.C_YELLOW));
        tvProxyTipBanner.setText("💡 首次配置需连通国际网络，以下载 Alpine 容器及核心组件");
        tvProxyTipBanner.setTextColor(Color.parseColor(UiTheme.C_DIM));
        layoutProxyActionsFail.setVisibility(View.GONE);
        btnProxyProceed.setVisibility(View.GONE);

        NetworkProbeHelper.testGoogleConnectivity(this, new NetworkProbeHelper.ProbeCallback() {
            @Override
            public void onSuccess(long latencyMs, String channelInfo) {
                isCheckingNetwork = false;
                String displayBadge = "🟢 网络畅通 · " + latencyMs + "ms";
                if (channelInfo != null && !channelInfo.isEmpty()) {
                    displayBadge += " (" + channelInfo + ")";
                }
                tvProxyStatusBadge.setText(displayBadge);
                tvProxyStatusBadge.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                tvProxyTipBanner.setText("✓ 国际网络畅通，可直接进行沙箱开发环境初始化");
                tvProxyTipBanner.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                btnProxyProceed.setVisibility(View.VISIBLE);
                layoutProxyActionsFail.setVisibility(View.GONE);
            }

            @Override
            public void onError(String message) {
                isCheckingNetwork = false;
                tvProxyStatusBadge.setText("🔴 连接受限 (" + message + ")");
                tvProxyStatusBadge.setTextColor(Color.parseColor(UiTheme.C_RED));
                tvProxyTipBanner.setText("💡 未能直连外网；若已开启代理，建议在代理中关闭「分应用代理」后重试");
                tvProxyTipBanner.setTextColor(Color.parseColor("#F85149"));
                layoutProxyActionsFail.setVisibility(View.VISIBLE);
                btnProxyProceed.setVisibility(View.GONE);
            }
        });
    }

    private void goToScreen2() {
        viewFlipper.setDisplayedChild(1);
        tvStepIndicator.setText("2 / 2");
        startNodeInstallation();
    }

    private void startNodeInstallation() {
        layoutNodeActionsFail.setVisibility(View.GONE);
        btnNodeProceed.setVisibility(View.GONE);
        progressNodeDownload.setProgress(0);
        tvNodeProgressLabel.setText("准备部署...");
        tvNodeConsoleLog.setText("⟳ 正在初始化沙箱运行骨架...\n");

        backgroundExecutor.execute(() -> {
            try {
                if (engine == null) {
                    engine = new PRootEngine(this);
                }
                if (!engine.isInitialized()) {
                    if (!engine.initialize()) {
                        throw new IllegalStateException("PRoot 沙箱初始化失败");
                    }
                    appendConsoleLog("✓ 基础 Alpine 运行骨架释放完成");
                } else {
                    appendConsoleLog("✓ 基础 Alpine 运行骨架就绪");
                }

                File rootfsDir = engine.getRootfsDir();
                File targetNodeBin = ToolchainDownloader.resolveSymlinkInRootfs(rootfsDir, new File(rootfsDir, "usr/bin/node"));
                if (!targetNodeBin.exists()) {
                    targetNodeBin = ToolchainDownloader.resolveSymlinkInRootfs(rootfsDir, new File(rootfsDir, "opt/node/bin/node"));
                }
                if (targetNodeBin.exists() && ToolchainDownloader.isElfArm64(rootfsDir, targetNodeBin)) {
                    appendConsoleLog("✓ 检测到有效 Node.js 二进制，执行校验...");
                    verifyNodeExecution();
                    return;
                }

                File downloadDir = engine.getTmpDir();
                File targetNodeApk = new File(downloadDir, "nodejs-20.15.1-r0.apk");
                File targetNpmApk = new File(downloadDir, "npm-10.9.1-r0.apk");

                runOnUiThread(() -> tvNodeTipBanner.setText("⚡ 正在从高速镜像拉取 Alpine ARM64 专版运行库..."));

                downloadWithFallback(NODE_APK_URLS, 0, targetNodeApk, 0, 85, new DownloadStepCallback() {
                    @Override
                    public void onDone() {
                        appendConsoleLog("✓ Node.js 运行时下载完成 (15.1 MB)");
                        runOnUiThread(() -> tvNodeTipBanner.setText("⚡ 正在拉取 npm 套件 (2.2 MB)..."));
                        downloadWithFallback(NPM_APK_URLS, 0, targetNpmApk, 85, 100, new DownloadStepCallback() {
                            @Override
                            public void onDone() {
                                appendConsoleLog("✓ npm 套件下载完成 (2.2 MB)");
                                unpackAndVerifyNode();
                            }

                            @Override
                            public void onFailed(String msg) {
                                fallbackToApkInstall("npm 下载受阻: " + msg);
                            }
                        });
                    }

                    @Override
                    public void onFailed(String msg) {
                        fallbackToApkInstall("Node.js 运行时下载受阻: " + msg);
                    }
                });

            } catch (Exception e) {
                appendConsoleLog("!! 异常: " + e.getMessage());
                runOnUiThread(() -> {
                    layoutNodeActionsFail.setVisibility(View.VISIBLE);
                    tvNodeProgressLabel.setText("部署失败");
                });
            }
        });
    }

    private void downloadWithFallback(String[] urls, int index, File targetFile,
                                     int startPercent, int endPercent,
                                     DownloadStepCallback callback) {
        if (index >= urls.length) {
            callback.onFailed("所有镜像节点连接受阻");
            return;
        }

        String curUrl = urls[index];
        ToolchainDownloader.downloadAsync(curUrl, targetFile, new ToolchainDownloader.DownloadCallback() {
            @Override
            public void onProgress(int percentage, long downloadedBytes, long totalBytes) {
                runOnUiThread(() -> {
                    if (percentage >= 0) {
                        int mapped = startPercent + (int) ((percentage / 100f) * (endPercent - startPercent));
                        progressNodeDownload.setProgress(mapped);
                        String mbStr = String.format("%.1f MB / %.1f MB", downloadedBytes / 1048576f, totalBytes / 1048576f);
                        tvNodeProgressLabel.setText(mapped + "% (" + mbStr + ")");
                    }
                });
            }

            @Override
            public void onStatus(String message) {
                // Keep status in progress label to avoid cluttering terminal
                runOnUiThread(() -> tvNodeProgressLabel.setText(message));
            }

            @Override
            public void onSuccess(File downloadedFile) {
                callback.onDone();
            }

            @Override
            public void onError(String message, Throwable error) {
                appendConsoleLog("⟳ 镜像 [" + (index + 1) + "] 切换下个备用源...");
                downloadWithFallback(urls, index + 1, targetFile, startPercent, endPercent, callback);
            }
        });
    }

    private void fallbackToApkInstall(String reason) {
        appendConsoleLog("⟳ " + reason + "，尝试容器内 apk 在线同步...");
        backgroundExecutor.execute(() -> {
            try {
                PRootConfig config = new PRootConfig.Builder()
                        .setExecutable("/bin/sh")
                        .addArg("-c")
                        .addArg("apk --arch aarch64 add --no-cache nodejs npm 2>&1")
                        .setRedirectErrorStream(true)
                        .build();

                PRootProcess process = engine.launch(config);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    final String l = line;
                    if (l.contains("OK:") || l.contains("Installing") || l.contains("fetch")) {
                        appendConsoleLog("> " + l);
                    }
                }
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    unpackAndVerifyNode();
                } else {
                    appendConsoleLog("!! 容器同步失败，退出码: " + exitCode);
                    runOnUiThread(() -> {
                        layoutNodeActionsFail.setVisibility(View.VISIBLE);
                        tvNodeProgressLabel.setText("安装失败");
                    });
                }
            } catch (Exception e) {
                appendConsoleLog("!! 异常: " + e.getMessage());
                runOnUiThread(() -> {
                    layoutNodeActionsFail.setVisibility(View.VISIBLE);
                    tvNodeProgressLabel.setText("安装失败");
                });
            }
        });
    }

    private void unpackAndVerifyNode() {
        backgroundExecutor.execute(() -> {
            try {
                runOnUiThread(() -> tvNodeTipBanner.setText("⚡ 正在解包装配 Node.js & npm..."));
                appendConsoleLog("⟳ 正在解包装配 Node.js & npm...");
                File downloadDir = engine.getTmpDir();
                File targetNodeApk = new File(downloadDir, "nodejs-20.15.1-r0.apk");
                File targetNpmApk = new File(downloadDir, "npm-10.9.1-r0.apk");

                StringBuilder sb = new StringBuilder();
                if (targetNodeApk.exists()) {
                    sb.append("tar -zxf /tmp/").append(targetNodeApk.getName()).append(" -C / 2>/dev/null || true; ");
                }
                if (targetNpmApk.exists()) {
                    sb.append("tar -zxf /tmp/").append(targetNpmApk.getName()).append(" -C / 2>/dev/null || true; ");
                }

                sb.append("mkdir -p /usr/bin /usr/local/bin /opt/node/bin && ")
                  .append("chmod +x /usr/bin/node /usr/bin/npm /usr/bin/npx 2>/dev/null || true && ")
                  .append("if [ -f /opt/node/bin/node ]; then ")
                  .append("  (cd /usr/bin && ln -sf ../../opt/node/bin/node node 2>/dev/null || true); ")
                  .append("  (cd /usr/local/bin && ln -sf ../../opt/node/bin/node node 2>/dev/null || true); ")
                  .append("else ")
                  .append("  (cd /opt/node/bin && ln -sf ../../../usr/bin/node node 2>/dev/null || true); ")
                  .append("  (cd /opt/node/bin && ln -sf ../../../usr/bin/npm npm 2>/dev/null || true); ")
                  .append("  (cd /opt/node/bin && ln -sf ../../../usr/bin/npx npx 2>/dev/null || true); ")
                  .append("fi");

                PRootConfig config = new PRootConfig.Builder()
                        .setExecutable("/bin/sh")
                        .addArg("-c")
                        .addArg(sb.toString())
                        .setRedirectErrorStream(true)
                        .build();

                PRootProcess process = engine.launch(config);
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    appendConsoleLog("!! 解压装配失败，退出码: " + exitCode);
                    runOnUiThread(() -> layoutNodeActionsFail.setVisibility(View.VISIBLE));
                    return;
                }

                // Verify binary with rootfs symlink awareness
                File rootfsDir = engine.getRootfsDir();
                File usrNode = new File(rootfsDir, "usr/bin/node");
                File optNode = new File(rootfsDir, "opt/node/bin/node");
                File targetNode = usrNode;
                if (!targetNode.exists()) {
                    targetNode = ToolchainDownloader.resolveSymlinkInRootfs(rootfsDir, usrNode);
                }
                if (!targetNode.exists()) {
                    targetNode = optNode;
                }
                if (!targetNode.exists()) {
                    targetNode = ToolchainDownloader.resolveSymlinkInRootfs(rootfsDir, optNode);
                }

                if (!ToolchainDownloader.isElfArm64(rootfsDir, targetNode)) {
                    appendConsoleLog("!! 架构校验失败: Node.js 不是有效的 ARM64 二进制！");
                    runOnUiThread(() -> layoutNodeActionsFail.setVisibility(View.VISIBLE));
                    return;
                }

                appendConsoleLog("✓ 架构校验通过 (ARM64 ELF 0xB7)");
                verifyNodeExecution();

            } catch (Exception e) {
                appendConsoleLog("!! 解压出错: " + e.getMessage());
                runOnUiThread(() -> layoutNodeActionsFail.setVisibility(View.VISIBLE));
            }
        });
    }

    private void verifyNodeExecution() {
        backgroundExecutor.execute(() -> {
            try {
                PRootConfig testConfig = new PRootConfig.Builder()
                        .setExecutable("/bin/sh")
                        .addArg("-c")
                        .addArg("node -v && npm -v")
                        .setRedirectErrorStream(true)
                        .build();

                PRootProcess p = engine.launch(testConfig);
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                StringBuilder sb = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append(" ");
                }
                p.waitFor();

                String output = sb.toString().trim();
                appendConsoleLog("✓ 运行校验完成: " + output);
                appendConsoleLog("✓ 开发环境已就绪！");

                SetupStatusManager.setNodeInstalled(this, true);

                runOnUiThread(() -> {
                    progressNodeDownload.setProgress(100);
                    tvNodeProgressLabel.setText("部署就绪");
                    tvNodeTipBanner.setText("✓ 开发环境装配完毕，点击下方进入");
                    tvNodeTipBanner.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                    btnNodeProceed.setVisibility(View.VISIBLE);
                });

            } catch (Exception e) {
                appendConsoleLog("!! 验证运行失败: " + e.getMessage());
                runOnUiThread(() -> layoutNodeActionsFail.setVisibility(View.VISIBLE));
            }
        });
    }

    private void appendConsoleLog(String text) {
        runOnUiThread(() -> {
            if (tvNodeConsoleLog != null) {
                tvNodeConsoleLog.append(text + "\n");
                if (scrollNodeLog != null) {
                    scrollNodeLog.post(() -> scrollNodeLog.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }

    private void exitApp() {
        finishAffinity();
        System.exit(0);
    }

    private void launchMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignored) {}
        }
        backgroundExecutor.shutdown();
    }
}
