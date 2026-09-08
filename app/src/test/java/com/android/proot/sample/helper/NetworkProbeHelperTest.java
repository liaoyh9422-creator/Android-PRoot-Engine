package com.android.proot.sample.helper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class NetworkProbeHelperTest {

    @Before
    @After
    public void tearDown() {
        NetworkProbeHelper.clearActiveProxy();
    }

    @Test
    public void testActiveProxyLifecycle() {
        assertNull("Initial proxy should be null", NetworkProbeHelper.getActiveProxy());
        assertNull("Initial proxy description should be null", NetworkProbeHelper.getActiveProxyDescription());

        NetworkProbeHelper.setExplicitProxy("127.0.0.1", 7890);
        Proxy activeProxy = NetworkProbeHelper.getActiveProxy();
        assertNotNull("Proxy must not be null after setting", activeProxy);
        assertEquals(Proxy.Type.HTTP, activeProxy.type());

        InetSocketAddress address = (InetSocketAddress) activeProxy.address();
        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(7890, address.getPort());
        assertEquals("127.0.0.1:7890", NetworkProbeHelper.getActiveProxyDescription());

        NetworkProbeHelper.clearActiveProxy();
        assertNull("Proxy should be cleared", NetworkProbeHelper.getActiveProxy());
        assertNull("Description should be cleared", NetworkProbeHelper.getActiveProxyDescription());
    }

    @Test
    public void testProbeCallbackInvocation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean invoked = new AtomicBoolean(false);

        NetworkProbeHelper.testGoogleConnectivity(new NetworkProbeHelper.ProbeCallback() {
            @Override
            public void onSuccess(long latencyMs, String channelInfo) {
                invoked.set(true);
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                invoked.set(true);
                latch.countDown();
            }
        });

        // Ensure asynchronous probe finishes and triggers either success or error callback
        boolean finished = latch.await(10, TimeUnit.SECONDS);
        assertTrue("Probe must complete asynchronously within timeout", finished);
        assertTrue("Callback must be invoked", invoked.get());
    }
}
