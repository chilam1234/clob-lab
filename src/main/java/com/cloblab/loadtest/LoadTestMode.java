package com.cloblab.loadtest;

public enum LoadTestMode {
    /** One thread, one engine — max single-core throughput. */
    SINGLE,
    /** N threads, one engine each (N symbols) — aggregate throughput. */
    MULTI,
    /** N threads share one engine (synchronized) — contention stress test. */
    CONTENDED
}
