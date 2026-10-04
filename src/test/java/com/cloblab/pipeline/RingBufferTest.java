package com.cloblab.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingBufferTest {
    @Test
    void offerAndPollInOrder() {
        RingBuffer<Integer> ring = new RingBuffer<>(4);
        assertTrue(ring.offer(1));
        assertTrue(ring.offer(2));
        assertEquals(2, ring.size());
        assertEquals(1, ring.poll());
        assertEquals(2, ring.poll());
        assertNull(ring.poll());
    }

    @Test
    void rejectsWhenFull() {
        RingBuffer<Integer> ring = new RingBuffer<>(2);
        assertTrue(ring.offer(1));
        assertTrue(ring.offer(2));
        assertTrue(!ring.offer(3));
    }
}
