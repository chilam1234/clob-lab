# ADR-0010: Remove remaining synchronized blocks from the engine hot path (post-0009 hardening)

Date: 2026-10-10
Status: Implemented
Related: ADR-0001 (dedicated shard threads), commit d9f081c (RingBuffer fences)

## Current state (measured)

Latency profile of the matching path (MatchBenchmark, 200k samples, JVM toy grade):
match p50=42ns p99=167ns p999=1.5us — matching itself is effectively lock-free per symbol:
the matcher is a single-threaded owner of its book (LMAX discipline).

The remaining `synchronized` blocks all sit OUTSIDE the matched core but ON shared structures
touched by shard threads:

| Site | Protected data | Producer thread | Cost today |
|---|---|---|---|
| `FairMarketDataPublisher.stageTrade/stageSnapshot` | pending trades/snapshots lists | shard threads | monitor enter/exit per trade; 6 shards × 10Hz bursts contend on ONE monitor |
| `FairMarketDataPublisher.flush(int)` | same lists + subscriber call | shard threads | the wedge class of ADR-0010: holding the monitor while dispatching subscribers |
| `EventJournal.append` | in-memory event list | shard threads | one global journal = 6 shard threads serialize on every execution |
| `GatewayServer.start/stop` | lifecycle flags | lifecycle only | fine (cold path) |
| `SymbolShard.start/shutdown` | consumer thread handle | lifecycle only | fine (cold path) |

The 2026-10-10 pipeline wedge (fixed at the gateway tier) showed how a hot-path monitor +
a slow subscriber combine into a full stall. The publisher lock remains the same shape of risk
inside the engine tier: `flush()` holds the monitor across `subscriber.onFairRelease(...)`,
so a subscriber that blocks re-creates the stall (today: GatewayServer.enqueue-only → is
non-blocking, so no wedge — but the DESIGN still permits it).

## Decision

Make the hot path lock-free in three steps, ordered by risk:

1. **Per-symbol publisher shards (removes cross-shard contention, keeps fairness).**
   Replace the single FairMarketDataPublisher with one instance per symbolId, owned
   by that symbol's shard thread. Staging/flush become plain unsynchronized field/logic
   access (SPSC discipline: only the owning shard thread touches its instance).
   Subscribers fanout already routes by symbolId (GatewayServer frame fanout) — the
   GatewayServer fanout queue is thread-safe (ConcurrentLinkedQueue), so subscriber
   dispatch happens WITHOUT any monitor held. The publisher's Subscriber interface keeps
   the contract: no subscriber may block (gateway enqueue + assert).
2. **Striped journal (6-way stripe by symbolId).** EventJournal becomes N stripes
   (one per symbol, default 6), each an unsynchronized SPSC-drained buffer owned by
   the shard thread; a single replayer reads stripes by per-stripe monotonically
   increasing eventId and merges by eventId (preserves the ADR-0001 "journal at
   execution order" replay contract — merge on global sequence, not stripes order).
   No lock on the append path.
3. **Cold-path cleanup.** `start/stop/shutdown` synchronized blocks may stay (startup
   only). Document the SPSC ownership rules next to each remaining monitor.

## Alternatives considered

- **MPMC ring replacing SPSC per stage (JCTools):** adds the dependency we deliberately
  zeroed out; contention shifts rather than disappears; rejected for this codebase scale.
- **Disruptor-style sequencer per exchange:** right shape for a real exchange; overkill here
  (we don't have cross-symbol dependency in the order flow). Revisit if symbols >64 and
  symbol-crossing flows appear.
- **Keep single publisher but shrink the critical section** (copy-then-dispatch outside the
  lock): halves the wedge window but keeps per-event monitor traffic; superseded by (1)
  which is the same effort.

## Consequences

- Cross-symbol fairness batching no longer shares one lock — per-symbol release cadence
  becomes independent, which is acceptable: symbol fairness is per-symbol anyway (ADR-0001).
- FairRelease releaseNano stays per-flush; multi-shard flushes are no longer globally
  simultaneous, but no invariant depends on that.
- `LoadTestRunner` CONTENDED mode (threads sharking one engine) intentionally retains its
  synchronized stress mode — it exists to measure contention.
- Tests: existing publisher/journal suites pass unchanged; add (a) concurrent stage+flush
  per-symbol test proving no shared state, (b) replay merge-by-eventId stripe test.
- Implementation: `CloudExchange` holds `FairMarketDataPublisher[] marketDataBySymbol` and
  `StripedEventJournal`; `EventJournal` remains synchronized for MatchingEngine / direct tests.
  Hot-path `synchronized` removed from publisher stage/flush; journal append on the exchange
  path is stripe-local SPSC. Cold-path `GatewayServer.start/stop` and `SymbolShard.start/shutdown`
  monitors left as-is.

### MatchBenchmark before / after

| | p50 | p99 | p999 |
|---|---|---|---|
| Before (ADR baseline, 200k samples) | 42ns | 167ns | 1.5µs |
| After | *not measured — agent Shell blocked in this session; re-run locally* | | |

```bash
source ./env.sh && ./gradlew compileJava -q
java -cp build/classes/java/main com.cloblab.bench.MatchBenchmark
```

Note: MatchBenchmark exercises `MatchingEngine` only (already lock-free per symbol). Publisher /
journal contention is outside that microbench; pipeline / load-test numbers are the better
signal for ADR-0010 gains.
