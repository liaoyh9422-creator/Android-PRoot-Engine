package com.android.proot.sample.ai;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Manages discovery, parsing, and execution commands for iFlow conversation sessions.
 */
public final class IFlowSessionManager {
    private static final String TAG = "IFlowSessionManager";

    public static class IFlowSession {
        public final String id;
        public final String model;
        public final String cwd;
        public final String title;
        public final long lastModified;
        public final long fileSize;
        public final File file;

        public IFlowSession(String id, String model, String cwd, String title, long lastModified, long fileSize, File file) {
            this.id = id;
            this.model = model;
            this.cwd = cwd;
            this.title = title;
            this.lastModified = lastModified;
            this.fileSize = fileSize;
            this.file = file;
        }
    }

    private IFlowSessionManager() {}

    /**
     * Loads all existing sessions from container rootfs ~/.iflow/projects/ directory.
     */
    public static List<IFlowSession> loadSessions(File rootfsDir) {
        if (rootfsDir == null || !rootfsDir.exists()) {
            return Collections.emptyList();
        }

        File projectsDir = new File(rootfsDir, "root/.iflow/projects");
        if (!projectsDir.exists() || !projectsDir.isDirectory()) {
            return Collections.emptyList();
        }

        List<File> jsonlFiles = new ArrayList<>();
        findJsonlFiles(projectsDir, jsonlFiles);

        if (jsonlFiles.isEmpty()) {
            return Collections.emptyList();
        }

        List<IFlowSession> list = new ArrayList<>();
        for (File f : jsonlFiles) {
            try {
                IFlowSession s = parseSessionFile(f);
                if (s != null) {
                    list.add(s);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed parsing session file: " + f.getName(), e);
            }
        }

        // Sort descending by last modified time (newest sessions first)
        Collections.sort(list, (a, b) -> Long.compare(b.lastModified, a.lastModified));
        return list;
    }

    private static void findJsonlFiles(File dir, List<File> outList) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                findJsonlFiles(f, outList);
            } else if (f.isFile() && f.getName().endsWith(".jsonl")) {
                outList.add(f);
            }
        }
    }

    public static IFlowSession parseSessionFile(File file) {
        if (file == null || !file.exists()) return null;

        String fallbackId = file.getName().replace(".jsonl", "");
        String id = fallbackId;
        String model = "default";
        String cwd = "/root";
        String title = null;
        long lastModified = file.lastModified();
        long fileSize = file.length();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int linesChecked = 0;
            while ((line = reader.readLine()) != null && linesChecked < 20) {
                linesChecked++;
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject j = new JSONObject(line);
                    if (j.has("sessionId")) id = j.getString("sessionId");
                    if (j.has("model")) model = j.getString("model");
                    if (j.has("cwd")) cwd = j.getString("cwd");

                    if (title == null) {
                        String extracted = extractUserPrompt(j);
                        if (extracted != null && !extracted.isEmpty()) {
                            title = extracted;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading session file: " + file.getName(), e);
        }

        if (title == null || title.trim().isEmpty()) {
            title = "(无对话记录)";
        } else {
            title = title.replace("\r", " ").replace("\n", " ").trim();
            if (title.length() > 100) {
                title = title.substring(0, 97) + "...";
            }
        }

        return new IFlowSession(id, model, cwd, title, lastModified, fileSize, file);
    }

    private static String extractUserPrompt(JSONObject j) {
        if (j.has("message")) {
            Object mObj = j.opt("message");
            if (mObj instanceof JSONObject) {
                JSONObject msg = (JSONObject) mObj;
                String role = msg.optString("role", "");
                if ("user".equalsIgnoreCase(role) || role.isEmpty()) {
                    String c = extractContent(msg.opt("content"));
                    if (c != null && !c.isEmpty()) return c;
                }
            }
        }
        if (j.has("role")) {
            String role = j.optString("role", "");
            if ("user".equalsIgnoreCase(role)) {
                String c = extractContent(j.opt("content"));
                if (c != null && !c.isEmpty()) return c;
            }
        }
        if (j.has("prompt")) {
            return j.optString("prompt");
        }
        return null;
    }

    private static String extractContent(Object contentObj) {
        if (contentObj == null) return null;
        if (contentObj instanceof String) {
            return (String) contentObj;
        }
        if (contentObj instanceof JSONArray) {
            JSONArray arr = (JSONArray) contentObj;
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject) {
                    JSONObject obj = (JSONObject) item;
                    String text = obj.optString("text", "");
                    if (!text.isEmpty()) return text;
                } else if (item instanceof String) {
                    return (String) item;
                }
            }
        }
        return null;
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }

    public static String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
}
