package com.cloblab.gateway;

import com.cloblab.model.Side;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayHaltTest {

    @Test
    void haltRejectsSubmitButAcceptsCancel() throws Exception {
        Gateway gateway = new Gateway(1, 32);
        CountDownLatch resting = new CountDownLatch(1);
        gateway.subscribe(frame -> resting.countDown());
        gateway.start();
        try {
            assertTrue(gateway.submitLimit(0, 1, Side.BUY, 100, 5).accepted());
            assertTrue(resting.await(2, TimeUnit.SECONDS));

            gateway.halt();
            assertTrue(gateway.halted());

            GatewayResult submit = gateway.submitLimit(0, 2, Side.BUY, 99, 1);
            assertEquals(GatewayResult.Status.REJECTED, submit.status());
            assertEquals("HALTED", submit.reason());

            GatewayResult cancel = gateway.cancel(0, 1);
            assertTrue(cancel.accepted());

            gateway.resume();
            assertFalse(gateway.halted());
            assertTrue(gateway.submitLimit(0, 3, Side.BUY, 98, 1).accepted());
        } finally {
            gateway.shutdown();
        }
    }
}
