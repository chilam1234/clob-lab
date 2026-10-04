package com.cloblab.gateway.transport;

import java.util.ArrayDeque;

/**
 * Per-symbol 1-minute OHLCV aggregate over trade prints (ADR-0007).
 * The live candle is re-emitted (unclosed) on every print; a closed (x=true) frame
 * marks the minute boundary. Bounded: only the live candle is held.
 */
final class CandleAggregator {
    static final long BUCKET_MILLIS = 60_000L;

    long bucketOpen = -1;
    String o, h, l, c, v;
    long volumeTicks;

    /** Update with one print; returns JSON kline frame (never null). */
    String onTrade(long priceTicks, long quantity, long nowMillis, String symbolUpper) {
        long bucket = nowMillis - (nowMillis % BUCKET_MILLIS);
        if (bucket != bucketOpen) {
            if (bucketOpen != -1) {
                // close the previous bucket conceptually: caller may choose to emit x=true first
            }
            bucketOpen = bucket;
            o = String.valueOf(priceTicks);
            h = o;
            l = o;
            c = o;
            v = "0";
            volumeTicks = 0;
        }
        long px = priceTicks;
        if (px > Long.parseLong(h)) {
            h = String.valueOf(px);
        }
        if (px < Long.parseLong(l)) {
            l = String.valueOf(px);
        }
        c = String.valueOf(px);
        volumeTicks += quantity;
        v = String.valueOf(volumeTicks);
        return klineJson(symbolUpper, false);
    }

    String klineJson(String symbolUpper, boolean closed) {
        return "{\"e\":\"kline\",\"E\":" + System.currentTimeMillis()
                + ",\"s\":\"" + symbolUpper + "\","
                + "\"k\":{\"t\":" + bucketOpen
                + ",\"T\":" + (bucketOpen + BUCKET_MILLIS - 1)
                + ",\"s\":\"" + symbolUpper + "\""
                + ",\"i\":\"1m\""
                + ",\"f\":0,\"L\":0"
                + ",\"o\":\"" + o + "\""
                + ",\"c\":\"" + c + "\""
                + ",\"h\":\"" + h + "\""
                + ",\"l\":\"" + l + "\""
                + ",\"v\":\"" + v + "\""
                + ",\"n\":1"
                + ",\"x\":" + closed
                + ",\"q\":\"" + v + "\""
                + ",\"V\":\"0\",\"Q\":\"0\",\"B\":\"0\"}}";
    }

    boolean hasCandle() { return bucketOpen != -1; }
}
