package com.cloblab.book;

import com.cloblab.marketdata.L2Snapshot;
import com.cloblab.model.Order;
import com.cloblab.model.Side;

import java.util.List;

/** Common order book API used by {@link com.cloblab.engine.MatchingEngine}. */
public interface OrderBookView {
    Long bestBid();

    Long bestAsk();

    long totalBidQuantity();

    long totalAskQuantity();

    boolean contains(long orderId);

    /** Resting order ids (admin mass-cancel). Snapshot; not live. */
    List<Long> restingOrderIds();

    long availableAtOrBetter(Side takerSide, long limitPriceTicks);

    L2Snapshot snapshot(int depth);

    void addResting(long orderId, Side side, long priceTicks, long quantity, long sequence);

    default void addRestingOrder(Order order) {
        addResting(order.orderId(), order.side(), order.priceTicks(), order.quantity(), order.sequence());
    }

    boolean cancel(long orderId);

    long matchAggressive(Side takerSide, long limitPriceTicks, long quantity, MatchSink sink);

    interface MatchSink {
        void onTrade(long makerOrderId, long priceTicks, long quantity);
    }
}
