package com.cloblab.loadtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadTestRunnerTest {
    @Test
    void parseArgsOverridesDefaults() {
        LoadTestConfig config = LoadTestRunner.parseArgs(new String[]{
                "--mode=multi",
                "--threads=8",
                "--warmup=2",
                "--duration=60"
        });

        assertEquals(LoadTestMode.MULTI, config.mode());
        assertEquals(8, config.threads());
        assertEquals(2, config.warmupSeconds());
        assertEquals(60, config.durationSeconds());
    }
}
