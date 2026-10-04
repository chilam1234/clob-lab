package com.cloblab.book;

import com.cloblab.model.Order;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * One price level on the book. Orders are FIFO within the level (price-time priority).
 */
final class PriceLevel {
    final long priceTicks;
    final Deque<Order> orders = new ArrayDeque<>();
    long totalQuantity;

    PriceLevel(long priceTicks) {
        this.priceTicks = priceTicks;
    }

    void add(Order order) {
        orders.addLast(order);
        totalQuantity += order.quantity();
    }

    Order peekFirst() {
        return orders.peekFirst();
    }

    Order removeFirst() {
        Order removed = orders.removeFirst();
        totalQuantity -= removed.quantity();
        return removed;
    }

    void updateFirstQuantity(long newQuantity) {
        Order first = orders.peekFirst();
        totalQuantity -= first.quantity();
        orders.removeFirst();
        Order updated = first.withQuantity(newQuantity);
        orders.addFirst(updated);
        totalQuantity += newQuantity;
    }

    void remove(long orderId) {
        var iterator = orders.iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next();
            if (order.orderId() == orderId) {
                iterator.remove();
                totalQuantity -= order.quantity();
                return;
            }
        }
    }

    boolean isEmpty() {
        return orders.isEmpty();
    }

    long totalQuantity() {
        return totalQuantity;
    }

    int orderCount() {
        return orders.size();
    }
}
