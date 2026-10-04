package com.cloblab.latency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyRecorderTest {

    @Test
    void emptyRecorderYieldsZeroedStats() {
        var stats = new LatencyRecorder(8).stats();
        assertEquals(0, stats.minNs());
        assertEquals(0, stats.p50Ns());
        assertEquals(0, stats.p99Ns());
        assertEquals(0, stats.p999Ns());
        assertEquals(0, stats.maxNs());
    }

    @Test
    void statsAreSortedPercentiles() {
        LatencyRecorder recorder = new LatencyRecorder(4);
        // grow beyond initial capacity to exercise the resize path
        for (long ns : new long[]{500, 100, 400, 200, 300, 300}) {
            recorder.record(ns);
        }
        var stats = recorder.stats();
        assertEquals(100, stats.minNs());
        assertEquals(300, stats.p50Ns());
        assertEquals(500, stats.p99Ns());
        assertEquals(500, stats.p999Ns());
        assertEquals(500, stats.maxNs());
        assertEquals(6, recorder.count());
    }

    @Test
    void mergeCombinesSamples() {
        LatencyRecorder a = new LatencyRecorder(2);
        a.record(100);
        LatencyRecorder b = new LatencyRecorder(2);
        b.record(300);
        b.record(200);
        a.merge(b);
        assertEquals(3, a.count());
        assertEquals(100, a.stats().minNs());
        assertEquals(300, a.stats().maxNs());
    }

    @Test
    void toStringContainsAllPercentiles() {
        LatencyRecorder recorder = new LatencyRecorder(1);
        recorder.record(42);
        String s = recorder.stats().toString();
        assertTrue(s.contains("42ns"));
    }

    @Test
    void mergeSelfIsRejected() {
        LatencyRecorder a = new LatencyRecorder(2);
        a.record(10);
        assertThrows(IllegalArgumentException.class, () -> a.merge(a));
        assertEquals(1, a.count());
        assertEquals(10, a.stats().minNs());
    }
}