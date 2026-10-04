package com.cloblab.marketdata;

import java.util.List;

/** Point-in-time depth snapshot of both sides of the book. */
public record L2Snapshot(List<BookLevel> bids, List<BookLevel> asks) {
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("L2Snapshot\n  ASKS (low → high):\n");
        for (int i = asks.size() - 1; i >= 0; i--) {
            BookLevel level = asks.get(i);
            sb.append("    ").append(level.priceTicks()).append(" × ").append(level.quantity())
                    .append(" (").append(level.orderCount()).append(" orders)\n");
        }
        sb.append("  --- spread ---\n");
        sb.append("  BIDS (high → low):\n");
        for (BookLevel level : bids) {
            sb.append("    ").append(level.priceTicks()).append(" × ").append(level.quantity())
                    .append(" (").append(level.orderCount()).append(" orders)\n");
        }
        return sb.toString();
    }
}
