# ADR-0001: Dedicated per-shard execution threads + event-driven gateway

- Status: Proposed
- Date: 2026-10-04
- Deciders: Billy Chan (repo owner), Hermes agent (audit)

## Context

clob-lab implements a "cloud-exchange pipeline" (Jasper 2024 / Onyx 2025
patterns): global sequencer → FancyPQ burst reorder → sharded matchers →
fair market-data fanout. The structure matches the papers, but the
execution model does not:

1. **Shards are not threaded.** `SymbolShard.processAll()` is called
   synchronously by `CloudExchange.flushBatch()` on the caller's thread.
   The `RingBuffer` (SPSC, LMAX-style) is never consumed by a dedicated
   thread — the "one symbol, one matcher thread" model documented in
   `SymbolShard`'s Javadoc is aspirational only.
2. **Market data releases are batch-poll driven.**
   `FairMarketDataPublisher.flush()` fires only when the ingress batch
   is fully processed. Latency is therefore bounded below by batch size
   and by how often the demo loop calls `flushBatch()`, not by engine
   throughput.
3. **No consumer exists for the event stream.** The trade UI (see
   docs/trade-ui-plan.md) needs a push-style feed of trades + L2 deltas.
   Today the only way to observe book state is pull (`snapshot()`).

Any downstream consumer — a trade UI, a replay tool, an analytics
process — is forced into the same batch-poll semantics, which is the
anti-pattern the referenced research exists to remove.

## Decision drivers

- The SPSC ring buffer is already correct for cross-thread use
  (release/acquire fences, ADR follow-up from the production-hardening
  pass); it is unused for its intended purpose.
- The user's goal for this repo is a credible portfolio project for
  low-latency trading-systems roles (quant/exchange tier).
- The trade UI plan needs a streaming API; building it on batch-poll
  semantics would immediately require rework.
- Keep the project single-binary, single-JVM: no external broker or
  message bus dependency at this scale.

## Decision

**Run each `SymbolShard` on its own dedicated busy-spin consumer thread,
and expose an asynchronous gateway over the whole pipeline.**

1. **Threaded shard loop (core change).**
   - `SymbolShard` gains a `start()` / `shutdown()` lifecycle: a fixed
     consumer thread that busy-spins `poll()` and applies commands,
     with `Thread.onSpinWait()` on empty. The thread is the sole
     consumer — SPSC contract preserved (producer = CloudExchange
     ingress router thread).
   - Matcher stays lock-free single-threaded; no change to matching
     semantics. `MatchResult` reuse caveat travels with the shard
     (apply() consumes it before emitting events).
   - Clean shutdown: volatile `running` flag + join with timeout;
     pending ring contents are drained before exit (documented, not
     "lost" silently).
2. **Event-driven MD release.**
   - After each processed command (or drained-frame), the shard stages
     trades + L2 snapshot and requests a flush. The fair-release
     guarantee becomes per-frame (all subscribers observe the same
     release) instead of per-batch. `flushBatch()` remains as a
     synchronous mode for tests/tools that want deterministic ticks.
3. **Gateway API (new package `com.cloblab.gateway`).**
   - `Gateway.start()` exposes: submit (limit/market/IOC/FOK/cancel)
     and subscribe (trade events, L2 updates), via a small interface —
     no HTTP/WebSocket transport in this ADR; transport is the trade
     UI workstream's first task and gets its own ADR if it forks from
     this design.
   - Back-pressure: submit returns a `GatewayResult` (ACCEPTED /
     REJECTED reason) instead of throwing, mapping the engine's
     fail-fast validation to a wire-level reject — matching how real
     gateways behave (reject responses, not exceptions over the wire).
4. **Load test + bench keep the synchronous path** so benchmark
   comparisons (FAST vs TREE, single vs multi) stay apples-to-apples.

## Consequences

Positive:
- The pipeline finally matches its documented architecture; the ring
  buffer's cross-thread hardening becomes load-bearing instead of dead
  code.
- Tail latency decouples from batch size — JMH/loadtest can measure the
  real claim ("~7M ops/sec aggregate") rather than a batch loop.
- Unblocks the trade UI: subscribers get push updates at frame
  granularity.

Negative / risks:
- Two threads per shard (producer router + consumer) on a laptop means
  context-switch noise in benchmarks — mitigated by keeping the
  synchronous mode for `bench`/`loadtest`.
- Journal ordering: with threads, execution order across shards is no
  longer total; the per-shard journal gains a shard id. Replay must be
  per-symbol (already effectively true — journals carry the global
  ingress sequence, and each symbol's matcher order is deterministic).
- `MatchResult` and `TradeBuffer` reuse is thread-confined per shard —
  must be documented in the gateway contract to prevent accidental
  cross-thread reads.

## Validation

- New test: shard thread drains ring commands in FIFO order, applies
  them, releases MD per frame, shuts down cleanly without losing
  in-flight commands.
- New test: gateway rejects (duplicate id, bad qty) produce REJECTED
  results, not exceptions, with the live book unaffected.
- Existing test suite must stay green without modification to matching
  semantics (`runSingle`, equivalence, replay tests).

## References

- Jasper: Scalable and Fair Multicast for Financial Exchanges in the
  Cloud, 2024 — https://arxiv.org/html/2402.09527v6
- Onyx (SIGCOMM 2025) — fairness in cloud exchanges
- LMAX Disruptor — https://lmax-exchange.github.io/disruptor/
- docs/trade-ui-plan.md — the consumer this ADR unblocks