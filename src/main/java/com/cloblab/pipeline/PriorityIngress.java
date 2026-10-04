package com.cloblab.pipeline;

import com.cloblab.book.OrderBookView;
import com.cloblab.protocol.InboundCommand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * FancyPQ-inspired ingress (Jasper 2024): during bursts, prioritize orders closer to the mid-price
 * because they are more likely to match immediately.
 */
public final class PriorityIngress {
    private final List<SequencedCommand> buffer = new ArrayList<>();
    private final int burstThreshold;

    public PriorityIngress(int burstThreshold) {
        this.burstThreshold = burstThreshold;
    }

    public List<SequencedCommand> reorder(List<SequencedCommand> batch, OrderBookView book) {
        if (batch.size() < burstThreshold) {
            return batch;
        }
        Long bestBid = book.bestBid();
        Long bestAsk = book.bestAsk();
        if (bestBid == null || bestAsk == null) {
            return batch;
        }
        long mid = (bestBid + bestAsk) / 2;
        buffer.clear();
        buffer.addAll(batch);
        buffer.sort(Comparator.comparingLong(cmd -> distanceFromMid(cmd.command(), mid)));
        return List.copyOf(buffer);
    }

    private static long distanceFromMid(InboundCommand command, long mid) {
        if (command.kind() == InboundCommand.Kind.CANCEL || command.priceTicks() == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(command.priceTicks() - mid);
    }

    /** Higher score = more critical (closer to mid). */
    public static long criticalityScore(InboundCommand command, OrderBookView book) {
        Long bestBid = book.bestBid();
        Long bestAsk = book.bestAsk();
        if (bestBid == null || bestAsk == null || command.priceTicks() == null) {
            return 0;
        }
        long mid = (bestBid + bestAsk) / 2;
        long distance = Math.abs(command.priceTicks() - mid);
        return Math.max(0, 10_000 - distance);
    }
}
