package com.cloblab.loadtest;

/**
 * Load test configuration. Override via CLI flags on {@link LoadTestRunner}.
 */
public record LoadTestConfig(
        LoadTestMode mode,
        int threads,
        int warmupSeconds,
        int durationSeconds,
        int seedLevelsPerSide,
        int mixLimitPct,
        int mixCancelPct,
        int mixIocPct) {

    public static LoadTestConfig defaults() {
        return new LoadTestConfig(LoadTestMode.SINGLE, 1, 3, 10, 500, 65, 20, 10);
    }

    public int mixSnapshotPct() {
        return 100 - mixLimitPct - mixCancelPct - mixIocPct;
    }

    public void validate() {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        if (durationSeconds < 1) {
            throw new IllegalArgumentException("duration must be >= 1");
        }
        if (mixSnapshotPct() < 0) {
            throw new IllegalArgumentException("mix percentages must sum to 100");
        }
    }
}
