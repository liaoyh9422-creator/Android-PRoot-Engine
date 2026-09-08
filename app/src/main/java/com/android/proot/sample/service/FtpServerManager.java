package com.android.proot.sample.service;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in zero-dependency pure-Java FTP Server for wireless file transfer between PC and Android PRoot workspace.
 * Compliant with RFC 959 (PASV mode, auth, directory navigation, file upload/download).
 */
public final class FtpServerManager {
    private static final String TAG = "FtpServerManager";

    public interface StatusListener {
        void onStatusChanged(boolean running, String message);
    }

    private static volatile FtpServerManager sInstance;

    private int port = 2121;
    private String username = "proot";
    private String password = "proot";
    private boolean anonymous = false;
    private File rootDir;

    private ServerSocket controlSocket;
    private boolean isRunning = false;
    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private StatusListener statusListener;

    public static FtpServerManager getInstance() {
        if (sInstance == null) {
            synchronized (FtpServerManager.class) {
                if (sInstance == null) {
                    sInstance = new FtpServerManager();
                }
            }
        }
        return sInstance;
    }

    private FtpServerManager() {}

    public synchronized boolean isRunning() {
        return isRunning;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        if (!isRunning) this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isAnonymous() {
        return anonymous;
    }

    public void setAnonymous(boolean anonymous) {
        this.anonymous = anonymous;
    }

    public File getRootDir() {
        return rootDir;
    }

    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    public synchronized void start(Context context, StatusListener listener) {
        this.statusListener = listener;
        if (isRunning) {
            notifyStatus(true, "FTP 服务已在运行中 (端口 " + port + ")");
            return;
        }

        if (rootDir == null && context != null) {
            rootDir = new File(context.getFilesDir(), "workspace");
            if (!rootDir.exists()) rootDir.mkdirs();
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                controlSocket = new ServerSocket(port);
                isRunning = true;
                notifyStatus(true, "FTP 服务启动成功，监听端口: " + port);
                Log.i(TAG, "FTP Server started on port " + port + ", root=" + rootDir.getAbsolutePath());

                while (isRunning && !controlSocket.isClosed()) {
                    Socket client = controlSocket.accept();
                    clientPool.execute(new FtpSession(client, rootDir, username, password, anonymous));
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "FTP server error", e);
                    isRunning = false;
                    notifyStatus(false, "FTP 服务异常停止: " + e.getMessage());
                }
            }
        });
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        try {
            if (controlSocket != null && !controlSocket.isClosed()) {
                controlSocket.close();
            }
        } catch (Exception ignored) {}
        notifyStatus(false, "FTP 服务已停止");
        Log.i(TAG, "FTP Server stopped");
    }

    private void notifyStatus(boolean running, String msg) {
        mainHandler.post(() -> {
            if (statusListener != null) statusListener.onStatusChanged(running, msg);
        });
    }

    public static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.getAddress().length == 4) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    // =========================================================================
    // RFC 959 Client Session Handler
    // =========================================================================

    private static class FtpSession implements Runnable {
        private final Socket controlSocket;
        private final File baseDir;
        private final String expectedUser;
        private final String expectedPass;
        private final boolean allowAnon;

        private File currentDir;
        private boolean authenticated = false;
        private String userEntered = "";
        private ServerSocket pasvServer;
        private Socket pasvDataSocket;

        public FtpSession(Socket client, File baseDir, String user, String pass, boolean allowAnon) {
            this.controlSocket = client;
            this.baseDir = (baseDir != null && baseDir.exists()) ? baseDir : new File("/");
            this.currentDir = this.baseDir;
            this.expectedUser = user;
            this.expectedPass = pass;
            this.allowAnon = allowAnon;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                out.print("220 PRoot Engine FTP Ready\r\n");
                out.flush();

                String line;
                while ((line = in.readLine()) != null) {
                    String trim = line.trim();
                    if (trim.isEmpty()) continue;
                    int spaceIdx = trim.indexOf(' ');
                    String cmd = (spaceIdx == -1 ? trim : trim.substring(0, spaceIdx)).toUpperCase(Locale.ROOT);
                    String arg = (spaceIdx == -1 ? "" : trim.substring(spaceIdx + 1).trim());

                    if ("QUIT".equals(cmd)) {
                        out.print("221 Goodbye\r\n");
                        out.flush();
                        break;
                    }

                    handleCommand(cmd, arg, out);
                }
            } catch (Exception e) {
                Log.d(TAG, "FTP session closed: " + e.getMessage());
            } finally {
                closeQuietly(controlSocket);
                closePasv();
            }
        }

        private void handleCommand(String cmd, String arg, PrintWriter out) throws Exception {
            if ("USER".equals(cmd)) {
                userEntered = arg;
                if (allowAnon && "anonymous".equalsIgnoreCase(arg)) {
                    authenticated = true;
                    out.print("230 Anonymous user logged in\r\n");
                } else {
                    out.print("331 User name okay, need password\r\n");
                }
                out.flush();
                return;
            }

            if ("PASS".equals(cmd)) {
                if (allowAnon || (expectedUser.equals(userEntered) && expectedPass.equals(arg))) {
                    authenticated = true;
                    out.print("230 User logged in, proceed\r\n");
                } else {
                    out.print("530 Login incorrect\r\n");
                }
                out.flush();
                return;
            }

            if (!authenticated) {
                out.print("530 Please login first\r\n");
                out.flush();
                return;
            }

            switch (cmd) {
                case "SYST":
                    out.print("215 UNIX Type: L8\r\n");
                    break;
                case "FEAT":
                    out.print("211-Features:\r\n UTF8\r\n PASV\r\n211 End\r\n");
                    break;
                case "OPTS":
                    out.print("200 OPTS OK\r\n");
                    break;
                case "PWD":
                    out.print("257 \"" + getRelativePath() + "\" is current directory\r\n");
                    break;
                case "TYPE":
                    out.print("200 Type set to " + arg + "\r\n");
                    break;
                case "NOOP":
                    out.print("200 OK\r\n");
                    break;
                case "PASV":
                    setupPasv(out);
                    return;
                case "EPSV":
                    setupEpsv(out);
                    return;
                case "CWD":
                    changeDir(arg, out);
                    break;
                case "CDUP":
                    changeDir("..", out);
                    break;
                case "LIST":
                case "NLST":
                    listDir(out, "NLST".equals(cmd));
                    break;
                case "RETR":
                    downloadFile(arg, out);
                    break;
                case "STOR":
                    uploadFile(arg, out);
                    break;
                case "DELE":
                    deleteFile(arg, out);
                    break;
                case "MKD":
                    makeDir(arg, out);
                    break;
                case "RMD":
                    removeDir(arg, out);
                    break;
                case "SIZE":
                    getFileSize(arg, out);
                    break;
                default:
                    out.print("502 Command not implemented\r\n");
                    break;
            }
            out.flush();
        }

        private void setupPasv(PrintWriter out) throws Exception {
            closePasv();
            pasvServer = new ServerSocket(0, 1, controlSocket.getLocalAddress());
            int p = pasvServer.getLocalPort();
            InetAddress localAddr = controlSocket.getLocalAddress();
            byte[] ip = localAddr.getAddress();
            String pasvResp = String.format(Locale.ROOT, "227 Entering Passive Mode (%d,%d,%d,%d,%d,%d)\r\n",
                    (ip[0] & 0xFF), (ip[1] & 0xFF), (ip[2] & 0xFF), (ip[3] & 0xFF), (p >> 8) & 0xFF, p & 0xFF);
            out.print(pasvResp);
            out.flush();
        }

        private void setupEpsv(PrintWriter out) throws Exception {
            closePasv();
            pasvServer = new ServerSocket(0, 1, controlSocket.getLocalAddress());
            int p = pasvServer.getLocalPort();
            out.print("229 Entering Extended Passive Mode (|||" + p + "|)\r\n");
            out.flush();
        }

        private Socket obtainDataSocket() throws Exception {
            if (pasvServer != null) {
                pasvServer.setSoTimeout(10000);
                Socket s = pasvServer.accept();
                closePasv();
                return s;
            }
            return null;
        }

        private void closePasv() {
            if (pasvServer != null) {
                try { pasvServer.close(); } catch (Exception ignored) {}
                pasvServer = null;
            }
        }

        private void changeDir(String path, PrintWriter out) {
            File target;
            if (path.startsWith("/")) {
                target = new File(baseDir, path.substring(1));
            } else {
                target = new File(currentDir, path);
            }
            try {
                File canonTarget = target.getCanonicalFile();
                File canonBase = baseDir.getCanonicalFile();
                if (canonTarget.getAbsolutePath().startsWith(canonBase.getAbsolutePath()) && canonTarget.isDirectory()) {
                    currentDir = canonTarget;
                    out.print("250 Directory successfully changed\r\n");
                } else {
                    out.print("550 Failed to change directory\r\n");
                }
            } catch (Exception e) {
                out.print("550 Directory error\r\n");
            }
        }

        private void listDir(PrintWriter out, boolean namesOnly) {
            try (Socket data = obtainDataSocket()) {
                if (data == null) {
                    out.print("425 Can't open data connection\r\n");
                    return;
                }
                out.print("150 Opening ASCII mode data connection for file list\r\n");
                out.flush();

                try (PrintWriter dataOut = new PrintWriter(new OutputStreamWriter(data.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    File[] files = currentDir.listFiles();
                    if (files != null) {
                        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
                        for (File f : files) {
                            if (namesOnly) {
                                dataOut.print(f.getName() + "\r\n");
                            } else {
                                String type = f.isDirectory() ? "d" : "-";
                                String perms = type + "rwxr-xr-x 1 owner group " + f.length() + " " + sdf.format(new Date(f.lastModified())) + " " + f.getName() + "\r\n";
                                dataOut.print(perms);
                            }
                        }
                    }
                }
                out.print("226 Transfer complete\r\n");
            } catch (Exception e) {
                out.print("426 Connection closed; transfer aborted\r\n");
            }
        }

        private void downloadFile(String path, PrintWriter out) {
            File file = resolvePath(path);
            if (file == null || !file.exists() || file.isDirectory()) {
                out.print("550 File not found\r\n");
                return;
            }

            try (Socket data = obtainDataSocket()) {
                if (data == null) {
                    out.print("425 Can't open data connection\r\n");
                    return;
                }
                out.print("150 Opening BINARY mode data connection for " + file.getName() + "\r\n");
                out.flush();

                try (InputStream fis = new FileInputStream(file);
                     OutputStream os = data.getOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
                    os.flush();
                }
                out.print("226 Transfer complete\r\n");
            } catch (Exception e) {
                out.print("426 Connection closed; transfer aborted\r\n");
            }
        }

        private void uploadFile(String path, PrintWriter out) {
            File file = resolvePath(path);
            if (file == null) {
                out.print("550 Permission denied\r\n");
                return;
            }

            try (Socket data = obtainDataSocket()) {
                if (data == null) {
                    out.print("425 Can't open data connection\r\n");
                    return;
                }
                out.print("150 Ok to send data\r\n");
                out.flush();

                try (InputStream is = data.getInputStream();
                     OutputStream fos = new FileOutputStream(file)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                }
                out.print("226 Transfer complete\r\n");
            } catch (Exception e) {
                out.print("426 Connection closed; transfer aborted\r\n");
            }
        }

        private void deleteFile(String path, PrintWriter out) {
            File file = resolvePath(path);
            if (file != null && file.exists() && file.delete()) {
                out.print("250 File deleted successfully\r\n");
            } else {
                out.print("550 Could not delete file\r\n");
            }
        }

        private void makeDir(String path, PrintWriter out) {
            File file = resolvePath(path);
            if (file != null && file.mkdirs()) {
                out.print("257 Directory created\r\n");
            } else {
                out.print("550 Could not create directory\r\n");
            }
        }

        private void removeDir(String path, PrintWriter out) {
            File file = resolvePath(path);
            if (file != null && file.isDirectory() && file.delete()) {
                out.print("250 Directory removed\r\n");
            } else {
                out.print("550 Could not remove directory\r\n");
            }
        }

        private void getFileSize(String path, PrintWriter out) {
            File file = resolvePath(path);
            if (file != null && file.exists() && file.isFile()) {
                out.print("213 " + file.length() + "\r\n");
            } else {
                out.print("550 File not found\r\n");
            }
        }

        private File resolvePath(String path) {
            try {
                File target = path.startsWith("/") ? new File(baseDir, path.substring(1)) : new File(currentDir, path);
                File canonTarget = target.getCanonicalFile();
                File canonBase = baseDir.getCanonicalFile();
                if (canonTarget.getAbsolutePath().startsWith(canonBase.getAbsolutePath())) {
                    return canonTarget;
                }
            } catch (Exception ignored) {}
            return null;
        }

        private String getRelativePath() {
            String base = baseDir.getAbsolutePath();
            String cur = currentDir.getAbsolutePath();
            if (cur.startsWith(base)) {
                String rel = cur.substring(base.length());
                return rel.isEmpty() ? "/" : rel;
            }
            return "/";
        }

        private void closeQuietly(Socket s) {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
    }
}
