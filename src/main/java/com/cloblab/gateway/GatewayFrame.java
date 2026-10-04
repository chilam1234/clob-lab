package com.cloblab.gateway;

import com.cloblab.marketdata.FairMarketDataPublisher;
import com.cloblab.marketdata.L2Snapshot;
import com.cloblab.model.Trade;

import java.util.List;

/**
 * One fair MD release: trades and L2 snapshots copied out of the matcher.
 *
 * <p>The matching engine reuses a single {@code MatchResult} per shard. Frames therefore
 * always reference copies, never the scratch buffers. Do not assume a frame remains valid
 * if you retain matcher-owned objects obtained outside this type.
 */
public record GatewayFrame(long releaseNano, List<Trade> trades, List<L2Snapshot> snapshots) {
    public GatewayFrame {
        trades = List.copyOf(trades);
        snapshots = List.copyOf(snapshots);
    }

    static GatewayFrame from(FairMarketDataPublisher.FairRelease release) {
        return new GatewayFrame(release.releaseNano(), release.trades(), release.snapshots());
    }
}
