package com.cloblab.engine;

import com.cloblab.model.OrderType;
import com.cloblab.model.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingEngineTest {
    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
    }

    @Test
    void restingOrderUpdatesBestBidAndAsk() {
        engine.submitLimitOrder(1, Side.BUY, 100, 10);
        engine.submitLimitOrder(2, Side.SELL, 101, 5);

        assertEquals(100L, engine.book().bestBid());
        assertEquals(101L, engine.book().bestAsk());
    }

    @Test
    void aggressiveBuyMatchesPriceTimePriority() {
        engine.submitLimitOrder(1, Side.SELL, 100, 20);
        engine.submitLimitOrder(2, Side.SELL, 100, 30);
        engine.submitLimitOrder(3, Side.SELL, 101, 50);

        var result = engine.submitLimitOrder(4, Side.BUY, 100, 25);

        assertEquals(2, result.trades().size());
        assertEquals(20, result.trades().get(0).quantity());
        assertEquals(1, result.trades().get(0).makerOrderId());
        assertEquals(5, result.trades().get(1).quantity());
        assertEquals(2, result.trades().get(1).makerOrderId());
        assertFalse(result.resting());
        assertEquals(75L, engine.book().totalAskQuantity());
    }

    @Test
    void partialFillLeavesRestingQuantity() {
        engine.submitLimitOrder(1, Side.SELL, 100, 10);

        var result = engine.submitLimitOrder(2, Side.BUY, 100, 25);

        assertEquals(1, result.trades().size());
        assertTrue(result.resting());
        assertEquals(15, result.remainingQuantity());
        assertEquals(15L, engine.book().totalBidQuantity());
    }

    @Test
    void cancelRemovesRestingOrder() {
        engine.submitLimitOrder(1, Side.BUY, 100, 10);
        assertTrue(engine.cancel(1));
        assertFalse(engine.book().contains(1));
        assertEquals(null, engine.book().bestBid());
    }

    @Test
    void buyDoesNotCrossAboveLimitPrice() {
        engine.submitLimitOrder(1, Side.SELL, 102, 10);

        var result = engine.submitLimitOrder(2, Side.BUY, 101, 5);

        assertTrue(result.trades().isEmpty());
        assertTrue(result.resting());
        assertEquals(101L, engine.book().bestBid());
    }

    @Test
    void marketOrderWalksTheBookAndNeverRests() {
        engine.submitLimitOrder(1, Side.SELL, 100, 10);
        engine.submitLimitOrder(2, Side.SELL, 101, 20);

        var result = engine.submitMarketOrder(3, Side.BUY, 25);

        assertEquals(OrderType.MARKET, result.orderType());
        assertFalse(result.resting());
        assertEquals(2, result.trades().size());
        assertEquals(10, result.trades().get(0).quantity());
        assertEquals(100, result.trades().get(0).priceTicks());
        assertEquals(15, result.trades().get(1).quantity());
        assertEquals(101, result.trades().get(1).priceTicks());
        assertEquals(5L, engine.book().totalAskQuantity());
        assertFalse(engine.book().contains(3));
    }

    @Test
    void marketOrderReportsUnfilledWhenBookIsEmpty() {
        var result = engine.submitMarketOrder(1, Side.BUY, 10);

        assertTrue(result.trades().isEmpty());
        assertEquals(10, result.remainingQuantity());
        assertFalse(result.resting());
    }

    @Test
    void iocPartialFillCancelsRemainder() {
        engine.submitLimitOrder(1, Side.SELL, 100, 10);

        var result = engine.submitIocOrder(2, Side.BUY, 100, 25);

        assertEquals(OrderType.IOC, result.orderType());
        assertEquals(1, result.trades().size());
        assertEquals(10, result.trades().get(0).quantity());
        assertEquals(15, result.remainingQuantity());
        assertFalse(result.resting());
        assertEquals(0L, engine.book().totalBidQuantity());
    }

    @Test
    void iocDoesNotCrossAboveLimitPrice() {
        engine.submitLimitOrder(1, Side.SELL, 102, 10);

        var result = engine.submitIocOrder(2, Side.BUY, 101, 5);

        assertTrue(result.trades().isEmpty());
        assertEquals(5, result.remainingQuantity());
        assertFalse(result.resting());
        assertEquals(null, engine.book().bestBid());
    }

    @Test
    void journalReplayReproducesBookState() {
        MatchingEngine.Journaled live = new MatchingEngine.Journaled();
        live.submitLimitOrder(1, Side.SELL, 100, 30);
        live.submitLimitOrder(2, Side.BUY, 100, 10);
        live.submitIocOrder(3, Side.BUY, 99, 5);
        live.cancel(1);

        MatchingEngine replayed = live.replay();

        assertEquals(live.book().bestBid(), replayed.book().bestBid());
        assertEquals(live.book().bestAsk(), replayed.book().bestAsk());
        assertEquals(live.book().totalBidQuantity(), replayed.book().totalBidQuantity());
        assertEquals(live.book().totalAskQuantity(), replayed.book().totalAskQuantity());
        assertEquals(4, live.journal().size());
    }

    @Test
    void fokFillsWhenFullLiquidityAvailable() {
        engine.submitLimitOrder(1, Side.SELL, 100, 10);
        engine.submitLimitOrder(2, Side.SELL, 101, 20);

        var result = engine.submitFokOrder(3, Side.BUY, 101, 10);

        assertEquals(OrderType.FOK, result.orderType());
        assertTrue(result.fullyFilled());
        assertEquals(0, result.remainingQuantity());
        assertEquals(1, result.trades().size());
        assertEquals(100, result.trades().get(0).priceTicks()); // price-time: best ask first
        assertEquals(20L, engine.book().totalAskQuantity()); // 20 left at 101
    }

    @Test
    void fokRejectedWhenInsufficientLiquidity() {
        engine.submitLimitOrder(1, Side.SELL, 100, 10);

        var result = engine.submitFokOrder(2, Side.BUY, 100, 25);

        assertTrue(result.trades().isEmpty());
        assertEquals(25, result.remainingQuantity());
        assertFalse(result.resting());
        assertEquals(10L, engine.book().totalAskQuantity());
    }

    @Test
    void fokFillsAcrossPriceLevelsWhenTotalLiquiditySufficient() {
        engine.submitLimitOrder(1, Side.SELL, 100, 15);
        engine.submitLimitOrder(2, Side.SELL, 101, 50);

        var result = engine.submitFokOrder(3, Side.BUY, 101, 20);

        assertTrue(result.fullyFilled());
        assertEquals(2, result.trades().size());
        assertEquals(15, result.trades().get(0).quantity());
        assertEquals(5, result.trades().get(1).quantity());
        assertEquals(45L, engine.book().totalAskQuantity()); // 50 - 5 at 101
    }

    @Test
    void l2SnapshotReturnsTopLevels() {
        engine.submitLimitOrder(1, Side.SELL, 101, 30);
        engine.submitLimitOrder(2, Side.SELL, 100, 20);
        engine.submitLimitOrder(3, Side.BUY, 99, 40);
        engine.submitLimitOrder(4, Side.BUY, 98, 25);
        engine.submitLimitOrder(5, Side.BUY, 98, 10);

        var snapshot = engine.snapshot(2);

        assertEquals(2, snapshot.asks().size());
        assertEquals(2, snapshot.bids().size());
        assertEquals(100, snapshot.asks().get(0).priceTicks());
        assertEquals(20, snapshot.asks().get(0).quantity());
        assertEquals(99, snapshot.bids().get(0).priceTicks());
        assertEquals(40, snapshot.bids().get(0).quantity());
        assertEquals(2, snapshot.bids().get(1).orderCount());
    }

    @Test
    void l2SnapshotQuantityDropsAfterPartialFill() {
        engine.submitLimitOrder(1, Side.SELL, 100, 20);

        engine.submitLimitOrder(2, Side.BUY, 100, 5);

        var snapshot = engine.snapshot(1);
        assertEquals(1, snapshot.asks().size());
        assertEquals(100, snapshot.asks().get(0).priceTicks());
        assertEquals(15, snapshot.asks().get(0).quantity());
        assertEquals(1, snapshot.asks().get(0).orderCount());
        assertEquals(15L, engine.book().totalAskQuantity());
    }

    @Test
    void l2SnapshotDropsEmptyLevelAfterFullFill() {
        engine.submitLimitOrder(1, Side.SELL, 100, 10);
        engine.submitLimitOrder(2, Side.SELL, 101, 30);

        engine.submitLimitOrder(3, Side.BUY, 100, 10);

        var snapshot = engine.snapshot(2);
        assertEquals(1, snapshot.asks().size());
        assertEquals(101, snapshot.asks().get(0).priceTicks());
        assertEquals(30, snapshot.asks().get(0).quantity());
        assertEquals(30L, engine.book().totalAskQuantity());
    }
}
