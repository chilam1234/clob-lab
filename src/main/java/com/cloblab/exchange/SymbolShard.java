package com.cloblab.exchange;

import com.cloblab.engine.MatchingEngine;
import com.cloblab.marketdata.FairMarketDataPublisher;
import com.cloblab.marketdata.L2Snapshot;
import com.cloblab.model.MatchResult;
import com.cloblab.model.OrderType;
import com.cloblab.pipeline.RingBuffer;
import com.cloblab.pipeline.SequencedCommand;
import com.cloblab.protocol.InboundCommand;

/**
 * One symbol, one matcher thread model: ring buffer feeds a dedicated {@link MatchingEngine}.
 */
public final class SymbolShard {
    private final int symbolId;
    private final MatchingEngine engine;
    private final RingBuffer<SequencedCommand> inboundRing;
    private final FairMarketDataPublisher publisher;

    public SymbolShard(int symbolId, int ringCapacity, FairMarketDataPublisher publisher) {
        this.symbolId = symbolId;
        this.engine = new MatchingEngine();
        this.inboundRing = new RingBuffer<>(ringCapacity);
        this.publisher = publisher;
    }

    public int symbolId() {
        return symbolId;
    }

    public MatchingEngine engine() {
        return engine;
    }

    public boolean enqueue(SequencedCommand command) {
        if (command.command().symbolId() != symbolId) {
            throw new IllegalArgumentException("wrong symbol for shard " + symbolId);
        }
        return inboundRing.offer(command);
    }

    /** Drain ring and apply to matcher — single-threaded consumer. */
    public int processAll() {
        int processed = 0;
        SequencedCommand sequenced;
        while ((sequenced = inboundRing.poll()) != null) {
            apply(sequenced);
            processed++;
        }
        return processed;
    }

    private void apply(SequencedCommand sequenced) {
        InboundCommand command = sequenced.command();
        MatchResult result = switch (command.kind()) {
            case SUBMIT -> switch (command.orderType()) {
                case LIMIT -> engine.submitLimitOrder(
                        command.orderId(), command.side(), command.priceTicks(), command.quantity());
                case MARKET -> engine.submitMarketOrder(command.orderId(), command.side(), command.quantity());
                case IOC -> engine.submitIocOrder(
                        command.orderId(), command.side(), command.priceTicks(), command.quantity());
                case FOK -> engine.submitFokOrder(
                        command.orderId(), command.side(), command.priceTicks(), command.quantity());
            };
            case CANCEL -> {
                engine.cancel(command.orderId());
                yield MatchResult.noMatch(0, false, OrderType.LIMIT);
            }
        };

        result.trades().forEach(publisher::stageTrade);
        publisher.stageSnapshot(engine.snapshot(3));
    }

    public L2Snapshot snapshot(int depth) {
        return engine.snapshot(depth);
    }
}
