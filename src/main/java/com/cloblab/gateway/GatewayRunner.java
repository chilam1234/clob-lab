package com.cloblab.gateway;

import com.cloblab.model.Side;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demo of the threaded gateway: dedicated shard consumers and per-frame fair MD.
 */
public final class GatewayRunner {
    public static void main(String[] args) {
        Gateway gateway = new Gateway(2, 1024);
        CountDownLatch seeded = new CountDownLatch(4);
        gateway.subscribe(frame -> {
            System.out.println(
                    "Gateway frame @" + frame.releaseNano() + "ns"
                            + " trades=" + frame.trades().size()
                            + " snapshots=" + frame.snapshots().size());
            seeded.countDown();
        });
        gateway.start();
        try {
            System.out.println("=== Gateway (threaded shards) ===\n");

            System.out.println(gateway.submitLimit(0, 1, Side.SELL, 101, 20));
            System.out.println(gateway.submitLimit(0, 2, Side.SELL, 100, 15));
            System.out.println(gateway.submitLimit(0, 3, Side.BUY, 99, 30));
            System.out.println(gateway.submitLimit(0, 4, Side.BUY, 100, 10));
            if (!seeded.await(2, TimeUnit.SECONDS)) {
                System.out.println("timed out waiting for seed frames");
            }
            System.out.println("duplicate: " + gateway.submitLimit(0, 3, Side.BUY, 98, 1));
            System.out.println("zero qty: " + gateway.submitLimit(0, 5, Side.BUY, 98, 0));
            System.out.println(gateway.submitMarket(1, 20, Side.SELL, 1));
            System.out.println(gateway.cancel(1, 20));

            var book = gateway.exchange().router().shard(0).engine().book();
            System.out.println("\nSymbol 0 best bid=" + book.bestBid() + " best ask=" + book.bestAsk());
            System.out.println("Journal events: " + gateway.exchange().journal().size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            gateway.shutdown();
        }
    }
}
