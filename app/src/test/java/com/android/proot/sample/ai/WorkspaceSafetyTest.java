package com.android.proot.sample.ai;

import org.junit.Test;

import static org.junit.Assert.*;

public class WorkspaceSafetyTest {

    @Test
    public void testSanitizePathValid() {
        assertEquals("/root", WorkspaceManager.sanitizePath("/root"));
        assertEquals("/sdcard/projects", WorkspaceManager.sanitizePath("/sdcard/projects/"));
        assertEquals("/sdcard/Download", WorkspaceManager.sanitizePath("  /sdcard/Download  "));
        assertEquals("/a/b/c", WorkspaceManager.sanitizePath("/a/b/c///"));
    }

    @Test
    public void testSanitizePathInvalid() {
        assertNull(WorkspaceManager.sanitizePath(null));
        assertNull(WorkspaceManager.sanitizePath(""));
        assertNull(WorkspaceManager.sanitizePath("   "));
        assertNull(WorkspaceManager.sanitizePath("relative/path"));
        assertNull(WorkspaceManager.sanitizePath("/bad\npath"));
        assertNull(WorkspaceManager.sanitizePath("/bad\rpath"));
        assertNull(WorkspaceManager.sanitizePath("/bad\0path"));
    }

    @Test
    public void testCommandInjectionDefense() {
        // Attack pattern 1: attempting to chain commands with semicolon
        String malicious1 = "/sdcard/projects'; rm -rf /; echo '";
        String safeCmd1 = WorkspaceManager.buildSafeCdCommand(malicious1);
        // Ensure single quotes were escaped with '\''
        assertTrue(safeCmd1.contains("'\\''"));
        assertTrue(safeCmd1.startsWith("cd -- '"));

        // Attack pattern 2: attempting command substitution with backticks or $(...)
        String malicious2 = "/sdcard/projects/$(rm -rf /)";
        String safeCmd2 = WorkspaceManager.buildSafeCdCommand(malicious2);
        // In bash, single quotes prevent variable expansion and command substitution
        assertTrue(safeCmd2.startsWith("cd -- '/sdcard/projects/$(rm -rf /)'"));
    }

    @Test
    public void testFormattingHelpers() {
        assertEquals("0 B", IFlowSessionManager.formatFileSize(0));
        assertEquals("500 B", IFlowSessionManager.formatFileSize(500));
        assertEquals("1.0 KB", IFlowSessionManager.formatFileSize(1024));
        assertEquals("1.0 MB", IFlowSessionManager.formatFileSize(1024 * 1024));
    }
}
