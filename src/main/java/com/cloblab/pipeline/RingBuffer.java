package com.cloblab.pipeline;

import java.lang.invoke.VarHandle;

/**
 * Single-producer single-consumer ring buffer — LMAX/Chronicle-style inter-stage queue.
 * Pre-allocated slots; no allocation on offer/poll hot path.
 *
 * <p>Thread-safety: exactly one producer thread and one consumer thread (SPSC). Index
 * publication uses release/acquire fences per the LMAX Disruptor pattern so slot contents
 * are visible when the opposing index write is observed. Violating SPSC (e.g. two producers)
 * is unsupported; use an MPMC queue instead.
 */
public final class RingBuffer<T> {
    private final Object[] slots;
    private final int capacity;
    private long producerIndex; // read/written across threads with fences
    private long consumerIndex; // read/written across threads with fences

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

    /** Producer-only. Publishes the slot BEFORE the producer index (release). */
    public boolean offer(T item) {
        long head = producerIndex;
        long tail = consumerIndex;
        if (head - tail >= capacity) {
            return false;
        }
        slots[(int) (head & (capacity - 1))] = item;
        VarHandle.releaseFence(); // slot store must not be reordered after index publication
        producerIndex = head + 1;
        return true;
    }

    /** Consumer-only. Reads the producer index, then the slot (acquire). */
    @SuppressWarnings("unchecked")
    public T poll() {
        long tail = consumerIndex;
        if (tail >= producerIndex) {
            return null;
        }
        int index = (int) (tail & (capacity - 1));
        VarHandle.acquireFence(); // slot read must not be reordered before index read
        T item = (T) slots[index];
        slots[index] = null;
        VarHandle.releaseFence(); // slot clear must happen before head publication
        consumerIndex = tail + 1;
        return item;
    }

    public int size() {
        return (int) Math.min(Integer.MAX_VALUE, producerIndex - consumerIndex);
    }
}
