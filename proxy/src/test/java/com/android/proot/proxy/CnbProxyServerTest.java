package com.android.proot.proxy;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CnbProxyServerTest {

    private CnbProxyServer server;

    @Before
    public void setUp() {
        server = CnbProxyServer.getInstance();
        server.clearLogs();
    }

    @Test
    public void testLogBufferingAndListener() {
        List<String> receivedLogs = new ArrayList<>();
        ProxyLogListener listener = (tag, msg) -> receivedLogs.add(msg);

        server.addLogListener(listener);

        server.logMessage("TEST", "Hello World");
        server.logMessage("REQ", "POST /v1/chat/completions");

        assertEquals(2, receivedLogs.size());
        assertTrue(receivedLogs.get(0).contains("[TEST] Hello World"));
        assertTrue(receivedLogs.get(1).contains("[REQ] POST /v1/chat/completions"));

        List<String> recentLogs = server.getRecentLogs();
        assertEquals(2, recentLogs.size());
        assertTrue(recentLogs.get(0).contains("[TEST] Hello World"));

        server.removeLogListener(listener);
        server.logMessage("UP", "Token refreshed");

        // Listener should not receive further messages after removal
        assertEquals(2, receivedLogs.size());
        // But internal buffer should have it
        assertEquals(3, server.getRecentLogs().size());
    }

    @Test
    public void testLogBufferCapacityLimit() {
        for (int i = 0; i < 300; i++) {
            server.logMessage("FILL", "Message " + i);
        }

        List<String> logs = server.getRecentLogs();
        assertEquals(250, logs.size());
        // First log should be Message 50 (300 - 250)
        assertTrue(logs.get(0).contains("Message 50"));
        // Last log should be Message 299
        assertTrue(logs.get(249).contains("Message 299"));
    }

    @Test
    public void testClearLogs() {
        server.logMessage("A", "Test 1");
        server.logMessage("B", "Test 2");
        assertFalse(server.getRecentLogs().isEmpty());

        server.clearLogs();
        assertTrue(server.getRecentLogs().isEmpty());
    }

    @Test
    public void testMulticastStateListeners() {
        List<String> events1 = new ArrayList<>();
        List<String> events2 = new ArrayList<>();

        CnbProxyServer.StateListener l1 = new CnbProxyServer.StateListener() {
            @Override public void onStarting() { events1.add("starting"); }
            @Override public void onStarted(int port, String baseUrl) { events1.add("started:" + port); }
            @Override public void onStopped() { events1.add("stopped"); }
            @Override public void onError(String message, Throwable error) { events1.add("error:" + message); }
        };

        CnbProxyServer.StateListener l2 = new CnbProxyServer.StateListener() {
            @Override public void onStarting() { events2.add("starting"); }
            @Override public void onStarted(int port, String baseUrl) { events2.add("started:" + port); }
            @Override public void onStopped() { events2.add("stopped"); }
            @Override public void onError(String message, Throwable error) { events2.add("error:" + message); }
        };

        server.addStateListener(l1);
        server.addStateListener(l2);

        server.stop();

        // Both listeners should receive onStopped
        assertTrue(events1.contains("stopped"));
        assertTrue(events2.contains("stopped"));

        server.removeStateListener(l1);
        events1.clear();
        events2.clear();

        server.stop();

        // l1 should not receive, l2 should still receive
        assertFalse(events1.contains("stopped"));
        assertTrue(events2.contains("stopped"));

        server.removeStateListener(l2);
    }
}
