package com.cloblab.loadtest;

import com.cloblab.engine.MatchingEngine;
import com.cloblab.latency.LatencyRecorder;
import com.cloblab.model.Side;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sustained load generator for the matching engine.
 *
 * <pre>
 *   ./gradlew runLoadTest
 *   ./gradlew runLoadTest --args="--mode=multi --threads=8 --duration=30"
 * </pre>
 */
public final class LoadTestRunner {
    private static final AtomicLong GLOBAL_ORDER_ID = new AtomicLong(10_000_000L);

    public static void main(String[] args) {
        LoadTestConfig config = parseArgs(args);
        config.validate();
        new LoadTestRunner(config).run();
    }

    private final LoadTestConfig config;

    LoadTestRunner(LoadTestConfig config) {
        this.config = config;
    }

    void run() {
        System.out.println("=== clob-lab load test ===");
        System.out.printf(Locale.US, "Mode: %s | threads: %d | warmup: %ds | duration: %ds%n",
                config.mode(), config.threads(), config.warmupSeconds(), config.durationSeconds());
        System.out.printf(Locale.US, "Mix: limit=%d%% cancel=%d%% ioc=%d%% snapshot=%d%%%n",
                config.mixLimitPct(), config.mixCancelPct(), config.mixIocPct(), config.mixSnapshotPct());
        System.out.println();

        long[] opCounts = new long[4];
        LatencyRecorder latency = switch (config.mode()) {
            case SINGLE -> runSingle(opCounts);
            case MULTI -> runParallel(false, opCounts);
            case CONTENDED -> runParallel(true, opCounts);
        };

        long totalOps = opCounts[0] + opCounts[1] + opCounts[2] + opCounts[3];
        double seconds = config.durationSeconds();
        double throughput = totalOps / seconds;

        System.out.println("--- results ---");
        System.out.printf(Locale.US, "Total operations: %,d%n", totalOps);
        System.out.printf(Locale.US, "Throughput: %,.0f ops/sec%n", throughput);
        System.out.println("Latency: " + latency.stats());
        System.out.printf(Locale.US, "  limit submits: %,d%n", opCounts[0]);
        System.out.printf(Locale.US, "  cancels:       %,d%n", opCounts[1]);
        System.out.printf(Locale.US, "  IOC submits:   %,d%n", opCounts[2]);
        System.out.printf(Locale.US, "  L2 snapshots:  %,d%n", opCounts[3]);
        System.out.println();
        System.out.println("Note: this is a local JVM toy load test, not exchange-grade benchmarking.");
        System.out.println("Use --mode=single for peak hot-path throughput, --mode=contended for lock stress.");
    }

    private LatencyRecorder runSingle(long[] opCounts) {
        MatchingEngine engine = new MatchingEngine();
        seed(engine, 0);
        Worker worker = new Worker(engine, null, opCounts, true);
        worker.warmup(config.warmupSeconds());
        return worker.runFor(config.durationSeconds());
    }

