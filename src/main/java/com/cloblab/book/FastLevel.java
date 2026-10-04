package com.cloblab.book;

/** FIFO queue head/tail at one tick — orders are indices into {@link OrderPool}. */
final class FastLevel {
    int head = -1;
    int tail = -1;
    long totalQuantity;
    int orderCount;

    boolean isEmpty() {
        return head < 0;
    }
}
