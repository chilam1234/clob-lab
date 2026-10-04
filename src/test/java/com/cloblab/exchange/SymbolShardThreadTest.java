package com.cloblab.exchange;

import com.cloblab.journal.EventJournal;
import com.cloblab.marketdata.FairMarketDataPublisher;
import com.cloblab.model.Side;
import com.cloblab.pipeline.Sequencer;
import com.cloblab.protocol.InboundCommand;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolShardThreadTest {
    @Test
    void dedicatedConsumerDrainsFifoAndShutdownDoesNotDropInFlight() throws Exception {
        EventJournal journal = new EventJournal();
        FairMarketDataPublisher md = new FairMarketDataPublisher();
        SymbolShard shard = new SymbolShard(0, 32, md, journal);
        Sequencer sequencer = new Sequencer();

        CountDownLatch frames = new CountDownLatch(8);
        md.subscribe(release -> frames.countDown());

        shard.start();
        shard.start();
        try {
            for (int i = 1; i <= 8; i++) {
                assertTrue(shard.enqueue(sequencer.stamp(
                        InboundCommand.submitLimit(0, i, Side.BUY, 90 + i, 1))));
            }
        } finally {
            shard.shutdown();
        }

        assertEquals(8, journal.size(), "in-flight ring commands must be drained on shutdown");
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, journal.events().get(i).orderId());
        }
        assertTrue(frames.await(2, TimeUnit.SECONDS), "per-frame MD should fire once per command");
        assertEquals(98L, shard.engine().book().bestBid());
        assertTrue(shard.engine().book().contains(8));
    }

    @Test
    void perFrameReleaseReachesSubscribers() throws Exception {
        FairMarketDataPublisher md = new FairMarketDataPublisher();
        SymbolShard shard = new SymbolShard(0, 8, md, new EventJournal());
        Sequencer sequencer = new Sequencer();
        var releases = new CopyOnWriteArrayList<FairMarketDataPublisher.FairRelease>();
        CountDownLatch frames = new CountDownLatch(2);
        md.subscribe(release -> {
            releases.add(release);
            frames.countDown();
        });

        shard.start();
        try {
            assertTrue(shard.enqueue(sequencer.stamp(
                    InboundCommand.submitLimit(0, 1, Side.SELL, 100, 5))));
            assertTrue(shard.enqueue(sequencer.stamp(
                    InboundCommand.submitLimit(0, 2, Side.BUY, 100, 5))));
            assertTrue(frames.await(2, TimeUnit.SECONDS));
        } finally {
            shard.shutdown();
        }

        assertEquals(2, releases.size());
        assertEquals(1, releases.get(0).snapshots().size());
        assertTrue(releases.get(0).trades().isEmpty());
        assertEquals(1, releases.get(1).trades().size());
        assertEquals(2, releases.get(1).trades().get(0).takerOrderId());
    }

    @Test
    void cloudExchangeStartAndShutdownJoinShardThreads() throws Exception {
        CloudExchange exchange = new CloudExchange(1, 8, 10);
        CountDownLatch released = new CountDownLatch(1);
        exchange.marketData().subscribe(r -> released.countDown());
        exchange.start();
        try {
            assertTrue(exchange.router().shard(0).enqueue(
                    exchange.sequencer().stamp(InboundCommand.submitLimit(0, 1, Side.BUY, 100, 3))));
            assertTrue(released.await(2, TimeUnit.SECONDS));
            assertEquals(100L, exchange.router().shard(0).engine().book().bestBid());
        } finally {
            exchange.shutdown();
        }
    }
}
