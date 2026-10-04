package com.cloblab.gateway;

import com.cloblab.model.Side;
import com.cloblab.protocol.InboundCommand;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayTest {
    @Test
    void perFrameMarketDataReachesSubscribers() throws Exception {
        Gateway gateway = new Gateway(1, 32);
        var frames = new CopyOnWriteArrayList<GatewayFrame>();
        CountDownLatch latch = new CountDownLatch(2);
        gateway.subscribe(frame -> {
            frames.add(frame);
            latch.countDown();
        });
        gateway.start();
        try {
            assertTrue(gateway.submitLimit(0, 1, Side.SELL, 100, 5).accepted());
            assertTrue(gateway.submitLimit(0, 2, Side.BUY, 100, 5).accepted());
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } finally {
            gateway.shutdown();
        }

        assertEquals(2, frames.size());
        assertEquals(1, frames.get(1).trades().size());
        assertFalse(frames.get(1).snapshots().isEmpty());
    }

    @Test
    void duplicateOrderIdAndZeroQtyAreRejectedWithoutChangingTheBook() throws Exception {
        Gateway gateway = new Gateway(1, 32);
        CountDownLatch resting = new CountDownLatch(1);
        gateway.subscribe(frame -> resting.countDown());
        gateway.start();
        try {
            GatewayResult first = gateway.submitLimit(0, 1, Side.BUY, 100, 5);
            assertEquals(GatewayResult.Status.ACCEPTED, first.status());
            assertTrue(resting.await(2, TimeUnit.SECONDS));

            var book = gateway.exchange().router().shard(0).engine().book();
            assertEquals(100L, book.bestBid());
            assertEquals(5L, book.totalBidQuantity());
            assertTrue(book.contains(1));

            GatewayResult duplicate = gateway.submitLimit(0, 1, Side.BUY, 99, 3);
            assertEquals(GatewayResult.Status.REJECTED, duplicate.status());
            assertTrue(duplicate.reason().contains("duplicate"));

            GatewayResult zeroQty = gateway.submitLimit(0, 2, Side.BUY, 99, 0);
            assertEquals(GatewayResult.Status.REJECTED, zeroQty.status());
            assertTrue(zeroQty.reason().contains("quantity"));

            assertEquals(100L, book.bestBid());
            assertEquals(5L, book.totalBidQuantity());
            assertTrue(book.contains(1));
            assertFalse(book.contains(2));
        } finally {
            gateway.shutdown();
        }
    }

    @Test
    void unknownSymbolIsRejectedNotThrown() {
        Gateway gateway = new Gateway(1, 8);
        GatewayResult result = gateway.submitLimit(9, 1, Side.BUY, 100, 1);
        assertEquals(GatewayResult.Status.REJECTED, result.status());
        assertFalse(result.accepted());
    }

    @Test
    void ringFullIsRejected() {
        Gateway gateway = new Gateway(1, 2);
        assertTrue(gateway.submitLimit(0, 1, Side.BUY, 100, 1).accepted());
        assertTrue(gateway.submitLimit(0, 2, Side.BUY, 99, 1).accepted());
        GatewayResult overflow = gateway.submitLimit(0, 3, Side.BUY, 98, 1);
        assertEquals(GatewayResult.Status.REJECTED, overflow.status());
        assertTrue(overflow.reason().contains("ring"));
        assertFalse(gateway.exchange().router().shard(0).engine().book().contains(1));
        assertFalse(gateway.exchange().router().shard(0).engine().book().contains(3));
    }

    @Test
    void submitKindsRouteWithoutThrowing() throws Exception {
        Gateway gateway = new Gateway(1, 32);
        CountDownLatch latch = new CountDownLatch(4);
        gateway.subscribe(frame -> latch.countDown());
        gateway.start();
        try {
            assertTrue(gateway.submit(InboundCommand.submitLimit(0, 1, Side.SELL, 100, 10)).accepted());
            assertTrue(gateway.submitIoc(0, 2, Side.BUY, 100, 1).accepted());
            assertTrue(gateway.submitFok(0, 3, Side.BUY, 100, 1).accepted());
            assertTrue(gateway.submitMarket(0, 4, Side.BUY, 1).accepted());
            assertTrue(gateway.cancel(0, 1).accepted());
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } finally {
            gateway.shutdown();
        }
    }
}
