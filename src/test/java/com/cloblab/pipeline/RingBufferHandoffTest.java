package com.cloblab.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-thread handoff exercises the release/acquire fences: producer publishes payloads,
 * consumer observes them only via the producer index. Also asserts offer() blocks nothing
 * and SPSC single-thread behaviour is preserved.
 */
class RingBufferHandoffTest {

    @RepeatedTest(20)
    void crossThreadHandoffPreservesOrderAndVisibility() throws Exception {
        final int n = 100_000;
        RingBuffer<Long> ring = new RingBuffer<>(1024);
        CountDownLatch producerDone = new CountDownLatch(1);
        AtomicLong lastReceived = new AtomicLong(-1);
        AtomicLong received = new AtomicLong(0);
        AtomicLong checksum = new AtomicLong(0);

        Thread consumer = new Thread(() -> {
            long expected = 1;
            long sum = 0;
            while (received.get() < n) {
                Long item = ring.poll();
                if (item != null) {
                    if (item != expected) {
                        throw new AssertionError("out of order: got " + item + " expected " + expected);
                    }
                    sum += item;
                    lastReceived.set(item);
                    received.incrementAndGet();
                    expected++;
                }
            }
            checksum.set(sum);
        });
        consumer.start();

        Thread producer = new Thread(() -> {
            for (long i = 1; i <= n; i++) {
                while (!ring.offer(i)) {
                    Thread.onSpinWait();
                }
            }
            producerDone.countDown();
        });
        producer.start();
        producerDone.await();
        consumer.join(10_000);

        assertEquals(n, received.get());
        assertEquals(n, lastReceived.get());
        assertEquals((long) n * (n + 1) / 2, checksum.get());
        assertNull(ring.poll());
    }

    @Test
    void spscSemanticsStillHold() {
        RingBuffer<Integer> ring = new RingBuffer<>(4);
        assertTrue(ring.offer(1));
        assertEquals(1, ring.poll());
        assertNull(ring.poll());
    }
}