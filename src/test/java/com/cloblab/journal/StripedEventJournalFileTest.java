package com.cloblab.journal;

import com.cloblab.exchange.CloudExchange;
import com.cloblab.model.Side;
import com.cloblab.protocol.InboundCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripedEventJournalFileTest {

    @TempDir
    Path tempDir;

    @Test
    void fileRoundTripWriteCloseReadEqualsInput() {
        Path dir = tempDir.resolve("journal");
        OrderEvent a = OrderEvent.submitLimit(1, 10, Side.BUY, 100, 5);
        OrderEvent b = OrderEvent.submitLimit(2, 11, Side.SELL, 101, 3);
        OrderEvent c = OrderEvent.cancel(3, 10);

        StripedEventJournal written = new StripedEventJournal(dir, 1);
        written.stripe(0).append(a);
        written.stripe(0).append(b);
        written.stripe(0).append(c);
        written.flush();
        written.close();

        StripedEventJournal read = new StripedEventJournal(dir, 1);
        List<OrderEvent> events = read.readAllEvents(0);
        assertEquals(List.of(a, b, c), events);
        read.close();
    }

    @Test
    void bootReplayRestoresBookEquality() {
        Path dir = tempDir.resolve("replay");
        StripedEventJournal journal = new StripedEventJournal(dir, 1);
        CloudExchange first = new CloudExchange(1, 32, Integer.MAX_VALUE, journal);
        first.submit(InboundCommand.submitLimit(0, 1, Side.SELL, 101, 10));
        first.submit(InboundCommand.submitLimit(0, 2, Side.BUY, 99, 7));
        first.flushBatch();
        var bookA = first.router().shard(0).engine().book();
        long bestAsk = bookA.bestAsk();
        long bestBid = bookA.bestBid();
        long askQty = bookA.totalAskQuantity();
        long bidQty = bookA.totalBidQuantity();
        first.shutdown();

        StripedEventJournal journal2 = new StripedEventJournal(dir, 1);
        CloudExchange second = new CloudExchange(1, 32, Integer.MAX_VALUE, journal2);
        JournalBootstrap.replay(second);
        var bookB = second.router().shard(0).engine().book();
        assertEquals(bestAsk, bookB.bestAsk());
        assertEquals(bestBid, bookB.bestBid());
        assertEquals(askQty, bookB.totalAskQuantity());
        assertEquals(bidQty, bookB.totalBidQuantity());
        second.shutdown();
    }

    @Test
    void gapDetectionFailsFast() {
        List<OrderEvent> gapped = List.of(
                OrderEvent.submitLimit(1, 1, Side.BUY, 100, 1),
                OrderEvent.submitLimit(3, 2, Side.BUY, 99, 1));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JournalBootstrap.detectGaps(gapped));
        assertTrue(ex.getMessage().contains("journal gap detected"));
    }
}
