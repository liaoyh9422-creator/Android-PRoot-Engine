package com.android.proot.proxy;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
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
        ProxyConfig config = new ProxyConfig.Builder()
                .setWebPort(7865)
                .setWebRoot(fakeRoot)
                .setEnableWebStudio(true)
                .build();

        Assert.assertEquals(7865, config.getWebPort());
        Assert.assertEquals(fakeRoot, config.getWebRoot());
        Assert.assertTrue(config.isEnableWebStudio());
    }
}
