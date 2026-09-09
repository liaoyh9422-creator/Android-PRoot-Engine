package com.android.proot.sample.service;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import com.android.proot.PRootConfig;
import com.android.proot.PRootEngine;
import com.android.proot.PRootProcess;
import com.android.proot.proxy.CnbProxyServer;
import com.android.proot.sample.ai.IFlowConfigManager;
import com.android.proot.sample.ai.WorkspaceManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Unified dispatch hub for AIDL and LocalSocket IPC operations.
 * Implements full six-dimensional standards (fault-tolerance, usability, reliability,
 * robustness, consistency, and maintainability).
 */
public final class IFlowIpcDispatcher {
    private static final String TAG = "IFlowIpcDispatcher";
    private static volatile IFlowIpcDispatcher sInstance;

    private final Context appContext;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public static IFlowIpcDispatcher getInstance(Context context) {
        if (sInstance == null) {
            synchronized (IFlowIpcDispatcher.class) {
                if (sInstance == null) {
                    sInstance = new IFlowIpcDispatcher(context != null ? context.getApplicationContext() : null);
                }
            }
        }
        return sInstance;
    }

    private IFlowIpcDispatcher(Context context) {
        this.appContext = context;
    }

    public boolean isEngineRunning() {
        try {
            PRootEngine engine = PRootEngine.getInstance(appContext);
            return engine != null && engine.isInitialized();
        } catch (Throwable t) {
            Log.w(TAG, "Error checking engine running: " + t.getMessage());
            return false;
        }
    }

    public String getStatusSummary() {
        try {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("pid", Process.myPid());
            json.put("package", appContext.getPackageName());

            PRootEngine engine = PRootEngine.getInstance(appContext);
            boolean engineInit = engine != null && engine.isInitialized();
            json.put("engine_initialized", engineInit);

            File rootfs = engine != null ? engine.getRootfsDir() : null;
            json.put("rootfs_exists", rootfs != null && rootfs.exists());

            String distro = "Unknown";
            if (rootfs != null) {
                if (new File(rootfs, "etc/alpine-release").exists()) distro = "Alpine Linux";
                else if (new File(rootfs, "etc/debian_version").exists()) distro = "Debian 12";
            }
            json.put("distro", distro);

            WorkspaceManager wm = WorkspaceManager.getInstance(appContext);
            String ws = wm != null ? wm.getActiveWorkspace() : "/root";
            json.put("active_workspace", ws);

            SshServerManager ssh = SshServerManager.getInstance();
            JSONObject sshObj = new JSONObject();
            sshObj.put("running", ssh.isRunning());
            sshObj.put("port", ssh.getPort());
            sshObj.put("type", ssh.getSshBinaryType(rootfs));
            json.put("ssh", sshObj);

            CnbProxyServer proxy = CnbProxyServer.getInstance();
            JSONObject srvObj = new JSONObject();
            srvObj.put("proxy_running", proxy.isRunning());
            srvObj.put("proxy_port", proxy.getActualPort());
            srvObj.put("web_running", proxy.getWebServer() != null);
            srvObj.put("web_port", proxy.getWebPort());
            json.put("services", srvObj);

            IFlowConfigManager cfg = IFlowConfigManager.getInstance(appContext);
            JSONObject llmObj = new JSONObject();
            llmObj.put("base_url", cfg.getBaseUrl());
            llmObj.put("model", cfg.getModel());
            llmObj.put("reasoning_effort", cfg.getReasoningEffort());
            llmObj.put("thinking_enabled", !"off".equalsIgnoreCase(cfg.getReasoningEffort()));
            json.put("llm", llmObj);

            return json.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + escapeJson(t.getMessage()) + "\"}";
        }
    }

    public Bundle getServicePortsBundle() {
        Bundle bundle = new Bundle();
        try {
            SshServerManager ssh = SshServerManager.getInstance();
            CnbProxyServer proxy = CnbProxyServer.getInstance();
            WorkspaceManager wm = WorkspaceManager.getInstance(appContext);
            PRootEngine engine = PRootEngine.getInstance(appContext);
            File rootfs = engine != null ? engine.getRootfsDir() : null;

            bundle.putInt("ssh_port", ssh.getPort());
            bundle.putBoolean("ssh_running", ssh.isRunning());
            bundle.putString("ssh_type", ssh.getSshBinaryType(rootfs));

            bundle.putInt("proxy_port", proxy.getActualPort());
            bundle.putBoolean("proxy_running", proxy.isRunning());

            bundle.putInt("web_port", proxy.getWebPort());
            bundle.putBoolean("web_running", proxy.getWebServer() != null);

            bundle.putString("active_workspace", wm != null ? wm.getActiveWorkspace() : "/root");
            bundle.putBoolean("engine_running", isEngineRunning());
        } catch (Throwable t) {
            Log.w(TAG, "Error building ports bundle: " + t.getMessage());
        }
        return bundle;
    }

