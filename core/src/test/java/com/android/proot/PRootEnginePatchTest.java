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
    public void testPatchIflowCardBorder_planBReplacementsAndIdempotency() throws Exception {
        File rootfsDir = tempFolder.newFolder("rootfs");
        File bundleDir = new File(rootfsDir, "usr/local/lib/node_modules/@iflow-ai/iflow-cli/bundle");
        bundleDir.mkdirs();

        File iflowJs = new File(bundleDir, "iflow.js");
        File patchFlag = new File(bundleDir, ".card_border_patched_v3");

        String rawContent = "const a = 1;\n" +
                "// 1. Tool call\n" +
                "return (0,N0.jsxs)(ie,{paddingX:1,paddingBottom:1,flexDirection:\"column\",children:[]});\n" +
                "// 2. Input box capsule\n" +
                "const box = {borderStyle:\"round\",borderLeft:!1,borderRight:!1};\n" +
                "// 3. User message bubble\n" +
                "const userMsg = {backgroundColor:ae?.UserMessageBackground||ae.Background,flexDirection:\"row\",paddingX:2,paddingY:0};\n" +
                "// 4. Thinking chain\n" +
                "(0,jA.jsxs)(ie,{marginY:1,flexDirection:\"column\",children:[(0,jA.jsxs)(ie,{marginBottom:1,children:[]})]});\n" +
                "// 5. Markdown code block\n" +
                "return(0,Uo.jsx)(ie,{paddingLeft:wJ,flexDirection:\"column\",width:o,flexShrink:0,children:c});\n" +
                "// 6. Error alert\n" +
                "zWi=({text:t})=>(0,DJ.jsxs)(ie,{flexDirection:\"row\",marginBottom:1,children:[]});\n" +
                "// 7. Notice alert\n" +
                "(0,TJ.jsxs)(ie,{flexDirection:\"row\",marginTop:1,marginBottom:o,children:[]});\n" +
                "// 8. Thinking ticker\n" +
                "color:ae.Comment,italic:!0,children:e?.subject?I.t(\"thinking.thinkingWithSubject\",{subject:e.subject}):I.t(\"thinking.thinking\")+\"...\"}),o&&\n";

        try (FileOutputStream fos = new FileOutputStream(iflowJs)) {
            fos.write(rawContent.getBytes(StandardCharsets.UTF_8));
        }

        Assert.assertFalse(patchFlag.exists());

        // First patch execution
        boolean result = PRootEngine.patchIflowCardBorder(rootfsDir);
        Assert.assertTrue("Patch should succeed", result);
        Assert.assertTrue("Marker flag v3 file should exist", patchFlag.exists());

        byte[] patchedBytes = java.nio.file.Files.readAllBytes(iflowJs.toPath());
        String patchedContent = new String(patchedBytes, StandardCharsets.UTF_8);

        // 1. Tool call
        Assert.assertFalse("Original padding should be replaced", patchedContent.contains("{paddingX:1,paddingBottom:1,flexDirection:\"column\""));
        Assert.assertTrue("Tool call borderStyle should be injected", patchedContent.contains("borderColor:\"#30363D\",marginTop:1,marginBottom:1,paddingX:1,paddingBottom:0"));

        // 2. Input box
        Assert.assertFalse("Input box borderLeft:!1 should be removed", patchedContent.contains("borderLeft:!1"));
        Assert.assertTrue("Input box should have closed round style", patchedContent.contains("borderStyle:\"round\""));

        // 3. User message bubble
        Assert.assertTrue("User message card should have round border and color",
                patchedContent.contains("borderStyle:\"round\",borderColor:\"#30363D\",backgroundColor:ae?.UserMessageBackground||ae.Background"));

        // 4. Thinking chain
        Assert.assertTrue("Thinking chain should have round card styling and width:\"100%\"",
                patchedContent.contains("(0,jA.jsxs)(ie,{borderStyle:\"round\",borderColor:\"#30363D\",paddingX:1,paddingY:0,marginY:1,flexDirection:\"column\",width:\"100%\""));

        // 5. Markdown code block
        Assert.assertTrue("Markdown code block should have round card styling",
                patchedContent.contains("return(0,Uo.jsx)(ie,{borderStyle:\"round\",borderColor:\"#30363D\",paddingX:1,paddingY:0,marginY:1,flexDirection:\"column\",width:o,flexShrink:0,children:c})"));

        // 6. Error alert
        Assert.assertTrue("Error alert should have round red border styling",
                patchedContent.contains("borderStyle:\"round\",borderColor:ae.AccentRed,paddingX:1,paddingY:0,marginY:1,flexDirection:\"row\""));

        // 7. Notice alert
        Assert.assertTrue("Notice alert should have round yellow border styling",
                patchedContent.contains("borderStyle:\"round\",borderColor:ae.AccentYellow,paddingX:1,paddingY:0,marginTop:1,marginBottom:o,flexDirection:\"row\""));

        // 8. Thinking ticker
        Assert.assertTrue("Thinking ticker should have real-time character count injected",
                patchedContent.contains("d?\" · \"+d.replace(/\\s+/g,\"\").length+\" 字\":\"\""));

        // Second patch execution (idempotency check)
        boolean secondResult = PRootEngine.patchIflowCardBorder(rootfsDir);
        Assert.assertTrue("Second call should return true (already patched)", secondResult);
    }
}
