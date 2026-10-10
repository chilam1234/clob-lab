package com.cloblab.journal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * N unsynchronized journal stripes (one per symbolId). Each stripe is SPSC: only the
 * owning shard thread appends. Replay merges stripes by {@link OrderEvent#eventId()}
 * ascending so order equals global ingress / execution order (ADR-0001).
 */
public final class StripedEventJournal {
    private final Stripe[] stripes;

    public StripedEventJournal(int stripeCount) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be positive: " + stripeCount);
        }
        this.stripes = new Stripe[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new Stripe();
        }
    }

    public int stripeCount() {
        return stripes.length;
    }

    /**
     * Sink for {@code symbolId}. Append is unsynchronized; call only from the shard
     * thread that owns that symbol.
     */
    public OrderEventSink stripe(int symbolId) {
        return stripes[symbolId];
    }

    /** Total events across all stripes. */
    public int size() {
        int total = 0;
        for (Stripe stripe : stripes) {
            total += stripe.size();
        }
        return total;
    }

    /**
     * Merge stripes by eventId ascending (global ingress sequence). Safe to call after
     * shards have quiesced; not a concurrent snapshot.
     */
    public List<OrderEvent> events() {
        PriorityQueue<StripeCursor> heap = new PriorityQueue<>(
                Comparator.comparingLong(c -> c.event.eventId()));
        for (Stripe stripe : stripes) {
            List<OrderEvent> list = stripe.events;
            if (!list.isEmpty()) {
                heap.offer(new StripeCursor(list, 0));
            }
        }
        List<OrderEvent> merged = new ArrayList<>(size());
        while (!heap.isEmpty()) {
            StripeCursor cursor = heap.poll();
            merged.add(cursor.event);
            int next = cursor.index + 1;
            if (next < cursor.list.size()) {
                heap.offer(new StripeCursor(cursor.list, next));
            }
        }
        return Collections.unmodifiableList(merged);
    }

    /** Unsynchronized SPSC buffer owned by one shard thread. */
    private static final class Stripe implements OrderEventSink {
        private final List<OrderEvent> events = new ArrayList<>();

        @Override
        public OrderEvent append(OrderEvent event) {
            events.add(event);
            return event;
        }

        int size() {
            return events.size();
        }
    }

    private static final class StripeCursor {
        final List<OrderEvent> list;
        final int index;
        final OrderEvent event;

        StripeCursor(List<OrderEvent> list, int index) {
            this.list = list;
            this.index = index;
            this.event = list.get(index);
        }
    }
}
