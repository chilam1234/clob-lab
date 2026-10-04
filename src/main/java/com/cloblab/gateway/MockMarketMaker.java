package com.cloblab.gateway;

import com.cloblab.model.Side;
import com.cloblab.protocol.InboundCommand;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Synthetic market maker driving the real pipeline: random-walk mid with volatility
 * spikes, two-sided resting quotes, and aggressive orders that cross and print trades.
 * All orders flow through {@link Gateway#submit} so consumers see exactly what real
 * orders see (acks, rejects, per-frame MD).
 *
 * <p>Producer safety: submits serialize inside Gateway/CloudExchange routing, preserving
 * the SPSC single-producer contract on each shard ring. Order ids are 1,000,000+ so they
 * never collide with UI-assigned ids.
 */
public final class MockMarketMaker {
    public static final long BASE_ID = 1_000_000L;
    private static final int MAX_TRACKED_RESTING = 4096;

    private final Gateway gateway;
    private final int symbolId;
    private final long intervalMillis;
    private final double volatility; // ticks per standard walk step
    private final long startMid;
    private final Thread thread;
    private volatile boolean running;
    private long nextOrderId = BASE_ID;
    private final Deque<Long> restingIds = new ArrayDeque<>();
    private long mid;

    public MockMarketMaker(Gateway gateway, int symbolId, long intervalMillis,
                           double volatility, long startMid) {
        this.gateway = gateway;
        this.symbolId = symbolId;
        this.intervalMillis = intervalMillis;
        this.volatility = volatility;
        this.startMid = startMid;
        this.mid = startMid;
        this.thread = new Thread(this::run, "clob-mock-maker-" + symbolId);
        this.thread.setDaemon(true);
    }

    public void start() {
        running = true;
        thread.start();
    }

    public void stop() {
        running = false;
        thread.interrupt();
    }

    private void run() {
        while (running) {
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            tick();
        }
    }

    private void tick() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // random walk: normal step, occasional volatility spike (5%)
        int step = (int) Math.round(rnd.nextGaussian() * volatility);
        if (rnd.nextInt(100) < 5) {
            step += (int) Math.round((rnd.nextBoolean() ? 1 : -1) * volatility * 5);
        }
        mid += step;
        // mean-revert toward startMid so the walk stays on the ladder
        mid += Math.round((startMid - mid) * 0.01);
        if (mid < 500) {
            mid = 500;
        }

        // 10% chance: cancel an old resting order
        if (!restingIds.isEmpty() && rnd.nextInt(10) == 0) {
            long id = restingIds.pollFirst();
            gateway.cancel(symbolId, id);
        }

        // two-sided limit quotes
        quote(rnd, Side.BUY);
        quote(rnd, Side.SELL);

        // ~30% chance: aggressive order crossing the spread
        if (rnd.nextInt(100) < 30) {
            long qty = rnd.nextLong(5, 31);
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            long px = side == Side.BUY ? mid + 1 : mid - 1;
            gateway.submit(InboundCommand.submitLimit(symbolId, nextOrderId++, side, px, qty));
        }
    }

    private void quote(ThreadLocalRandom rnd, Side side) {
        long distance = (long) Math.ceil(rnd.nextDouble() * 2 * volatility) + 1;
        long qty = rnd.nextLong(1, 11);
        long px = side == Side.BUY ? mid - distance : mid + distance;
        long orderId = nextOrderId++;
        var result = gateway.submit(InboundCommand.submitLimit(symbolId, orderId, side, px, qty));
        if (result.accepted()) {
            restingIds.addLast(orderId);
            while (restingIds.size() > MAX_TRACKED_RESTING) {
                restingIds.pollFirst();
            }
        }
    }
}
