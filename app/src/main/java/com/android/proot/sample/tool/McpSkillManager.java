package com.android.proot.sample.tool;

import android.content.Context;
import android.util.Log;

import com.android.proot.sample.ai.IFlowConfigManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages Model Context Protocol (MCP) server definitions and custom Skills for iFlow CLI.
 * Bi-directionally syncs with ~/.iflow/settings.json (mcpServers), ~/.iflow/rules, and ~/.iflow/skills/.
 */
public final class McpSkillManager {
    private static final String TAG = "McpSkillManager";

    public interface PingCallback {
        void onResult(boolean success, int httpCode, long latencyMs, String detail);
    }

    public static class ImportResult {
        public int importedCount = 0;
        public int updatedCount = 0;
        public int skippedCount = 0;
        public boolean success = true;
        public String message = "";
    }

    public static class McpServer {
        public String name;
        public String type = "stdio"; // "stdio", "sse", "httpStream"
        public String command = "";
        public List<String> args = new ArrayList<>();
        public String url = "";
        public Map<String, String> headers = new LinkedHashMap<>();
        public boolean enabled = true;

        public McpServer(String name, String command) {
            this.name = name != null ? name.trim() : "";
            this.command = command != null ? command.trim() : "";
            this.type = "stdio";
        }

        public McpServer(String name, String type, String url) {
            this.name = name != null ? name.trim() : "";
            this.type = (type != null && !type.trim().isEmpty()) ? type.trim().toLowerCase() : "sse";
            this.url = url != null ? url.trim() : "";
        }

        public boolean isRemote() {
            if ("sse".equalsIgnoreCase(type) || "httpStream".equalsIgnoreCase(type)) {
                return true;
            }
            return url != null && !url.trim().isEmpty() && (command == null || command.trim().isEmpty());
        }

        public String getEffectiveType() {
            if (isRemote()) {
                return (type != null && !type.trim().isEmpty()) ? type.trim().toLowerCase() : "sse";
            }
            return "stdio";
        }
    }

    public static class Skill {
        public String id;
        public String title;
        public String description;
        public String prompt;

