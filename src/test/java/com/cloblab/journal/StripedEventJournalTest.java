package com.cloblab.journal;

import com.cloblab.model.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StripedEventJournalTest {

    @Test
    void replayMergeOrdersByEventIdAcrossStripes() {
        StripedEventJournal journal = new StripedEventJournal(3);
        // Append out of global order across stripes (as concurrent shards would).
        journal.stripe(2).append(OrderEvent.submitLimit(3, 30, Side.SELL, 101, 1));
        journal.stripe(0).append(OrderEvent.submitLimit(1, 10, Side.BUY, 100, 1));
        journal.stripe(1).append(OrderEvent.submitLimit(2, 20, Side.BUY, 99, 1));
        journal.stripe(0).append(OrderEvent.cancel(4, 10));
        journal.stripe(2).append(OrderEvent.submitLimit(5, 31, Side.SELL, 102, 2));

        assertEquals(5, journal.size());
        List<OrderEvent> merged = journal.events();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                merged.stream().map(OrderEvent::eventId).toList());
        assertEquals(10L, merged.get(0).orderId());
        assertEquals(20L, merged.get(1).orderId());
        assertEquals(30L, merged.get(2).orderId());
        assertEquals(OrderEvent.OrderEventKind.CANCEL, merged.get(3).kind());
        assertEquals(31L, merged.get(4).orderId());
    }

    @Test
    void stripeCountMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new StripedEventJournal(0));
    }
}
