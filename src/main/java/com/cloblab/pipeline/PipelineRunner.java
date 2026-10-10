package com.cloblab.pipeline;

import com.cloblab.exchange.CloudExchange;
import com.cloblab.model.Side;
import com.cloblab.protocol.InboundCommand;

/**
 * Demo of the 2024+ cloud-exchange pipeline: sequencer, FancyPQ burst, sharded matchers, fair MD.
 */
public final class PipelineRunner {
    public static void main(String[] args) {
        CloudExchange exchange = new CloudExchange(/*symbols*/ 2, /*ring*/ 1024, /*burst threshold*/ 3);

        exchange.subscribeMarketData(release -> System.out.println(
                "Fair MD release symbol=" + release.symbolId()
                        + " @" + release.releaseNano() + "ns"
                        + " trades=" + release.trades().size()
                        + " snapshots=" + release.snapshots().size()));

        System.out.println("=== CloudExchange pipeline demo ===\n");

        seedLiquidity(exchange, 0);
        printBook(exchange, 0, "Symbol 0 initial");

        System.out.println("\n--- Burst ingress (FancyPQ reorders by mid-price distance) ---");
        exchange.submit(InboundCommand.submitLimit(0, 10, Side.BUY, 95, 5));
        exchange.submit(InboundCommand.submitLimit(0, 11, Side.BUY, 100, 5));
        exchange.submit(InboundCommand.submitLimit(0, 12, Side.BUY, 99, 5));
        var tick = exchange.flushBatch();
        System.out.println("Routed=" + tick.routed() + " processed=" + tick.processed()
                + " deferred=" + tick.deferred());
        printBook(exchange, 0, "After burst flush");

        System.out.println("\n--- Sequencer global order ---");
        System.out.println("Next sequence (monotonic ingress): " + exchange.sequencer().nextSequenceValue());

        System.out.println("\n--- Multi-symbol shard routing ---");
        exchange.submit(InboundCommand.submitLimit(1, 20, Side.SELL, 200, 10));
        exchange.flushBatch();
        printBook(exchange, 1, "Symbol 1 book");

        System.out.println("\nJournal events recorded: " + exchange.journal().size());
        System.out.println("\nInspired by: Jasper (2024), Onyx (SIGCOMM 2025), LMAX Disruptor, Chronicle.");
    }

    private static void seedLiquidity(CloudExchange exchange, int symbolId) {
        exchange.submit(InboundCommand.submitLimit(symbolId, 1, Side.SELL, 101, 20));
        exchange.submit(InboundCommand.submitLimit(symbolId, 2, Side.SELL, 100, 15));
        exchange.submit(InboundCommand.submitLimit(symbolId, 3, Side.BUY, 99, 30));
        exchange.flushBatch();
    }

    private static void printBook(CloudExchange exchange, int symbolId, String label) {
        var book = exchange.router().shard(symbolId).engine().book();
        System.out.println(label);
        System.out.println("  best bid: " + book.bestBid() + " (qty=" + book.totalBidQuantity() + ")");
        System.out.println("  best ask: " + book.bestAsk() + " (qty=" + book.totalAskQuantity() + ")");
    }
}
