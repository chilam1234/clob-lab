package com.cloblab.engine;

/** Primitive trade scratch — no Trade objects on the match loop. */
final class TradeBuffer {
    private long[] makerIds = new long[8];
    private long[] takerIds = new long[8];
    private long[] priceTicks = new long[8];
    private long[] quantities = new long[8];
    private int size;

    void clear() {
        size = 0;
    }

    int size() {
        return size;
    }

    void add(long makerOrderId, long takerOrderId, long price, long quantity) {
        if (size == makerIds.length) {
            int n = size * 2;
            makerIds = java.util.Arrays.copyOf(makerIds, n);
            takerIds = java.util.Arrays.copyOf(takerIds, n);
            priceTicks = java.util.Arrays.copyOf(priceTicks, n);
            quantities = java.util.Arrays.copyOf(quantities, n);
        }
        makerIds[size] = makerOrderId;
        takerIds[size] = takerOrderId;
        priceTicks[size] = price;
        quantities[size] = quantity;
        size++;
    }

    long[] makerIds() {
        return makerIds;
    }

    long[] takerIds() {
        return takerIds;
    }

    long[] priceTicks() {
        return priceTicks;
    }

    long[] quantities() {
        return quantities;
    }
}
