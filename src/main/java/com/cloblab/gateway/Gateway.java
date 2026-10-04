package com.cloblab.gateway;

import com.cloblab.exchange.CloudExchange;
import com.cloblab.model.OrderType;
import com.cloblab.model.Side;
import com.cloblab.protocol.InboundCommand;

import java.util.function.Consumer;

/**
 * Asynchronous ingress over {@link CloudExchange}: submit orders and subscribe to per-frame
 * trade + L2 events. No HTTP/WebSocket transport — that is a later workstream.
 *
 * <p><b>Thread confinement.</b> Each shard matcher reuses one {@code MatchResult} and
 * {@code TradeBuffer}. {@link GatewayFrame} instances delivered to subscribers always
 * reference copies of trades and L2 snapshots. Do not read matcher scratch state from
 * subscriber threads.
 *
 * <p>SPSC: {@link #submit} is the ring producer (CloudExchange routing) for each shard;
 * the shard consumer thread is the sole {@code poll()} caller after {@link #start()}.
 */
public final class Gateway {
    private final CloudExchange exchange;

    public Gateway(int symbolCount, int ringCapacityPerShard) {
        this(new CloudExchange(symbolCount, ringCapacityPerShard, Integer.MAX_VALUE));
    }

    public Gateway(CloudExchange exchange) {
        this.exchange = exchange;
    }

    public CloudExchange exchange() {
        return exchange;
    }

    public void start() {
        exchange.start();
    }

    public void shutdown() {
        exchange.shutdown();
    }

    /**
     * Subscribe to per-frame fair releases. Callbacks run on the shard consumer thread;
     * frames hold copies (see class Javadoc). Do not call {@link #submit} from the callback
     * (that would violate the shard ring's SPSC contract).
     */
    public void subscribe(Consumer<GatewayFrame> subscriber) {
        exchange.marketData().subscribe(release -> subscriber.accept(GatewayFrame.from(release)));
    }

    public GatewayResult submitLimit(int symbolId, long orderId, Side side, long priceTicks, long quantity) {
        return submit(InboundCommand.submitLimit(symbolId, orderId, side, priceTicks, quantity));
    }

    public GatewayResult submitMarket(int symbolId, long orderId, Side side, long quantity) {
        return submit(InboundCommand.submitMarket(symbolId, orderId, side, quantity));
    }

    public GatewayResult submitIoc(int symbolId, long orderId, Side side, long priceTicks, long quantity) {
        return submit(InboundCommand.submitIoc(symbolId, orderId, side, priceTicks, quantity));
    }

    public GatewayResult submitFok(int symbolId, long orderId, Side side, long priceTicks, long quantity) {
        return submit(InboundCommand.submitFok(symbolId, orderId, side, priceTicks, quantity));
    }

    public GatewayResult cancel(int symbolId, long orderId) {
        return submit(InboundCommand.cancel(symbolId, orderId));
    }

    /**
     * Route a command onto the symbol shard ring. Returns {@link GatewayResult.Status#REJECTED}
     * for validation failures (duplicate id, non-positive qty, unknown symbol, …) instead of
     * throwing. Engine {@link IllegalArgumentException} is mapped the same way.
     */
    public GatewayResult submit(InboundCommand command) {
        try {
            GatewayResult rejected = validate(command);
            if (rejected != null) {
                return rejected;
            }
            boolean offered = exchange.router().route(exchange.sequencer().stamp(command));
            if (!offered) {
                return GatewayResult.rejected("ring full");
            }
            return GatewayResult.ok();
        } catch (IllegalArgumentException e) {
            return GatewayResult.rejected(e.getMessage());
        }
    }

    private GatewayResult validate(InboundCommand command) {
        if (command.kind() == InboundCommand.Kind.CANCEL) {
            return null;
        }
        if (command.quantity() <= 0) {
            return GatewayResult.rejected("quantity must be positive: " + command.quantity());
        }
        if (command.orderId() <= 0) {
            return GatewayResult.rejected("orderId must be positive: " + command.orderId());
        }
        if (command.side() == null) {
            return GatewayResult.rejected("side must not be null");
        }
        if (command.orderType() == OrderType.LIMIT
                && (command.priceTicks() == null || command.priceTicks() <= 0)) {
            return GatewayResult.rejected("limit/market price must be positive: " + command.priceTicks());
        }
        if (exchange.router().shard(command.symbolId()).engine().book().contains(command.orderId())) {
            return GatewayResult.rejected(
                    "duplicate orderId: " + command.orderId() + " (cancel or fill it first)");
        }
        return null;
    }
}
