package com.cloblab.marketdata;

import com.cloblab.model.Trade;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0010: per-symbol publishers share no staging state — concurrent stage+flush
 * on distinct instances must not interleave trades across releases.
 */
class FairMarketDataPublisherPerSymbolTest {

    @Test
    void concurrentStageAndFlushPerSymbolSharesNoState() throws Exception {
        int symbols = 6;
        int stagesPerSymbol = 200;
        FairMarketDataPublisher[] publishers = new FairMarketDataPublisher[symbols];
        List<List<FairMarketDataPublisher.FairRelease>> releasesBySymbol = new ArrayList<>(symbols);
        for (int s = 0; s < symbols; s++) {
            publishers[s] = new FairMarketDataPublisher();
            List<FairMarketDataPublisher.FairRelease> bucket =
                    Collections.synchronizedList(new ArrayList<>());
            releasesBySymbol.add(bucket);
            publishers[s].subscribe(bucket::add);
        }

        CyclicBarrier start = new CyclicBarrier(symbols);
        CountDownLatch done = new CountDownLatch(symbols);
        AtomicInteger failures = new AtomicInteger();

        for (int s = 0; s < symbols; s++) {
            int symbolId = s;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    FairMarketDataPublisher pub = publishers[symbolId];
                    for (int i = 0; i < stagesPerSymbol; i++) {
                        pub.stageTrade(new Trade(symbolId, symbolId * 1_000L + i, 100, 1));
                        if ((i + 1) % 10 == 0) {
                            pub.flush(symbolId);
                        }
                    }
                    pub.flush(symbolId);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "publisher-shard-" + symbolId);
            t.start();
        }

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, failures.get());

        for (int s = 0; s < symbols; s++) {
            List<FairMarketDataPublisher.FairRelease> releases = releasesBySymbol.get(s);
            assertTrue(!releases.isEmpty());
            int tradeCount = 0;
            for (FairMarketDataPublisher.FairRelease release : releases) {
                assertEquals(s, release.symbolId());
                for (Trade trade : release.trades()) {
                    assertEquals(s, trade.makerOrderId(), "trade leaked across publishers");
                    tradeCount++;
                }
            }
            assertEquals(stagesPerSymbol, tradeCount);
        }
    }
}
