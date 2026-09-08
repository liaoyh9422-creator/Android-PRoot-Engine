package com.android.proot.sample.terminal;

import org.junit.Test;

import static org.junit.Assert.*;

public class TerminalSessionItemTest {

    @Test
    public void testSessionItemInitializationAndDefaults() {
        TerminalSessionItem item = new TerminalSessionItem(
                1, 1, "bash", "/workspace", new String[]{"/bin/bash", "-l"}, null, 1234
        );

        assertEquals(1, item.getId());
        assertEquals(1, item.getNumber());
        assertEquals("bash", item.getTitle());
        assertEquals("/workspace", item.getWorkDir());
        assertArrayEquals(new String[]{"/bin/bash", "-l"}, item.getCmdArgs());
        assertEquals(1234, item.getPid());
        assertNull(item.getSession());
        // Since session is null, isRunning() evaluates safely to false
        assertFalse(item.isRunning());
        assertEquals(0, item.getExitCode());
        assertTrue(item.getCreatedAt() > 0);

        assertEquals("#1:bash", item.getShortLabel());
        assertEquals("PID 1234 • /workspace", item.getDetailSummary());
    }

    @Test
    public void testSessionItemEdgeCases() {
        // Null or whitespace title defaults to bash
        TerminalSessionItem itemNull = new TerminalSessionItem(
                2, 2, null, null, null, null, 5678
        );
        assertEquals("bash", itemNull.getTitle());
        assertEquals("/root", itemNull.getWorkDir());
        assertEquals(0, itemNull.getCmdArgs().length);
        assertEquals("#2:bash", itemNull.getShortLabel());
        assertEquals("PID 5678 • /root", itemNull.getDetailSummary());

        // Update title dynamically
        itemNull.setTitle("iflow");
        assertEquals("iflow", itemNull.getTitle());
        assertEquals("#2:iflow", itemNull.getShortLabel());

        // Ignoring invalid title updates
        itemNull.setTitle("   ");
        assertEquals("iflow", itemNull.getTitle());

        itemNull.setTitle(null);
        assertEquals("iflow", itemNull.getTitle());

        // Exit status update
        itemNull.setExitCode(130);
        assertEquals(130, itemNull.getExitCode());
    }
}
