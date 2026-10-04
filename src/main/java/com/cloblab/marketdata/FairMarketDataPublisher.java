package com.cloblab.marketdata;

import com.cloblab.model.Trade;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Onyx/Jasper-style outbound fairness lite: batch market-data releases so all
 * subscribers observe the same update at the same flush (minimal spread).
 */
public final class FairMarketDataPublisher {
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final List<Trade> pendingTrades = new ArrayList<>();
    private final List<L2Snapshot> pendingSnapshots = new ArrayList<>();
    private long lastReleaseNano;

    public interface Subscriber {
        void onFairRelease(FairRelease release);
    }

    public record FairRelease(long releaseNano, List<Trade> trades, List<L2Snapshot> snapshots) {}

    public void subscribe(Subscriber subscriber) {
        subscribers.add(subscriber);
    }

    public synchronized void stageTrade(Trade trade) {
        pendingTrades.add(trade);
    }

    public synchronized void stageSnapshot(L2Snapshot snapshot) {
        pendingSnapshots.add(snapshot);
    }

    /**
     * Release staged updates to all subscribers simultaneously.
     * Used both as {@link com.cloblab.exchange.CloudExchange#flushBatch()} batch mode and as
     * the per-frame flush requested by a dedicated shard consumer after each command.
     */
    public synchronized FairRelease flush() {
        if (pendingTrades.isEmpty() && pendingSnapshots.isEmpty()) {
            return null;
        }
        lastReleaseNano = System.nanoTime();
        FairRelease release = new FairRelease(
                lastReleaseNano,
                List.copyOf(pendingTrades),
                List.copyOf(pendingSnapshots));
        pendingTrades.clear();
        pendingSnapshots.clear();
        for (Subscriber subscriber : subscribers) {
            subscriber.onFairRelease(release);
        }
        return release;
    }

    public long lastReleaseNano() {
        return lastReleaseNano;
    }
}
