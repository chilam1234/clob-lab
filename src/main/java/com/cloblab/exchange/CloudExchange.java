package com.cloblab.exchange;

import com.cloblab.journal.StripedEventJournal;
import com.cloblab.marketdata.FairMarketDataPublisher;
import com.cloblab.pipeline.PriorityIngress;
import com.cloblab.pipeline.SequencedCommand;
import com.cloblab.pipeline.Sequencer;
import com.cloblab.protocol.InboundCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloud-exchange pipeline (2024+ research applied):
 * ingress → global sequencer → FancyPQ burst reorder → sharded matchers → fair MD fanout → journal.
 *
 * <p>Per-symbol {@link FairMarketDataPublisher} instances and a {@link StripedEventJournal}
 * remove cross-shard monitor contention on the hot path (ADR-0010).
 */
public final class CloudExchange {
    private final Sequencer sequencer = new Sequencer();
    private final PriorityIngress priorityIngress;
    private final ExchangeRouter router;
    private final FairMarketDataPublisher[] marketDataBySymbol;
    private final StripedEventJournal aggregateJournal;
    private final List<SequencedCommand> ingressBatch = new ArrayList<>();

    public CloudExchange(int symbolCount, int ringCapacityPerShard, int burstThreshold) {
        this.priorityIngress = new PriorityIngress(burstThreshold);
        this.marketDataBySymbol = new FairMarketDataPublisher[symbolCount];
        this.aggregateJournal = new StripedEventJournal(symbolCount);
        SymbolShard[] shards = new SymbolShard[symbolCount];
        for (int i = 0; i < symbolCount; i++) {
            marketDataBySymbol[i] = new FairMarketDataPublisher();
            shards[i] = new SymbolShard(
                    i, ringCapacityPerShard, marketDataBySymbol[i], aggregateJournal.stripe(i));
        }
        this.router = new ExchangeRouter(shards);
    }

    /** Per-symbol publisher owned by that symbol's shard (SPSC). */
    public FairMarketDataPublisher marketData(int symbolId) {
        return marketDataBySymbol[symbolId];
    }

    /**
     * Subscribe to fair releases from every per-symbol publisher.
     * Callbacks run on the owning shard thread (or flushBatch caller); must not block.
     */
    public void subscribeMarketData(FairMarketDataPublisher.Subscriber subscriber) {
        for (FairMarketDataPublisher publisher : marketDataBySymbol) {
            publisher.subscribe(subscriber);
        }
    }

    public ExchangeRouter router() {
        return router;
    }

    public StripedEventJournal journal() {
        return aggregateJournal;
    }

    public Sequencer sequencer() {
        return sequencer;
    }

    /**
     * Start each shard's dedicated consumer thread. Tests keep the default synchronous
     * {@link #flushBatch()} path and must not call this.
     */
    public void start() {
        for (SymbolShard shard : router.shards()) {
            shard.start();
        }
    }

    /** Stop every shard consumer, draining in-flight ring commands on each thread. */
    public void shutdown() {
        for (SymbolShard shard : router.shards()) {
            shard.shutdown();
        }
    }

    /**
     * Queue a command for the next {@link #flushBatch()}.
     * Ingress is unbounded, so this always returns {@code true}.
     * Ring back-pressure is reported as {@link ExchangeTick#deferred()} after flush.
     */
    public boolean submit(InboundCommand command) {
        SequencedCommand sequenced = sequencer.stamp(command);
        ingressBatch.add(sequenced);
        return true;
    }

    public int pendingIngress() {
        return ingressBatch.size();
    }

    /** FancyPQ reorder per symbol, offer to shard rings, drain matchers, fair MD release. */
    public ExchangeTick flushBatch() {
        if (ingressBatch.isEmpty()) {
            return new ExchangeTick(0, 0, 0, null);
        }

        Map<Integer, List<SequencedCommand>> bySymbol = new LinkedHashMap<>();
        for (SequencedCommand sequenced : ingressBatch) {
            bySymbol.computeIfAbsent(sequenced.command().symbolId(), k -> new ArrayList<>()).add(sequenced);
        }

        List<SequencedCommand> deferred = new ArrayList<>();
        int routed = 0;
        for (var entry : bySymbol.entrySet()) {
            List<SequencedCommand> forSymbol = priorityIngress.reorder(
                    entry.getValue(), router.shard(entry.getKey()).engine().book());
            for (SequencedCommand sequenced : forSymbol) {
                if (router.route(sequenced)) {
                    routed++;
                } else {
                    deferred.add(sequenced);
                }
            }
        }

        int processed = 0;
        for (SymbolShard shard : router.shards()) {
            processed += shard.processAll();
        }

        ingressBatch.clear();
        ingressBatch.addAll(deferred);
        FairMarketDataPublisher.FairRelease release = null;
        for (int symbolId = 0; symbolId < marketDataBySymbol.length; symbolId++) {
            FairMarketDataPublisher.FairRelease next = marketDataBySymbol[symbolId].flush(symbolId);
            if (next != null) {
                release = next;
            }
        }
        return new ExchangeTick(routed, processed, deferred.size(), release);
    }

    public record ExchangeTick(
            int routed,
            int processed,
            int deferred,
            FairMarketDataPublisher.FairRelease release) {}
}
