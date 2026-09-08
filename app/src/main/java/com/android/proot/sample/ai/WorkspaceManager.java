package com.android.proot.sample.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages active and recent workspaces for iFlow and PRoot shell sessions with path sanitization and shell safety.
 */
public final class WorkspaceManager {
    private static final String TAG = "WorkspaceManager";
    private static final String PREF_NAME = "workspace_config";
    private static final String KEY_ACTIVE_WORKSPACE = "active_workspace";
    private static final String KEY_RECENT_WORKSPACES = "recent_workspaces";

    public static final String DEFAULT_WORKSPACE = "/workspace";
    public static final String DEFAULT_ROOTFS_HOME = "/root";
    public static final String DEFAULT_SDCARD_PROJECTS = "/sdcard/projects";
    public static final String DEFAULT_SDCARD_ROOT = "/sdcard";

    private static final int MAX_RECENT_WORKSPACES = 8;
    private static volatile WorkspaceManager sInstance;

    private final Context appContext;

    public static WorkspaceManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (WorkspaceManager.class) {
                if (sInstance == null) {
                    sInstance = new WorkspaceManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private WorkspaceManager(Context context) {
        this.appContext = context;
        // Ensure private files/workspace exists on disk
        try {
            File ws = new File(context.getFilesDir(), "workspace");
            if (!ws.exists()) ws.mkdirs();
        } catch (Exception ignored) {}
    }

    private SharedPreferences getPrefs() {
        return appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Returns the currently active workspace guest path (e.g. "/workspace" or "/root").
     */
    public synchronized String getActiveWorkspace() {
        String ws = getPrefs().getString(KEY_ACTIVE_WORKSPACE, DEFAULT_WORKSPACE);
        if (ws == null || ws.trim().isEmpty() || !ws.startsWith("/")) {
            return DEFAULT_WORKSPACE;
        }
        return ws.trim();
    }

    /**
     * Sets the active workspace and records it in the recent workspaces history.
     */
    public synchronized void setActiveWorkspace(String path) {
        String cleanPath = sanitizePath(path);
        if (cleanPath == null) {
            cleanPath = DEFAULT_WORKSPACE;
        }

        getPrefs().edit().putString(KEY_ACTIVE_WORKSPACE, cleanPath).apply();
        addRecentWorkspace(cleanPath);
    }

    /**
     * Returns list of recent workspaces, always starting with current active workspace and standard presets.
     */
    public synchronized List<String> getRecentWorkspaces() {
        String jsonStr = getPrefs().getString(KEY_RECENT_WORKSPACES, null);
        List<String> list = new ArrayList<>();

        if (jsonStr != null && !jsonStr.trim().isEmpty()) {
            try {
                JSONArray arr = new JSONArray(jsonStr);
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i, "").trim();
                    if (!p.isEmpty() && p.startsWith("/") && !list.contains(p)) {
                        list.add(p);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed parsing recent workspaces json", e);
            }
        }

        // Ensure defaults are present
        if (!list.contains(DEFAULT_WORKSPACE)) list.add(0, DEFAULT_WORKSPACE);
        if (!list.contains(DEFAULT_ROOTFS_HOME)) list.add(DEFAULT_ROOTFS_HOME);
        if (!list.contains(DEFAULT_SDCARD_PROJECTS)) list.add(DEFAULT_SDCARD_PROJECTS);
        if (!list.contains(DEFAULT_SDCARD_ROOT)) list.add(DEFAULT_SDCARD_ROOT);

        return Collections.unmodifiableList(list);
    }

    public synchronized void addRecentWorkspace(String path) {
        String cleanPath = sanitizePath(path);
        if (cleanPath == null) return;

        List<String> current = new ArrayList<>(getRecentWorkspaces());
        current.remove(cleanPath);
        current.add(0, cleanPath);

        while (current.size() > MAX_RECENT_WORKSPACES) {
            current.remove(current.size() - 1);
        }

        saveRecentWorkspaces(current);
    }

    public synchronized void removeRecentWorkspace(String path) {
        if (DEFAULT_WORKSPACE.equals(path) || DEFAULT_ROOTFS_HOME.equals(path)) return; // Keep defaults permanent
        List<String> current = new ArrayList<>(getRecentWorkspaces());
        current.remove(path);
        saveRecentWorkspaces(current);
    }

    private void saveRecentWorkspaces(List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) {
            arr.put(s);
        }
        getPrefs().edit().putString(KEY_RECENT_WORKSPACES, arr.toString()).apply();
    }

    /**
     * Sanitizes a guest path to prevent escape or illegal control characters.
     */
    public static String sanitizePath(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty() || !p.startsWith("/")) {
            return null;
        }
        // Remove trailing slash if length > 1
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        // Disallow dangerous control characters
        if (p.contains("\0") || p.contains("\n") || p.contains("\r")) {
            return null;
        }
        return p;
    }

    /**
     * Generates a single-quote escaped bash cd command to prevent command injection.
     */
    public static String buildSafeCdCommand(String guestPath) {
        String clean = sanitizePath(guestPath);
        if (clean == null) clean = DEFAULT_WORKSPACE;
        // Escape single quote: replace ' with '\''
        String escaped = clean.replace("'", "'\\''");
        return "cd -- '" + escaped + "' && echo -e \"\\033[32m✔ 工作区已切换至: $(pwd)\\033[0m\"\n";
    }

    /**
     * Ensures target workspace directory physically exists on the host filesystem.
     */
    public static boolean ensureDirectoryExists(File rootfsDir, String guestPath) {
        String clean = sanitizePath(guestPath);
        if (clean == null) return false;

        try {
            File hostDir = resolveHostFile(rootfsDir, clean);
            if (hostDir != null) {
                if (!hostDir.exists()) {
                    return hostDir.mkdirs();
                }
                return hostDir.isDirectory();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed ensuring directory exists: " + guestPath, e);
        }
        return false;
    }

    /**
     * Resolves guest path to host filesystem File.
     */
    public static File resolveHostFile(File rootfsDir, String guestPath) {
        if (guestPath == null) return null;

        if (guestPath.startsWith("/workspace")) {
            File baseDir = null;
            if (sInstance != null && sInstance.appContext != null) {
                baseDir = new File(sInstance.appContext.getFilesDir(), "workspace");
            } else if (rootfsDir != null && rootfsDir.getParentFile() != null) {
                baseDir = new File(rootfsDir.getParentFile(), "workspace");
            }
            if (baseDir != null) {
                String rel = guestPath.substring("/workspace".length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                return new File(baseDir, rel);
            }
        }

        if (guestPath.startsWith("/sdcard")) {
            File ext = Environment.getExternalStorageDirectory();
            String rel = guestPath.substring("/sdcard".length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return new File(ext, rel);
        }

        if (guestPath.startsWith("/storage/emulated/0")) {
            File ext = Environment.getExternalStorageDirectory();
            String rel = guestPath.substring("/storage/emulated/0".length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return new File(ext, rel);
        }

        if (guestPath.startsWith("/root") && rootfsDir != null) {
            String rel = guestPath.substring("/root".length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            return new File(new File(rootfsDir, "root"), rel);
        }

        if (rootfsDir != null) {
            String rel = guestPath.startsWith("/") ? guestPath.substring(1) : guestPath;
            return new File(rootfsDir, rel);
        }

        return null;
    }
}
