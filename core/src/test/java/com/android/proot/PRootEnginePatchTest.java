package com.android.proot;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class PRootEnginePatchTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testPatchIflowCardBorder_nullOrMissing() {
        Assert.assertFalse(PRootEngine.patchIflowCardBorder(null));
        Assert.assertFalse(PRootEngine.patchIflowCardBorder(tempFolder.getRoot()));
    }

    @Test
    public void testPatchIflowCardBorder_successAndIdempotency() throws Exception {
        File rootfsDir = tempFolder.newFolder("rootfs");
        File bundleDir = new File(rootfsDir, "usr/local/lib/node_modules/@iflow-ai/iflow-cli/bundle");
        bundleDir.mkdirs();

        File iflowJs = new File(bundleDir, "iflow.js");
        File patchFlag = new File(bundleDir, ".card_border_patched");

        String rawContent = "const a = 1;\n" +
                "return (0,N0.jsxs)(ie,{paddingX:1,paddingBottom:1,flexDirection:\"column\",children:[]});\n" +
                "return (0,fc.jsxs)(ie,{paddingX:1,paddingBottom:1,flexDirection:\"column\",children:[]});\n";

        try (FileOutputStream fos = new FileOutputStream(iflowJs)) {
            fos.write(rawContent.getBytes(StandardCharsets.UTF_8));
        }

        Assert.assertFalse(patchFlag.exists());

        // First patch execution
        boolean result = PRootEngine.patchIflowCardBorder(rootfsDir);
        Assert.assertTrue("Patch should succeed", result);
        Assert.assertTrue("Marker flag file should exist", patchFlag.exists());

        byte[] patchedBytes = java.nio.file.Files.readAllBytes(iflowJs.toPath());
        String patchedContent = new String(patchedBytes, StandardCharsets.UTF_8);

        Assert.assertFalse("Original padding should be replaced", patchedContent.contains("{paddingX:1,paddingBottom:1,flexDirection:\"column\""));
        Assert.assertTrue("Round borderStyle should be injected", patchedContent.contains("borderStyle:\"round\""));
        Assert.assertTrue("BorderColor #30363D should be injected", patchedContent.contains("borderColor:\"#30363D\""));

        // Second patch execution (idempotency check)
        boolean secondResult = PRootEngine.patchIflowCardBorder(rootfsDir);
        Assert.assertTrue("Second call should return true (already patched)", secondResult);
    }
}
