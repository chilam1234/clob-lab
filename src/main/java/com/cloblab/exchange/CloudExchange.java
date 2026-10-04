package com.cloblab.exchange;

import com.cloblab.journal.EventJournal;
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
 */
public final class CloudExchange {
    private final Sequencer sequencer = new Sequencer();
    private final PriorityIngress priorityIngress;
    private final ExchangeRouter router;
    private final FairMarketDataPublisher marketData;
    private final EventJournal aggregateJournal = new EventJournal();
    private final List<SequencedCommand> ingressBatch = new ArrayList<>();

    public CloudExchange(int symbolCount, int ringCapacityPerShard, int burstThreshold) {
        this.priorityIngress = new PriorityIngress(burstThreshold);
        this.marketData = new FairMarketDataPublisher();
        SymbolShard[] shards = new SymbolShard[symbolCount];
        for (int i = 0; i < symbolCount; i++) {
            shards[i] = new SymbolShard(i, ringCapacityPerShard, marketData, aggregateJournal);
        }
        this.router = new ExchangeRouter(shards);
    }

    public FairMarketDataPublisher marketData() {
        return marketData;
    }

    public ExchangeRouter router() {
        return router;
    }

    public EventJournal journal() {
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
        var release = marketData.flush();
        return new ExchangeTick(routed, processed, deferred.size(), release);
    }

    public record ExchangeTick(
            int routed,
            int processed,
            int deferred,
            FairMarketDataPublisher.FairRelease release) {}
}
