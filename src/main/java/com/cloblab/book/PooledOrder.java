package com.cloblab.book;

import com.cloblab.model.Side;

/** Mutable order node stored in {@link OrderPool} — intrusive doubly-linked list per price level. */
final class PooledOrder {
    long orderId;
    Side side;
    long priceTicks;
    long quantity;
    long sequence;
    int prev = -1;
    int next = -1;
    int levelIndex = -1;

    void clear() {
        orderId = 0;
        side = null;
        priceTicks = 0;
        quantity = 0;
        sequence = 0;
        prev = -1;
        next = -1;
        levelIndex = -1;
    }
}
