package com.cloblab.journal;

import com.cloblab.engine.MatchingEngine;
import com.cloblab.model.OrderType;
import com.cloblab.model.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class EventReplayerTest {

    @Test
    void replayRestoresBookAcrossAllOrderTypes() {
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimitOrder(1, Side.SELL, 101, 30);
        engine.submitLimitOrder(2, Side.SELL, 100, 20);
        engine.submitMarketOrder(3, Side.BUY, 5);
        engine.submitIocOrder(4, Side.BUY, 100, 8);
        engine.submitFokOrder(5, Side.BUY, 100, 4);
        engine.cancel(1);

        EventJournal journal = new EventJournal();
        journal.nextSubmitLimit(1, Side.SELL, 101, 30);
        journal.nextSubmitLimit(2, Side.SELL, 100, 20);
        journal.nextSubmitMarket(3, Side.BUY, 5);
        journal.nextSubmitIoc(4, Side.BUY, 100, 8);
        journal.nextSubmitFok(5, Side.BUY, 100, 4);
        journal.nextCancel(1);

        MatchingEngine replayed = EventReplayer.replay(journal.events());
        assertEquals(engine.book().bestBid(), replayed.book().bestBid());
        assertEquals(engine.book().bestAsk(), replayed.book().bestAsk());
        assertEquals(engine.book().totalBidQuantity(), replayed.book().totalBidQuantity());
        assertEquals(engine.book().totalAskQuantity(), replayed.book().totalAskQuantity());
    }

    @Test
    void applyRejectsUnknownEventKind() {
        OrderEvent bogus = new OrderEvent(1, null, 1L, Side.BUY, 100L, 5L, OrderType.LIMIT);
        assertThrows(NullPointerException.class,
                () -> EventReplayer.apply(new MatchingEngine(), bogus));
    }

    @Test
    void applyRejectsUnknownOrderType() {
        OrderEvent bogus = new OrderEvent(1, OrderEvent.OrderEventKind.SUBMIT, 1L, Side.BUY, 100L, 5L, null);
        assertThrows(IllegalArgumentException.class,
                () -> EventReplayer.apply(new MatchingEngine(), bogus));
    }
}