package com.android.proot.sample.helper;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Manages setup wizard completion status and toolchain installation flags.
 */
public final class SetupStatusManager {

    private static final String PREF_NAME = "proot_setup_state";
    private static final String KEY_SETUP_COMPLETED = "key_setup_completed";
    private static final String KEY_NODEJS_INSTALLED = "key_nodejs_installed";
    private static final String KEY_PREFIX_TOOLCHAIN = "key_toolchain_";

    private SetupStatusManager() {}

    public static boolean isSetupCompleted(Context context) {
        if (context == null) return false;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_SETUP_COMPLETED, false);
    }

    public static void setSetupCompleted(Context context, boolean completed) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SETUP_COMPLETED, completed)
                .apply();
    }

    public static boolean isNodeInstalled(Context context, File rootfsDir) {
        if (context == null) return false;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean flagged = sp.getBoolean(KEY_NODEJS_INSTALLED, false);
        if (!flagged) return false;
        if (rootfsDir == null || !rootfsDir.exists()) return false;
        File nodeBin = new File(rootfsDir, "usr/bin/node");
        File optNodeBin = new File(rootfsDir, "opt/node/bin/node");
        return nodeBin.exists() || optNodeBin.exists();
    }

    public static void setNodeInstalled(Context context, boolean installed) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NODEJS_INSTALLED, installed)
                .apply();
    }

    public static boolean isToolchainInstalled(Context context, String toolchainId) {
        if (context == null || toolchainId == null) return false;
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PREFIX_TOOLCHAIN + toolchainId, false);
    }

    public static void setToolchainInstalled(Context context, String toolchainId, boolean installed) {
        if (context == null || toolchainId == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PREFIX_TOOLCHAIN + toolchainId, installed)
                .apply();
    }

    public static void resetSetup(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }
}