        public Skill(String id, String title, String description, String prompt) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.prompt = prompt;
        }
    }

    private static volatile McpSkillManager sInstance;

    public static McpSkillManager getInstance() {
        if (sInstance == null) {
            synchronized (McpSkillManager.class) {
                if (sInstance == null) {
                    sInstance = new McpSkillManager();
                }
            }
        }
        return sInstance;
    }

    private McpSkillManager() {}

    // =========================================================================
    // MCP Servers Management
    // =========================================================================

    private final ExecutorService pingExecutor = Executors.newCachedThreadPool();

    public List<McpServer> loadMcpServers(File rootfsDir) {
        List<McpServer> list = new ArrayList<>();
        if (rootfsDir == null || !rootfsDir.exists()) return list;

        File configFile = new File(rootfsDir, "root/.iflow/settings.json");
        JSONObject json = IFlowConfigManager.readSettingsJson(configFile);
        JSONObject mcpObj = json.optJSONObject("mcpServers");

        if (mcpObj != null) {
            Iterator<String> keys = mcpObj.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject item = mcpObj.optJSONObject(name);
                if (item != null) {
                    McpServer s = parseMcpServerJson(name, item);
                    if (s != null) {
                        list.add(s);
                    }
                }
            }
        }

        // If list is empty, pre-populate standard recommended templates
        if (list.isEmpty()) {
            list.addAll(getRecommendedMcpTemplates());
            saveMcpServers(rootfsDir, list);
        }

        return list;
    }

    public static McpServer parseMcpServerJson(String name, JSONObject item) {
        if (item == null) return null;
        String cmd = item.optString("command", "");
        String url = item.optString("url", "");
        String type = item.optString("type", item.optString("transport", ""));

        McpServer s = new McpServer(name, cmd);
        s.url = url;
        s.enabled = !item.optBoolean("disabled", false);

        if (!type.isEmpty()) {
            s.type = type.toLowerCase();
        } else if (!url.isEmpty()) {
            s.type = "sse";
        } else {
            s.type = "stdio";
        }

        JSONArray argsArr = item.optJSONArray("args");
        if (argsArr != null) {
            for (int i = 0; i < argsArr.length(); i++) {
                s.args.add(argsArr.optString(i));
            }
        }

        JSONObject headersObj = item.optJSONObject("headers");
        if (headersObj != null) {
            Iterator<String> hKeys = headersObj.keys();
            while (hKeys.hasNext()) {
                String hk = hKeys.next();
                s.headers.put(hk, headersObj.optString(hk));
            }
        }

        return s;
    }

    public List<McpServer> getRecommendedMcpTemplates() {
        List<McpServer> presets = new ArrayList<>();

        McpServer fs = new McpServer("filesystem", "npx");
        fs.args.add("-y");
        fs.args.add("@modelcontextprotocol/server-filesystem");
        fs.args.add("/workspace");
        presets.add(fs);

        McpServer fetch = new McpServer("fetch", "uvx");
        fetch.args.add("mcp-server-fetch");
        presets.add(fetch);

        McpServer adb = new McpServer("adb-bridge", "adb");
        adb.args.add("devices");
        presets.add(adb);

        McpServer remoteSse = new McpServer("cloud-sse-demo", "sse", "https://api.example.com/mcp/sse");
        remoteSse.enabled = false;
        remoteSse.headers.put("Authorization", "Bearer your-token-here");
        presets.add(remoteSse);

        return presets;
    }

    public boolean saveMcpServers(File rootfsDir, List<McpServer> servers) {
        if (rootfsDir == null || !rootfsDir.exists()) return false;
        File configFile = new File(rootfsDir, "root/.iflow/settings.json");
        JSONObject json = IFlowConfigManager.readSettingsJson(configFile);

        try {
            JSONObject mcpObj = new JSONObject();
            if (servers != null) {
                for (McpServer s : servers) {
                    if (s == null || s.name == null || s.name.trim().isEmpty()) continue;
                    JSONObject item = new JSONObject();
                    item.put("disabled", !s.enabled);

                    if (s.isRemote()) {
                        item.put("type", s.getEffectiveType());
                        item.put("url", s.url != null ? s.url.trim() : "");
                        if (s.headers != null && !s.headers.isEmpty()) {
                            JSONObject hObj = new JSONObject();
                            for (Map.Entry<String, String> e : s.headers.entrySet()) {
                                hObj.put(e.getKey(), e.getValue());
                            }
                            item.put("headers", hObj);
                        }
                    } else {
                        item.put("type", "stdio");
                        item.put("command", s.command != null ? s.command.trim() : "");
                        JSONArray arr = new JSONArray();
                        if (s.args != null) {
                            for (String a : s.args) arr.put(a);
                        }
                        item.put("args", arr);
                    }
                    mcpObj.put(s.name.trim(), item);
                }
            }
            json.put("mcpServers", mcpObj);
            return IFlowConfigManager.writeSettingsJson(configFile, json);
        } catch (Exception e) {
            Log.e(TAG, "Failed saving mcpServers to settings.json", e);
            return false;
        }
    }

    /**
     * Intelligently parses and imports MCP server configurations from raw JSON string.
     * Supports:
     * 1. Full Claude Desktop / Cursor config: { "mcpServers": { ... } }
     * 2. Direct mapping: { "srv1": { ... }, "srv2": { ... } }
     * 3. Array of server objects: [ { "name": "...", ... } ]
     * 4. Single server object: { "name": "...", "url": "..." }
     */
    public ImportResult importMcpServersFromJson(File rootfsDir, String jsonStr, boolean overwrite) {
        ImportResult result = new ImportResult();
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            result.success = false;
            result.message = "导入内容不能为空";
            return result;
        }

        try {
            String trimmed = jsonStr.trim();
            Map<String, McpServer> parsedServers = new LinkedHashMap<>();

            if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String sName = obj.optString("name", "server_" + (i + 1));
                    McpServer s = parseMcpServerJson(sName, obj);
                    if (s != null) parsedServers.put(s.name, s);
                }
            } else if (trimmed.startsWith("{")) {
                JSONObject root = new JSONObject(trimmed);
                JSONObject mcpMap = root.optJSONObject("mcpServers");
                if (mcpMap == null) {
                    // Check if it is a single server with "name" and "url"/"command"
                    if (root.has("name") && (root.has("url") || root.has("command"))) {
                        String sName = root.getString("name");
                        McpServer s = parseMcpServerJson(sName, root);
                        if (s != null) parsedServers.put(s.name, s);
                    } else {
                        // Direct map of serverName -> serverConfig
                        mcpMap = root;
                    }
                }

                if (mcpMap != null) {
                    Iterator<String> keys = mcpMap.keys();
                    while (keys.hasNext()) {
                        String sName = keys.next();
                        JSONObject item = mcpMap.optJSONObject(sName);
                        if (item != null) {
                            McpServer s = parseMcpServerJson(sName, item);
                            if (s != null) parsedServers.put(s.name, s);
                        }
                    }
                }
            } else {
                result.success = false;
                result.message = "JSON 格式必须以 { 或 [ 开头";
                return result;
            }

            if (parsedServers.isEmpty()) {
                result.success = false;
                result.message = "未在 JSON 中解析出有效的 MCP 服务定义";
                return result;
            }

            List<McpServer> currentList = loadMcpServers(rootfsDir);
            Map<String, McpServer> currentMap = new LinkedHashMap<>();
            for (McpServer s : currentList) {
                currentMap.put(s.name, s);
            }

            for (McpServer incoming : parsedServers.values()) {
                if (currentMap.containsKey(incoming.name)) {
                    if (overwrite) {
                        currentMap.put(incoming.name, incoming);
                        result.updatedCount++;
                    } else {
                        result.skippedCount++;
                    }
                } else {
                    currentMap.put(incoming.name, incoming);
                    result.importedCount++;
                }
            }

            boolean saved = saveMcpServers(rootfsDir, new ArrayList<>(currentMap.values()));
            if (!saved) {
                result.success = false;
                result.message = "保存到 settings.json 失败";
                return result;
            }

            result.success = true;
            result.message = String.format(Locale.getDefault(), "导入完成: 新增 %d 个, 覆盖 %d 个, 跳过 %d 个",
                    result.importedCount, result.updatedCount, result.skippedCount);
            return result;
        } catch (Exception e) {
            result.success = false;
            result.message = "JSON 解析失败: " + e.getMessage();
            return result;
        }
    }

    /**
     * Asynchronously pings remote MCP server URL with 3000ms timeout.
     */
    public void pingRemoteServer(String urlStr, Map<String, String> headers, PingCallback callback) {
        if (urlStr == null || urlStr.trim().isEmpty()) {
            if (callback != null) callback.onResult(false, 0, 0, "URL 不能为空");
            return;
        }

        String finalUrl = urlStr.trim();
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
            finalUrl = "https://" + finalUrl;
        }
        final String effectiveUrl = finalUrl;

        pingExecutor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                URL u = new URL(effectiveUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "iFlow-MCP-Probe/1.0");

                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null) {
                            conn.setRequestProperty(e.getKey().trim(), e.getValue().trim());
                        }
                    }
                }

                int code = conn.getResponseCode();
                long latency = System.currentTimeMillis() - start;
                boolean ok = (code >= 200 && code < 400) || code == 401 || code == 403 || code == 405;
                String detail;
                if (code >= 200 && code < 400) {
                    detail = "连通正常 (" + code + ")";
                } else if (code == 405) {
                    detail = "端点就绪 (" + code + ")";
                } else if (code == 401 || code == 403) {
                    detail = "服务响应 (" + code + ")";
                } else {
                    detail = "异常状态 (" + code + ")";
                }
                if (callback != null) {
                    callback.onResult(ok, code, latency, detail);
                }
                conn.disconnect();
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                if (callback != null) {
                    callback.onResult(false, -1, latency, "无法连接: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Generates the next sequential MCP server name in "mcp-N" format (e.g. mcp-1, mcp-2).
     */
    public static String generateNextMcpName(List<McpServer> existingServers) {
        int maxIndex = 0;
        Set<String> names = new HashSet<>();
        Pattern pattern = Pattern.compile("^mcp[-_]?(\\d+)$", Pattern.CASE_INSENSITIVE);
        if (existingServers != null) {
            for (McpServer s : existingServers) {
                if (s != null && s.name != null) {
                    String n = s.name.trim();
                    names.add(n.toLowerCase(Locale.ROOT));
                    Matcher m = pattern.matcher(n);
                    if (m.matches()) {
                        try {
                            int idx = Integer.parseInt(m.group(1));
                            if (idx > maxIndex) maxIndex = idx;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        int candidate = maxIndex + 1;
        while (names.contains("mcp-" + candidate) || names.contains("mcp" + candidate)) {
            candidate++;
        }
        return "mcp-" + candidate;
    }

    /**
     * Synchronizes agy user memory and rules into PRoot container ~/.iflow/rules.
     */
    public boolean syncAgyMemoryToIFlow(File rootfsDir) {
        if (rootfsDir == null || !rootfsDir.exists()) return false;
        File iflowDir = new File(rootfsDir, "root/.iflow");
        if (!iflowDir.exists()) iflowDir.mkdirs();

        File rulesFile = new File(iflowDir, "rules");
        String rulesContent = "# 🧭 iFlow 全局智能体记忆与交互铁律 (Synced from AGY)\n\n" +
                "> 本文件为 iFlow 默认全局载入的最高优先级规则与交互习惯记忆。\n\n" +
                "## 🎯 核心交互铁律与用户记忆\n\n" +
                "1. **绝对节奏控制 (Pacing Control)**：\n" +
                "   - 当用户说明 **「先不开始」/「先做决策不开始」** 时，**严禁写操作或修改代码**，仅分析、设计方案并等待；\n" +
                "   - 收到 **「开始」** 指令后才动手执行落地；收到 **「继续」** 时承接推进；\n" +
                "   - 遇到决策点主动列出关键路线与权衡，待用户拍板。\n\n" +
                "2. **线框与方案决策 (Wireframe & Decisions)**：\n" +
                "   - 涉及 UI/UX 布局、终端按键、弹窗、卡片变动时，主动使用 **字符线框图 (ASCII Wireframe)** 呈现效果；\n" +
                "   - 提供方案 A / 方案 B 对比并标出推荐项 (Recommended)，供用户拍板。\n\n" +
                "3. **六维质量标准 (Six-Dimensional Quality Pillars)**：\n" +
                "   - 所有代码必须兼顾 **容错性 / 可用性 / 可靠性 / 健壮性 / 一致性 / 可维护性**；\n" +
                "   - 落地后主动自测、排查环境缺口、确保编译与单元测试通过。\n\n" +
                "4. **Git 与工程协作 (Git & Engineering)**：\n" +
                "   - 涉及多分支时先拉取合并上游最新提交并排查冲突；\n" +
                "   - 提交时提供清晰严谨的改动说明与冲突说明；及时清理冗余代码与废弃物料。\n";

        try (FileWriter fw = new FileWriter(rulesFile)) {
            fw.write(rulesContent);
            Log.i(TAG, "Successfully synced agy memory to ~/.iflow/rules");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed syncing agy memory to ~/.iflow/rules", e);
            return false;
        }
    }

    // =========================================================================
    // Skills Management
    // =========================================================================

    public List<Skill> loadSkills(File rootfsDir) {
        List<Skill> skills = new ArrayList<>();
        if (rootfsDir == null || !rootfsDir.exists()) return skills;

        File skillsDir = new File(rootfsDir, "root/.iflow/skills");
        if (!skillsDir.exists()) skillsDir.mkdirs();

        File[] files = skillsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".md")) {
                    String content = readFile(f);
                    String id = f.getName().replace(".md", "");
                    String title = id;
                    String desc = "";

                    // Parse basic markdown headers or frontmatter and extract body prompt
                    String[] lines = content.split("\n");
                    StringBuilder body = new StringBuilder();
                    boolean passedHeader = false;
                    for (String line : lines) {
                        String trim = line.trim();
                        if (!passedHeader) {
                            if (trim.startsWith("# ")) {
                                title = trim.substring(2).trim();
                                continue;
                            } else if (trim.startsWith("description:") || trim.startsWith("描述:")) {
                                desc = trim.substring(trim.indexOf(":") + 1).trim();
                                continue;
                            } else if (trim.isEmpty()) {
                                continue;
                            }
                            passedHeader = true;
                        }
                        if (passedHeader) {
                            if (body.length() > 0) body.append("\n");
                            body.append(line);
                        }
                    }
                    String prompt = body.length() > 0 ? body.toString().trim() : content;
                    skills.add(new Skill(id, title, desc, prompt));
                }
            }
        }

        // Populate preset skills if empty
        if (skills.isEmpty()) {
            installPresetSkills(rootfsDir);
            return loadSkills(rootfsDir);
        }

        return skills;
    }

    public void installPresetSkills(File rootfsDir) {
        if (rootfsDir == null || !rootfsDir.exists()) return;
        File skillsDir = new File(rootfsDir, "root/.iflow/skills");
        skillsDir.mkdirs();

        saveSkillFile(skillsDir, "code-review",
                "# 代码审查大师 (Code Review)\n" +
                        "描述: 按照 Google 工程规范深度审查代码质量、坏味道与潜在Bug。\n\n" +
                        "指令指引: 详细分析工作区改动，关注并发安全、内存泄漏、空指针与代码可维护性。");

        saveSkillFile(skillsDir, "android-expert",
                "# Android 专家助手 (Android Helper)\n" +
                        "描述: 自动化执行 ADB 调试、Logcat 过滤与 APK 诊断。\n\n" +
                        "指令指引: 配合 local adb 工具调试应用，排查 Crash 与性能瓶颈。");

        saveSkillFile(skillsDir, "refactor-clean",
                "# 架构重构与精炼 (Refactor)\n" +
                        "描述: 基于 Martin Fowler 重构标准改善架构与代码设计。\n\n" +
                        "指令指引: 遵循消除重复代码、单一职责原则提取方法和重构模块。");
    }

    public boolean saveSkill(File rootfsDir, String id, String title, String desc, String prompt) {
        if (rootfsDir == null || id == null || id.trim().isEmpty()) return false;
        File skillsDir = new File(rootfsDir, "root/.iflow/skills");
        skillsDir.mkdirs();

        String cleanId = id.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        String content = "# " + title + "\n描述: " + desc + "\n\n" + prompt;
        return saveSkillFile(skillsDir, cleanId, content);
    }

    public boolean deleteSkill(File rootfsDir, String id) {
        if (rootfsDir == null || id == null) return false;
        File f = new File(rootfsDir, "root/.iflow/skills/" + id + ".md");
        if (f.exists()) {
            return f.delete();
        }
        return false;
    }

    private boolean saveSkillFile(File dir, String id, String content) {
        File file = new File(dir, id + ".md");
        try (FileWriter fw = new FileWriter(file)) {
            fw.write(content);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed saving skill " + id, e);
            return false;
        }
    }

    private String readFile(File f) {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
