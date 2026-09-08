package com.android.proot.proxy;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class WebStudioServerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testComputeWebSocketAccept_rfc6455StandardVector() {
        // RFC 6455 section 4.2.2 standard test vector
        String clientKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String actualAccept = WebStudioServer.computeWebSocketAccept(clientKey);
        Assert.assertEquals("RFC 6455 WebSocket accept key must match standard vector", expectedAccept, actualAccept);
    }

    @Test
    public void testDeployWebZip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry index = new ZipEntry("index.html");
            zos.putNextEntry(index);
            zos.write("<html><body>Test</body></html>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            ZipEntry css = new ZipEntry("assets/test.css");
            zos.putNextEntry(css);
            zos.write("body { margin: 0; }".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        File targetDir = tempFolder.newFolder("web");
        boolean ok = WebStudioServer.deployWebZip(new ByteArrayInputStream(baos.toByteArray()), targetDir);
        Assert.assertTrue("Deploy should succeed", ok);
        Assert.assertTrue("index.html must exist", new File(targetDir, "index.html").exists());
        Assert.assertTrue("assets/test.css must exist", new File(targetDir, "assets/test.css").exists());
    }

    @Test
    public void testProxyConfigWithWebStudio() {
        File fakeRoot = new File("/data/data/com.android.proot.sample/files/web");
        File providersFile = new File("/data/data/com.android.proot.sample/files/iflow-providers.json");
        ProxyConfig config = new ProxyConfig.Builder()
                .setWebPort(7865)
                .setWebRoot(fakeRoot)
                .setProvidersFile(providersFile)
                .setEnableWebStudio(true)
                .build();

        Assert.assertEquals(7865, config.getWebPort());
        Assert.assertEquals(fakeRoot, config.getWebRoot());
        Assert.assertEquals(providersFile, config.getProvidersFile());
        Assert.assertTrue(config.isEnableWebStudio());
    }

    @Test
    public void testDefaultProvidersContract_satisfiesComposerReady() throws Exception {
        File provFile = tempFolder.newFile("test-providers.json");
        provFile.delete(); // fresh
        ProxyConfig config = new ProxyConfig.Builder()
                .setListenHost("127.0.0.1")
                .setPort(7863)
                .setWebPort(0)
                .setProvidersFile(provFile)
                .build();

        WebStudioServer server = new WebStudioServer(config, null);
        server.start();
        int port = server.getActualPort();

        try {
            String jsonStr = doHttpRequest(port, "GET", "/api/providers", null);
            JSONObject root = new JSONObject(jsonStr);
            String active = root.getString("active");
            Assert.assertEquals("deepseek", active);

            JSONArray providers = root.getJSONArray("providers");
            Assert.assertTrue("Must have at least 2 default providers (deepseek and iflow)", providers.length() >= 2);

            JSONObject deepseek = null;
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.getJSONObject(i);
                if ("deepseek".equals(p.getString("id"))) {
                    deepseek = p;
                    break;
                }
            }
            Assert.assertNotNull("deepseek provider must exist", deepseek);

            // Verify Composer.vue readiness conditions:
            // 1. provider.id === config.activeId
            Assert.assertEquals(active, deepseek.getString("id"));
            // 2. hasKey === true and apiKeyMasked
            Assert.assertTrue("hasKey must be true", deepseek.getBoolean("hasKey"));
            Assert.assertFalse("apiKeyMasked must not be empty", deepseek.getString("apiKeyMasked").isEmpty());
            // 3. provider.models.length > 0
            Assert.assertTrue("models must not be empty", deepseek.getJSONArray("models").length() > 0);
            // 4. provider.baseUrl must be present and valid
            Assert.assertTrue("baseUrl must be present", deepseek.has("baseUrl") && !deepseek.getString("baseUrl").isEmpty());
            Assert.assertTrue("base_url compatibility field must match baseUrl", deepseek.getString("baseUrl").equals(deepseek.getString("base_url")));
            // 5. model / active_model
            Assert.assertTrue("model must not be empty", deepseek.has("model") && !deepseek.getString("model").isEmpty());
            Assert.assertEquals(deepseek.getString("model"), deepseek.getString("active_model"));
            // 6. active flag
            Assert.assertTrue("deepseek active flag must be true", deepseek.getBoolean("active"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testProviderUpsertActivateAndSelectModel() throws Exception {
        File provFile = tempFolder.newFile("test-providers-crud.json");
        provFile.delete();

        ProxyConfig config = new ProxyConfig.Builder()
                .setListenHost("127.0.0.1")
                .setPort(7863)
                .setWebPort(0)
                .setProvidersFile(provFile)
                .build();

        WebStudioServer server = new WebStudioServer(config, null);
        AtomicReference<String> notifiedModel = new AtomicReference<>("");
        AtomicReference<String> notifiedProviderId = new AtomicReference<>("");
        server.setProviderChangeListener((id, baseUrl, apiKey, model) -> {
            notifiedProviderId.set(id);
            notifiedModel.set(model);
        });
        server.start();
        int port = server.getActualPort();

        try {
            // 1. Create a custom provider (e.g. OpenAI)
            JSONObject input = new JSONObject();
            input.put("id", "openai");
            input.put("name", "OpenAI Custom");
            input.put("apiKey", "sk-proj-abc12345678");
            input.put("baseUrl", "https://api.openai.com/v1");
            JSONArray models = new JSONArray().put("gpt-4o").put("gpt-4o-mini");
            input.put("models", models);
            input.put("model", "gpt-4o");
            input.put("activate", true);

            String createResp = doHttpRequest(port, "POST", "/api/providers", input.toString());
            JSONObject createJson = new JSONObject(createResp);
            Assert.assertTrue(createJson.optBoolean("ok"));

            // Verify active changed and notified
            Assert.assertEquals("openai", notifiedProviderId.get());
            Assert.assertEquals("gpt-4o", notifiedModel.get());

            // Verify GET /api/providers
            String listResp = doHttpRequest(port, "GET", "/api/providers", null);
            JSONObject listJson = new JSONObject(listResp);
            Assert.assertEquals("openai", listJson.getString("active"));

            // 2. Select a different model
            JSONObject selModelInput = new JSONObject().put("model", "gpt-4o-mini");
            String selResp = doHttpRequest(port, "POST", "/api/providers/openai/select-model", selModelInput.toString());
            Assert.assertTrue(new JSONObject(selResp).optBoolean("ok"));
            Assert.assertEquals("gpt-4o-mini", notifiedModel.get());
            Assert.assertEquals("gpt-4o-mini", server.getActiveModel());

            // 3. Switch active provider back to deepseek
            String actResp = doHttpRequest(port, "POST", "/api/providers/deepseek/activate", "");
            Assert.assertTrue(new JSONObject(actResp).optBoolean("ok"));
            Assert.assertEquals("deepseek", server.getActiveProviderId());

            // 4. Reveal key
            String revealResp = doHttpRequest(port, "POST", "/api/providers/openai/reveal", "");
            JSONObject revJson = new JSONObject(revealResp);
            Assert.assertEquals("sk-proj-abc12345678", revJson.getString("apiKey"));

            // 5. Discover models fallback
            String discResp = doHttpRequest(port, "POST", "/api/providers/openai/discover-models", "{\"persist\":false}");
            JSONObject discJson = new JSONObject(discResp);
            Assert.assertTrue(discJson.has("models"));
            Assert.assertTrue(discJson.getJSONArray("models").length() > 0);

            // 6. Delete provider
            String delResp = doHttpRequest(port, "DELETE", "/api/providers/openai", null);
            Assert.assertTrue(new JSONObject(delResp).optBoolean("ok"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testRuntimeDoctorAndHealth() throws Exception {
        ProxyConfig config = new ProxyConfig.Builder()
                .setListenHost("127.0.0.1")
                .setPort(7863)
                .setWebPort(0)
                .build();

        WebStudioServer server = new WebStudioServer(config, null);
        server.start();
        int port = server.getActualPort();

        try {
            // Test /api/runtime/doctor
            String doctorResp = doHttpRequest(port, "GET", "/api/runtime/doctor", null);
            JSONObject doc = new JSONObject(doctorResp);
            Assert.assertTrue(doc.getBoolean("ok"));
            Assert.assertTrue(doc.has("runtime"));
            Assert.assertEquals("ready", doc.getJSONObject("runtime").getString("status"));
            Assert.assertTrue(doc.getJSONObject("facts").getBoolean("sh"));

            // Test /api/runtime/health
            String healthResp = doHttpRequest(port, "GET", "/api/runtime/health", null);
            JSONObject health = new JSONObject(healthResp);
            Assert.assertEquals("ok", health.getString("status"));
            Assert.assertEquals("/root", health.getString("cwd"));
            Assert.assertTrue(health.getJSONObject("engine").getBoolean("initialized"));
            Assert.assertEquals(server.getActiveModel(), health.getJSONObject("engine").getString("llm"));

            // Test /api/sessions
            String sessResp = doHttpRequest(port, "GET", "/api/sessions", null);
            JSONObject sess = new JSONObject(sessResp);
            Assert.assertTrue(sess.has("sessions"));

            // Test /api/settings/connection
            String connResp = doHttpRequest(port, "GET", "/api/settings/connection", null);
            JSONObject conn = new JSONObject(connResp);
            Assert.assertTrue(conn.has("providerRetryCount"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testAvailableToolsContract() throws Exception {
        JSONArray tools = WebStudioServer.getAvailableTools();
        Assert.assertNotNull(tools);
        Assert.assertTrue("Should have at least 8 tools", tools.length() >= 8);

        boolean hasShell = false;
        boolean hasReadFile = false;
        boolean hasWriteFile = false;
        boolean hasEditFile = false;
        boolean hasListDir = false;
        boolean hasGrepFiles = false;
        boolean hasWebSearch = false;
        boolean hasFetch = false;
        boolean hasDoctor = false;

        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.getJSONObject(i);
            Assert.assertEquals("function", t.optString("type"));
            JSONObject fn = t.getJSONObject("function");
            String name = fn.getString("name");
            Assert.assertFalse(fn.optString("description").isEmpty());
            Assert.assertTrue(fn.has("parameters"));

            if ("shell".equals(name)) hasShell = true;
            if ("read_file".equals(name)) hasReadFile = true;
            if ("write_file".equals(name)) hasWriteFile = true;
            if ("edit_file".equals(name)) hasEditFile = true;
            if ("list_dir".equals(name)) hasListDir = true;
            if ("grep_files".equals(name)) hasGrepFiles = true;
            if ("web_search".equals(name)) hasWebSearch = true;
            if ("fetch".equals(name)) hasFetch = true;
            if ("runtime_doctor".equals(name)) hasDoctor = true;
        }

        Assert.assertTrue("hasShell", hasShell);
        Assert.assertTrue("hasReadFile", hasReadFile);
        Assert.assertTrue("hasWriteFile", hasWriteFile);
        Assert.assertTrue("hasEditFile", hasEditFile);
        Assert.assertTrue("hasListDir", hasListDir);
        Assert.assertTrue("hasGrepFiles", hasGrepFiles);
        Assert.assertTrue("hasWebSearch", hasWebSearch);
        Assert.assertTrue("hasFetch", hasFetch);
        Assert.assertTrue("hasDoctor", hasDoctor);
    }

    @Test
    public void testCanonicalToolNameAndUiToolName() {
        // canonicalToolName mappings
        Assert.assertEquals("shell", WebStudioServer.canonicalToolName("bash"));
        Assert.assertEquals("shell", WebStudioServer.canonicalToolName("sh"));
        Assert.assertEquals("shell", WebStudioServer.canonicalToolName("terminal"));
        Assert.assertEquals("shell", WebStudioServer.canonicalToolName("exec"));
        Assert.assertEquals("read_file", WebStudioServer.canonicalToolName("cat"));
        Assert.assertEquals("read_file", WebStudioServer.canonicalToolName("read"));
        Assert.assertEquals("write_file", WebStudioServer.canonicalToolName("write"));
        Assert.assertEquals("edit_file", WebStudioServer.canonicalToolName("edit"));
        Assert.assertEquals("edit_file", WebStudioServer.canonicalToolName("patch"));
        Assert.assertEquals("list_dir", WebStudioServer.canonicalToolName("ls"));
        Assert.assertEquals("list_dir", WebStudioServer.canonicalToolName("dir"));
        Assert.assertEquals("grep_files", WebStudioServer.canonicalToolName("grep"));
        Assert.assertEquals("grep_files", WebStudioServer.canonicalToolName("search"));
        Assert.assertEquals("web_search", WebStudioServer.canonicalToolName("web"));
        Assert.assertEquals("fetch", WebStudioServer.canonicalToolName("browse"));
        Assert.assertEquals("fetch", WebStudioServer.canonicalToolName("webfetch"));
        Assert.assertEquals("runtime_doctor", WebStudioServer.canonicalToolName("doctor"));

        // getUiToolName mappings
        Assert.assertEquals("bash", WebStudioServer.getUiToolName("shell"));
        Assert.assertEquals("read", WebStudioServer.getUiToolName("read_file"));
        Assert.assertEquals("write", WebStudioServer.getUiToolName("write_file"));
        Assert.assertEquals("edit", WebStudioServer.getUiToolName("edit_file"));
        Assert.assertEquals("ls", WebStudioServer.getUiToolName("list_dir"));
        Assert.assertEquals("grep", WebStudioServer.getUiToolName("grep_files"));
        Assert.assertEquals("webfetch", WebStudioServer.getUiToolName("fetch"));
        Assert.assertEquals("websearch", WebStudioServer.getUiToolName("web_search"));
    }

    @Test
    public void testToolExecutionFileCrudAndDoctor() throws Exception {
        ProxyConfig config = new ProxyConfig.Builder()
                .setListenHost("127.0.0.1")
                .setPort(7863)
                .setWebPort(0)
                .build();

        File workDir = tempFolder.newFolder("workspace");
        WebStudioServer server = new WebStudioServer(config, null);
        server.setCwd(workDir.getAbsolutePath());

        // 1. Shell runner delegation
        AtomicReference<String> executedCmd = new AtomicReference<>("");
        server.setShellRunner((command, cwd, timeoutMs) -> {
            executedCmd.set(command);
            return "Shell output from mocked runner";
        });

        JSONObject shellArgs = new JSONObject().put("command", "echo 'hello world'");
        WebStudioServer.ToolResult shellRes = server.executeTool("shell", shellArgs, "sess-1");
        Assert.assertFalse(shellRes.isError);
        Assert.assertEquals("echo 'hello world'", executedCmd.get());
        Assert.assertEquals("Shell output from mocked runner", shellRes.output);

        // 2. write_file
        File testFile = new File(workDir, "sample.txt");
        JSONObject writeArgs = new JSONObject()
                .put("path", testFile.getAbsolutePath())
                .put("content", "Line 1: Alpha\nLine 2: Beta\nLine 3: Gamma\n");
        WebStudioServer.ToolResult writeRes = server.executeTool("write_file", writeArgs, "sess-1");
        Assert.assertFalse(writeRes.isError);
        Assert.assertTrue(testFile.exists());

        // 3. read_file
        JSONObject readArgs = new JSONObject()
                .put("path", testFile.getAbsolutePath())
                .put("offset", 1)
                .put("limit", 10);
        WebStudioServer.ToolResult readRes = server.executeTool("read_file", readArgs, "sess-1");
        Assert.assertFalse(readRes.isError);
        Assert.assertTrue(readRes.output.contains("1 | Line 1: Alpha"));
        Assert.assertTrue(readRes.output.contains("2 | Line 2: Beta"));

        // 4. edit_file
        JSONObject editArgs = new JSONObject()
                .put("path", testFile.getAbsolutePath())
                .put("old_string", "Beta")
                .put("new_string", "Delta");
        WebStudioServer.ToolResult editRes = server.executeTool("edit_file", editArgs, "sess-1");
        Assert.assertFalse(editRes.isError);

        WebStudioServer.ToolResult readAfterEdit = server.executeTool("read_file", readArgs, "sess-1");
        Assert.assertTrue(readAfterEdit.output.contains("Line 2: Delta"));
        Assert.assertFalse(readAfterEdit.output.contains("Line 2: Beta"));

        // 5. list_dir
        JSONObject listArgs = new JSONObject().put("path", workDir.getAbsolutePath());
        WebStudioServer.ToolResult listRes = server.executeTool("list_dir", listArgs, "sess-1");
        Assert.assertFalse(listRes.isError);
        Assert.assertTrue(listRes.output.contains("sample.txt"));

        // 6. grep_files
        JSONObject grepArgs = new JSONObject()
                .put("path", workDir.getAbsolutePath())
                .put("query", "Delta");
        WebStudioServer.ToolResult grepRes = server.executeTool("grep_files", grepArgs, "sess-1");
        Assert.assertFalse(grepRes.isError);
        Assert.assertTrue(grepRes.output.contains("sample.txt:2: Line 2: Delta"));

        // 7. runtime_doctor
        WebStudioServer.ToolResult docRes = server.executeTool("runtime_doctor", new JSONObject(), "sess-1");
        Assert.assertFalse(docRes.isError);
        JSONObject docJson = new JSONObject(docRes.output);
        Assert.assertEquals("ready", docJson.optString("status"));
        Assert.assertEquals("proot_linux", docJson.optString("backend"));
    }

    @Test
    public void testSessionCwdRestApi() throws Exception {
        ProxyConfig config = new ProxyConfig.Builder()
                .setListenHost("127.0.0.1")
                .setPort(7863)
                .setWebPort(0)
                .build();

        WebStudioServer server = new WebStudioServer(config, null);
        server.setCwd("/root/default-ws");
        server.start();
        int port = server.getActualPort();

        try {
            // GET default cwd
            String getResp = doHttpRequest(port, "GET", "/api/sessions/my-session/cwd", null);
            JSONObject getJson = new JSONObject(getResp);
            Assert.assertEquals("/root/default-ws", getJson.getString("cwd"));

            // POST update cwd
            JSONObject updateBody = new JSONObject().put("cwd", "/root/custom-project");
            String postResp = doHttpRequest(port, "POST", "/api/sessions/my-session/cwd", updateBody.toString());
            JSONObject postJson = new JSONObject(postResp);
            Assert.assertTrue(postJson.getBoolean("ok"));
            Assert.assertEquals("/root/custom-project", postJson.getString("cwd"));

            // GET updated cwd
            String getResp2 = doHttpRequest(port, "GET", "/api/sessions/my-session/cwd", null);
            JSONObject getJson2 = new JSONObject(getResp2);
            Assert.assertEquals("/root/custom-project", getJson2.getString("cwd"));
        } finally {
            server.stop();
        }
    }

    private String doHttpRequest(int port, String method, String path, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString(StandardCharsets.UTF_8.name());
    }
}
