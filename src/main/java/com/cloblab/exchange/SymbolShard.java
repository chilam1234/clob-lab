package com.cloblab.exchange;

import com.cloblab.engine.MatchingEngine;
import com.cloblab.journal.EventJournal;
import com.cloblab.journal.OrderEvent;
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
    private final EventJournal journal;

    public SymbolShard(int symbolId, int ringCapacity, FairMarketDataPublisher publisher, EventJournal journal) {
        this.symbolId = symbolId;
        this.engine = new MatchingEngine();
        this.inboundRing = new RingBuffer<>(ringCapacity);
        this.publisher = publisher;
        this.journal = journal;
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
        journalExecution(sequenced);
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

    /**
     * Journal at execution time using the global ingress sequence as the event ID so a
     * replayer can restore execution order even though FancyPQ reordered the batch.
     */
    private void journalExecution(SequencedCommand sequenced) {
        InboundCommand command = sequenced.command();
        long eventId = sequenced.globalSequence();
        OrderEvent event = switch (command.kind()) {
            case SUBMIT -> switch (command.orderType()) {
                case LIMIT -> OrderEvent.submitLimit(eventId, command.orderId(),
                        command.side(), command.priceTicks(), command.quantity());
                case MARKET -> OrderEvent.submitMarket(eventId, command.orderId(),
                        command.side(), command.quantity());
                case IOC -> OrderEvent.submitIoc(eventId, command.orderId(),
                        command.side(), command.priceTicks(), command.quantity());
                case FOK -> OrderEvent.submitFok(eventId, command.orderId(),
                        command.side(), command.priceTicks(), command.quantity());
            };
            case CANCEL -> OrderEvent.cancel(eventId, command.orderId());
        };
        journal.append(event);
    }
}
