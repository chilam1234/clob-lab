package com.cloblab.journal;

/**
 * Append target for a journal stripe or single {@link EventJournal}.
 *
 * <p>SPSC ownership: when backed by a {@link StripedEventJournal} stripe, only the owning
 * shard thread may call {@link #append}.
 */
@FunctionalInterface
public interface OrderEventSink {
    OrderEvent append(OrderEvent event);
}
