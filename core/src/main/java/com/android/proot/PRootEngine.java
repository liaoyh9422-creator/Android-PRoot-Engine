package com.android.proot;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core user-space virtualization engine for running GNU Linux ARM64 binaries on Android without Root.
 */
public class PRootEngine {
    private static final String TAG = "PRootEngine";

    private final Context context;
    private final File filesDir;
    private final File rootfsDir;
    private final File glibcDir;
    private final File tmpDir;
    private final List<String> customDnsServers = new ArrayList<>();

    private String nativeLibDir;
    private String prootPath;
    private String loaderPath;
    private String linkerPath;
    private String bashPath;
    private boolean initialized = false;

    public PRootEngine(Context context) {
        this(context, context.getFilesDir());
    }

    public PRootEngine(Context context, File baseDir) {
        this.context = context.getApplicationContext();
        this.filesDir = baseDir;
        this.rootfsDir = new File(baseDir, "proot-rootfs");
        this.glibcDir = new File(baseDir, "glibc");
        this.tmpDir = new File(baseDir, "tmp");
        this.tmpDir.mkdirs();
    }

    /**
     * Sets custom DNS servers to use inside the virtualized environment.
     */
    public void setCustomDnsServers(List<String> dnsServers) {
        this.customDnsServers.clear();
        if (dnsServers != null) {
            this.customDnsServers.addAll(dnsServers);
        }
    }

