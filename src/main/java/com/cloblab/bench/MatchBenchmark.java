package com.cloblab.bench;

import com.cloblab.engine.MatchingEngine;
import com.cloblab.latency.LatencyRecorder;
import com.cloblab.model.Side;

/**
 * Simple microbenchmark: seed a book, then measure limit-order match latency.
 */
public final class MatchBenchmark {
    private static final int WARMUP = 50_000;
    private static final int SAMPLES = 200_000;

    public static void main(String[] args) {
        MatchingEngine engine = new MatchingEngine();

        // Seed resting liquidity on both sides.
        for (long i = 1; i <= 1_000; i++) {
            engine.submitLimitOrder(i, Side.SELL, 100 + (i % 5), 10);
            engine.submitLimitOrder(1_000 + i, Side.BUY, 99 - (i % 5), 10);
        }

        for (int i = 0; i < WARMUP; i++) {
            long orderId = 10_000 + i;
            engine.submitLimitOrder(orderId, Side.BUY, 105, 1);
            engine.cancel(orderId);
        }

        LatencyRecorder recorder = new LatencyRecorder(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long orderId = 100_000 + i;
            long start = System.nanoTime();
            engine.submitLimitOrder(orderId, Side.BUY, 105, 1);
            long elapsed = System.nanoTime() - start;
            recorder.record(elapsed);
            engine.cancel(orderId);
        }

        System.out.println("Match latency over " + recorder.count() + " samples:");
        System.out.println(recorder.stats());
        System.out.println();
        System.out.println("Note: this is a JVM toy benchmark, not exchange-grade measurement.");
        System.out.println("For production work you'd warm the JIT longer, pin threads, and use JMH.");
    }
}
