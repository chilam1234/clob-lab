package com.cloblab.book;

import com.cloblab.marketdata.BookLevel;
import com.cloblab.marketdata.L2Snapshot;
import com.cloblab.model.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Array-backed CLOB: O(1) tick lookup, pooled intrusive lists, no alloc on match loop.
 * Price must fall in [{@link #tickBase}, {@link #tickBase} + {@link #tickSpan}).
 */
public final class FastOrderBook implements OrderBookView {
    private final int tickBase;
    private final int tickSpan;
    private final FastLevel[] bids;
    private final FastLevel[] asks;
    private final OrderPool pool;
    private final Map<Long, Integer> orderIndex = new HashMap<>();

    private long bestBidTick = Long.MIN_VALUE;
    private long bestAskTick = Long.MAX_VALUE;
    private long totalBidQty;
    private long totalAskQty;

    public FastOrderBook(int tickBase, int tickSpan, int orderPoolCapacity) {
        this.tickBase = tickBase;
        this.tickSpan = tickSpan;
        this.bids = new FastLevel[tickSpan];
        this.asks = new FastLevel[tickSpan];
        for (int i = 0; i < tickSpan; i++) {
            bids[i] = new FastLevel();
            asks[i] = new FastLevel();
        }
        this.pool = new OrderPool(orderPoolCapacity);
    }

    /** Default range covers typical lab prices (1 … 1_000_000 ticks). */
    public static FastOrderBook createDefault() {
        return new FastOrderBook(0, 1_000_000, 524_288);
    }

    @Override
    public Long bestBid() {
        return bestBidTick == Long.MIN_VALUE ? null : bestBidTick;
    }

    @Override
    public Long bestAsk() {
        return bestAskTick == Long.MAX_VALUE ? null : bestAskTick;
    }

    @Override
    public long totalBidQuantity() {
        return totalBidQty;
    }

    @Override
    public long totalAskQuantity() {
        return totalAskQty;
    }

    @Override
    public boolean contains(long orderId) {
        return orderIndex.containsKey(orderId);
    }

    @Override
    public long availableAtOrBetter(Side takerSide, long limitPriceTicks) {
        long available = 0;
        if (takerSide == Side.BUY) {
            for (long tick = bestAskTick; tick <= limitPriceTicks && tick <= tickBase + tickSpan - 1; tick++) {
                FastLevel level = levelAt(asks, tick);
                if (level == null || level.isEmpty()) {
                    continue;
                }
                available += level.totalQuantity;
                if (available >= Long.MAX_VALUE / 2) {
                    return available;
                }
            }
        } else {
            for (long tick = bestBidTick; tick >= limitPriceTicks && tick >= tickBase; tick--) {
                FastLevel level = levelAt(bids, tick);
                if (level == null || level.isEmpty()) {
                    continue;
                }
                available += level.totalQuantity;
            }
        }
        return available;
    }

    @Override
    public L2Snapshot snapshot(int depth) {
        return new L2Snapshot(collectSide(bids, bestBidTick, -1, depth), collectSide(asks, bestAskTick, 1, depth));
    }

    @Override
    public void addResting(long orderId, Side side, long priceTicks, long quantity, long sequence) {
        int slot = pool.acquire();
        PooledOrder node = pool.get(slot);
        node.orderId = orderId;
        node.side = side;
        node.priceTicks = priceTicks;
        node.quantity = quantity;
        node.sequence = sequence;

        FastLevel level = levelFor(side, priceTicks);
        int levelIdx = toIndex(priceTicks);
        node.levelIndex = levelIdx;
        append(level, slot);

        orderIndex.put(orderId, slot);
        if (side == Side.BUY) {
            totalBidQty += quantity;
            if (priceTicks > bestBidTick) {
                bestBidTick = priceTicks;
            }
        } else {
            totalAskQty += quantity;
            if (priceTicks < bestAskTick) {
                bestAskTick = priceTicks;
            }
        }
    }

    @Override
    public boolean cancel(long orderId) {
        Integer slotObj = orderIndex.remove(orderId);
        if (slotObj == null) {
            return false;
        }
        removeNode(slotObj);
        return true;
    }

    @Override
    public long matchAggressive(Side takerSide, long limitPriceTicks, long quantity, MatchSink sink) {
        long remaining = quantity;
        if (takerSide == Side.BUY) {
            while (remaining > 0 && bestAskTick != Long.MAX_VALUE && bestAskTick <= limitPriceTicks) {
                FastLevel level = levelAt(asks, bestAskTick);
                if (level == null || level.isEmpty()) {
                    advanceBestAsk();
                    continue;
                }
                remaining = matchLevel(Side.SELL, bestAskTick, level, remaining, sink);
                if (level.isEmpty()) {
                    advanceBestAsk();
                }
            }
        } else {
            while (remaining > 0 && bestBidTick != Long.MIN_VALUE && bestBidTick >= limitPriceTicks) {
                FastLevel level = levelAt(bids, bestBidTick);
                if (level == null || level.isEmpty()) {
                    advanceBestBid();
                    continue;
                }
                remaining = matchLevel(Side.BUY, bestBidTick, level, remaining, sink);
                if (level.isEmpty()) {
                    advanceBestBid();
                }
            }
        }
        return remaining;
    }

    private long matchLevel(Side makerSide, long levelPrice, FastLevel level, long remaining, MatchSink sink) {
        while (remaining > 0 && !level.isEmpty()) {
            int headSlot = level.head;
            PooledOrder maker = pool.get(headSlot);
            long fillQty = Math.min(remaining, maker.quantity);
            sink.onTrade(maker.orderId, levelPrice, fillQty);

            remaining -= fillQty;
            maker.quantity -= fillQty;
            level.totalQuantity -= fillQty;

            if (makerSide == Side.BUY) {
                totalBidQty -= fillQty;
            } else {
                totalAskQty -= fillQty;
            }

            if (maker.quantity == 0) {
                orderIndex.remove(maker.orderId);
                popHead(level);
                pool.release(headSlot);
            }
        }
        return remaining;
    }

    private List<BookLevel> collectSide(FastLevel[] side, long startTick, int direction, int depth) {
        List<BookLevel> levels = new ArrayList<>(depth);
        if ((direction < 0 && startTick == Long.MIN_VALUE) || (direction > 0 && startTick == Long.MAX_VALUE)) {
            return List.of();
        }
        long tick = startTick;
        while (levels.size() < depth && tick >= tickBase && tick < tickBase + tickSpan) {
            FastLevel level = levelAt(side, tick);
            if (level != null && !level.isEmpty()) {
                levels.add(new BookLevel(tick, level.totalQuantity, level.orderCount));
            }
            tick += direction;
            if (direction < 0 && tick < tickBase) {
                break;
            }
            if (direction > 0 && tick >= tickBase + tickSpan) {
                break;
            }
        }
        return List.copyOf(levels);
    }

    private void append(FastLevel level, int slot) {
        if (level.isEmpty()) {
            level.head = slot;
            level.tail = slot;
        } else {
            pool.get(level.tail).next = slot;
            pool.get(slot).prev = level.tail;
            level.tail = slot;
        }
        level.totalQuantity += pool.get(slot).quantity;
        level.orderCount++;
    }

    private void popHead(FastLevel level) {
        int headSlot = level.head;
        PooledOrder head = pool.get(headSlot);
        level.orderCount--;
        int next = head.next;
        if (next < 0) {
            level.head = -1;
            level.tail = -1;
        } else {
            level.head = next;
            pool.get(next).prev = -1;
        }
    }

    private void removeNode(int slot) {
        PooledOrder node = pool.get(slot);
        FastLevel level = levelFor(node.side, node.priceTicks);

        if (node.side == Side.BUY) {
            totalBidQty -= node.quantity;
        } else {
            totalAskQty -= node.quantity;
        }

        level.totalQuantity -= node.quantity;
        level.orderCount--;

        int prev = node.prev;
        int next = node.next;
        if (prev < 0) {
            level.head = next;
        } else {
            pool.get(prev).next = next;
        }
        if (next < 0) {
            level.tail = prev;
        } else {
            pool.get(next).prev = prev;
        }

        if (node.side == Side.BUY && node.priceTicks == bestBidTick && level.isEmpty()) {
            advanceBestBid();
        } else if (node.side == Side.SELL && node.priceTicks == bestAskTick && level.isEmpty()) {
            advanceBestAsk();
        }

        pool.release(slot);
    }

    private void advanceBestAsk() {
        bestAskTick++;
        while (bestAskTick < tickBase + tickSpan) {
            FastLevel level = levelAt(asks, bestAskTick);
            if (level != null && !level.isEmpty()) {
                return;
            }
            bestAskTick++;
        }
        bestAskTick = Long.MAX_VALUE;
    }

    private void advanceBestBid() {
        bestBidTick--;
        while (bestBidTick >= tickBase) {
            FastLevel level = levelAt(bids, bestBidTick);
            if (level != null && !level.isEmpty()) {
                return;
            }
            bestBidTick--;
        }
        bestBidTick = Long.MIN_VALUE;
    }

    private FastLevel levelFor(Side side, long priceTicks) {
        return side == Side.BUY ? levelAt(bids, priceTicks) : levelAt(asks, priceTicks);
    }

    private FastLevel levelAt(FastLevel[] side, long priceTicks) {
        int idx = toIndex(priceTicks);
        if (idx < 0) {
            return null;
        }
        return side[idx];
    }

    private int toIndex(long priceTicks) {
        if (priceTicks < tickBase || priceTicks >= tickBase + tickSpan) {
            return -1;
        }
        return (int) (priceTicks - tickBase);
    }
}
