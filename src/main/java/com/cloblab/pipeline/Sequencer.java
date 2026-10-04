package com.cloblab.pipeline;

import com.cloblab.protocol.InboundCommand;

import java.util.concurrent.atomic.AtomicLong;

/** Assigns monotonic global sequence at ingress so the matcher sees generation order. */
public final class Sequencer {
    private final AtomicLong nextSequence = new AtomicLong(1);

    public SequencedCommand stamp(InboundCommand command) {
        long seq = nextSequence.getAndIncrement();
        long ingressNano = System.nanoTime();
        return new SequencedCommand(seq, ingressNano, command);
    }

    public long nextSequenceValue() {
        return nextSequence.get();
    }
}
