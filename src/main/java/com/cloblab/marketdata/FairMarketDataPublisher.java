package com.cloblab.marketdata;

import com.cloblab.model.Trade;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Onyx/Jasper-style outbound fairness lite: batch market-data releases so all
 * subscribers observe the same update at the same flush (minimal spread).
 *
 * <p><b>SPSC ownership.</b> Each instance is owned by exactly one symbol shard thread.
 * Only that thread may call {@link #stageTrade}, {@link #stageSnapshot}, or {@link #flush}.
 * {@link com.cloblab.exchange.CloudExchange} holds one publisher per symbolId; cross-shard
 * staging must not share an instance. Subscribe may be called from any thread
 * (CopyOnWriteArrayList).
 *
 * <p><b>Subscriber contract.</b> {@link Subscriber#onFairRelease} must not block — enqueue
 * only (e.g. GatewayServer fanout queue). Holding the subscriber callback stalls the
 * owning shard's match loop.
 */
public final class FairMarketDataPublisher {
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final List<Trade> pendingTrades = new ArrayList<>();
    private final List<L2Snapshot> pendingSnapshots = new ArrayList<>();
    private long lastReleaseNano;

    public interface Subscriber {
        /**
         * Deliver one fair release. Must not block; enqueue and return.
         */
        void onFairRelease(FairRelease release);
    }

    public record FairRelease(int symbolId, long releaseNano, List<Trade> trades, List<L2Snapshot> snapshots) {}

    public void subscribe(Subscriber subscriber) {
        subscribers.add(subscriber);
    }

    /** Stage a trade. Owning shard thread only (SPSC). */
    public void stageTrade(Trade trade) {
        pendingTrades.add(trade);
    }

    /** Stage an L2 snapshot. Owning shard thread only (SPSC). */
    public void stageSnapshot(L2Snapshot snapshot) {
        pendingSnapshots.add(snapshot);
    }

    /**
     * Release staged updates to all subscribers simultaneously.
     * Used both as {@link com.cloblab.exchange.CloudExchange#flushBatch()} batch mode and as
     * the per-frame flush requested by a dedicated shard consumer after each command.
     * Owning shard thread only (SPSC).
     */
    public FairRelease flush() { return flush(-1); }

    /** Per-shard flush: the release carries the shard's symbolId. Owning shard thread only. */
    public FairRelease flush(int symbolId) {
        if (pendingTrades.isEmpty() && pendingSnapshots.isEmpty()) {
            return null;
        }
        lastReleaseNano = System.nanoTime();
        FairRelease release = new FairRelease(
                symbolId,
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
