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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Manages Model Context Protocol (MCP) server definitions and custom Skills for iFlow CLI.
 * Bi-directionally syncs with ~/.iflow/settings.json (mcpServers) and ~/.iflow/skills/.
 */
public final class McpSkillManager {
    private static final String TAG = "McpSkillManager";

    public static class McpServer {
        public String name;
        public String command;
        public List<String> args = new ArrayList<>();
        public boolean enabled = true;

        public McpServer(String name, String command) {
            this.name = name;
            this.command = command;
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
                    String cmd = item.optString("command", "");
                    McpServer s = new McpServer(name, cmd);
                    s.enabled = !item.optBoolean("disabled", false);
                    JSONArray argsArr = item.optJSONArray("args");
                    if (argsArr != null) {
                        for (int i = 0; i < argsArr.length(); i++) {
                            s.args.add(argsArr.optString(i));
                        }
                    }
                    list.add(s);
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
                    JSONObject item = new JSONObject();
                    item.put("command", s.command);
                    item.put("disabled", !s.enabled);
                    JSONArray arr = new JSONArray();
                    for (String a : s.args) arr.put(a);
                    item.put("args", arr);
                    mcpObj.put(s.name, item);
                }
            }
            json.put("mcpServers", mcpObj);
            return IFlowConfigManager.writeSettingsJson(configFile, json);
        } catch (Exception e) {
            Log.e(TAG, "Failed saving mcpServers to settings.json", e);
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
                    String prompt = content;

                    // Parse basic markdown headers or frontmatter
                    String[] lines = content.split("\n");
                    for (String line : lines) {
                        String trim = line.trim();
                        if (trim.startsWith("# ")) {
                            title = trim.substring(2).trim();
                        } else if (trim.startsWith("description:") || trim.startsWith("描述:")) {
                            desc = trim.substring(trim.indexOf(":") + 1).trim();
                        }
                    }
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
