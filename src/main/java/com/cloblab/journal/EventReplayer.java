package com.cloblab.journal;

import com.cloblab.engine.MatchingEngine;
import com.cloblab.model.OrderType;

import java.util.List;

/**
 * Replays a journal into a fresh matching engine (no journaling on replay).
 */
public final class EventReplayer {
    private EventReplayer() {}

    public static MatchingEngine replay(List<OrderEvent> events) {
        MatchingEngine engine = new MatchingEngine();
        for (OrderEvent event : events) {
            apply(engine, event);
        }
        return engine;
    }

    public static void apply(MatchingEngine engine, OrderEvent event) {
        switch (event.kind()) {
            case SUBMIT -> {
                if (event.orderType() == OrderType.LIMIT) {
                    engine.submitLimitOrder(event.orderId(), event.side(), event.priceTicks(), event.quantity());
                } else if (event.orderType() == OrderType.MARKET) {
                    engine.submitMarketOrder(event.orderId(), event.side(), event.quantity());
                } else if (event.orderType() == OrderType.IOC) {
                    engine.submitIocOrder(event.orderId(), event.side(), event.priceTicks(), event.quantity());
                } else if (event.orderType() == OrderType.FOK) {
                    engine.submitFokOrder(event.orderId(), event.side(), event.priceTicks(), event.quantity());
                } else {
                    throw new IllegalArgumentException("Unknown order type: " + event.orderType());
                }
            }
            case CANCEL -> engine.cancel(event.orderId());
            default -> throw new IllegalArgumentException("Unknown event kind: " + event.kind());
        }
    }
}
