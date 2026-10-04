package com.cloblab.pipeline;

/**
 * Single-producer single-consumer ring buffer — LMAX/Chronicle-style inter-stage queue.
 * Pre-allocated slots; no allocation on offer/poll hot path.
 */
public final class RingBuffer<T> {
    private final Object[] slots;
    private final int capacity;
    private long producerIndex;
    private long consumerIndex;

    public RingBuffer(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be a positive power of two");
        }
        this.capacity = capacity;
        this.slots = new Object[capacity];
    }

    public int capacity() {
        return capacity;
    }

    public boolean offer(T item) {
        long head = producerIndex;
        long tail = consumerIndex;
        if (head - tail >= capacity) {
            return false;
        }
        slots[(int) (head & (capacity - 1))] = item;
        producerIndex = head + 1;
        return true;
    }

    @SuppressWarnings("unchecked")
    public T poll() {
        long tail = consumerIndex;
        if (tail >= producerIndex) {
            return null;
        }
        int index = (int) (tail & (capacity - 1));
        T item = (T) slots[index];
        slots[index] = null;
        consumerIndex = tail + 1;
        return item;
    }

    public int size() {
        return (int) Math.min(Integer.MAX_VALUE, producerIndex - consumerIndex);
    }
}
