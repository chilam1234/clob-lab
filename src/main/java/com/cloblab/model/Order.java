package com.cloblab.model;

/**
 * Immutable resting order snapshot. Price is in integer ticks (e.g. cents).
 * sequence establishes time priority at the same price level.
 */
public record Order(long orderId, Side side, long priceTicks, long quantity, long sequence) {
    public Order withQuantity(long newQuantity) {
        return new Order(orderId, side, priceTicks, newQuantity, sequence);
    }
}
