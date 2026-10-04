package com.cloblab.exchange;

import com.cloblab.pipeline.SequencedCommand;

/** Routes sequenced commands to symbol shards (single-writer per shard). */
public final class ExchangeRouter {
    private final SymbolShard[] shards;

    public ExchangeRouter(SymbolShard[] shards) {
        this.shards = shards;
    }

    public SymbolShard shard(int symbolId) {
        if (symbolId < 0 || symbolId >= shards.length) {
            throw new IllegalArgumentException("unknown symbolId: " + symbolId);
        }
        return shards[symbolId];
    }

    public boolean route(SequencedCommand command) {
        return shard(command.command().symbolId()).enqueue(command);
    }

    public int shardCount() {
        return shards.length;
    }

    public SymbolShard[] shards() {
        return shards;
    }
}
