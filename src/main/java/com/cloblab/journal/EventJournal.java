package com.cloblab.journal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only log of order events. Enables deterministic replay.
 *
 * <p>Single-instance / test path: {@link #append} is synchronized for multi-threaded
 * callers. Hot-path exchange journaling uses {@link StripedEventJournal} instead (SPSC
 * per symbol, no monitor).
 */
public final class EventJournal implements OrderEventSink {
    private final AtomicLong nextEventId = new AtomicLong(1);
    private final List<OrderEvent> events = new ArrayList<>();

    @Override
    public synchronized OrderEvent append(OrderEvent event) {
        events.add(event);
        return event;
    }

    public OrderEvent nextSubmitLimit(long orderId, com.cloblab.model.Side side, long priceTicks, long quantity) {
        return append(OrderEvent.submitLimit(nextEventId.getAndIncrement(), orderId, side, priceTicks, quantity));
    }

    public OrderEvent nextSubmitMarket(long orderId, com.cloblab.model.Side side, long quantity) {
        return append(OrderEvent.submitMarket(nextEventId.getAndIncrement(), orderId, side, quantity));
    }

    public OrderEvent nextSubmitIoc(long orderId, com.cloblab.model.Side side, long priceTicks, long quantity) {
        return append(OrderEvent.submitIoc(nextEventId.getAndIncrement(), orderId, side, priceTicks, quantity));
    }

    public OrderEvent nextSubmitFok(long orderId, com.cloblab.model.Side side, long priceTicks, long quantity) {
        return append(OrderEvent.submitFok(nextEventId.getAndIncrement(), orderId, side, priceTicks, quantity));
    }

    public OrderEvent nextCancel(long orderId) {
        return append(OrderEvent.cancel(nextEventId.getAndIncrement(), orderId));
    }

    public synchronized List<OrderEvent> events() {
        return Collections.unmodifiableList(events);
    }

    public synchronized int size() {
        return events.size();
    }
}
