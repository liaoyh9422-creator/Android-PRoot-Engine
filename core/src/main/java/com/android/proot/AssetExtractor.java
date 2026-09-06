package com.android.proot;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * High-speed asset extraction and archive unpacking utility.
 */
public class AssetExtractor {

    /**
     * Extracts a single asset file to the specified target file.
     */
    public static void extractAsset(Context context, String name, File target) throws IOException {
        if (target.getParentFile() != null) target.getParentFile().mkdirs();
        try (InputStream is = context.getAssets().open(name);
             OutputStream os = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        }
    }

    /**
     * Extracts a compressed tar archive (.tar.gz / .tar.bin) from assets into the target directory.
     */
    public static void extractAssetTar(Context context, String assetName, File destDir) throws IOException {
        destDir.mkdirs();
        File tarFile = new File(destDir, ".tmp_extract.tar.gz");
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(tarFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
        }
        ProcessBuilder pb;
        if (new File("/system/bin/toybox").exists()) {
            pb = new ProcessBuilder("/system/bin/toybox", "tar", "xzf", tarFile.getAbsolutePath());
        } else if (new File("/system/bin/tar").exists()) {
            pb = new ProcessBuilder("/system/bin/tar", "xzf", tarFile.getAbsolutePath());
        } else {
            pb = new ProcessBuilder("tar", "xzf", tarFile.getAbsolutePath());
        }
        pb.directory(destDir);
        pb.redirectErrorStream(true);
        Process tarProc = pb.start();
        try {
            tarProc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        tarFile.delete();
    }

    /**
     * Recursively extracts an asset directory tree into the target directory.
     */
    public static void extractAssetDir(Context context, String assetPath, File outDir) throws IOException {
        AssetManager am = context.getAssets();
        String[] children = am.list(assetPath);
        if (children == null) return;
        if (children.length == 0) {
            File outFile = new File(outDir.getParentFile(), outDir.getName());
            if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
            try (InputStream is = am.open(assetPath);
                 OutputStream os = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            }
            return;
        }
        for (String child : children) {
            String childPath = assetPath + "/" + child;
            String[] subChildren = am.list(childPath);
            if (subChildren != null && subChildren.length > 0) {
                File subDir = new File(outDir, child);
                subDir.mkdirs();
                extractAssetDir(context, childPath, subDir);
            } else {
                File outFile = new File(outDir, child);
                if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
                try (InputStream is = am.open(childPath);
                     OutputStream os = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                }
            }
        }
    }
}
