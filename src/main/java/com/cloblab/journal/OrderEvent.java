package com.cloblab.journal;

import com.cloblab.model.OrderType;
import com.cloblab.model.Side;

/**
 * Immutable input event for journal + replay.
 * priceTicks is {@code null} for market orders.
 */
public record OrderEvent(
        long eventId,
        OrderEventKind kind,
        long orderId,
        Side side,
        Long priceTicks,
        long quantity,
        OrderType orderType) {

    public enum OrderEventKind {
        SUBMIT,
        CANCEL
    }

    public static OrderEvent submitLimit(long eventId, long orderId, Side side, long priceTicks, long quantity) {
        return new OrderEvent(eventId, OrderEventKind.SUBMIT, orderId, side, priceTicks, quantity, OrderType.LIMIT);
    }

    public static OrderEvent submitMarket(long eventId, long orderId, Side side, long quantity) {
        return new OrderEvent(eventId, OrderEventKind.SUBMIT, orderId, side, null, quantity, OrderType.MARKET);
    }

    public static OrderEvent submitIoc(long eventId, long orderId, Side side, long priceTicks, long quantity) {
        return new OrderEvent(eventId, OrderEventKind.SUBMIT, orderId, side, priceTicks, quantity, OrderType.IOC);
    }

    public static OrderEvent submitFok(long eventId, long orderId, Side side, long priceTicks, long quantity) {
        return new OrderEvent(eventId, OrderEventKind.SUBMIT, orderId, side, priceTicks, quantity, OrderType.FOK);
    }

    public static OrderEvent cancel(long eventId, long orderId) {
        return new OrderEvent(eventId, OrderEventKind.CANCEL, orderId, null, null, 0, null);
    }
}
