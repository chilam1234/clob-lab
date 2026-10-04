package com.cloblab.protocol;

import com.cloblab.model.OrderType;
import com.cloblab.model.Side;

/**
 * Raw command from a participant before global sequencing (Jasper/Onyx ingress).
 */
public record InboundCommand(
        int symbolId,
        Kind kind,
        long orderId,
        Side side,
        Long priceTicks,
        long quantity,
        OrderType orderType) {

    public enum Kind {
        SUBMIT,
        CANCEL
    }

    public static InboundCommand submitLimit(int symbolId, long orderId, Side side, long priceTicks, long quantity) {
        return new InboundCommand(symbolId, Kind.SUBMIT, orderId, side, priceTicks, quantity, OrderType.LIMIT);
    }

    public static InboundCommand submitIoc(int symbolId, long orderId, Side side, long priceTicks, long quantity) {
        return new InboundCommand(symbolId, Kind.SUBMIT, orderId, side, priceTicks, quantity, OrderType.IOC);
    }

    public static InboundCommand cancel(int symbolId, long orderId) {
        return new InboundCommand(symbolId, Kind.CANCEL, orderId, null, null, 0, null);
    }
}
