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
