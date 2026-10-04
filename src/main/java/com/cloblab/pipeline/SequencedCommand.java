package com.cloblab.pipeline;

import com.cloblab.protocol.InboundCommand;

/** Command after global sequencing — inbound fairness (Jasper-style generation order). */
public record SequencedCommand(long globalSequence, long ingressNano, InboundCommand command) {}
