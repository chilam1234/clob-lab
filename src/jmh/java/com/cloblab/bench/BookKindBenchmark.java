package com.cloblab.bench;

import com.cloblab.engine.BookKind;
import com.cloblab.engine.MatchingEngine;
import com.cloblab.model.Side;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compare {@link BookKind#FAST} vs {@link BookKind#TREE} on the same hot paths.
 * Run: {@code ./gradlew jmh}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class BookKindBenchmark {
    @Param({"FAST", "TREE"})
    private BookKind bookKind;

    private MatchingEngine engine;
    private final AtomicLong orderId = new AtomicLong(1);

    @Setup(Level.Trial)
    public void seedBook() {
        engine = MatchingEngine.create(bookKind);
        for (long i = 1; i <= 500; i++) {
            engine.submitLimitOrder(i, Side.SELL, 100 + (i % 10), 5);
            engine.submitLimitOrder(500 + i, Side.BUY, 99 - (i % 10), 5);
        }
    }

    @Benchmark
    public void limitOrderMatch(Blackhole blackhole) {
        long id = orderId.getAndIncrement();
        blackhole.consume(engine.submitLimitOrder(id, Side.BUY, 105, 1));
        engine.cancel(id);
    }

    @Benchmark
    public void fokOrderFullFill(Blackhole blackhole) {
        long id = orderId.getAndIncrement();
        blackhole.consume(engine.submitFokOrder(id, Side.BUY, 105, 1));
    }

    @Benchmark
    public void l2SnapshotDepth5(Blackhole blackhole) {
        blackhole.consume(engine.snapshot(5));
    }
}
