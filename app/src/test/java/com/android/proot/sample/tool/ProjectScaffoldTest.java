package com.android.proot.sample.tool;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class ProjectScaffoldTest {

    private File tempRootfs;

    @Before
    public void setUp() throws IOException {
        tempRootfs = Files.createTempDirectory("test_rootfs_").toFile();
    }

    @After
    public void tearDown() {
        if (tempRootfs != null && tempRootfs.exists()) {
            deleteRecursive(tempRootfs);
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    @Test
    public void testScaffoldAndroidProject() throws IOException {
        String guestPath = ProjectScaffoldManager.createProject(
                tempRootfs, "/workspace", "my_test_apk", ProjectScaffoldManager.ProjectType.ANDROID_APK);
        assertEquals("/workspace/my_test_apk", guestPath);

        File projDir = new File(tempRootfs, "workspace/my_test_apk");
        assertTrue(projDir.exists());
        assertTrue(new File(projDir, "AndroidManifest.xml").exists());
        assertTrue(new File(projDir, "src/com/example/my_test_apk/MainActivity.java").exists());
        assertTrue(new File(projDir, "build.sh").exists());
        assertTrue(new File(projDir, "build.sh").canExecute());
    }

    @Test
    public void testScaffoldCppProject() throws IOException {
        String guestPath = ProjectScaffoldManager.createProject(
                tempRootfs, "/workspace", "cpp_app", ProjectScaffoldManager.ProjectType.CPP_CMAKE);
        assertEquals("/workspace/cpp_app", guestPath);

        File projDir = new File(tempRootfs, "workspace/cpp_app");
        assertTrue(projDir.exists());
        assertTrue(new File(projDir, "CMakeLists.txt").exists());
        assertTrue(new File(projDir, "src/main.cpp").exists());
        assertTrue(new File(projDir, "src/calc.h").exists());
        assertTrue(new File(projDir, "src/calc.cpp").exists());
        assertTrue(new File(projDir, "build.sh").exists());
    }

    @Test
    public void testScaffoldRustProject() throws IOException {
        String guestPath = ProjectScaffoldManager.createProject(
                tempRootfs, "/workspace", "rust_demo", ProjectScaffoldManager.ProjectType.RUST_JNI);
        assertEquals("/workspace/rust_demo", guestPath);

        File projDir = new File(tempRootfs, "workspace/rust_demo");
        assertTrue(projDir.exists());
        assertTrue(new File(projDir, "Cargo.toml").exists());
        assertTrue(new File(projDir, "src/main.rs").exists());
        assertTrue(new File(projDir, "src/lib.rs").exists());
        assertTrue(new File(projDir, "build.sh").exists());
    }

    @Test
    public void testScaffoldGoProject() throws IOException {
        String guestPath = ProjectScaffoldManager.createProject(
                tempRootfs, "/workspace", "go_demo", ProjectScaffoldManager.ProjectType.GO_JNI);
        assertEquals("/workspace/go_demo", guestPath);

        File projDir = new File(tempRootfs, "workspace/go_demo");
        assertTrue(projDir.exists());
        assertTrue(new File(projDir, "go.mod").exists());
        assertTrue(new File(projDir, "main.go").exists());
        assertTrue(new File(projDir, "jni_export.go").exists());
        assertTrue(new File(projDir, "build.sh").exists());
    }
}
