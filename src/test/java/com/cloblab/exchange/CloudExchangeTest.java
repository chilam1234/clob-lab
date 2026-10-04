package com.cloblab.exchange;

import com.cloblab.model.Side;
import com.cloblab.pipeline.PriorityIngress;
import com.cloblab.protocol.InboundCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudExchangeTest {
    @Test
    void sequencerAssignsMonotonicGlobalOrder() {
        CloudExchange exchange = new CloudExchange(1, 64, 10);
        exchange.submit(InboundCommand.submitLimit(0, 1, Side.BUY, 100, 5));
        exchange.submit(InboundCommand.submitLimit(0, 2, Side.SELL, 101, 5));
        exchange.flushBatch();
        assertEquals(3, exchange.sequencer().nextSequenceValue());
    }

    @Test
    void burstReordersCriticalOrdersFirst() {
        CloudExchange exchange = new CloudExchange(1, 64, 2);
        exchange.submit(InboundCommand.submitLimit(0, 1, Side.SELL, 101, 1));
        exchange.submit(InboundCommand.submitLimit(0, 2, Side.BUY, 99, 10));
        exchange.flushBatch();

        // Arrival: far aggressive (10), closer aggressive (11), at mid (12).
        // Mid = 100. Distances: 105→5, 101→1, 100→0. FancyPQ should match 11 before 10.
        exchange.submit(InboundCommand.submitLimit(0, 10, Side.BUY, 105, 1));
        exchange.submit(InboundCommand.submitLimit(0, 11, Side.BUY, 101, 1));
        exchange.submit(InboundCommand.submitLimit(0, 12, Side.BUY, 100, 1));
        var tick = exchange.flushBatch();

        assertEquals(1, tick.release().trades().size());
        assertEquals(11, tick.release().trades().get(0).takerOrderId());
        assertEquals(105L, exchange.router().shard(0).engine().book().bestBid());
    }

    @Test
    void fairMarketDataReleaseIncludesTradesAndSnapshots() {
        CloudExchange exchange = new CloudExchange(1, 64, 10);
        exchange.submit(InboundCommand.submitLimit(0, 1, Side.SELL, 100, 5));
        exchange.submit(InboundCommand.submitLimit(0, 2, Side.BUY, 100, 5));
        var tick = exchange.flushBatch();
        assertTrue(tick.release() != null);
        assertEquals(1, tick.release().trades().size());
        assertTrue(tick.release().snapshots().size() >= 1);
    }

    @Test
    void multiSymbolRoutesToCorrectShard() {
        CloudExchange exchange = new CloudExchange(2, 64, 10);
        exchange.submit(InboundCommand.submitLimit(0, 1, Side.BUY, 100, 5));
        exchange.submit(InboundCommand.submitLimit(1, 2, Side.SELL, 200, 7));
        exchange.flushBatch();
        assertEquals(100L, exchange.router().shard(0).engine().book().bestBid());
        assertEquals(200L, exchange.router().shard(1).engine().book().bestAsk());
    }

    @Test
    void journalRecordsPipelineSubmissions() {
        CloudExchange exchange = new CloudExchange(1, 64, 10);
        exchange.submit(InboundCommand.submitLimit(0, 1, Side.BUY, 100, 5));
        exchange.submit(InboundCommand.cancel(0, 1));
        exchange.flushBatch();
        assertEquals(2, exchange.journal().size());
    }

    @Test
    void priorityScoreCloserToMidIsHigher() {
        CloudExchange exchange = new CloudExchange(1, 64, 10);
        exchange.submit(InboundCommand.submitLimit(0, 1, Side.SELL, 101, 5));
        exchange.submit(InboundCommand.submitLimit(0, 2, Side.BUY, 99, 5));
        exchange.flushBatch();
        var book = exchange.router().shard(0).engine().book();
        long nearMid = PriorityIngress.criticalityScore(
                InboundCommand.submitLimit(0, 3, Side.BUY, 100, 1), book);
        long farMid = PriorityIngress.criticalityScore(
                InboundCommand.submitLimit(0, 4, Side.BUY, 90, 1), book);
        assertTrue(nearMid > farMid);
    }

    @Test
    void submitQueuesToIngressAndDoesNotTouchTheRing() {
        CloudExchange exchange = new CloudExchange(1, 2, 10);

        assertTrue(exchange.submit(InboundCommand.submitLimit(0, 1, Side.BUY, 100, 5)));
        assertTrue(exchange.submit(InboundCommand.submitLimit(0, 2, Side.BUY, 99, 5)));
        assertTrue(exchange.submit(InboundCommand.submitLimit(0, 3, Side.BUY, 98, 5)));

        assertEquals(3, exchange.pendingIngress());
        assertEquals(null, exchange.router().shard(0).engine().book().bestBid());
    }

    @Test
    void flushDefersWhenShardRingIsFullThenDrainsOnNextFlush() {
        CloudExchange exchange = new CloudExchange(1, 2, 10);
        exchange.submit(InboundCommand.submitLimit(0, 1, Side.BUY, 100, 1));
        exchange.submit(InboundCommand.submitLimit(0, 2, Side.BUY, 99, 1));
        exchange.submit(InboundCommand.submitLimit(0, 3, Side.BUY, 98, 1));

        var first = exchange.flushBatch();
        assertEquals(2, first.routed());
        assertEquals(2, first.processed());
        assertEquals(1, first.deferred());
        assertEquals(1, exchange.pendingIngress());
        assertEquals(100L, exchange.router().shard(0).engine().book().bestBid());
        assertFalse(exchange.router().shard(0).engine().book().contains(3));

        var second = exchange.flushBatch();
        assertEquals(1, second.routed());
        assertEquals(1, second.processed());
        assertEquals(0, second.deferred());
        assertEquals(0, exchange.pendingIngress());
        assertTrue(exchange.router().shard(0).engine().book().contains(3));
    }
}
