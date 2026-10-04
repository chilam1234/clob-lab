package com.cloblab.model;

import java.util.List;

/**
 * Outcome of one submit. The matching engine reuses a single instance —
 * read it before the next submit on that engine.
 * {@link #trades()} allocates Trade records; the match loop itself does not.
 */
public final class MatchResult {
    private long[] makerIds = new long[8];
    private long[] takerIds = new long[8];
    private long[] priceTicks = new long[8];
    private long[] quantities = new long[8];
    private int tradeCount;
    private long remainingQuantity;
    private boolean resting;
    private OrderType orderType = OrderType.LIMIT;
    private List<Trade> materialized;

    public List<Trade> trades() {
        if (tradeCount == 0) {
            return List.of();
        }
        if (materialized != null) {
            return materialized;
        }
        Trade[] copy = new Trade[tradeCount];
        for (int i = 0; i < tradeCount; i++) {
            copy[i] = new Trade(makerIds[i], takerIds[i], priceTicks[i], quantities[i]);
        }
        materialized = List.of(copy);
        return materialized;
    }

    public long remainingQuantity() {
        return remainingQuantity;
    }

    public boolean resting() {
        return resting;
    }

    public OrderType orderType() {
        return orderType;
    }

    public boolean fullyFilled() {
        return remainingQuantity == 0 && tradeCount > 0;
    }

    public static MatchResult noMatch(long remainingQuantity, boolean resting, OrderType orderType) {
        MatchResult result = new MatchResult();
        result.captureEmpty(remainingQuantity, resting, orderType);
        return result;
    }

    public static MatchResult rejected(long requestedQuantity, OrderType orderType) {
        return noMatch(requestedQuantity, false, orderType);
    }

    public MatchResult captureEmpty(long remainingQuantity, boolean resting, OrderType orderType) {
        this.remainingQuantity = remainingQuantity;
        this.resting = resting;
        this.orderType = orderType;
        this.tradeCount = 0;
        this.materialized = null;
        return this;
    }

    public MatchResult capture(
            long[] makers,
            long[] takers,
            long[] prices,
            long[] qtys,
            int count,
            long remainingQuantity,
            boolean resting,
            OrderType orderType) {
        if (count > makerIds.length) {
            makerIds = new long[count];
            takerIds = new long[count];
            priceTicks = new long[count];
            quantities = new long[count];
        }
        if (count > 0) {
            System.arraycopy(makers, 0, makerIds, 0, count);
            System.arraycopy(takers, 0, takerIds, 0, count);
            System.arraycopy(prices, 0, priceTicks, 0, count);
            System.arraycopy(qtys, 0, quantities, 0, count);
        }
        this.tradeCount = count;
        this.remainingQuantity = remainingQuantity;
        this.resting = resting;
        this.orderType = orderType;
        this.materialized = null;
        return this;
    }
}
