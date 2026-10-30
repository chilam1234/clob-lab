package com.cloblab.journal;

import com.cloblab.model.OrderType;
import com.cloblab.model.Side;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;

/**
 * Binary encoding of {@link OrderEvent} for durable stripe journals (ADR-0011).
 *
 * <pre>
 * eventId:long, kind:byte, orderId:long, side:byte, hasPrice:boolean,
 * [priceTicks:long], quantity:long, orderType:byte
 * </pre>
 */
public final class OrderEventCodec {
    private static final byte KIND_SUBMIT = 0;
    private static final byte KIND_CANCEL = 1;
    private static final byte SIDE_NONE = 0;
    private static final byte SIDE_BUY = 1;
    private static final byte SIDE_SELL = 2;
    private static final byte TYPE_NONE = 0;
    private static final byte TYPE_LIMIT = 1;
    private static final byte TYPE_MARKET = 2;
    private static final byte TYPE_IOC = 3;
    private static final byte TYPE_FOK = 4;

    private OrderEventCodec() {}

    public static void write(DataOutput out, OrderEvent event) throws IOException {
        out.writeLong(event.eventId());
        out.writeByte(event.kind() == OrderEvent.OrderEventKind.CANCEL ? KIND_CANCEL : KIND_SUBMIT);
        out.writeLong(event.orderId());
        out.writeByte(encodeSide(event.side()));
        if (event.priceTicks() == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeLong(event.priceTicks());
        }
        out.writeLong(event.quantity());
        out.writeByte(encodeType(event.orderType()));
    }

    /**
     * Read one event, or {@code null} on clean EOF at a record boundary.
     * Partial trailing bytes (hard kill mid-record) also return {@code null}.
     */
    public static OrderEvent read(DataInput in) throws IOException {
        long eventId;
        try {
            eventId = in.readLong();
        } catch (EOFException e) {
            return null;
        }
        try {
            byte kind = in.readByte();
            long orderId = in.readLong();
            Side side = decodeSide(in.readByte());
            Long priceTicks = in.readBoolean() ? in.readLong() : null;
            long quantity = in.readLong();
            OrderType orderType = decodeType(in.readByte());
            OrderEvent.OrderEventKind eventKind = kind == KIND_CANCEL
                    ? OrderEvent.OrderEventKind.CANCEL
                    : OrderEvent.OrderEventKind.SUBMIT;
            return new OrderEvent(eventId, eventKind, orderId, side, priceTicks, quantity, orderType);
        } catch (EOFException e) {
            // Truncated record after crash — drop the partial trail.
            return null;
        }
    }

    private static byte encodeSide(Side side) {
        if (side == null) {
            return SIDE_NONE;
        }
        return side == Side.BUY ? SIDE_BUY : SIDE_SELL;
    }

    private static Side decodeSide(byte code) {
        return switch (code) {
            case SIDE_BUY -> Side.BUY;
            case SIDE_SELL -> Side.SELL;
            default -> null;
        };
    }

    private static byte encodeType(OrderType type) {
        if (type == null) {
            return TYPE_NONE;
        }
        return switch (type) {
            case LIMIT -> TYPE_LIMIT;
            case MARKET -> TYPE_MARKET;
            case IOC -> TYPE_IOC;
            case FOK -> TYPE_FOK;
        };
    }

    private static OrderType decodeType(byte code) {
        return switch (code) {
            case TYPE_LIMIT -> OrderType.LIMIT;
            case TYPE_MARKET -> OrderType.MARKET;
            case TYPE_IOC -> OrderType.IOC;
            case TYPE_FOK -> OrderType.FOK;
            default -> null;
        };
    }
}
