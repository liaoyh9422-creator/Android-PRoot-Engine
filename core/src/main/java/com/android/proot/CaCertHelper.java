package com.android.proot;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;

/**
 * Helper to synthesize standard Linux CA certificates and DNS configuration from Android system.
 */
public class CaCertHelper {
    private static final String TAG = "PRoot:CaCertHelper";

    /**
     * Aggregates Android system CA certificates (/system/etc/security/cacerts)
     * into a single standard PEM bundle (etc/ssl/certs/ca-certificates.crt).
     */
    public static void setupCaCertificates(File rootfsDir) {
        try {
            File sslCertsDir = new File(rootfsDir, "etc/ssl/certs");
            sslCertsDir.mkdirs();
            File androidCaDir = new File("/system/etc/security/cacerts");
            if (!androidCaDir.exists()) return;

            File[] caFiles = androidCaDir.listFiles();
            if (caFiles == null || caFiles.length == 0) return;

            File mergedCert = new File(sslCertsDir, "ca-certificates.crt");
            try (PrintWriter merged = new PrintWriter(mergedCert)) {
                for (File caFile : caFiles) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(caFile)))) {
                        String line;
                        while ((line = br.readLine()) != null) merged.println(line);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to setup CA certificates: " + e.getMessage());
        }
    }

    /**
     * Creates standard resolv.conf, nsswitch.conf, and hosts files in rootfs/etc.
     */
    public static void setupNetworkConfig(File rootfsDir, List<String> customDnsServers) {
        try {
            File etcDir = new File(rootfsDir, "etc");
            etcDir.mkdirs();

            // 1. resolv.conf
            File resolvConf = new File(etcDir, "resolv.conf");
            try (PrintWriter pw = new PrintWriter(resolvConf)) {
                if (customDnsServers != null && !customDnsServers.isEmpty()) {
                    for (String dns : customDnsServers) {
                        pw.println("nameserver " + dns);
                    }
                } else {
                    // Default high-availability public DNS servers
                    pw.println("nameserver 223.5.5.5");
                    pw.println("nameserver 119.29.29.29");
                    pw.println("nameserver 114.114.114.114");
                    pw.println("nameserver 8.8.8.8");
                    pw.println("nameserver 1.1.1.1");
                }
                pw.println("options timeout:2 attempts:2");
            }

            // 2. nsswitch.conf
            File nsswitchConf = new File(etcDir, "nsswitch.conf");
            try (PrintWriter ns = new PrintWriter(nsswitchConf)) {
                ns.println("hosts: files dns");
                ns.println("networks: files dns");
            }

            // 3. hosts
            File hostsFile = new File(etcDir, "hosts");
            try (PrintWriter hosts = new PrintWriter(hostsFile)) {
                hosts.println("127.0.0.1 localhost");
                hosts.println("::1 localhost");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write network config: " + e.getMessage());
        }
    }
}