    /**
     * Initializes the container environment:
     * 1. Probes native binaries (libproot.so, libloader.so, libldlinux.so).
     * 2. Extracts and verifies glibc runtime assets (glibc-libs-arm64.tar.bin).
     * 3. Constructs the guest rootfs directory skeleton, DNS resolv.conf, and CA certificates.
     *
     * @return true if initialization succeeded and engine is ready for execution.
     */
    public synchronized boolean initialize() {
        if (!probeNativeLibs()) {
            Log.e(TAG, "PRoot native libraries (libproot.so, libloader.so) not found in app lib directories.");
            return false;
        }

        boolean hasAlpine = setupAlpineRootfs();
        if (!hasAlpine) {
            Log.w(TAG, "Alpine rootfs asset not present, falling back to glibc setup...");
            if (!setupGlibcLibs()) {
                Log.e(TAG, "Failed to setup runtime libraries.");
                return false;
            }
        } else {
            // Optional auxiliary glibc runtime
            setupGlibcLibs();
        }

        setupRootfs();
        this.initialized = true;
        Log.i(TAG, "PRootEngine successfully initialized.");
        return true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Launches a Linux executable within the PRoot sandbox using the specified configuration.
     */
    public PRootProcess launch(PRootConfig config) throws IOException {
        if (!initialized) {
            if (!initialize()) {
                throw new IllegalStateException("PRootEngine failed to initialize.");
            }
        }

        List<String> cmd = buildCommandLine(config);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(config.isRedirectErrorStream());

        pb.environment().putAll(buildEnvironment(config));
        Process process = pb.start();
        return new PRootProcess(process);
    }

    /**
     * Builds the complete environment variable map for a container session.
     */
    public Map<String, String> buildEnvironment(PRootConfig config) {
        Map<String, String> env = new HashMap<>();
        if (nativeLibDir != null) {
            env.put("LD_LIBRARY_PATH", nativeLibDir);
        }
        if (loaderPath != null) {
            env.put("PROOT_LOADER", loaderPath);
        }
        env.put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
        env.put("TMPDIR", "/tmp");
        env.put("HOME", "/root");
        env.put("USER", "root");
        env.put("PATH", "/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin");
        env.put("GODEBUG", "netdns=go");
        env.put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt");
        env.put("SSL_CERT_DIR", "/etc/ssl/certs");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("ENV", "/etc/profile");
        env.putAll(config.getEnvVars());
        return env;
    }

    /**
     * Builds the complete PRoot argument array.
     */
    public List<String> buildCommandLine(PRootConfig config) {
        List<String> cmd = new ArrayList<>();
        cmd.add(prootPath);
        cmd.add("-r");
        cmd.add(rootfsDir.getAbsolutePath());

        if (config.isLink2Symlink()) {
            cmd.add("--link2symlink");
        }
        if (config.isFakeRoot()) {
            cmd.add("--root-id");
        }

        cmd.add("--cwd=" + config.getWorkDir());

        // Standard system bind mounts
        cmd.add("-b"); cmd.add("/dev");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add("/data");
        cmd.add("-b"); cmd.add("/sdcard");
        cmd.add("-b"); cmd.add("/storage");
        if (new File("/system").exists()) {
            cmd.add("-b"); cmd.add("/system");
        }

        boolean isAlpine = new File(rootfsDir, "bin/busybox").exists();

        // Glibc dynamic linker
        if (linkerPath != null && !isAlpine) {
            cmd.add("-b"); cmd.add(linkerPath + ":/lib/ld-linux-aarch64.so.1");
        }

        // Glibc shared libraries (auxiliary)
        File lib64Dir = new File(glibcDir, "usr/lib64");
        if (lib64Dir.exists() && !isAlpine) {
            cmd.add("-b"); cmd.add(lib64Dir.getAbsolutePath() + ":/usr/lib64");
            cmd.add("-b"); cmd.add(lib64Dir.getAbsolutePath() + ":/lib64");
        }

        File binDir = new File(glibcDir, "usr/bin");
        if (binDir.isDirectory() && !isAlpine) {
            cmd.add("-b"); cmd.add(binDir.getAbsolutePath() + ":/usr/bin");
        }

        // App workspace and temporary directory
        cmd.add("-b"); cmd.add(filesDir.getAbsolutePath() + ":/app");
        cmd.add("-b"); cmd.add(tmpDir.getAbsolutePath() + ":/tmp");

        // Network and CA certificate bindings (fallback)
        if (!isAlpine) {
            File etcDir = new File(rootfsDir, "etc");
            File resolvConf = new File(etcDir, "resolv.conf");
            if (resolvConf.exists()) {
                cmd.add("-b"); cmd.add(resolvConf.getAbsolutePath() + ":/etc/resolv.conf");
            }
            File mergedCert = new File(etcDir, "ssl/certs/ca-certificates.crt");
            if (mergedCert.exists()) {
                cmd.add("-b"); cmd.add(mergedCert.getAbsolutePath() + ":/etc/ssl/certs/ca-certificates.crt");
            }
        }

        // Embedded shell fallback / bash mapping (only for glibc fallback, NEVER for Alpine)
        if (!isAlpine) {
            File guestSh = new File(rootfsDir, "bin/sh");
            if (!guestSh.exists() && bashPath != null) {
                cmd.add("-b"); cmd.add(bashPath + ":/bin/sh");
            } else if (bashPath != null) {
                cmd.add("-b"); cmd.add(bashPath + ":/bin/bash");
            }
        }

        // User custom bind mounts
        for (String bind : config.getBindMounts()) {
            cmd.add("-b");
            cmd.add(bind);
        }

        // Executable handling
        if (config.getHostExecutableFile() != null) {
            File hostFile = config.getHostExecutableFile();
            hostFile.setExecutable(true, false);
            String guestBinaryPath = "/usr/local/bin/" + hostFile.getName();
            cmd.add("-b");
            cmd.add(hostFile.getAbsolutePath() + ":" + guestBinaryPath);
            cmd.add(guestBinaryPath);
        } else {
            cmd.add(config.getExecutable());
        }

        // Executable arguments
        cmd.addAll(config.getArgs());

        return cmd;
    }

    /**
     * Probes native libraries in nativeLibraryDir and fallback locations.
     */
    private boolean probeNativeLibs() {
        prootPath = getNativeLib("libproot.so");
        loaderPath = getNativeLib("libloader.so");
        linkerPath = getNativeLib("libldlinux.so");
        bashPath = getNativeLib("libbash.so");

        if (linkerPath == null) {
            File ld = new File(glibcDir, "lib/ld-linux-aarch64.so.1");
            if (ld.exists()) {
                linkerPath = ld.getAbsolutePath();
            }
        }

        Log.d(TAG, "Native libs: proot=" + prootPath + ", loader=" + loaderPath + ", linker=" + linkerPath);
        return prootPath != null && loaderPath != null;
    }

    /**
     * Resolves a native shared library file path by name.
     */
    public String getNativeLib(String name) {
        nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        File f = new File(nativeLibDir, name);
        if (f.exists()) return f.getAbsolutePath();

        String dataDir = context.getApplicationInfo().dataDir;
        String[] altDirs = {
                nativeLibDir.replace("arm64", "arm64-v8a"),
                nativeLibDir.replace("arm64-v8a", "arm64"),
                dataDir + "/lib/arm64-v8a",
                dataDir + "/lib/arm64",
                dataDir + "/lib"
        };
        for (String dir : altDirs) {
            File alt = new File(dir, name);
            if (alt.exists()) {
                nativeLibDir = dir;
                return alt.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Extracts pre-configured Alpine Linux rootfs (containing apk, ca-certificates, and aichat agent).
     */
    private boolean setupAlpineRootfs() {
        File busybox = new File(rootfsDir, "bin/busybox");
        File marker = new File(rootfsDir, ".alpine_done_v6");

        if (busybox.exists() && marker.exists()) {
            return true;
        }

        boolean hasAsset = false;
        try (InputStream is = context.getAssets().open("alpine-rootfs-arm64.tar.bin")) {
            hasAsset = (is != null);
        } catch (Exception ignored) {}

        if (!hasAsset) {
            return busybox.exists();
        }

        try {
            Log.i(TAG, "Extracting pre-configured Alpine rootfs to: " + rootfsDir.getAbsolutePath());
            rootfsDir.mkdirs();
            AssetExtractor.extractAssetTar(context, "alpine-rootfs-arm64.tar.bin", rootfsDir);

            // Ensure critical permissions
            File bb = new File(rootfsDir, "bin/busybox");
            if (bb.exists()) bb.setExecutable(true, false);
            File bashBin = new File(rootfsDir, "bin/bash");
            if (bashBin.exists()) bashBin.setExecutable(true, false);
            File apkBin = new File(rootfsDir, "sbin/apk");
            if (apkBin.exists()) apkBin.setExecutable(true, false);
            File pigoBin = new File(rootfsDir, "usr/local/bin/pigo");
            if (pigoBin.exists()) pigoBin.setExecutable(true, false);

            // Ensure bin/sh exists and uses a relative symlink to busybox
            // Absolute symlinks like '/bin/busybox' break when checked on Android host
            File sh = new File(rootfsDir, "bin/sh");
            if (bb.exists()) {
                sh.delete();
                try {
                    android.system.Os.symlink("busybox", sh.getAbsolutePath());
                } catch (Exception e) {
                    // Fallback to copy if symlink creation is not permitted
                    try (InputStream in = new java.io.FileInputStream(bb);
                         OutputStream out = new FileOutputStream(sh)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                }
                sh.setExecutable(true, false);
                sh.setReadable(true, false);
            }

            boolean ok = bb.exists();
            if (ok) {
                try (FileWriter fw = new FileWriter(marker)) {
                    fw.write("ready");
                }
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract Alpine rootfs", e);
            return false;
        }
    }

    /**
     * Extracts glibc runtime slice from assets if not already unpacked.
     */
    private boolean setupGlibcLibs() {
        File libc = new File(glibcDir, "usr/lib64/libc.so.6");
        File linker = new File(glibcDir, "lib/ld-linux-aarch64.so.1");
        File uname = new File(glibcDir, "usr/bin/uname");
        File marker = new File(glibcDir, ".done");

        if (libc.exists() && (linker.exists() || linkerPath != null) && uname.exists() && marker.exists()) {
            return true;
        }

        glibcDir.mkdirs();

        // Check if glibc asset exists
        boolean hasAsset = false;
        try (InputStream is = context.getAssets().open("glibc-libs-arm64.tar.bin")) {
            hasAsset = (is != null);
        } catch (Exception ignored) {}

        if (!hasAsset) {
            return libc.exists();
        }

        try {
            Log.i(TAG, "Extracting glibc runtime asset to: " + glibcDir.getAbsolutePath());
            File tarFile = new File(glibcDir, "glibc-libs.tar.gz");
            try (InputStream is = context.getAssets().open("glibc-libs-arm64.tar.bin");
                 FileOutputStream fos = new FileOutputStream(tarFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }

            Process tarProc = new ProcessBuilder("tar", "xzf", tarFile.getAbsolutePath())
                    .directory(glibcDir)
                    .redirectErrorStream(true)
                    .start();
            try {
                tarProc.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            tarFile.delete();

            File libDir = new File(glibcDir, "usr/lib64");
            if (libDir.exists()) {
                File[] libs = libDir.listFiles();
                if (libs != null) {
                    for (File lib : libs) lib.setReadable(true, false);
                }
            }

            File binDir = new File(glibcDir, "usr/bin");
            if (binDir.exists()) {
                File[] bins = binDir.listFiles();
                if (bins != null) {
                    for (File bin : bins) {
                        bin.setExecutable(true, false);
                        bin.setReadable(true, false);
                    }
                }
            }

            boolean ok = new File(glibcDir, "usr/lib64/libc.so.6").exists();
            if (ok) {
                try (FileWriter fw = new FileWriter(marker)) {
                    fw.write("ready");
                }
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract glibc runtime", e);
            return false;
        }
    }

    /**
     * Builds rootfs directory skeleton and generates DNS and CA configurations.
     */
    private void setupRootfs() {
        new File(rootfsDir, "lib").mkdirs();
        new File(rootfsDir, "usr/lib64").mkdirs();
        new File(rootfsDir, "lib64").mkdirs();
        new File(rootfsDir, "usr/local/bin").mkdirs();
        new File(rootfsDir, "usr/bin").mkdirs();
        new File(rootfsDir, "bin").mkdirs();
        new File(rootfsDir, "tmp").mkdirs();
        new File(rootfsDir, "app").mkdirs();
        new File(rootfsDir, "sdcard").mkdirs();
        new File(rootfsDir, "storage").mkdirs();
        new File(rootfsDir, "proc").mkdirs();
        new File(rootfsDir, "dev").mkdirs();
        new File(rootfsDir, "sys").mkdirs();
        new File(rootfsDir, "system").mkdirs();
        new File(rootfsDir, "etc").mkdirs();

        CaCertHelper.setupNetworkConfig(rootfsDir, customDnsServers);
        CaCertHelper.setupCaCertificates(rootfsDir);
    }

    public File getRootfsDir() { return rootfsDir; }
    public File getGlibcDir() { return glibcDir; }
    public File getFilesDir() { return filesDir; }
    public File getTmpDir() { return tmpDir; }
    public String getProotPath() { return prootPath; }
    public String getLoaderPath() { return loaderPath; }
    public String getLinkerPath() { return linkerPath; }
}
