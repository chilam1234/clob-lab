package com.cloblab.latency;

import java.util.Arrays;

/**
 * Latency histogram in nanoseconds with percentile stats.
 */
public final class LatencyRecorder {
    private long[] samples;
    private int count;

    public LatencyRecorder(int initialCapacity) {
        this.samples = new long[Math.max(1, initialCapacity)];
    }

    public void record(long nanos) {
        if (count >= samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[count++] = nanos;
    }

    public int count() {
        return count;
    }

    public void merge(LatencyRecorder other) {
        if (other == this) {
            throw new IllegalArgumentException("cannot merge a recorder into itself (unbounded growth)");
        }
        for (int i = 0; i < other.count; i++) {
            record(other.samples[i]);
        }
    }

    public LatencyStats stats() {
        if (count == 0) {
            return new LatencyStats(0, 0, 0, 0, 0);
        }
        long[] copy = Arrays.copyOf(samples, count);
        Arrays.sort(copy);
        return new LatencyStats(
                copy[0],
                percentile(copy, 50),
                percentile(copy, 99),
                percentile(copy, 99.9),
                copy[copy.length - 1]);
    }

    private static long percentile(long[] sorted, double pct) {
        int index = Math.min(sorted.length - 1, (int) Math.ceil(pct / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, index)];
    }

    public record LatencyStats(long minNs, long p50Ns, long p99Ns, long p999Ns, long maxNs) {
        @Override
        public String toString() {
            return "min=" + minNs + "ns p50=" + p50Ns + "ns p99=" + p99Ns + "ns p999=" + p999Ns + "ns max=" + maxNs + "ns";
        }
    }
}
