package com.android.proot.sample.helper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Encapsulates runtime storage permission checks and prompts across Android versions (M to UpsideDownCake+).
 */
public final class StoragePermissionHelper {
    public static final int REQUEST_CODE_STORAGE_PERMS = 1001;
    public static final int REQUEST_CODE_MANAGE_STORAGE = 1002;

    private static final String PREF_NAME = "proot_permissions";
    private static final String KEY_STORAGE_ASKED = "storage_asked";

    private StoragePermissionHelper() {}

    /**
     * Checks if storage permission has already been granted.
     */
    public static boolean hasStoragePermission(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * Shows permission explanation and requests permission if not granted yet.
     */
    public static void requestStoragePermissionIfNeeded(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (hasStoragePermission(activity)) return;

        SharedPreferences sp = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean askedBefore = sp.getBoolean(KEY_STORAGE_ASKED, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!askedBefore) {
                sp.edit().putBoolean(KEY_STORAGE_ASKED, true).apply();
                new AlertDialog.Builder(activity)
                        .setTitle("存储访问权限")
                        .setMessage("为使 PRoot 虚拟化环境能够访问手机外部存储（/sdcard），建议授予“所有文件访问权限”。\n\n即使暂不授予，内置 Linux 终端环境仍可完全正常使用。")
                        .setPositiveButton("前往授权", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                                activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                            } catch (Exception e1) {
                                try {
                                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                                    activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                                } catch (Exception e2) {
                                    try {
                                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                        intent.setData(Uri.parse("package:" + activity.getPackageName()));
                                        activity.startActivity(intent);
                                    } catch (Exception ignored) {}
                                }
                            }
                        })
                        .setNegativeButton("稍后再说", null)
                        .show();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!askedBefore) {
                sp.edit().putBoolean(KEY_STORAGE_ASKED, true).apply();
                activity.requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_CODE_STORAGE_PERMS);
            }
        }
    }

    /**
     * Handles onRequestPermissionsResult from Activity.
     */
    public static boolean handleRequestPermissionsResult(Context context, int requestCode, int[] grantResults) {
        if (requestCode == REQUEST_CODE_STORAGE_PERMS) {
            if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "存储权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "存储权限未授予，/sdcard 访问受限", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return false;
    }

    /**
     * Handles onActivityResult from Activity.
     */
    public static boolean handleActivityResult(Context context, int requestCode) {
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(context, "所有文件访问权限已授予", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "所有文件访问权限未授予，/sdcard 访问受限", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        return false;
    }
}
