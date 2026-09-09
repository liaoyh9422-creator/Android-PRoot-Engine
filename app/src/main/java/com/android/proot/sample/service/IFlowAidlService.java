package com.android.proot.sample.service;

import android.app.Service;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.android.proot.aidl.IIFlowService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android Service exposing IIFlowService AIDL Binder IPC and abstract LocalSocket bridge.
 * Discovered by Termux and external Android apps via action "com.android.proot.action.IFLOW_SERVICE".
 */
public class IFlowAidlService extends Service {
    private static final String TAG = "IFlowAidlService";
    public static final String ACTION_IFLOW_SERVICE = "com.android.proot.action.IFLOW_SERVICE";
    public static final String ABSTRACT_SOCKET_NAME = "iflow_ipc_socket";

    private IFlowIpcDispatcher dispatcher;
    private final ExecutorService socketExecutor = Executors.newCachedThreadPool();
    private volatile LocalServerSocket localServerSocket;
    private volatile boolean isListening = false;

    private final IIFlowService.Stub mBinder = new IIFlowService.Stub() {
        @Override
        public boolean isEngineRunning() {
            return dispatcher.isEngineRunning();
        }

        @Override
        public String getStatusSummary() {
            return dispatcher.getStatusSummary();
        }

        @Override
        public Bundle getServicePorts() {
            return dispatcher.getServicePortsBundle();
        }

        @Override
        public String executeCommand(String command, String cwd, int timeoutMs) {
            return dispatcher.executeCommand(command, cwd, timeoutMs);
        }

        @Override
        public String getActiveWorkspace() {
            return dispatcher.getActiveWorkspace();
        }

        @Override
        public boolean setActiveWorkspace(String path) {
            return dispatcher.setActiveWorkspace(path);
        }

        @Override
        public boolean controlService(String serviceName, String action) {
            return dispatcher.controlService(serviceName, action);
        }

        @Override
        public boolean updateLlmConfig(String baseUrl, String apiKey, String model, String reasoningEffort) {
            return dispatcher.updateLlmConfig(baseUrl, apiKey, model, reasoningEffort);
        }

        @Override
        public String getLlmConfigJson() {
            return dispatcher.getLlmConfigJson();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        dispatcher = IFlowIpcDispatcher.getInstance(this);
        startAbstractSocketBridge();
        deployTermuxHelperScript();
        Log.i(TAG, "IFlowAidlService started and ready for AIDL and LocalSocket IPC.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Keep service alive in background
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Binding incoming intent: " + intent);
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAbstractSocketBridge();
        Log.i(TAG, "IFlowAidlService destroyed.");
    }

    private void startAbstractSocketBridge() {
        socketExecutor.execute(() -> {
            try {
                localServerSocket = new LocalServerSocket(ABSTRACT_SOCKET_NAME);
                isListening = true;
                Log.i(TAG, "LocalServerSocket listening on abstract @" + ABSTRACT_SOCKET_NAME);

                while (isListening && localServerSocket != null) {
                    try {
                        LocalSocket client = localServerSocket.accept();
                        handleSocketClient(client);
                    } catch (Exception e) {
                        if (!isListening) break;
                        Log.w(TAG, "LocalSocket accept error: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed creating LocalServerSocket: " + e.getMessage());
            }
        });
    }

    private void handleSocketClient(LocalSocket client) {
        socketExecutor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    String resp = dispatcher.handleJsonRequest(line);
                    writer.write(resp);
                    writer.write("\n");
                    writer.flush();
                }
            } catch (Exception ignored) {
            } finally {
                try { client.close(); } catch (Exception ignored) {}
            }
        });
    }

    private void stopAbstractSocketBridge() {
        isListening = false;
        try {
            if (localServerSocket != null) {
                localServerSocket.close();
                localServerSocket = null;
            }
        } catch (Exception ignored) {}
        socketExecutor.shutdownNow();
    }

