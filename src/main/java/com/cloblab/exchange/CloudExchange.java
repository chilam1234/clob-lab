package com.cloblab.exchange;

import com.cloblab.journal.OrderEvent;
import com.cloblab.journal.StripedEventJournal;
import com.cloblab.latency.LatencyRecorder;
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
    private final LatencyRecorder latencyRecorder;
    private final List<SequencedCommand> ingressBatch = new ArrayList<>();

    public CloudExchange(int symbolCount, int ringCapacityPerShard, int burstThreshold) {
        this(symbolCount, ringCapacityPerShard, burstThreshold,
                new StripedEventJournal(symbolCount), new LatencyRecorder(8192));
    }

    public CloudExchange(
            int symbolCount,
            int ringCapacityPerShard,
            int burstThreshold,
            StripedEventJournal journal) {
        this(symbolCount, ringCapacityPerShard, burstThreshold, journal, new LatencyRecorder(8192));
    }

    public CloudExchange(
            int symbolCount,
            int ringCapacityPerShard,
            int burstThreshold,
            StripedEventJournal journal,
            LatencyRecorder latencyRecorder) {
        this.priorityIngress = new PriorityIngress(burstThreshold);
        this.marketDataBySymbol = new FairMarketDataPublisher[symbolCount];
        this.aggregateJournal = journal;
        this.latencyRecorder = latencyRecorder;
        SymbolShard[] shards = new SymbolShard[symbolCount];
        for (int i = 0; i < symbolCount; i++) {
            marketDataBySymbol[i] = new FairMarketDataPublisher();
            shards[i] = new SymbolShard(
                    i, ringCapacityPerShard, marketDataBySymbol[i], aggregateJournal.stripe(i),
                    latencyRecorder);
        }
        this.router = new ExchangeRouter(shards);
    }

    /** Factory honouring {@code -Dclob.journal} / {@code -Dclob.journal.dir}. */
    public static CloudExchange fromSystemProperties(int symbolCount, int ringCapacityPerShard) {
        StripedEventJournal journal = StripedEventJournal.fromSystemProperties(symbolCount);
        return new CloudExchange(symbolCount, ringCapacityPerShard, Integer.MAX_VALUE, journal);
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

    public LatencyRecorder latencyRecorder() {
        return latencyRecorder;
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
        aggregateJournal.flush();
        if (aggregateJournal.isFileMode()) {
            aggregateJournal.close();
        }
    }

    /**
     * Mass-cancel all resting orders on one symbol (admin kill-switch path).
     * Cancels synchronously via {@code engine.cancel} and appends CANCEL journal events
     * so boot replay does not resurrect the cancelled book.
     */
    public int cancelAll(int symbolId) {
        SymbolShard shard = router.shard(symbolId);
        int removed = 0;
        for (Long orderId : shard.engine().book().restingOrderIds()) {
            if (shard.engine().cancel(orderId)) {
                SequencedCommand stamped = sequencer.stamp(InboundCommand.cancel(symbolId, orderId));
                aggregateJournal.stripe(symbolId).append(OrderEvent.cancel(stamped.globalSequence(), orderId));
                removed++;
            }
        }
        return removed;
    }

    /** Mass-cancel every symbol. */
    public int cancelAllSymbols() {
        int total = 0;
        for (SymbolShard shard : router.shards()) {
            total += cancelAll(shard.symbolId());
        }
        return total;
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
