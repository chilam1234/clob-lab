package com.cloblab.book;

import com.cloblab.model.Order;
import com.cloblab.model.Side;
import com.cloblab.marketdata.BookLevel;
import com.cloblab.marketdata.L2Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Single-symbol CLOB with price-time priority.
 *
 * Bids: highest price first. Asks: lowest price first.
 * Within a price level, orders are FIFO by sequence number.
 */
public final class OrderBook implements OrderBookView {
    private final NavigableMap<Long, PriceLevel> bids =
            new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<Long, OrderLocation> orderIndex = new HashMap<>();

    @Override
    public Long bestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    @Override
    public Long bestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    @Override
    public long totalBidQuantity() {
        return bids.values().stream().mapToLong(PriceLevel::totalQuantity).sum();
    }

    @Override
    public long totalAskQuantity() {
        return asks.values().stream().mapToLong(PriceLevel::totalQuantity).sum();
    }

    @Override
    public boolean contains(long orderId) {
        return orderIndex.containsKey(orderId);
    }

    /** Quantity available on the opposite side at or better than limitPrice. */
    @Override
    public long availableAtOrBetter(Side takerSide, long limitPriceTicks) {
        NavigableMap<Long, PriceLevel> opposite = side(takerSide.opposite());
        long available = 0;
        for (Map.Entry<Long, PriceLevel> entry : opposite.entrySet()) {
            if (!isPriceCrossed(takerSide, limitPriceTicks, entry.getKey())) {
                break;
            }
            available += entry.getValue().totalQuantity();
        }
        return available;
    }

    /** L2 depth snapshot: top {@code depth} price levels per side. */
    @Override
    public L2Snapshot snapshot(int depth) {
        return new L2Snapshot(snapshotSide(bids, depth), snapshotSide(asks, depth));
    }

    private static List<BookLevel> snapshotSide(NavigableMap<Long, PriceLevel> side, int depth) {
        List<BookLevel> levels = new ArrayList<>();
        int count = 0;
        for (PriceLevel level : side.values()) {
            if (count++ >= depth) {
                break;
            }
            levels.add(new BookLevel(level.priceTicks, level.totalQuantity(), level.orderCount()));
        }
        return List.copyOf(levels);
    }

    @Override
    public void addResting(long orderId, Side side, long priceTicks, long quantity, long sequence) {
        addRestingOrder(new Order(orderId, side, priceTicks, quantity, sequence));
    }

    @Override
    public void addRestingOrder(Order order) {
        NavigableMap<Long, PriceLevel> side = side(order.side());
        PriceLevel level = side.computeIfAbsent(order.priceTicks(), PriceLevel::new);
        level.add(order);
        orderIndex.put(order.orderId(), new OrderLocation(order.side(), order.priceTicks(), order.orderId()));
    }

    @Override
    public boolean cancel(long orderId) {
        OrderLocation location = orderIndex.remove(orderId);
        if (location == null) {
            return false;
        }

        NavigableMap<Long, PriceLevel> side = side(location.side());
        PriceLevel level = side.get(location.priceTicks());
        if (level == null) {
            return false;
        }

        level.remove(orderId);
        if (level.isEmpty()) {
            side.remove(location.priceTicks());
        }
        return true;
    }

    @Override
    public long matchAggressive(Side takerSide, long limitPriceTicks, long quantity, MatchSink sink) {
        Side bookSide = takerSide.opposite();
        NavigableMap<Long, PriceLevel> opposite = side(bookSide);
        long remaining = quantity;

        while (remaining > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, PriceLevel> top = opposite.firstEntry();
            long levelPrice = top.getKey();
            if (!isPriceCrossed(takerSide, limitPriceTicks, levelPrice)) {
                break;
            }

            PriceLevel level = top.getValue();
            while (remaining > 0 && !level.isEmpty()) {
                Order maker = level.peekFirst();
                long fillQty = Math.min(remaining, maker.quantity());
                sink.onTrade(maker.orderId(), levelPrice, fillQty);

                remaining -= fillQty;
                long makerRemaining = maker.quantity() - fillQty;
                if (makerRemaining == 0) {
                    level.removeFirst();
                    orderIndex.remove(maker.orderId());
                } else {
                    level.updateFirstQuantity(makerRemaining);
                }
            }

            if (level.isEmpty()) {
                opposite.remove(levelPrice);
            }
        }

        return remaining;
    }

    private static boolean isPriceCrossed(Side takerSide, long limitPriceTicks, long levelPrice) {
        return takerSide == Side.BUY ? limitPriceTicks >= levelPrice : limitPriceTicks <= levelPrice;
    }

    private NavigableMap<Long, PriceLevel> side(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private record OrderLocation(Side side, long priceTicks, long orderId) {}
}
