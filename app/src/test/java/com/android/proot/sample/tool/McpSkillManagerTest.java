package com.android.proot.sample.tool;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class McpSkillManagerTest {

    private File tempRootfs;
    private McpSkillManager manager;

    @Before
    public void setUp() throws IOException {
        tempRootfs = Files.createTempDirectory("test_mcp_rootfs_").toFile();
        manager = McpSkillManager.getInstance();
    }

    @After
    public void tearDown() {
        if (tempRootfs != null && tempRootfs.exists()) {
            deleteRecursive(tempRootfs);
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private String readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = fis.read(buf);
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    @Test
    public void testMcpServerModel_stdioAndRemote() {
        // 1. stdio
        McpSkillManager.McpServer stdio = new McpSkillManager.McpServer("local-fs", "npx");
        stdio.args.add("-y");
        stdio.args.add("@mcp/server-fs");
        assertFalse(stdio.isRemote());
        assertEquals("stdio", stdio.getEffectiveType());

        // 2. remote sse
        McpSkillManager.McpServer sse = new McpSkillManager.McpServer("cloud-sse", "sse", "https://api.example.com/sse");
        assertTrue(sse.isRemote());
        assertEquals("sse", sse.getEffectiveType());

        // 3. remote httpStream
        McpSkillManager.McpServer httpStream = new McpSkillManager.McpServer("cloud-stream", "httpStream", "https://api.example.com/stream");
        assertTrue(httpStream.isRemote());
        assertEquals("httpstream", httpStream.getEffectiveType());

        // 4. inferred remote when url is present without command
        McpSkillManager.McpServer inferred = new McpSkillManager.McpServer("inferred-remote", "");
        inferred.url = "https://remote.mcp/api";
        assertTrue(inferred.isRemote());
    }

    @Test
    public void testParseMcpServerJson() throws Exception {
        // stdio JSON
        JSONObject stdioJson = new JSONObject();
        stdioJson.put("command", "uvx");
        JSONArray args = new JSONArray();
        args.put("fetch-tool");
        stdioJson.put("args", args);
        stdioJson.put("disabled", false);

        McpSkillManager.McpServer s1 = McpSkillManager.parseMcpServerJson("fetcher", stdioJson);
        assertNotNull(s1);
        assertEquals("fetcher", s1.name);
        assertEquals("uvx", s1.command);
        assertEquals(1, s1.args.size());
        assertEquals("fetch-tool", s1.args.get(0));
        assertFalse(s1.isRemote());
        assertTrue(s1.enabled);

        // remote httpStream JSON with headers
        JSONObject remoteJson = new JSONObject();
        remoteJson.put("type", "httpStream");
        remoteJson.put("url", "https://mcp.ai/v1");
        remoteJson.put("disabled", true);
        JSONObject headers = new JSONObject();
        headers.put("Authorization", "Bearer token-xyz");
        remoteJson.put("headers", headers);

        McpSkillManager.McpServer s2 = McpSkillManager.parseMcpServerJson("ai-gateway", remoteJson);
        assertNotNull(s2);
        assertEquals("ai-gateway", s2.name);
        assertEquals("httpstream", s2.type);
        assertEquals("https://mcp.ai/v1", s2.url);
        assertFalse(s2.enabled);
        assertTrue(s2.isRemote());
        assertEquals("Bearer token-xyz", s2.headers.get("Authorization"));
    }

    @Test
    public void testLoadAndSaveMcpServers_persistence() {
        // Initial load should create template config in settings.json
        List<McpSkillManager.McpServer> initial = manager.loadMcpServers(tempRootfs);
        assertNotNull(initial);
        assertFalse(initial.isEmpty());

        File configFile = new File(tempRootfs, "root/.iflow/settings.json");
        assertTrue(configFile.exists());

        // Add custom remote server and save
        List<McpSkillManager.McpServer> customList = new ArrayList<>(initial);
        McpSkillManager.McpServer myRemote = new McpSkillManager.McpServer("my-remote-sse", "sse", "https://custom.org/mcp");
        myRemote.headers.put("X-Custom-Key", "Secret123");
        customList.add(myRemote);

        boolean saved = manager.saveMcpServers(tempRootfs, customList);
        assertTrue(saved);

        // Reload and verify
        List<McpSkillManager.McpServer> reloaded = manager.loadMcpServers(tempRootfs);
        assertEquals(customList.size(), reloaded.size());

        McpSkillManager.McpServer found = null;
        for (McpSkillManager.McpServer s : reloaded) {
            if ("my-remote-sse".equals(s.name)) {
                found = s;
                break;
            }
        }
        assertNotNull(found);
        assertTrue(found.isRemote());
        assertEquals("https://custom.org/mcp", found.url);
        assertEquals("sse", found.type);
        assertEquals("Secret123", found.headers.get("X-Custom-Key"));
    }

    @Test
    public void testImportFromJson_claudeAndCursorFormat() {
        // Pre-initialize
        manager.loadMcpServers(tempRootfs);

        String claudeJson = "{\n" +
                "  \"mcpServers\": {\n" +
                "    \"github-remote\": {\n" +
                "      \"type\": \"httpStream\",\n" +
                "      \"url\": \"https://github.com/mcp\",\n" +
                "      \"headers\": {\"Authorization\": \"Bearer gh_token_123\"}\n" +
                "    },\n" +
                "    \"sqlite-local\": {\n" +
                "      \"command\": \"npx\",\n" +
                "      \"args\": [\"-y\", \"@mcp/server-sqlite\", \"/data/db.sqlite\"]\n" +
                "    }\n" +
                "  }\n" +
                "}";

        McpSkillManager.ImportResult res = manager.importMcpServersFromJson(tempRootfs, claudeJson, false);
        assertTrue(res.success);
        assertTrue(res.importedCount >= 2);

        List<McpSkillManager.McpServer> servers = manager.loadMcpServers(tempRootfs);
        boolean foundGithub = false;
        boolean foundSqlite = false;
        for (McpSkillManager.McpServer s : servers) {
            if ("github-remote".equals(s.name)) {
                foundGithub = true;
                assertTrue(s.isRemote());
                assertEquals("httpstream", s.type);
                assertEquals("Bearer gh_token_123", s.headers.get("Authorization"));
            }
            if ("sqlite-local".equals(s.name)) {
                foundSqlite = true;
                assertFalse(s.isRemote());
                assertEquals("npx", s.command);
                assertEquals(3, s.args.size());
            }
        }
        assertTrue(foundGithub);
        assertTrue(foundSqlite);
    }

    @Test
    public void testImportFromJson_overwriteStrategy() {
        manager.loadMcpServers(tempRootfs);

        // 1. Initial import
        String json1 = "{\n" +
                "  \"test-server\": {\n" +
                "    \"type\": \"sse\",\n" +
                "    \"url\": \"https://original.com/sse\"\n" +
                "  }\n" +
                "}";
        McpSkillManager.ImportResult res1 = manager.importMcpServersFromJson(tempRootfs, json1, true);
        assertTrue(res1.success);
        assertEquals(1, res1.importedCount);

        // 2. Import same server with different URL and overwrite = false (skip)
        String json2 = "{\n" +
                "  \"test-server\": {\n" +
                "    \"type\": \"sse\",\n" +
                "    \"url\": \"https://new-url.com/sse\"\n" +
                "  }\n" +
                "}";
        McpSkillManager.ImportResult resSkip = manager.importMcpServersFromJson(tempRootfs, json2, false);
        assertTrue(resSkip.success);
        assertEquals(0, resSkip.importedCount);
        assertEquals(1, resSkip.skippedCount);

        List<McpSkillManager.McpServer> listAfterSkip = manager.loadMcpServers(tempRootfs);
        for (McpSkillManager.McpServer s : listAfterSkip) {
            if ("test-server".equals(s.name)) {
                assertEquals("https://original.com/sse", s.url);
            }
        }

        // 3. Import same server with overwrite = true
        McpSkillManager.ImportResult resOverwrite = manager.importMcpServersFromJson(tempRootfs, json2, true);
        assertTrue(resOverwrite.success);
        assertEquals(0, resOverwrite.importedCount);
        assertEquals(1, resOverwrite.updatedCount);

        List<McpSkillManager.McpServer> listAfterOverwrite = manager.loadMcpServers(tempRootfs);
        for (McpSkillManager.McpServer s : listAfterOverwrite) {
            if ("test-server".equals(s.name)) {
                assertEquals("https://new-url.com/sse", s.url);
            }
        }
    }

    @Test
    public void testImportFromJson_invalidJson() {
        McpSkillManager.ImportResult res = manager.importMcpServersFromJson(tempRootfs, "{invalid json content", true);
        assertFalse(res.success);
        assertTrue(res.message.contains("JSON"));
    }

    @Test
    public void testSyncAgyMemoryToIFlow() throws IOException {
        boolean ok = manager.syncAgyMemoryToIFlow(tempRootfs);
        assertTrue(ok);

        File rulesFile = new File(tempRootfs, "root/.iflow/rules");
        assertTrue(rulesFile.exists());
        String content = readFile(rulesFile);

        // Check key memory rules
        assertTrue(content.contains("绝对节奏控制"));
        assertTrue(content.contains("先不开始"));
        assertTrue(content.contains("字符线框图"));
        assertTrue(content.contains("ASCII Wireframe"));
        assertTrue(content.contains("六维质量标准"));
        assertTrue(content.contains("容错性"));
        assertTrue(content.contains("可维护性"));
        assertTrue(content.contains("Git 与工程协作"));
    }

    @Test
    public void testSkillsManagement() {
        // Save new skill
        boolean saved = manager.saveSkill(tempRootfs, "test-skill", "Test Skill Title", "Description", "System prompt body");
        assertTrue(saved);

        File skillFile = new File(tempRootfs, "root/.iflow/skills/test-skill.md");
        assertTrue(skillFile.exists());

        // Load skills
        List<McpSkillManager.Skill> skills = manager.loadSkills(tempRootfs);
        assertNotNull(skills);
        boolean found = false;
        for (McpSkillManager.Skill sk : skills) {
            if ("test-skill".equals(sk.id)) {
                found = true;
                assertEquals("Test Skill Title", sk.title);
                assertEquals("Description", sk.description);
                assertEquals("System prompt body", sk.prompt);
            }
        }
        assertTrue(found);

        // Delete skill
        boolean deleted = manager.deleteSkill(tempRootfs, "test-skill");
        assertTrue(deleted);
        assertFalse(skillFile.exists());
    }

    @Test
    public void testGenerateNextMcpName() {
        // 1. Empty list
        assertEquals("mcp-1", McpSkillManager.generateNextMcpName(new ArrayList<>()));

        // 2. Standard list with mcp-1 and mcp-2
        List<McpSkillManager.McpServer> list = new ArrayList<>();
        list.add(new McpSkillManager.McpServer("mcp-1", "echo"));
        list.add(new McpSkillManager.McpServer("mcp-2", "echo"));
        assertEquals("mcp-3", McpSkillManager.generateNextMcpName(list));

        // 3. Jump in indices
        list.add(new McpSkillManager.McpServer("mcp-7", "echo"));
        assertEquals("mcp-8", McpSkillManager.generateNextMcpName(list));

        // 4. Mixed case and other tools
        list.add(new McpSkillManager.McpServer("filesystem", "npx"));
        list.add(new McpSkillManager.McpServer("MCP-10", "uvx"));
        assertEquals("mcp-11", McpSkillManager.generateNextMcpName(list));
    }

    @Test
    public void testEditMcpServer_persistence() {
        manager.loadMcpServers(tempRootfs);

        List<McpSkillManager.McpServer> servers = manager.loadMcpServers(tempRootfs);
        McpSkillManager.McpServer toEdit = servers.get(0);
        String oldName = toEdit.name;

        // Edit properties
        toEdit.name = "edited-mcp";
        toEdit.type = "httpstream";
        toEdit.url = "https://edited.api.com/mcp";
        toEdit.headers.put("Authorization", "Bearer token-edited");

        boolean saved = manager.saveMcpServers(tempRootfs, servers);
        assertTrue(saved);

        // Reload and verify edit
        List<McpSkillManager.McpServer> reloaded = manager.loadMcpServers(tempRootfs);
        McpSkillManager.McpServer found = null;
        for (McpSkillManager.McpServer s : reloaded) {
            if ("edited-mcp".equals(s.name)) {
                found = s;
                break;
            }
        }
        assertNotNull(found);
        assertEquals("httpstream", found.type);
        assertEquals("https://edited.api.com/mcp", found.url);
        assertEquals("Bearer token-edited", found.headers.get("Authorization"));
    }

    @Test
    public void testEditSkill_persistence() {
        // Initial creation
        manager.saveSkill(tempRootfs, "dev-tool", "Initial Title", "Initial Desc", "Initial prompt");

        // Edit skill
        boolean updated = manager.saveSkill(tempRootfs, "dev-tool", "Updated Title", "Updated Desc", "Updated prompt body");
        assertTrue(updated);

        // Reload and verify updated content
        List<McpSkillManager.Skill> skills = manager.loadSkills(tempRootfs);
        McpSkillManager.Skill found = null;
        for (McpSkillManager.Skill sk : skills) {
            if ("dev-tool".equals(sk.id)) {
                found = sk;
                break;
            }
        }
        assertNotNull(found);
        assertEquals("Updated Title", found.title);
        assertEquals("Updated Desc", found.description);
        assertEquals("Updated prompt body", found.prompt);
    }
}