    /**
     * Deploys the convenient Termux CLI script 'iflow-ipc' to /sdcard/iflow-ipc
     * and /data/data/com.termux/files/usr/bin/iflow-ipc if accessible.
     */
    private void deployTermuxHelperScript() {
        socketExecutor.execute(() -> {
            try {
                String script = "#!/usr/bin/env bash\n" +
                        "# iflow-ipc: Zero-network bridge to iFlow Android AIDL & LocalSocket Service\n" +
                        "PYTHON_BIN=$(command -v python3 || command -v python || true)\n" +
                        "if [ -z \"$PYTHON_BIN\" ]; then\n" +
                        "  echo \"Error: python3 is required in Termux for iflow-ipc bridge.\"\n" +
                        "  exit 1\n" +
                        "fi\n\n" +
                        "case \"$1\" in\n" +
                        "  status|ports|config)\n" +
                        "    \"$PYTHON_BIN\" -c '\n" +
                        "import socket, sys, json\n" +
                        "s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)\n" +
                        "try:\n" +
                        "    s.connect(\"\\0iflow_ipc_socket\")\n" +
                        "    s.sendall(json.dumps({\"action\": sys.argv[1]}).encode(\"utf-8\") + b\"\\n\")\n" +
                        "    res = s.recv(16384).decode(\"utf-8\").strip()\n" +
                        "    print(res)\n" +
                        "except Exception as e:\n" +
                        "    print(f\"[Error connecting to iFlow IPC]: {e}\")\n" +
                        "    sys.exit(1)\n" +
                        "finally:\n" +
                        "    s.close()\n" +
                        "' \"$1\"\n" +
                        "    ;;\n" +
                        "  exec)\n" +
                        "    shift\n" +
                        "    CMD=\"$*\"\n" +
                        "    if [ -z \"$CMD\" ]; then\n" +
                        "      echo \"Usage: iflow-ipc exec <command>\"\n" +
                        "      exit 1\n" +
                        "    fi\n" +
                        "    \"$PYTHON_BIN\" -c '\n" +
                        "import socket, sys, json\n" +
                        "s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)\n" +
                        "try:\n" +
                        "    s.connect(\"\\0iflow_ipc_socket\")\n" +
                        "    payload = {\"action\": \"exec\", \"command\": sys.argv[1]}\n" +
                        "    s.sendall(json.dumps(payload).encode(\"utf-8\") + b\"\\n\")\n" +
                        "    res = s.recv(65536).decode(\"utf-8\").strip()\n" +
                        "    data = json.loads(res)\n" +
                        "    if \"output\" in data:\n" +
                        "        print(data[\"output\"], end=\"\")\n" +
                        "    else:\n" +
                        "        print(res)\n" +
                        "except Exception as e:\n" +
                        "    print(f\"[Error connecting to iFlow IPC]: {e}\")\n" +
                        "    sys.exit(1)\n" +
                        "finally:\n" +
                        "    s.close()\n" +
                        "' \"$CMD\"\n" +
                        "    ;;\n" +
                        "  workspace)\n" +
                        "    \"$PYTHON_BIN\" -c '\n" +
                        "import socket, sys, json\n" +
                        "s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)\n" +
                        "try:\n" +
                        "    s.connect(\"\\0iflow_ipc_socket\")\n" +
                        "    payload = {\"action\": \"workspace\"}\n" +
                        "    if len(sys.argv) > 1 and sys.argv[1]:\n" +
                        "        payload[\"path\"] = sys.argv[1]\n" +
                        "    s.sendall(json.dumps(payload).encode(\"utf-8\") + b\"\\n\")\n" +
                        "    print(s.recv(16384).decode(\"utf-8\").strip())\n" +
                        "except Exception as e:\n" +
                        "    print(f\"[Error connecting to iFlow IPC]: {e}\")\n" +
                        "    sys.exit(1)\n" +
                        "finally:\n" +
                        "    s.close()\n" +
                        "' \"$2\"\n" +
                        "    ;;\n" +
                        "  service)\n" +
                        "    \"$PYTHON_BIN\" -c '\n" +
                        "import socket, sys, json\n" +
                        "s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)\n" +
                        "try:\n" +
                        "    s.connect(\"\\0iflow_ipc_socket\")\n" +
                        "    payload = {\"action\": \"service\", \"name\": sys.argv[1], \"op\": sys.argv[2]}\n" +
                        "    s.sendall(json.dumps(payload).encode(\"utf-8\") + b\"\\n\")\n" +
                        "    print(s.recv(16384).decode(\"utf-8\").strip())\n" +
                        "except Exception as e:\n" +
                        "    print(f\"[Error connecting to iFlow IPC]: {e}\")\n" +
                        "    sys.exit(1)\n" +
                        "finally:\n" +
                        "    s.close()\n" +
                        "' \"$2\" \"${3:-start}\"\n" +
                        "    ;;\n" +
                        "  *)\n" +
                        "    echo \"iFlow IPC CLI - Connects to iFlow Android AIDL / LocalSocket Bridge\"\n" +
                        "    echo \"Usage:\"\n" +
                        "    echo \"  iflow-ipc status                  Show container, service, and engine status\"\n" +
                        "    echo \"  iflow-ipc ports                   Get dynamic SSH, Web, and Proxy ports\"\n" +
                        "    echo \"  iflow-ipc exec <command>          Run command directly inside PRoot container\"\n" +
                        "    echo \"  iflow-ipc workspace [path]        Get or set active workspace\"\n" +
                        "    echo \"  iflow-ipc service <ssh|web> <start|stop|restart>\"\n" +
                        "    echo \"  iflow-ipc config                  Get LLM configuration\"\n" +
                        "    ;;\n" +
                        "esac\n";

                // Save to /sdcard/iflow-ipc
                File sdcardTarget = new File("/sdcard/iflow-ipc");
                try (FileOutputStream fos = new FileOutputStream(sdcardTarget)) {
                    fos.write(script.getBytes(StandardCharsets.UTF_8));
                    sdcardTarget.setExecutable(true, false);
                }

                // Try save to Termux bin if directory exists
                File termuxBin = new File("/data/data/com.termux/files/usr/bin/iflow-ipc");
                if (termuxBin.getParentFile() != null && termuxBin.getParentFile().exists()) {
                    try (FileOutputStream fos = new FileOutputStream(termuxBin)) {
                        fos.write(script.getBytes(StandardCharsets.UTF_8));
                        termuxBin.setExecutable(true, false);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed deploying helper script: " + e.getMessage());
            }
        });
    }
}
