package com.cloblab.book;

/** Fixed-size pool of {@link PooledOrder} nodes — no allocation on match/rest/cancel hot path. */
final class OrderPool {
    private final PooledOrder[] orders;
    private final int[] freeStack;
    private int freeTop;

    OrderPool(int capacity) {
        orders = new PooledOrder[capacity];
        for (int i = 0; i < capacity; i++) {
            orders[i] = new PooledOrder();
        }
        freeStack = new int[capacity];
        freeTop = capacity;
        for (int i = 0; i < capacity; i++) {
            freeStack[--freeTop] = i;
        }
    }

    int acquire() {
        if (freeTop >= freeStack.length) {
            throw new IllegalStateException("Order pool exhausted — increase capacity");
        }
        return freeStack[freeTop++];
    }

    void release(int index) {
        orders[index].clear();
        freeStack[--freeTop] = index;
    }

    PooledOrder get(int index) {
        return orders[index];
    }
}
