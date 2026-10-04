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
 * One symbol, one matcher thread: the ring is SPSC (producer = {@link CloudExchange} routing).
 *
 * <p>{@link #processAll()} remains the synchronous drain used by {@link CloudExchange#flushBatch()}.
 * {@link #start()} runs a dedicated busy-spin consumer that applies the same {@code poll() + apply()}
 * path and requests a fair MD flush after each command.
 */
public final class SymbolShard {
    private static final long SHUTDOWN_JOIN_MILLIS = 5_000L;

    private final int symbolId;
    private final MatchingEngine engine;
    private final RingBuffer<SequencedCommand> inboundRing;
    private final FairMarketDataPublisher publisher;
    private final EventJournal journal;

    private volatile boolean running;
    private Thread consumer;

    public SymbolShard(int symbolId, int ringCapacity, FairMarketDataPublisher publisher, EventJournal journal) {
        this.symbolId = symbolId;
        this.engine = new MatchingEngine();
        this.inboundRing = new RingBuffer<>(ringCapacity);
        this.publisher = publisher;
        this.journal = journal;
    }

    /**
     * Start the dedicated SPSC consumer thread. No-op if already running.
     * Matching semantics are unchanged: the thread is the sole caller of {@link RingBuffer#poll()}.
     */
    public synchronized void start() {
        if (consumer != null && consumer.isAlive()) {
            return;
        }
        running = true;
        consumer = new Thread(this::runLoop, "clob-shard-" + symbolId);
        consumer.setDaemon(true);
        consumer.start();
    }

    /**
     * Stop the consumer: clear the volatile {@code running} flag, join with timeout, and rely on
     * the thread to drain remaining ring commands before exit so in-flight work is not dropped.
     */
    public void shutdown() {
        Thread thread;
        synchronized (this) {
            running = false;
            thread = consumer;
        }
        if (thread == null) {
            return;
        }
        try {
            thread.join(SHUTDOWN_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    /** Drain ring and apply to matcher — single-threaded consumer (synchronous / test path). */
    public int processAll() {
        int processed = 0;
        SequencedCommand sequenced;
        while ((sequenced = inboundRing.poll()) != null) {
            apply(sequenced);
            processed++;
        }
        return processed;
    }

    private void runLoop() {
        while (running) {
            SequencedCommand sequenced = inboundRing.poll();
            if (sequenced == null) {
                Thread.onSpinWait();
                continue;
            }
            consumeFrame(sequenced);
        }
        SequencedCommand remaining;
        while ((remaining = inboundRing.poll()) != null) {
            consumeFrame(remaining);
        }
    }

    /**
     * Apply one command then request a per-frame fair MD release. {@link MatchResult} is consumed
     * here (trades materialized) before the next apply reuses the engine scratch result.
     */
    private void consumeFrame(SequencedCommand sequenced) {
        try {
            apply(sequenced);
        } catch (IllegalArgumentException ignored) {
            // Validation belongs at Gateway.submit as REJECTED; keep the consumer alive.
            if (ignored.getMessage() == null) {
                throw ignored;
            }
        }
        publisher.flush(symbolId);
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
