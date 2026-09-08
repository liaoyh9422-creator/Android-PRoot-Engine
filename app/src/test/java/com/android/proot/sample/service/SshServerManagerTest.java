package com.android.proot.sample.service;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class SshServerManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testEscapePassword() {
        Assert.assertEquals("proot", SshServerManager.escapePassword("proot"));
        Assert.assertEquals("my'\\''pass", SshServerManager.escapePassword("my'pass"));
        Assert.assertEquals("proot", SshServerManager.escapePassword(null));
    }

    @Test
    public void testPortAndPassGettersSetters() {
        SshServerManager ssh = SshServerManager.getInstance();
        ssh.setPort(2222);
        Assert.assertEquals(2222, ssh.getPort());

        ssh.setPort(22222);
        Assert.assertEquals(22222, ssh.getPort());

        // Invalid port ignored
        ssh.setPort(-1);
        Assert.assertEquals(22222, ssh.getPort());

        ssh.setPort(70000);
        Assert.assertEquals(22222, ssh.getPort());

        // Reset to 2222
        ssh.setPort(2222);
        Assert.assertEquals(2222, ssh.getPort());

        ssh.setPassword("secret123");
        Assert.assertEquals("secret123", ssh.getPassword());
        ssh.setPassword("proot");
    }

    @Test
    public void testBinaryDetection_notInstalled() {
        File fakeDir = new File(tempFolder.getRoot(), "empty-rootfs");
        fakeDir.mkdirs();

        SshServerManager ssh = SshServerManager.getInstance();
        Assert.assertFalse(ssh.isInstalled(fakeDir));
        Assert.assertFalse(ssh.isSshdInstalled(fakeDir));
        Assert.assertFalse(ssh.isDropbearInstalled(fakeDir));
        Assert.assertEquals("Not Installed", ssh.getSshBinaryType(fakeDir));
    }

    @Test
    public void testBinaryDetection_dropbearOnly() throws Exception {
        File rootfs = tempFolder.newFolder("dropbear-rootfs");
        File sbin = new File(rootfs, "usr/sbin");
        sbin.mkdirs();
        new File(sbin, "dropbear").createNewFile();

        SshServerManager ssh = SshServerManager.getInstance();
        Assert.assertTrue(ssh.isInstalled(rootfs));
        Assert.assertFalse(ssh.isSshdInstalled(rootfs));
        Assert.assertTrue(ssh.isDropbearInstalled(rootfs));
        Assert.assertEquals("Dropbear", ssh.getSshBinaryType(rootfs));
    }

    @Test
    public void testBinaryDetection_sshdPreferred() throws Exception {
        File rootfs = tempFolder.newFolder("openssh-rootfs");
        File sbin = new File(rootfs, "usr/sbin");
        sbin.mkdirs();
        new File(sbin, "dropbear").createNewFile();
        new File(sbin, "sshd").createNewFile();

        SshServerManager ssh = SshServerManager.getInstance();
        Assert.assertTrue(ssh.isInstalled(rootfs));
        Assert.assertTrue(ssh.isSshdInstalled(rootfs));
        Assert.assertTrue(ssh.isDropbearInstalled(rootfs));
        Assert.assertEquals("OpenSSH (sshd)", ssh.getSshBinaryType(rootfs));
    }
}