    private LatencyRecorder runParallel(boolean contended, long[] opCounts) {
        MatchingEngine shared = contended ? new MatchingEngine() : null;
        if (contended) {
            seed(shared, 0);
        }

        ExecutorService pool = Executors.newFixedThreadPool(config.threads());
        CountDownLatch start = new CountDownLatch(1);
        LatencyRecorder[] recorders = new LatencyRecorder[config.threads()];
        long[][] perThreadOps = new long[config.threads()][4];

        for (int t = 0; t < config.threads(); t++) {
            final int threadIndex = t;
            MatchingEngine engine = contended ? shared : new MatchingEngine();
            Object lock = contended ? shared : null;
            if (!contended) {
                seed(engine, threadIndex);
            }
            pool.submit(() -> {
                Worker worker = new Worker(engine, lock, perThreadOps[threadIndex], contended);
                try {
                    start.await();
                    worker.warmup(config.warmupSeconds());
                    recorders[threadIndex] = worker.runFor(config.durationSeconds());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(config.durationSeconds() + config.warmupSeconds() + 30L, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }

        LatencyRecorder merged = new LatencyRecorder(65536);
        for (int t = 0; t < config.threads(); t++) {
            if (recorders[t] != null) {
                merged.merge(recorders[t]);
            }
            for (int i = 0; i < 4; i++) {
                opCounts[i] += perThreadOps[t][i];
            }
        }
        return merged;
    }

    private static void seed(MatchingEngine engine, int symbolOffset) {
        long base = 100 + symbolOffset * 10;
        for (long i = 1; i <= 500; i++) {
            long askPrice = base + (i % 5);
            long bidPrice = base - 1 - (i % 5);
            engine.submitLimitOrder(i, Side.SELL, askPrice, 5);
            engine.submitLimitOrder(1000 + i, Side.BUY, bidPrice, 5);
        }
    }

    static LoadTestConfig parseArgs(String[] args) {
        LoadTestConfig defaults = LoadTestConfig.defaults();
        LoadTestMode mode = defaults.mode();
        int threads = defaults.threads();
        int warmup = defaults.warmupSeconds();
        int duration = defaults.durationSeconds();

        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                mode = LoadTestMode.valueOf(arg.substring("--mode=".length()).toUpperCase(Locale.US));
            } else if (arg.startsWith("--threads=")) {
                threads = Integer.parseInt(arg.substring("--threads=".length()));
            } else if (arg.startsWith("--warmup=")) {
                warmup = Integer.parseInt(arg.substring("--warmup=".length()));
            } else if (arg.startsWith("--duration=")) {
                duration = Integer.parseInt(arg.substring("--duration=".length()));
            }
        }

        return new LoadTestConfig(mode, threads, warmup, duration,
                defaults.seedLevelsPerSide(), defaults.mixLimitPct(),
                defaults.mixCancelPct(), defaults.mixIocPct());
    }

    private final class Worker {
        private final MatchingEngine engine;
        private final Object lock;
        private final long[] opCounts;
        private final Deque<Long> cancellable = new ArrayDeque<>();
        private final LatencyRecorder latency = new LatencyRecorder(65536);

        Worker(MatchingEngine engine, Object lock, long[] opCounts, boolean contended) {
            this.engine = engine;
            this.lock = contended ? lock : null;
            this.opCounts = opCounts;
        }

        void warmup(int seconds) {
            runUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
        }

        LatencyRecorder runFor(int seconds) {
            runUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
            return latency;
        }

        private void runUntil(long endNanos) {
            int tick = 0;
            while (System.nanoTime() < endNanos) {
                int bucket = tick % 100;
                tick++;
                if (bucket < config.mixLimitPct()) {
                    timedLimitSubmit();
                } else if (bucket < config.mixLimitPct() + config.mixCancelPct()) {
                    timedCancel();
                } else if (bucket < config.mixLimitPct() + config.mixCancelPct() + config.mixIocPct()) {
                    timedIoc();
                } else {
                    timedSnapshot();
                }
            }
        }

        private void timedLimitSubmit() {
            long id = GLOBAL_ORDER_ID.incrementAndGet();
            Side side = (id & 1) == 0 ? Side.BUY : Side.SELL;
            long price = side == Side.BUY ? 105 : 95;
            long start = System.nanoTime();
            runLocked(() -> engine.submitLimitOrder(id, side, price, 1));
            latency.record(System.nanoTime() - start);
            opCounts[0]++;
            cancellable.addLast(id);
            if (cancellable.size() > 512) {
                cancellable.removeFirst();
            }
        }

        private void timedCancel() {
            Long id = cancellable.pollFirst();
            if (id == null) {
                timedLimitSubmit();
                return;
            }
            long start = System.nanoTime();
            runLocked(() -> engine.cancel(id));
            latency.record(System.nanoTime() - start);
            opCounts[1]++;
        }

        private void timedIoc() {
            long id = GLOBAL_ORDER_ID.incrementAndGet();
            Side side = (id & 1) == 0 ? Side.BUY : Side.SELL;
            long price = side == Side.BUY ? 105 : 95;
            long start = System.nanoTime();
            runLocked(() -> engine.submitIocOrder(id, side, price, 1));
            latency.record(System.nanoTime() - start);
            opCounts[2]++;
        }

        private void timedSnapshot() {
            long start = System.nanoTime();
            runLocked(() -> engine.snapshot(5));
            latency.record(System.nanoTime() - start);
            opCounts[3]++;
        }

        private void runLocked(Runnable action) {
            if (lock == null) {
                action.run();
            } else {
                synchronized (lock) {
                    action.run();
                }
            }
        }
    }
}
