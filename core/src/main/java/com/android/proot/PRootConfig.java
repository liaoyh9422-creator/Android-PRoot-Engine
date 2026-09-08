package com.android.proot;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution configuration and options builder for launching Linux binaries via PRoot.
 */
public class PRootConfig {
    private final String executable;
    private final File hostExecutableFile;
    private final List<String> args;
    private final String workDir;
    private final boolean fakeRoot;
    private final boolean link2Symlink;
    private final boolean redirectErrorStream;
    private final Map<String, String> envVars;
    private final List<String> bindMounts;

    private PRootConfig(Builder builder) {
        this.executable = builder.executable;
        this.hostExecutableFile = builder.hostExecutableFile;
        this.args = Collections.unmodifiableList(new ArrayList<>(builder.args));
        this.workDir = builder.workDir;
        this.fakeRoot = builder.fakeRoot;
        this.link2Symlink = builder.link2Symlink;
        this.redirectErrorStream = builder.redirectErrorStream;
        this.envVars = Collections.unmodifiableMap(new LinkedHashMap<>(builder.envVars));
        this.bindMounts = Collections.unmodifiableList(new ArrayList<>(builder.bindMounts));
    }

    public String getExecutable() { return executable; }
    public File getHostExecutableFile() { return hostExecutableFile; }
    public List<String> getArgs() { return args; }
    public String getWorkDir() { return workDir; }
    public boolean isFakeRoot() { return fakeRoot; }
    public boolean isLink2Symlink() { return link2Symlink; }
    public boolean isRedirectErrorStream() { return redirectErrorStream; }
    public Map<String, String> getEnvVars() { return envVars; }
    public List<String> getBindMounts() { return bindMounts; }

    public static class Builder {
        private String executable;
        private File hostExecutableFile;
        private final List<String> args = new ArrayList<>();
        private String workDir = "/app";
        private boolean fakeRoot = true;
        private boolean link2Symlink = true;
        private boolean redirectErrorStream = true;
        private final Map<String, String> envVars = new LinkedHashMap<>();
        private final List<String> bindMounts = new ArrayList<>();

        /**
         * Sets an executable residing inside the guest rootfs (e.g. "/bin/sh", "/usr/bin/python3").
         */
        public Builder setExecutable(String executablePathInRootfs) {
            this.executable = executablePathInRootfs;
            this.hostExecutableFile = null;
            return this;
        }

        /**
         * Sets a host executable file located on the Android filesystem (e.g. in filesDir or cacheDir).
         * PRoot will automatically bind-mount this file and make it executable.
         */
        public Builder setExecutable(File hostExecutableFile) {
            this.hostExecutableFile = hostExecutableFile;
            this.executable = null;
            return this;
        }

        public Builder addArg(String arg) {
            if (arg != null) this.args.add(arg);
            return this;
        }

        public Builder addArgs(String... args) {
            if (args != null) {
                for (String a : args) {
                    if (a != null) this.args.add(a);
                }
            }
            return this;
        }

        public Builder addArgs(List<String> args) {
            if (args != null) {
                for (String a : args) {
                    if (a != null) this.args.add(a);
                }
            }
            return this;
        }

        public Builder setWorkDir(String workDir) {
            if (workDir != null && !workDir.isEmpty()) {
                this.workDir = workDir;
            }
            return this;
        }

        public Builder setFakeRoot(boolean fakeRoot) {
            this.fakeRoot = fakeRoot;
            return this;
        }

        public Builder setLink2Symlink(boolean link2Symlink) {
            this.link2Symlink = link2Symlink;
            return this;
        }

        public Builder setRedirectErrorStream(boolean redirect) {
            this.redirectErrorStream = redirect;
            return this;
        }

        public Builder addEnv(String key, String value) {
            if (key != null && value != null) {
                this.envVars.put(key, value);
            }
            return this;
        }

        public Builder addEnvs(Map<String, String> envs) {
            if (envs != null) {
                this.envVars.putAll(envs);
            }
            return this;
        }

        /**
         * Adds a bind mount, e.g. "/host/path:/guest/path" or "/host/path".
         */
        public Builder addBind(String bind) {
            if (bind != null && !bind.trim().isEmpty()) {
                this.bindMounts.add(bind.trim());
            }
            return this;
        }

        /**
         * Adds a host-to-guest bind mount.
         */
        public Builder addBind(String hostPath, String guestPath) {
            if (hostPath != null && guestPath != null) {
                this.bindMounts.add(hostPath.trim() + ":" + guestPath.trim());
            }
            return this;
        }

        public PRootConfig build() {
            if (executable == null && hostExecutableFile == null) {
                throw aerialIllegalArg("Executable must be specified (via setExecutable).");
            }
            return new PRootConfig(this);
        }

        private IllegalArgumentException aerialIllegalArg(String msg) {
            return new IllegalArgumentException(msg);
        }
    }
}
