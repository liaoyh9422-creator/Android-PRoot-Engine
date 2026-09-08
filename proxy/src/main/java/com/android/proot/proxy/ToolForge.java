package com.android.proot.proxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ToolForge: Handles XYML/QNML tool calling protocol emulation for CNB.
 * Converts OpenAI `tools` definitions into structured System Prompt instructions,
 * and parses model XML output into standard OpenAI `tool_calls` JSON structures.
 */
public final class ToolForge {

    private static final Pattern INVOKE = Pattern.compile(
            "(?is)<\\|(XYML|QNML)\\|invoke\\s+name=\"([^\"]+)\"\\s*>(.*?)</\\|\\1\\|invoke\\s*>"
    );
    private static final Pattern PARAMETER = Pattern.compile(
            "(?is)<\\|(XYML|QNML)\\|parameter\\s+name=\"([^\"]+)\"\\s*>(.*?)</\\|\\1\\|parameter\\s*>"
    );
    private static final Pattern XML_CALL = Pattern.compile(
            "(?is)<tool_call(?:\\s+name=\"([^\"]+)\")?\\s*>(.*?)</tool_call\\s*>"
    );
    private static final Pattern XML_NAME = Pattern.compile(
            "(?is)<name\\s*>(.*?)</name\\s*>"
    );
    private static final Pattern XML_ARGUMENT = Pattern.compile(
            "(?is)<parameter\\s+name=\"([^\"]+)\"\\s*>(.*?)</parameter\\s*>"
    );

    private ToolForge() {}

    public static String buildInstructions(JSONArray tools) {
        if (tools == null || tools.length() == 0) {
            return "";
        }

        Set<String> names = allowedNames(tools);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < tools.length(); i++) {
            JSONObject raw = tools.optJSONObject(i);
            if (raw == null) continue;

            JSONObject tool = raw.optJSONObject("function");
            if (tool == null) tool = raw;

            String name = tool.optString("name", "").trim();
            if (name.isEmpty()) continue;

            JSONObject parameters = tool.optJSONObject("parameters");
            String desc = clip(tool.optString("description", ""), 240);

            sb.append("Action name: ").append(name).append("\n")
              .append("Description: ").append(desc).append("\n")
              .append("Parameters: ").append(parameters == null ? "{}" : parameters.toString()).append("\n\n");
        }

        String exampleName = names.isEmpty() ? "TOOL_NAME" : names.iterator().next();
        JSONObject exampleArgs = new JSONObject();
        try {
            exampleArgs.put("ARG", "value");
        } catch (Exception ignored) {}
        String example = renderToolCall(exampleName, exampleArgs);

