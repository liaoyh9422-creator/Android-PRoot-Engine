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
}
