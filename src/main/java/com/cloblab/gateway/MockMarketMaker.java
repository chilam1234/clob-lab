package com.cloblab.gateway;

import com.cloblab.model.Side;
import com.cloblab.protocol.InboundCommand;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Synthetic market maker driving the real pipeline: random-walk mid, two-sided resting
 * quotes, and occasional aggressive orders that cross and print trades. All orders flow
 * through {@link Gateway#submit} so the UI sees exactly what real orders see (acks,
 * rejects, per-frame MD).
 *
 * <p>Producer safety: submits serialize inside Gateway/CloudExchange routing, preserving
 * the SPSC single-producer contract on each shard ring. Order ids are 1,000,000+ so they
 * never collide with UI-assigned ids.
 */
public final class MockMarketMaker {
    private static final long BASE_ID = 1_000_000L;
    private static final int MAX_TRACKED_RESTING = 4096;

    private final Gateway gateway;
    private final int symbolId;
    private final long intervalMillis;
    private final Thread thread;
    private volatile boolean running;
    private long nextOrderId = BASE_ID;
    private final Deque<Long> restingIds = new ArrayDeque<>();
    private long mid = 1000;

    public MockMarketMaker(Gateway gateway, int symbolId, long intervalMillis) {
        this.gateway = gateway;
        this.symbolId = symbolId;
        this.intervalMillis = intervalMillis;
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
        mid += rnd.nextInt(-3, 4);
        if (mid < 500) {
            mid = 500;
        }

        // 10% chance: cancel an old resting order
        if (!restingIds.isEmpty() && rnd.nextInt(10) == 0) {
            long id = restingIds.pollFirst();
            gateway.cancel(symbolId, id);
        }

        // two-sided limit quotes around mid
        quote(rnd, Side.BUY);
        quote(rnd, Side.SELL);

        // ~30% chance: aggressive order crossing the spread
        if (rnd.nextInt(100) < 30) {
            long qty = rnd.nextLong(5, 31);
            if (rnd.nextBoolean()) {
                gateway.submit(InboundCommand.submitLimit(symbolId, nextOrderId++, Side.BUY, mid + 1, qty));
            } else {
                gateway.submit(InboundCommand.submitLimit(symbolId, nextOrderId++, Side.SELL, mid - 1, qty));
            }
        }
    }

    private void quote(ThreadLocalRandom rnd, Side side) {
        long distance = rnd.nextInt(2, 21);
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