        return "=== XYML TOOL CALL PROTOCOL ===\n" +
                "You have access to these tools:\n\n" +
                sb.toString() +
                "Default protocol for new tool calls: XYML\n" +
                "Accepted parse protocols by this client: XYML, QNML\n" +
                "Available action names: " + join(names) + "\n\n" +
                "FORMAT:\n" + example + "\n\n" +
                "RULES:\n" +
                "1. If a tool is needed, output a parseable XYML tool-call block. If no tool is needed, answer normally.\n" +
                "2. Use exact action names and parameter names from the schema.\n" +
                "3. Put strings in plain text or CDATA; objects and arrays may use JSON.\n" +
                "4. Never invent a tool or leave a required parameter empty.\n" +
                "5. After a tool result, call another tool only if needed; otherwise answer normally.\n\n" +
                "CORRECT EXAMPLE:\n" + example + "\n" +
                "Remember: preferred form is <|XYML|tool_calls>...</|XYML|tool_calls>.\n" +
                "=== END XYML TOOL INSTRUCTIONS ===";
    }

    public static JSONArray injectMessages(JSONArray messages, JSONArray tools) {
        if (messages == null) return new JSONArray();
        if (tools == null || tools.length() == 0) return messages;

        String instructions = buildInstructions(tools);
        if (instructions.isEmpty()) return messages;

        JSONArray convertedMessages = new JSONArray();

        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null) continue;

            String role = msg.optString("role", "user");
            String content = contentText(msg.opt("content"));

            if ("tool".equalsIgnoreCase(role) || "toolResult".equalsIgnoreCase(role)) {
                JSONObject converted = new JSONObject();
                try {
                    converted.put("role", "user");
                    String id = msg.optString("tool_call_id", "unknown");
                    String name = msg.optString("name", "");
                    String header = "[Tool Result id=" + id + (name.isEmpty() ? "" : " name=" + name) + "]";
                    converted.put("content", header + "\n" + content);
                    convertedMessages.put(converted);
                } catch (Exception ignored) {}
            } else if ("assistant".equalsIgnoreCase(role)) {
                JSONArray calls = msg.optJSONArray("tool_calls");
                if (calls != null && calls.length() > 0) {
                    StringBuilder assistantText = new StringBuilder();
                    if (!content.isEmpty()) {
                        assistantText.append(content).append("\n");
                    }
                    for (int j = 0; j < calls.length(); j++) {
                        JSONObject call = calls.optJSONObject(j);
                        if (call == null) continue;
                        JSONObject fn = call.optJSONObject("function");
                        String name = fn != null ? fn.optString("name", "") : call.optString("name", "");
                        JSONObject args = parseArguments(fn != null ? fn.opt("arguments") : call.opt("arguments"));
                        if (!name.isEmpty()) {
                            assistantText.append(renderToolCall(name, args)).append("\n");
                        }
                    }
                    JSONObject converted = new JSONObject();
                    try {
                        converted.put("role", "assistant");
                        converted.put("content", assistantText.toString().trim());
                        convertedMessages.put(converted);
                    } catch (Exception ignored) {}
                } else {
                    JSONObject converted = new JSONObject();
                    try {
                        converted.put("role", "assistant");
                        converted.put("content", content);
                        convertedMessages.put(converted);
                    } catch (Exception ignored) {}
                }
            } else {
                JSONObject converted = new JSONObject();
                try {
                    converted.put("role", "developer".equalsIgnoreCase(role) ? "system" : role);
                    converted.put("content", content);
                    convertedMessages.put(converted);
                } catch (Exception ignored) {}
            }
        }

        // Inject instructions into first system message or prepend as new system message
        if (convertedMessages.length() > 0) {
            JSONObject first = convertedMessages.optJSONObject(0);
            if (first != null && "system".equalsIgnoreCase(first.optString("role"))) {
                String existing = first.optString("content", "").trim();
                try {
                    first.put("content", existing.isEmpty() ? instructions : existing + "\n\n" + instructions);
                } catch (Exception ignored) {}
                return convertedMessages;
            }
        }

        JSONArray result = new JSONArray();
        try {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", instructions);
            result.put(systemMsg);
            for (int i = 0; i < convertedMessages.length(); i++) {
                result.put(convertedMessages.get(i));
            }
        } catch (Exception ignored) {}

        return result;
    }

    public static JSONArray parseToolCalls(String text, JSONArray tools) {
        if (text == null || text.trim().isEmpty()) {
            return new JSONArray();
        }

        Set<String> allowed = allowedNames(tools);
        LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();

        // 1. Parse XYML / QNML syntax
        Matcher mInvoke = INVOKE.matcher(text);
        while (mInvoke.find()) {
            String name = mInvoke.group(2).trim();
            String inner = mInvoke.group(3);
            JSONObject args = new JSONObject();
            Matcher mParam = PARAMETER.matcher(inner);
            while (mParam.find()) {
                String pName = mParam.group(2).trim();
                String pVal = mParam.group(3);
                putValue(args, pName, pVal);
            }
            addCall(unique, name, args, allowed);
        }

        // 2. Parse XML tool_call syntax
        Matcher mCall = XML_CALL.matcher(text);
        while (mCall.find()) {
            String name = mCall.group(1);
            String inner = mCall.group(2);
            if (name == null || name.trim().isEmpty()) {
                Matcher mName = XML_NAME.matcher(inner);
                if (mName.find()) {
                    name = mName.group(1).trim();
                }
            }
            if (name != null && !name.trim().isEmpty()) {
                name = name.trim();
                JSONObject args = new JSONObject();
                Matcher mArg = XML_ARGUMENT.matcher(inner);
                while (mArg.find()) {
                    String pName = mArg.group(1).trim();
                    String pVal = mArg.group(2);
                    putValue(args, pName, pVal);
                }
                addCall(unique, name, args, allowed);
            }
        }

        // 3. Scan JSON fallback fragments if still empty
        if (unique.isEmpty()) {
            parseJsonFragments(text, allowed, unique);
        }

        JSONArray result = new JSONArray();
        for (JSONObject call : unique.values()) {
            result.put(call);
        }
        return result;
    }

    public static String stripProtocolMarkup(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("(?is)<\\|(?:XYML|QNML)\\|tool_calls>.*?</\\|(?:XYML|QNML)\\|tool_calls>", "");
        cleaned = cleaned.replaceAll("(?is)<tool_call.*?</tool_call>", "");
        return cleaned.trim();
    }

    // Helper methods

    private static Set<String> allowedNames(JSONArray tools) {
        if (tools == null) return Collections.emptySet();
        Set<String> set = new HashSet<>();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject raw = tools.optJSONObject(i);
            if (raw == null) continue;
            JSONObject tool = raw.optJSONObject("function");
            if (tool == null) tool = raw;
            String name = tool.optString("name", "").trim();
            if (!name.isEmpty()) set.add(name);
        }
        return set;
    }

    private static String clip(String text, int maxLen) {
        if (text == null) return "";
        text = text.trim();
        if (text.length() <= maxLen) return text;
        return text.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private static String contentText(Object obj) {
        if (obj == null) return "";
        if (obj instanceof String) return (String) obj;
        if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null && "text".equals(item.optString("type"))) {
                    sb.append(item.optString("text", ""));
                }
            }
            return sb.toString();
        }
        return String.valueOf(obj);
    }

    private static void putValue(JSONObject target, String name, String val) {
        if (name == null || name.isEmpty() || val == null) return;
        String trimmed = val.trim();
        if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) {
            trimmed = trimmed.substring(9, trimmed.length() - 3);
        }
        try {
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    target.put(name, new JSONObject(trimmed));
                    return;
                } catch (Exception e1) {
                    try {
                        target.put(name, new JSONArray(trimmed));
                        return;
                    } catch (Exception ignored) {}
                }
            }
            target.put(name, trimmed);
        } catch (Exception ignored) {}
    }

    private static void addCall(LinkedHashMap<String, JSONObject> unique, String name, JSONObject args, Set<String> allowed) {
        if (name == null || name.isEmpty()) return;
        if (!allowed.isEmpty() && !allowed.contains(name)) return;

        String key = name + "\u0000" + (args != null ? args.toString() : "{}");
        if (unique.containsKey(key)) return;

        try {
            String id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
            JSONObject call = new JSONObject();
            call.put("id", id);
            call.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("arguments", args != null ? args.toString() : "{}");
            call.put("function", fn);
            unique.put(key, call);
        } catch (Exception ignored) {}
    }

    private static String renderToolCall(String name, JSONObject args) {
        StringBuilder sb = new StringBuilder();
        sb.append("<|XYML|tool_calls>\n  <|XYML|invoke name=\"").append(escape(name)).append("\">\n");
        if (args != null) {
            Iterator<String> it = args.keys();
            while (it.hasNext()) {
                String k = it.next();
                sb.append("    <|XYML|parameter name=\"").append(escape(k)).append("\">")
                  .append(escapeValue(args.opt(k)))
                  .append("</|XYML|parameter>\n");
            }
        }
        sb.append("  </|XYML|invoke>\n</|XYML|tool_calls>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeValue(Object val) {
        if (val == null) return "";
        String s = String.valueOf(val);
        if (s.contains("<") || s.contains(">") || s.contains("&")) {
            return "<![CDATA[" + s + "]]>";
        }
        return s;
    }

    private static String join(Set<String> set) {
        if (set == null || set.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static JSONObject parseArguments(Object obj) {
        if (obj instanceof JSONObject) return (JSONObject) obj;
        if (obj instanceof String) {
            String s = ((String) obj).trim();
            if (s.startsWith("{") && s.endsWith("}")) {
                try {
                    return new JSONObject(s);
                } catch (Exception ignored) {}
            }
        }
        return new JSONObject();
    }

    private static void parseJsonFragments(String text, Set<String> allowed, LinkedHashMap<String, JSONObject> unique) {
        int idx = 0;
        while ((idx = text.indexOf('{', idx)) != -1) {
            int end = findMatchingBrace(text, idx);
            if (end == -1) {
                idx++;
                continue;
            }
            String candidate = text.substring(idx, end + 1);
            try {
                JSONObject json = new JSONObject(candidate);
                String name = json.optString("name", "");
                if (name.isEmpty() && json.has("function")) {
                    JSONObject fn = json.optJSONObject("function");
                    if (fn != null) name = fn.optString("name", "");
                }
                if (!name.isEmpty()) {
                    JSONObject args = parseArguments(json.opt("arguments"));
                    if (args.length() == 0 && json.has("input")) {
                        args = parseArguments(json.opt("input"));
                    }
                    addCall(unique, name, args, allowed);
                }
            } catch (Exception ignored) {}
            idx = end + 1;
        }
    }

    private static int findMatchingBrace(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
