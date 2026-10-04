package com.cloblab.loadtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoadTestConfigTest {

    @Test
    void snapshotPercentIsRemainder() {
        LoadTestConfig config = LoadTestConfig.defaults();
        assertEquals(5, config.mixSnapshotPct());
    }

    @Test
    void rejectsZeroThreads() {
        LoadTestConfig config = new LoadTestConfig(LoadTestMode.SINGLE, 0, 1, 10, 500, 65, 20, 10);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void rejectsZeroDuration() {
        LoadTestConfig config = new LoadTestConfig(LoadTestMode.SINGLE, 1, 1, 0, 500, 65, 20, 10);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void rejectsOversubscribedMix() {
        LoadTestConfig config = new LoadTestConfig(LoadTestMode.SINGLE, 1, 1, 10, 500, 80, 20, 10);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void validConfigPasses() {
        LoadTestConfig.defaults().validate();
    }
}