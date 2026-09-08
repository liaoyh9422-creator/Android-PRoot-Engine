package com.android.proot.sample.helper;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Robust stream downloader with progress, SHA256 integrity, and ARM64 ELF verification.
 */
public final class ToolchainDownloader {

    public interface DownloadCallback {
        void onProgress(int percentage, long downloadedBytes, long totalBytes);
        void onStatus(String message);
        void onSuccess(File targetFile);
        void onError(String message, Throwable error);
    }

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int TIMEOUT_MS = 15000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static Handler sMainHandler;

    private static void postToMain(Runnable r) {
        if (sMainHandler == null) {
            try {
                if (Looper.getMainLooper() != null) {
                    sMainHandler = new Handler(Looper.getMainLooper());
                }
            } catch (Throwable ignored) {}
        }
        if (sMainHandler != null) {
            sMainHandler.post(r);
        } else {
            r.run();
        }
    }

    private ToolchainDownloader() {}

    /**
     * Downloads an archive from a URL with redirection handling and progress reporting.
     */
    public static void downloadAsync(String urlString, File targetFile, DownloadCallback callback) {
        EXECUTOR.execute(() -> {
            File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;

            try {
                postToMain(() -> callback.onStatus("正在连接镜像源..."));
                URL currentUrl = new URL(urlString);
                int redirects = 0;

                while (redirects < 5) {
                    java.net.Proxy proxy = NetworkProbeHelper.getActiveProxy();
                    conn = (HttpURLConnection) (proxy != null ? currentUrl.openConnection(proxy) : currentUrl.openConnection());
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "PRoot-Toolchain-Downloader/1.0");

                    int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                            || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                            || responseCode == 307
                            || responseCode == 308) {
                        String newUrl = conn.getHeaderField("Location");
                        currentUrl = new URL(newUrl);
                        conn.disconnect();
                        redirects++;
                        continue;
                    }

                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        throw new IllegalStateException("HTTP server responded with " + responseCode);
                    }
                    break;
                }

                long totalBytes = conn.getContentLength();
                if (tempFile.exists()) tempFile.delete();
                tempFile.getParentFile().mkdirs();

                in = conn.getInputStream();
                out = new FileOutputStream(tempFile);
                byte[] buffer = new byte[BUFFER_SIZE];
                long downloadedBytes = 0;
                int bytesRead;
                long lastProgressTime = 0;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;

                    long now = System.currentTimeMillis();
                    if (now - lastProgressTime > 150 || downloadedBytes == totalBytes) {
                        lastProgressTime = now;
                        final long curBytes = downloadedBytes;
                        final int percent = totalBytes > 0 ? (int) ((curBytes * 100) / totalBytes) : -1;
                        postToMain(() -> callback.onProgress(percent, curBytes, totalBytes));
                    }
                }

                out.flush();
                out.close();
                out = null;
                in.close();
                in = null;

                // Atomic rename
                if (targetFile.exists()) targetFile.delete();
                if (!tempFile.renameTo(targetFile)) {
                    throw new IllegalStateException("Failed to finalize downloaded archive: rename failed");
                }

                postToMain(() -> {
                    callback.onProgress(100, targetFile.length(), targetFile.length());
                    callback.onSuccess(targetFile);
                });

            } catch (Exception e) {
                if (tempFile.exists()) tempFile.delete();
                postToMain(() -> callback.onError(e.getMessage() != null ? e.getMessage() : "下载失败", e));
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /**
     * Resolves a potentially broken absolute symlink or relative symlink
     * inside the guest rootfs directory.
     * When guest Linux creates an absolute symlink e.g. /opt/node/bin/node,
     * the host Android sees it as pointing to /opt/... which does not exist on host.
     * This method resolves it back into rootfsDir/opt/node/bin/node.
     */
    public static File resolveSymlinkInRootfs(File rootfsDir, File file) {
        if (file == null) return null;
        if (rootfsDir == null) return file;
        try {
            java.nio.file.Path p = file.toPath();
            if (java.nio.file.Files.isSymbolicLink(p)) {
                java.nio.file.Path target = java.nio.file.Files.readSymbolicLink(p);
                String targetStr = target.toString();
                if (targetStr.startsWith("/")) {
                    // Absolute path inside guest rootfs
                    File resolved = new File(rootfsDir, targetStr.substring(1));
                    if (resolved.exists()) {
                        return resolved;
                    }
                } else {
                    // Relative path from file's parent
                    File parent = file.getParentFile();
                    File resolved = new File(parent, targetStr).getCanonicalFile();
                    if (resolved.exists()) {
                        return resolved;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return file;
    }

    /**
     * Inspects ELF header e_machine field.
     * Returns true strictly if e_machine == 183 (EM_AARCH64).
     */
    public static boolean isElfArm64(File file) {
        if (file == null || !file.exists() || file.length() < 20) return false;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] hdr = new byte[20];
            raf.readFully(hdr);
            if (hdr[0] != 0x7f || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F') {
                return false;
            }
            int machine = (hdr[18] & 0xFF) | ((hdr[19] & 0xFF) << 8);
            return machine == 183;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Inspects ELF header e_machine field with rootfs symlink awareness.
     */
    public static boolean isElfArm64(File rootfsDir, File file) {
        File resolved = resolveSymlinkInRootfs(rootfsDir, file);
        return isElfArm64(resolved);
    }

    /**
     * Calculates SHA256 checksum of a file.
     */
    public static String calculateSha256(File file) {
        if (file == null || !file.exists()) return null;
        try (InputStream is = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUFFER_SIZE];
            int r;
            while ((r = is.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Synchronously downloads an archive from a URL with redirection handling.
     */
    public static boolean downloadSync(String urlString, File targetFile) {
        File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;

        try {
            URL currentUrl = new URL(urlString);
            int redirects = 0;

            while (redirects < 5) {
                java.net.Proxy proxy = NetworkProbeHelper.getActiveProxy();
                conn = (HttpURLConnection) (proxy != null ? currentUrl.openConnection(proxy) : currentUrl.openConnection());
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "PRoot-Toolchain-Downloader/1.0");

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                        || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                        || responseCode == 307
                        || responseCode == 308) {
                    String newUrl = conn.getHeaderField("Location");
                    currentUrl = new URL(newUrl);
                    conn.disconnect();
                    redirects++;
                    continue;
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return false;
                }
                break;
            }

            if (tempFile.exists()) tempFile.delete();
            tempFile.getParentFile().mkdirs();

            in = conn.getInputStream();
            out = new FileOutputStream(tempFile);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }

            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;

            if (targetFile.exists()) targetFile.delete();
            return tempFile.renameTo(targetFile);

        } catch (Exception e) {
            if (tempFile.exists()) tempFile.delete();
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }
}
