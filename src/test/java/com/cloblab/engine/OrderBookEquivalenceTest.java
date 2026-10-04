package com.cloblab.engine;

import com.cloblab.model.OrderType;
import com.cloblab.model.Side;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures FAST and TREE books produce identical outcomes. */
class OrderBookEquivalenceTest {

    @ParameterizedTest
    @EnumSource(BookKind.class)
    void aggressiveBuyMatchesPriceTimePriority(BookKind kind) {
        MatchingEngine engine = MatchingEngine.create(kind);
        engine.submitLimitOrder(1, Side.SELL, 100, 20);
        engine.submitLimitOrder(2, Side.SELL, 100, 30);
        engine.submitLimitOrder(3, Side.SELL, 101, 50);

        var result = engine.submitLimitOrder(4, Side.BUY, 100, 25);

        assertEquals(2, result.trades().size());
        assertEquals(20, result.trades().get(0).quantity());
        assertEquals(75L, engine.book().totalAskQuantity());
    }

    @ParameterizedTest
    @EnumSource(BookKind.class)
    void fokRejectedWhenInsufficientLiquidity(BookKind kind) {
        MatchingEngine engine = MatchingEngine.create(kind);
        engine.submitLimitOrder(1, Side.SELL, 100, 10);

        var result = engine.submitFokOrder(2, Side.BUY, 100, 25);

        assertTrue(result.trades().isEmpty());
        assertEquals(10L, engine.book().totalAskQuantity());
    }

    @ParameterizedTest
    @EnumSource(BookKind.class)
    void fastAndTreeProduceSameFinalBook(BookKind kind) {
        MatchingEngine reference = runScenario(MatchingEngine.create(BookKind.TREE));
        MatchingEngine underTest = runScenario(MatchingEngine.create(kind));

        assertEquals(reference.book().bestBid(), underTest.book().bestBid());
        assertEquals(reference.book().bestAsk(), underTest.book().bestAsk());
        assertEquals(reference.book().totalBidQuantity(), underTest.book().totalBidQuantity());
        assertEquals(reference.book().totalAskQuantity(), underTest.book().totalAskQuantity());
        assertEquals(reference.snapshot(5), underTest.snapshot(5));
    }

    private static MatchingEngine runScenario(MatchingEngine engine) {
        engine.submitLimitOrder(1, Side.SELL, 101, 30);
        engine.submitLimitOrder(2, Side.SELL, 100, 20);
        engine.submitLimitOrder(3, Side.BUY, 99, 40);
        engine.submitLimitOrder(4, Side.BUY, 98, 25);
        engine.submitIocOrder(5, Side.BUY, 100, 15);
        engine.submitFokOrder(6, Side.BUY, 101, 5);
        engine.cancel(1);
        engine.submitMarketOrder(7, Side.SELL, 10);
        return engine;
    }
}
