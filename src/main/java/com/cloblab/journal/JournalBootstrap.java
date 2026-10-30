package com.cloblab.journal;

import com.cloblab.exchange.CloudExchange;
import com.cloblab.exchange.SymbolShard;
import com.cloblab.pipeline.Sequencer;

import java.util.List;

/**
 * Boot-time journal replay into a fresh {@link CloudExchange} before ingress resumes (ADR-0011).
 *
 * <p>Replays each stripe into its shard engine, logs per-stripe counts + max eventId, advances
 * the sequencer past the max eventId, and fails fast on gaps in the global eventId sequence.
 */
public final class JournalBootstrap {
    private JournalBootstrap() {}

    /**
     * Replay durable (or in-memory) journal events into shard engines.
     *
     * @return max eventId replayed, or 0 if the journal is empty
     * @throws IllegalStateException on gap: message contains {@code journal gap detected}
     */
    public static long replay(CloudExchange exchange) {
        StripedEventJournal journal = exchange.journal();
        long maxEventId = 0L;
        int stripeCount = journal.stripeCount();
        for (int stripe = 0; stripe < stripeCount; stripe++) {
            List<OrderEvent> events = journal.readAllEvents(stripe);
            SymbolShard shard = exchange.router().shard(stripe);
            long stripeMax = 0L;
            for (OrderEvent event : events) {
                EventReplayer.apply(shard.engine(), event);
                if (event.eventId() > stripeMax) {
                    stripeMax = event.eventId();
                }
            }
            if (stripeMax > maxEventId) {
                maxEventId = stripeMax;
            }
            System.out.println("[clob-journal] stripe=" + stripe
                    + " events=" + events.size()
                    + " maxEventId=" + stripeMax);
        }

        List<OrderEvent> merged = journal.events();
        detectGaps(merged);

        Sequencer sequencer = exchange.sequencer();
        if (maxEventId > 0) {
            sequencer.advancePast(maxEventId);
        }
        System.out.println("[clob-journal] replay complete totalEvents=" + merged.size()
                + " maxEventId=" + maxEventId
                + " nextSequence=" + sequencer.nextSequenceValue());
        return maxEventId;
    }

    /** Fail-fast if eventIds are not contiguous (sorted ascending, unique). */
    public static void detectGaps(List<OrderEvent> mergedAscending) {
        if (mergedAscending.isEmpty()) {
            return;
        }
        long expected = mergedAscending.get(0).eventId();
        for (OrderEvent event : mergedAscending) {
            long id = event.eventId();
            if (id != expected) {
                throw new IllegalStateException(
                        "journal gap detected: expected eventId=" + expected + " but found " + id);
            }
            expected = id + 1;
        }
    }
}
