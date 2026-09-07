package com.android.proot.proxy;

/**
 * Listener interface for proxy server log and error reporting.
 */
@FunctionalInterface
public interface ProxyLogListener {
    void onLog(String tag, String message);

    default void onError(String message, Throwable throwable) {
        onLog("ERROR", message + (throwable != null ? ": " + throwable.getMessage() : ""));
    }
}
