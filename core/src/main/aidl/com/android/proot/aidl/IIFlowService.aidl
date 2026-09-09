// IIFlowService.aidl
package com.android.proot.aidl;

import android.os.Bundle;

/**
 * AIDL interface for inter-process communication with iFlow PRoot engine.
 * Allows external callers (like Termux, Tasker, automation scripts) to
 * inspect engine status, execute container commands, control daemons, and sync configs.
 */
interface IIFlowService {
    // 1. Engine & Runtime Diagnostics
    boolean isEngineRunning();
    String getStatusSummary();
    Bundle getServicePorts();

    // 2. Container Command Execution (root execution within guest environment)
    String executeCommand(String command, String cwd, int timeoutMs);

    // 3. Workspace Management
    String getActiveWorkspace();
    boolean setActiveWorkspace(String path);

    // 4. Daemon Services Lifecycle (ssh, web, proxy)
    boolean controlService(String serviceName, String action);

    // 5. LLM Configuration Sync
    boolean updateLlmConfig(String baseUrl, String apiKey, String model, String reasoningEffort);
    String getLlmConfigJson();
}
