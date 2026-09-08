package com.android.proot.sample.tool;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class EnvironmentManagerDefinitionsTest {

    @Test
    public void testToolchainDefinitions() {
        EnvironmentManager manager = EnvironmentManager.getInstance();
        assertNotNull(manager);

        List<EnvironmentManager.ToolchainItem> items = manager.getToolchains();
        assertEquals(5, items.size());

        // 1. Base tools
        EnvironmentManager.ToolchainItem base = manager.getItem(EnvironmentManager.ID_BASE);
        assertNotNull(base);
        assertTrue(base.components.contains("git"));
        assertTrue(base.components.contains("make"));

        // 2. Android Core
        EnvironmentManager.ToolchainItem android = manager.getItem(EnvironmentManager.ID_ANDROID);
        assertNotNull(android);
        assertTrue(android.components.contains("aapt2"));
        assertTrue(android.components.contains("android-35"));

        // 3. C/C++ NDK
        EnvironmentManager.ToolchainItem cpp = manager.getItem(EnvironmentManager.ID_CPP);
        assertNotNull(cpp);
        assertTrue(cpp.components.contains("clang"));
        assertTrue(cpp.components.contains("cmake"));

        // 4. Rust JNI
        EnvironmentManager.ToolchainItem rust = manager.getItem(EnvironmentManager.ID_RUST);
        assertNotNull(rust);
        assertTrue(rust.components.contains("rustc"));
        assertTrue(rust.components.contains("cargo"));

        // 5. Go JNI
        EnvironmentManager.ToolchainItem go = manager.getItem(EnvironmentManager.ID_GO);
        assertNotNull(go);
        assertTrue(go.components.contains("go"));
        assertTrue(go.components.contains("cgo"));
    }
}
