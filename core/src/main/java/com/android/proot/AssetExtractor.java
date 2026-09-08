package com.android.proot;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * High-speed asset extraction and archive unpacking utility.
 * Uses pure Java streaming tar/gzip extraction with fallback to system toybox.
 */
public class AssetExtractor {
    private static final String TAG = "AssetExtractor";

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
     * Prioritizes pure-Java streaming extraction (preserving symlinks and permissions) without
     * relying on external toybox/tar process executions that may be blocked by SELinux.
     */
    public static void extractAssetTar(Context context, String assetName, File destDir) throws IOException {
        destDir.mkdirs();

        // 1. Pure Java streaming tar.gz extraction
        try (InputStream raw = context.getAssets().open(assetName);
             InputStream gz = new GZIPInputStream(raw)) {
            extractTarStream(gz, destDir);
            Log.i(TAG, "Successfully extracted " + assetName + " via pure Java stream engine.");
            return;
        } catch (Exception e) {
            Log.w(TAG, "Pure Java extraction failed or interrupted, falling back to system tar: " + e.getMessage());
        }

        // 2. Fallback to system toybox tar if needed
        File tarFile = new File(destDir, ".tmp_extract.tar.gz");
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(tarFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
        }

        try {
            ProcessBuilder pb;
            if (new File("/system/bin/toybox").exists()) {
                pb = new ProcessBuilder("/system/bin/toybox", "tar", "xzf", tarFile.getAbsolutePath(), "-C", destDir.getAbsolutePath());
            } else if (new File("/system/bin/tar").exists()) {
                pb = new ProcessBuilder("/system/bin/tar", "xzf", tarFile.getAbsolutePath(), "-C", destDir.getAbsolutePath());
            } else {
                pb = new ProcessBuilder("tar", "xzf", tarFile.getAbsolutePath(), "-C", destDir.getAbsolutePath());
            }
            pb.directory(destDir);
            pb.redirectErrorStream(true);
            Process tarProc = pb.start();
            try (InputStream is = tarProc.getInputStream()) {
                byte[] drain = new byte[4096];
                while (is.read(drain) != -1) {}
            }
            int exitCode = tarProc.waitFor();
            if (exitCode != 0) {
                Log.e(TAG, "System tar fallback returned exit code " + exitCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "System tar fallback failed", e);
        } finally {
            tarFile.delete();
        }
    }

    /**
     * Unpacks a tar archive stream into destDir, restoring file contents, permissions, and symlinks.
     */
    public static void extractTarStream(InputStream in, File destDir) throws IOException {
        byte[] header = new byte[512];
        String nextLongName = null;
        String nextLongLinkName = null;
        while (true) {
            int read = readFully(in, header, 0, 512);
            if (read < 512) break;

            // Two consecutive zero blocks indicate end of archive
            boolean allZero = true;
            for (int i = 0; i < 512; i++) {
                if (header[i] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) break;

            String name = readString(header, 0, 100);
            String prefix = readString(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }

            int mode = readOctal(header, 100, 8);
            long size = readOctalLong(header, 124, 12);
            byte typeFlag = header[156];
            String linkName = readString(header, 157, 100);

            // Handle GNU tar long file/link names
            if (typeFlag == 'L') {
                byte[] data = new byte[(int) size];
                readFully(in, data, 0, data.length);
                nextLongName = new String(data, StandardCharsets.UTF_8).trim().replace("\0", "");
                long remainder = size % 512;
                if (remainder > 0) skipFully(in, 512 - remainder);
                continue;
            } else if (typeFlag == 'K') {
                byte[] data = new byte[(int) size];
                readFully(in, data, 0, data.length);
                nextLongLinkName = new String(data, StandardCharsets.UTF_8).trim().replace("\0", "");
                long remainder = size % 512;
                if (remainder > 0) skipFully(in, 512 - remainder);
                continue;
            } else if (typeFlag == 'x' || typeFlag == 'g') {
                long remainder = size % 512;
                skipFully(in, size + (remainder > 0 ? 512 - remainder : 0));
                continue;
            }

            if (nextLongName != null) {
                name = nextLongName;
                nextLongName = null;
            }
            if (nextLongLinkName != null) {
                linkName = nextLongLinkName;
                nextLongLinkName = null;
            }
            if (name.isEmpty()) continue;

            File outFile = new File(destDir, name);

            if (typeFlag == '5' || name.endsWith("/")) {
                outFile.mkdirs();
            } else if (typeFlag == '2' || typeFlag == '1') {
                // Symlink (2) or Hard link (1)
                outFile.getParentFile().mkdirs();
                if (outFile.exists()) {
                    outFile.delete();
                }
                boolean linked = false;
                try {
                    Os.symlink(linkName, outFile.getAbsolutePath());
                    linked = true;
                } catch (Exception e) {
                    Log.d(TAG, "Os.symlink failed for " + name + ": " + e.getMessage());
                }

                // If symlink failed (e.g. FUSE filesystem limitation), copy target as fallback
                if (!linked) {
                    File target = linkName.startsWith("/")
                            ? new File(destDir, linkName.substring(1))
                            : new File(outFile.getParentFile(), linkName);
                    if (target.exists() && target.isFile()) {
                        copyFile(target, outFile);
                        if ((mode & 0111) != 0) outFile.setExecutable(true, false);
                    }
                }
            } else {
                // Regular file
                outFile.getParentFile().mkdirs();
                if (outFile.exists()) {
                    outFile.delete();
                }
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    long remaining = size;
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int n = in.read(buf, 0, toRead);
                        if (n == -1) throw new IOException("Unexpected EOF while reading tar file: " + name);
                        fos.write(buf, 0, n);
                        remaining -= n;
                    }
                }

                if ((mode & 0111) != 0) {
                    outFile.setExecutable(true, false);
                }
                outFile.setReadable(true, false);
            }

            // Align to 512-byte boundary
            long remainder = size % 512;
            if (remainder > 0 && typeFlag != '5' && typeFlag != '2' && typeFlag != '1') {
                long pad = 512 - remainder;
                skipFully(in, pad);
            }
        }
    }

    private static void copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (Exception ignored) {}
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(b, off + total, len - total);
            if (n == -1) break;
            total += n;
        }
        return total;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) break;
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static String readString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static int readOctal(byte[] b, int off, int len) {
        return (int) readOctalLong(b, off, len);
    }

    private static long readOctalLong(byte[] b, int off, int len) {
        long result = 0;
        int start = off;
        int end = off + len;
        while (start < end && (b[start] == ' ' || b[start] == 0)) {
            start++;
        }
        while (end > start && (b[end - 1] == ' ' || b[end - 1] == 0)) {
            end--;
        }
        for (int i = start; i < end; i++) {
            byte c = b[i];
            if (c < '0' || c > '7') break;
            result = (result << 3) + (c - '0');
        }
        return result;
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
