package com.cloblab.exchange;

import com.cloblab.journal.StripedEventJournal;
import com.cloblab.marketdata.FairMarketDataPublisher;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolShardWatchdogTest {

    @Test
    void uncaughtPathSetsFailedAndRunsInjectableExitAction() {
        StripedEventJournal journal = new StripedEventJournal(1);
        SymbolShard shard = new SymbolShard(0, 8, new FairMarketDataPublisher(), journal.stripe(0));
        AtomicInteger exits = new AtomicInteger();
        shard.setExitAction(exits::incrementAndGet);

        assertFalse(shard.failed());
        shard.onConsumerDeath(Thread.currentThread(), new RuntimeException("boom"));

        assertTrue(shard.failed());
        assertEquals(1, exits.get());
        assertFalse(shard.isAlive());
    }

    @Test
    void startInstallsHandlerAndExitActionDefaultsDoNotRunOnCleanShutdown() throws Exception {
        StripedEventJournal journal = new StripedEventJournal(1);
        SymbolShard shard = new SymbolShard(0, 8, new FairMarketDataPublisher(), journal.stripe(0));
        AtomicInteger exits = new AtomicInteger();
        shard.setExitAction(exits::incrementAndGet);
        shard.start();
        assertTrue(shard.isAlive());
        shard.shutdown();
        assertEquals(0, exits.get());
        assertFalse(shard.failed());
    }
}
