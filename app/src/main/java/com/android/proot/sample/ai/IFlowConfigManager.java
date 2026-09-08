package com.android.proot.sample.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.proot.proxy.CnbProxyServer;
import com.android.proot.proxy.ProxyConfig;
import com.android.proot.proxy.WebStudioServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages iFlow AI configuration persistence, container synchronization, and model list discovery.
 */
public final class IFlowConfigManager {
    private static final String TAG = "IFlowConfigManager";
    private static final String PREF_NAME = "iflow_config";

    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL = "model";
    public static final String KEY_YOLO = "yolo";
    public static final String KEY_REASONING_EFFORT = "reasoning_effort";

    public static final String DEFAULT_MODEL = "deepseek-v4-flash";
    public static final String DEFAULT_CNB_KEY = "cnb-free";
    public static final String DEFAULT_REASONING_EFFORT = "high";

    private static volatile IFlowConfigManager sInstance;

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ModelSuccessCallback {
        void onSuccess(List<String> models);
    }

    public interface ModelErrorCallback {
        void onError(String error);
    }

    public static IFlowConfigManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (IFlowConfigManager.class) {
                if (sInstance == null) {
                    sInstance = new IFlowConfigManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private IFlowConfigManager(Context context) {
        this.appContext = context;
    }

    private SharedPreferences getPrefs() {
        return appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getBaseUrl() {
        String url = getPrefs().getString(KEY_BASE_URL, "").trim();
        if (url.isEmpty()) {
            return CnbProxyServer.getInstance().getBaseUrl();
        }
        return url;
    }

    public String getApiKey() {
        String key = getPrefs().getString(KEY_API_KEY, "").trim();
        if (key.isEmpty() && isLocalProxy(getBaseUrl())) {
            return DEFAULT_CNB_KEY;
        }
        return key;
    }

    public String getModel() {
        String m = getPrefs().getString(KEY_MODEL, "").trim();
        if (m.isEmpty()) {
            return DEFAULT_MODEL;
        }
        return m;
    }

    public boolean isAutoApprove() {
        return getPrefs().getBoolean(KEY_YOLO, true);
    }

    public String getReasoningEffort() {
        return getPrefs().getString(KEY_REASONING_EFFORT, DEFAULT_REASONING_EFFORT);
    }

    public void setReasoningEffort(String effort) {
        String eff = effort != null && !effort.trim().isEmpty() ? effort.trim() : DEFAULT_REASONING_EFFORT;
        getPrefs().edit().putString(KEY_REASONING_EFFORT, eff).apply();
    }

    public static boolean isLocalProxy(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("127.0.0.1") || lower.contains("localhost");
    }

    public void saveConfig(String baseUrl, String apiKey, String model, boolean yolo, String reasoningEffort, File rootfsDir) {
        String trimmedUrl = baseUrl != null ? baseUrl.trim() : "";
        String trimmedKey = apiKey != null ? apiKey.trim() : "";
        String trimmedModel = model != null ? model.trim() : "";
        String trimmedEffort = reasoningEffort != null && !reasoningEffort.trim().isEmpty() ? reasoningEffort.trim() : DEFAULT_REASONING_EFFORT;

        if (trimmedKey.isEmpty() && isLocalProxy(trimmedUrl)) {
            trimmedKey = DEFAULT_CNB_KEY;
        }

        getPrefs().edit()
                .putString(KEY_BASE_URL, trimmedUrl)
                .putString(KEY_API_KEY, trimmedKey)
                .putString(KEY_MODEL, trimmedModel)
                .putBoolean(KEY_YOLO, yolo)
                .putString(KEY_REASONING_EFFORT, trimmedEffort)
                .apply();

        syncIFlowConfigToRootfs(rootfsDir);
    }

    public void saveConfig(String baseUrl, String apiKey, String model, boolean yolo, File rootfsDir) {
        saveConfig(baseUrl, apiKey, model, yolo, getReasoningEffort(), rootfsDir);
    }

    public void syncIFlowConfigToRootfs(File rootfsDir) {
        if (rootfsDir == null || !rootfsDir.exists()) {
            return;
        }

        String baseUrl = getBaseUrl();
        String apiKey = getApiKey();
        String model = getModel();

        if (isLocalProxy(baseUrl)) {
            ensureProxyRunningIfNeeded(baseUrl);
        }

        cleanLegacyPigoFiles(rootfsDir);

        File iflowDir = new File(rootfsDir, "root/.iflow");
        iflowDir.mkdirs();
        File configFile = new File(iflowDir, "settings.json");

        String effort = getReasoningEffort();
        boolean enableThinking = !"low".equalsIgnoreCase(effort);

        JSONObject json = readSettingsJson(configFile);
        try {
            json.put("selectedAuthType", "openai-compatible");
            json.put("apiKey", apiKey);
            json.put("baseUrl", baseUrl);
            json.put("modelName", model);
            // Permanently bypass discontinuation/farewell letter notice
            json.put("hasViewedFarewellLetter", true);
            // Coomi-aligned thinking lifecycle configuration
            json.put("thinkingModeEnabled", enableThinking);
            json.put("reasoningEffort", effort);
            // Preserve language setting or default to zh-CN
            if (!json.has("language") || json.optString("language").isEmpty()) {
                json.put("language", "zh-CN");
            }

            try (FileWriter fw = new FileWriter(configFile)) {
                fw.write(json.toString(2));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write ~/.iflow/settings.json", e);
        }

        File envFile = new File(rootfsDir, "root/.iflow.env");
        try (FileWriter fw = new FileWriter(envFile)) {
            fw.write("export LANG=\"zh_CN.UTF-8\"\n");
            fw.write("export LC_ALL=\"zh_CN.UTF-8\"\n");
            fw.write("export LANGUAGE=\"zh_CN:zh\"\n");
            // TrueColor (24-bit) terminal environment
            fw.write("export TERM=\"xterm-256color\"\n");
            fw.write("export COLORTERM=\"truecolor\"\n");
            fw.write("export FORCE_COLOR=\"3\"\n");
            // Thinking mode defaults
            fw.write("export DEFAULT_REASONING_EFFORT=\"" + escapeShell(effort) + "\"\n");
            fw.write("export THINKING_DISPLAY_MODE=\"visible\"\n");
            fw.write("export MAX_THINKING_TOKENS=\"31999\"\n");
            if (!apiKey.isEmpty()) {
                fw.write("export IFLOW_API_KEY=\"" + escapeShell(apiKey) + "\"\n");
                fw.write("export OPENAI_API_KEY=\"" + escapeShell(apiKey) + "\"\n");
            }
            if (!baseUrl.isEmpty()) {
                fw.write("export IFLOW_BASE_URL=\"" + escapeShell(baseUrl) + "\"\n");
                fw.write("export OPENAI_BASE_URL=\"" + escapeShell(baseUrl) + "\"\n");
            }
            if (!model.isEmpty()) {
                fw.write("export IFLOW_MODEL_NAME=\"" + escapeShell(model) + "\"\n");
            }
        } catch (Exception ignored) {}

        ensureEnvSourceInScript(new File(rootfsDir, "root/.bashrc"));
        ensureEnvSourceInScript(new File(rootfsDir, "root/.profile"));
    }

    public static JSONObject readSettingsJson(File configFile) {
        if (configFile != null && configFile.exists() && configFile.length() > 0) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
                }
                return new JSONObject(baos.toString("UTF-8"));
            } catch (Exception ex) {
                Log.w(TAG, "Failed parsing existing settings.json, creating fallback", ex);
            }
        }
        return new JSONObject();
    }

    public static boolean writeSettingsJson(File configFile, JSONObject json) {
        if (configFile == null || json == null) return false;
        try {
            if (configFile.getParentFile() != null) configFile.getParentFile().mkdirs();
            try (FileWriter fw = new FileWriter(configFile)) {
                fw.write(json.toString(2));
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed writing settings.json", e);
            return false;
        }
    }

    private void cleanLegacyPigoFiles(File rootfsDir) {
        try {
            File pigoBin = new File(rootfsDir, "usr/local/bin/pigo");
            if (pigoBin.exists()) pigoBin.delete();

            File piBin = new File(rootfsDir, "usr/local/bin/pi");
            if (piBin.exists()) piBin.delete();

            File pigoEnv = new File(rootfsDir, "root/.pigo.env");
            if (pigoEnv.exists()) pigoEnv.delete();

            File pigoDir = new File(rootfsDir, "root/.pigo");
            deleteRecursive(pigoDir);

            File pigoConfigDir = new File(rootfsDir, "root/.config/pigo");
            deleteRecursive(pigoConfigDir);
        } catch (Exception ignored) {}
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }

    private void ensureEnvSourceInScript(File file) {
        try {
            String content = "";
            if (file.exists()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
                }
                content = baos.toString("UTF-8");
            }
            if (!content.contains(".iflow.env")) {
                try (FileWriter fw = new FileWriter(file, true)) {
                    fw.write("\n[ -f /root/.iflow.env ] && . /root/.iflow.env\n");
                }
            }
        } catch (Exception ignored) {}
    }

    private String escapeShell(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public File getWebDir() {
        return new File(appContext.getFilesDir(), "web");
    }

    public void ensureWebAssetsDeployed() {
        File webDir = getWebDir();
        File indexHtml = new File(webDir, "index.html");
        if (!indexHtml.exists() || indexHtml.length() == 0) {
            try (InputStream is = appContext.getAssets().open("web.zip")) {
                WebStudioServer.deployWebZip(is, webDir);
            } catch (Exception ignored) {}
        }
    }

    public void ensureProxyRunningIfNeeded(String url) {
        if (url == null || url.isEmpty() || isLocalProxy(url)) {
            if (!CnbProxyServer.getInstance().isRunning() && !CnbProxyServer.getInstance().isStarting()) {
                ensureWebAssetsDeployed();
                String effort = getReasoningEffort();
                boolean enableThinking = !"low".equalsIgnoreCase(effort);
                CnbProxyServer.getInstance().startAsync(new ProxyConfig.Builder()
                        .setWebRoot(getWebDir())
                        .setReasoningEffort(effort)
                        .setEnableThinking(enableThinking)
                        .build());
            }
        }
    }

    public void fetchModels(String baseUrl, String apiKey, ModelSuccessCallback onSuccess, ModelErrorCallback onError) {
        executor.execute(() -> {
            try {
                String target = baseUrl != null ? baseUrl.trim() : "";
                while (target.endsWith("/")) {
                    target = target.substring(0, target.length() - 1);
                }
                if (!target.endsWith("/models")) {
                    target = target + "/models";
                }
                URL url = new URL(target);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                if (apiKey != null && !apiKey.trim().isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                }
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    String resp = baos.toString("UTF-8");
                    JSONObject json = new JSONObject(resp);
                    JSONArray data = json.optJSONArray("data");
                    List<String> models = new ArrayList<>();
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject m = data.getJSONObject(i);
                            String id = m.optString("id");
                            if (!id.isEmpty()) models.add(id);
                        }
                    }
                    mainHandler.post(() -> {
                        if (onSuccess != null) onSuccess.onSuccess(models);
                    });
                } else {
                    InputStream es = conn.getErrorStream();
                    String errStr = "";
                    if (es != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[2048];
                        int n;
                        while ((n = es.read(buf)) != -1) baos.write(buf, 0, n);
                        errStr = baos.toString("UTF-8");
                    }
                    final String finalErr = "HTTP " + code + (errStr.isEmpty() ? "" : ": " + errStr);
                    mainHandler.post(() -> {
                        if (onError != null) onError.onError(finalErr);
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (onError != null) onError.onError(e.getMessage() != null ? e.getMessage() : e.toString());
                });
            }
        });
    }
}
