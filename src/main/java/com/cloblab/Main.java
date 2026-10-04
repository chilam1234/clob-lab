package com.cloblab;

import com.cloblab.engine.MatchingEngine;
import com.cloblab.model.MatchResult;
import com.cloblab.model.Side;

/**
 * Interactive demo: limit, market, IOC, FOK, L2 snapshot, journal + replay.
 */
public final class Main {
    public static void main(String[] args) {
        System.out.println("=== Limit order demo ===");
        limitDemo();

        System.out.println("\n=== Market order demo ===");
        marketDemo();

        System.out.println("\n=== IOC demo ===");
        iocDemo();

        System.out.println("\n=== FOK demo ===");
        fokDemo();

        System.out.println("\n=== L2 snapshot demo ===");
        l2Demo();

        System.out.println("\n=== Journal + replay demo ===");
        journalDemo();
    }

    private static void limitDemo() {
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimitOrder(1, Side.SELL, 101, 50);
        engine.submitLimitOrder(2, Side.SELL, 100, 20);
        engine.submitLimitOrder(3, Side.BUY, 99, 30);
        printBook(engine, "Initial book");

        MatchResult buy = engine.submitLimitOrder(5, Side.BUY, 100, 25);
        System.out.println("Limit BUY 25 @ 100 -> trades=" + buy.trades() + ", resting=" + buy.resting());
        printBook(engine, "After limit buy");
    }

    private static void marketDemo() {
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimitOrder(1, Side.SELL, 100, 15);
        engine.submitLimitOrder(2, Side.SELL, 101, 40);
        printBook(engine, "Before market buy");

        MatchResult market = engine.submitMarketOrder(3, Side.BUY, 30);
        System.out.println("Market BUY 30 -> trades=" + market.trades() + ", unfilled=" + market.remainingQuantity());
        printBook(engine, "After market buy (no resting qty)");
    }

    private static void iocDemo() {
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimitOrder(1, Side.SELL, 100, 10);
        printBook(engine, "Before IOC");

        MatchResult ioc = engine.submitIocOrder(2, Side.BUY, 100, 25);
        System.out.println("IOC BUY 25 @ 100 -> trades=" + ioc.trades() + ", cancelled=" + ioc.remainingQuantity());
        printBook(engine, "After IOC (15 cancelled, nothing rested)");
    }

    private static void fokDemo() {
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimitOrder(1, Side.SELL, 100, 10);
        engine.submitLimitOrder(2, Side.SELL, 101, 50);

        MatchResult rejected = engine.submitFokOrder(3, Side.BUY, 100, 25);
        System.out.println("FOK BUY 25 @ 100 (only 10 available) -> trades=" + rejected.trades()
                + ", rejected=" + rejected.remainingQuantity());
        printBook(engine, "After rejected FOK (book unchanged)");

        MatchResult filled = engine.submitFokOrder(4, Side.BUY, 100, 10);
        System.out.println("FOK BUY 10 @ 100 -> trades=" + filled.trades() + ", fullyFilled=" + filled.fullyFilled());
        printBook(engine, "After successful FOK");
    }

    private static void l2Demo() {
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimitOrder(1, Side.SELL, 101, 30);
        engine.submitLimitOrder(2, Side.SELL, 100, 20);
        engine.submitLimitOrder(3, Side.SELL, 100, 15);
        engine.submitLimitOrder(4, Side.BUY, 99, 40);
        engine.submitLimitOrder(5, Side.BUY, 98, 25);

        System.out.println(engine.snapshot(3));
    }

    private static void journalDemo() {
        MatchingEngine.Journaled live = new MatchingEngine.Journaled();
        live.submitLimitOrder(1, Side.SELL, 100, 20);
        live.submitLimitOrder(2, Side.BUY, 100, 8);
        live.cancel(1);
        printBook(live, "Live engine");

        MatchingEngine replayed = live.replay();
        printBook(replayed, "Replayed from journal (should match live)");

        boolean sameBestAsk = java.util.Objects.equals(live.book().bestAsk(), replayed.book().bestAsk());
        boolean sameBestBid = java.util.Objects.equals(live.book().bestBid(), replayed.book().bestBid());
        System.out.println("Replay consistent: " + (sameBestAsk && sameBestBid));
        System.out.println("Journal events: " + live.journal().size());
    }

    private static void printBook(MatchingEngine engine, String label) {
        printBook(engine.book(), label);
    }

    private static void printBook(MatchingEngine.Journaled engine, String label) {
        printBook(engine.book(), label);
    }

    private static void printBook(com.cloblab.book.OrderBookView book, String label) {
        System.out.println();
        System.out.println(label);
        System.out.println("  best bid: " + book.bestBid() + " (qty=" + book.totalBidQuantity() + ")");
        System.out.println("  best ask: " + book.bestAsk() + " (qty=" + book.totalAskQuantity() + ")");
    }
}
