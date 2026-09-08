package com.android.proot.sample.helper;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

public class ToolchainDownloaderElfTest {

    @Test
    public void testElfArm64Detection() throws IOException {
        File tempArm64 = File.createTempFile("elf_arm64_", ".bin");
        tempArm64.deleteOnExit();

        byte[] arm64Hdr = new byte[32];
        arm64Hdr[0] = 0x7f;
        arm64Hdr[1] = 'E';
        arm64Hdr[2] = 'L';
        arm64Hdr[3] = 'F';
        arm64Hdr[4] = 2; // 64-bit
        arm64Hdr[5] = 1; // little endian
        // e_machine at byte 18-19: 183 = 0x00B7 (little endian: b[18]=0xB7, b[19]=0x00)
        arm64Hdr[18] = (byte) 0xB7;
        arm64Hdr[19] = 0x00;

        try (FileOutputStream fos = new FileOutputStream(tempArm64)) {
            fos.write(arm64Hdr);
        }

        assertTrue(ToolchainDownloader.isElfArm64(tempArm64));
    }

    @Test
    public void testElfX86Rejected() throws IOException {
        File tempX86 = File.createTempFile("elf_x86_", ".bin");
        tempX86.deleteOnExit();

        byte[] x86Hdr = new byte[32];
        x86Hdr[0] = 0x7f;
        x86Hdr[1] = 'E';
        x86Hdr[2] = 'L';
        x86Hdr[3] = 'F';
        // e_machine at byte 18-19: 62 = 0x003E (EM_X86_64)
        x86Hdr[18] = 0x3E;
        x86Hdr[19] = 0x00;

        try (FileOutputStream fos = new FileOutputStream(tempX86)) {
            fos.write(x86Hdr);
        }

        // Must reject non-ARM64!
        assertFalse(ToolchainDownloader.isElfArm64(tempX86));
    }

    @Test
    public void testNonElfFileRejected() throws IOException {
        File tempText = File.createTempFile("text_", ".txt");
        tempText.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tempText)) {
            fos.write("echo Hello World".getBytes());
        }

        assertFalse(ToolchainDownloader.isElfArm64(tempText));
        assertFalse(ToolchainDownloader.isElfArm64(null));
        assertFalse(ToolchainDownloader.isElfArm64(new File("/non/existent/path")));
    }

    @Test
    public void testSha256Calculation() throws IOException {
        File tempFile = File.createTempFile("sha_", ".bin");
        tempFile.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write("hello world".getBytes());
        }

        String hash = ToolchainDownloader.calculateSha256(tempFile);
        assertNotNull(hash);
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash);
    }

    @Test
    public void testSymlinkResolutionInRootfs() throws IOException {
        File tempDir = File.createTempFile("rootfs_test_", "");
        tempDir.delete();
        tempDir.mkdirs();
        tempDir.deleteOnExit();

        File optBin = new File(tempDir, "opt/node/bin");
        optBin.mkdirs();
        File realBinary = new File(optBin, "node");

        byte[] arm64Hdr = new byte[32];
        arm64Hdr[0] = 0x7f; arm64Hdr[1] = 'E'; arm64Hdr[2] = 'L'; arm64Hdr[3] = 'F';
        arm64Hdr[4] = 2; arm64Hdr[5] = 1;
        arm64Hdr[18] = (byte) 0xB7; arm64Hdr[19] = 0x00;
        try (FileOutputStream fos = new FileOutputStream(realBinary)) {
            fos.write(arm64Hdr);
        }

        File usrBin = new File(tempDir, "usr/bin");
        usrBin.mkdirs();
        File symlink = new File(usrBin, "node");

        // Create absolute symlink (simulating guest proot perspective: /opt/node/bin/node)
        java.nio.file.Files.createSymbolicLink(symlink.toPath(), java.nio.file.Paths.get("/opt/node/bin/node"));

        // Host directly cannot resolve this absolute symlink because /opt does not exist on host
        assertFalse(symlink.exists());

        // But resolveSymlinkInRootfs must resolve it properly!
        File resolved = ToolchainDownloader.resolveSymlinkInRootfs(tempDir, symlink);
        assertNotNull(resolved);
        assertTrue(resolved.exists());
        assertEquals(realBinary.getAbsolutePath(), resolved.getAbsolutePath());

        // And isElfArm64 with rootfsDir must succeed!
        assertTrue(ToolchainDownloader.isElfArm64(tempDir, symlink));
    }
}