    public String executeCommand(String command, String cwd, int timeoutMs) {
        if (command == null || command.trim().isEmpty()) {
            return "Error: Empty command specified";
        }
        final int effectiveTimeout = Math.min(Math.max(timeoutMs, 1000), 180000); // 1s ~ 3min

        WorkspaceManager wm = WorkspaceManager.getInstance(appContext);
        String targetCwd = (cwd != null && !cwd.trim().isEmpty() && cwd.startsWith("/"))
                ? cwd.trim()
                : (wm != null ? wm.getActiveWorkspace() : "/root");

        Callable<String> task = () -> {
            PRootEngine engine = PRootEngine.getInstance(appContext);
            if (!engine.isInitialized()) {
                boolean ok = engine.initialize();
                if (!ok) return "Error: Failed to initialize PRootEngine";
            }

            File rootfs = engine.getRootfsDir();
            String sh = (rootfs != null && new File(rootfs, "bin/bash").exists()) ? "/bin/bash" : "/bin/sh";

            PRootConfig config = new PRootConfig.Builder()
                    .setExecutable(sh)
                    .addArgs("-c", command)
                    .setWorkDir(targetCwd)
                    .setFakeRoot(true)
                    .setRedirectErrorStream(true)
                    .build();

            PRootProcess proc = engine.launch(config);
            return drainProcessWithTimeout(proc, effectiveTimeout);
        };

        Future<String> future = executor.submit(task);
        try {
            return future.get(effectiveTimeout + 2000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            return "Error: Command timed out after " + effectiveTimeout + "ms";
        } catch (Throwable t) {
            return "Error executing command: " + t.getMessage();
        }
    }

    public String getActiveWorkspace() {
        try {
            WorkspaceManager wm = WorkspaceManager.getInstance(appContext);
            return wm != null ? wm.getActiveWorkspace() : "/root";
        } catch (Throwable t) {
            return "/root";
        }
    }

    public boolean setActiveWorkspace(String path) {
        if (path == null || path.trim().isEmpty() || !path.startsWith("/")) return false;
        try {
            WorkspaceManager wm = WorkspaceManager.getInstance(appContext);
            if (wm != null) {
                wm.setActiveWorkspace(path.trim());
            }
            CnbProxyServer.getInstance().setCwd(path.trim());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Error setting active workspace: " + t.getMessage());
            return false;
        }
    }

    public boolean controlService(String serviceName, String action) {
        if (serviceName == null || action == null) return false;
        String s = serviceName.trim().toLowerCase();
        String a = action.trim().toLowerCase();

        try {
            if ("ssh".equals(s)) {
                SshServerManager ssh = SshServerManager.getInstance();
                PRootEngine engine = PRootEngine.getInstance(appContext);
                if ("start".equals(a)) {
                    ssh.startServer(engine, null);
                    return true;
                } else if ("stop".equals(a)) {
                    ssh.stopServer(engine, null);
                    return true;
                } else if ("restart".equals(a)) {
                    ssh.stopServer(engine, (ok, m) -> ssh.startServer(engine, null));
                    return true;
                }
            } else if ("web".equals(s) || "proxy".equals(s)) {
                CnbProxyServer proxy = CnbProxyServer.getInstance();
                if ("start".equals(a)) {
                    proxy.startAsync(new com.android.proot.proxy.ProxyConfig.Builder().build());
                    return true;
                } else if ("stop".equals(a)) {
                    proxy.stop();
                    return true;
                } else if ("restart".equals(a)) {
                    proxy.stop();
                    proxy.startAsync(new com.android.proot.proxy.ProxyConfig.Builder().build());
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error controlling service " + serviceName + ": " + t.getMessage());
        }
        return false;
    }

    public boolean updateLlmConfig(String baseUrl, String apiKey, String model, String reasoningEffort) {
        try {
            IFlowConfigManager cm = IFlowConfigManager.getInstance(appContext);
            String b = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl.trim() : cm.getBaseUrl();
            String k = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey.trim() : cm.getApiKey();
            String m = (model != null && !model.trim().isEmpty()) ? model.trim() : cm.getModel();
            String eff = (reasoningEffort != null && !reasoningEffort.trim().isEmpty()) ? reasoningEffort.trim() : cm.getReasoningEffort();
            PRootEngine engine = PRootEngine.getInstance(appContext);

            cm.saveConfig(b, k, m, cm.isAutoApprove(), eff, engine != null ? engine.getRootfsDir() : null);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Error updating LLM config: " + t.getMessage());
            return false;
        }
    }

    public String getLlmConfigJson() {
        try {
            IFlowConfigManager cm = IFlowConfigManager.getInstance(appContext);
            JSONObject obj = new JSONObject();
            obj.put("ok", true);
            obj.put("base_url", cm.getBaseUrl());
            obj.put("api_key", cm.getApiKey());
            obj.put("model", cm.getModel());
            obj.put("reasoning_effort", cm.getReasoningEffort());
            obj.put("thinking_enabled", !"off".equalsIgnoreCase(cm.getReasoningEffort()));
            return obj.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + escapeJson(t.getMessage()) + "\"}";
        }
    }

    /**
     * Handles single-line JSON string request from LocalSocket (or Termux CLI).
     */
    public String handleJsonRequest(String requestJson) {
        try {
            if (requestJson == null || requestJson.trim().isEmpty()) {
                return "{\"ok\":false,\"error\":\"Empty request\"}";
            }
            JSONObject req = new JSONObject(requestJson.trim());
            String action = req.optString("action", req.optString("cmd", "status")).trim().toLowerCase();

            switch (action) {
                case "status":
                    return getStatusSummary();
                case "ports": {
                    JSONObject p = new JSONObject();
                    p.put("ok", true);
                    SshServerManager ssh = SshServerManager.getInstance();
                    CnbProxyServer proxy = CnbProxyServer.getInstance();
                    p.put("ssh_port", ssh.getPort());
                    p.put("ssh_running", ssh.isRunning());
                    p.put("proxy_port", proxy.getActualPort());
                    p.put("proxy_running", proxy.isRunning());
                    p.put("web_port", proxy.getWebPort());
                    p.put("web_running", proxy.getWebServer() != null);
                    return p.toString();
                }
                case "exec": {
                    String command = req.optString("command", req.optString("exec", ""));
                    String cwd = req.optString("cwd", "");
                    int timeout = req.optInt("timeout", 30000);
                    String out = executeCommand(command, cwd, timeout);
                    JSONObject res = new JSONObject();
                    res.put("ok", !out.startsWith("Error:"));
                    res.put("output", out);
                    return res.toString();
                }
                case "workspace": {
                    if (req.has("path")) {
                        boolean ok = setActiveWorkspace(req.optString("path"));
                        return "{\"ok\":" + ok + ",\"workspace\":\"" + getActiveWorkspace() + "\"}";
                    }
                    return "{\"ok\":true,\"workspace\":\"" + getActiveWorkspace() + "\"}";
                }
                case "service": {
                    String sName = req.optString("name", req.optString("service", ""));
                    String sAction = req.optString("op", req.optString("state", "start"));
                    boolean ok = controlService(sName, sAction);
                    return "{\"ok\":" + ok + "}";
                }
                case "config": {
                    if (req.has("base_url") || req.has("model") || req.has("api_key") || req.has("reasoning_effort")) {
                        boolean ok = updateLlmConfig(
                                req.optString("base_url", null),
                                req.optString("api_key", null),
                                req.optString("model", null),
                                req.optString("reasoning_effort", null)
                        );
                        return "{\"ok\":" + ok + "}";
                    }
                    return getLlmConfigJson();
                }
                default:
                    return "{\"ok\":false,\"error\":\"Unknown action: " + escapeJson(action) + "\"}";
            }
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + escapeJson(t.getMessage()) + "\"}";
        }
    }

    private String drainProcessWithTimeout(PRootProcess proc, int timeoutMs) {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;

        try {
            InputStream is = proc.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            char[] buffer = new char[1024];

            while (System.currentTimeMillis() < deadline) {
                while (reader.ready()) {
                    int read = reader.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        sb.append(buffer, 0, read);
                    } else if (read == -1) {
                        break;
                    }
                }

                try {
                    int exit = proc.exitValue();
                    // Process terminated, read any remaining bytes
                    int remaining;
                    while ((remaining = reader.read(buffer, 0, buffer.length)) != -1) {
                        sb.append(buffer, 0, remaining);
                    }
                    return sb.toString();
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(50);
                }
            }

            // Timed out
            proc.destroy();
            sb.append("\n[Process terminated by timeout (").append(timeoutMs).append("ms)]");
        } catch (Throwable t) {
            sb.append("\n[Exception reading output: ").append(t.getMessage()).append("]");
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
