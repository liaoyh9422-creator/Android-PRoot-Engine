package com.android.proot.proxy;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ToolForgeTest {

    @Test
    public void testToolInjectionAndParsing() throws Exception {
        JSONArray tools = new JSONArray();
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        JSONObject fn = new JSONObject();
        fn.put("name", "bash_exec");
        fn.put("description", "Execute bash command in Linux rootfs");
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        JSONObject cmd = new JSONObject();
        cmd.put("type", "string");
        props.put("command", cmd);
        params.put("properties", props);
        fn.put("parameters", params);
        tool.put("function", fn);
        tools.put(tool);

        JSONArray messages = new JSONArray();
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", "List files");
        messages.put(userMsg);

        JSONArray injected = ToolForge.injectMessages(messages, tools);
        assertEquals(2, injected.length());
        JSONObject sys = injected.getJSONObject(0);
        assertEquals("system", sys.getString("role"));
        assertTrue(sys.getString("content").contains("bash_exec"));
        assertTrue(sys.getString("content").contains("<|XYML|tool_calls>"));

        // Test parser
        String modelOutput = "I will list files:\n<|XYML|tool_calls><|XYML|invoke name=\"bash_exec\"><|XYML|parameter name=\"command\">ls -la</|XYML|parameter></|XYML|invoke></|XYML|tool_calls>";
        JSONArray calls = ToolForge.parseToolCalls(modelOutput, tools);
        assertEquals(1, calls.length());
        JSONObject call = calls.getJSONObject(0);
        assertEquals("function", call.getString("type"));
        JSONObject functionCall = call.getJSONObject("function");
        assertEquals("bash_exec", functionCall.getString("name"));
        JSONObject parsedArgs = new JSONObject(functionCall.getString("arguments"));
        assertEquals("ls -la", parsedArgs.getString("command"));

        // Test stripping protocol markup
        String stripped = ToolForge.stripProtocolMarkup(modelOutput);
        assertEquals("I will list files:", stripped.trim());
    }

    @Test
    public void testProxyConfigBuilder() {
        ProxyConfig config = new ProxyConfig.Builder()
                .setPort(7864)
                .setApiKey("")
                .setModel("deepseek-v4-flash")
                .setPoolMin(2)
                .setPoolMax(6)
                .setReasoningEffort("xhigh")
                .setEnableThinking(true)
                .build();

        assertEquals(7864, config.getPort());
        assertEquals("deepseek-v4-flash", config.getModel());
        assertEquals(2, config.getPoolMin());
        assertEquals(6, config.getPoolMax());
        assertTrue(config.isForcePromptTools());
        assertTrue(config.isEnableThinking());
        assertEquals("xhigh", config.getReasoningEffort());
    }

    @Test
    public void testExtractThinking() {
        String input = "<think>\nLet us analyze the user request step by step.\nFirst check files.\n</think>\nHere is the final answer.";
        String[] result = ToolForge.extractThinking(input);
        assertEquals("Let us analyze the user request step by step.\nFirst check files.", result[0]);
        assertEquals("Here is the final answer.", result[1]);

        // Test with unclosed think tag
        String unclosed = "<think>Still thinking without closing tag";
        String[] unclosedResult = ToolForge.extractThinking(unclosed);
        assertEquals("Still thinking without closing tag", unclosedResult[0]);
        assertEquals("", unclosedResult[1]);

        // Test without think tag
        String regular = "Plain text without thinking.";
        String[] regularResult = ToolForge.extractThinking(regular);
        assertEquals("", regularResult[0]);
        assertEquals("Plain text without thinking.", regularResult[1]);
    }
}
