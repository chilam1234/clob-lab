# ADR-0011: Resiliency floor — watchdog, kill switch, durable journal + boot replay, health surface, ingress bounds

Date: 2026-10-10
Status: Implemented
Related: ADR-0001 (shards, replay contract), ADR-0010 (lock-free hot path);
research basis: deleg_0b3c0ef9 (LMAX, Aeron Cluster, Chronicle, Coinbase May-2026 postmortem,
SEC 15c3-5, NASDAQ kill-switch filings — see /tmp delegation task-0.log for full source list).

## Context

The Oct-2026 resiliency audit (static, file-verified) found the engine well-disciplined in its
core (sequenced ingress, deterministic per-symbol matching, stripe journal + replayer, non-
blocking fanout) but missing the production resiliency floor across five axes:

1. No liveness detection on shard consumers — a dead thread stalls one symbol silently.
2. No kill switch — no way to halt trading or mass-cancel on abnormal conditions.
3. Journal is memory-only — a JVM restart loses all events and books.
4. No replay-on-boot — EventReplayer exists but is wired nowhere in the startup path.
5. No health/metrics surface — :8080 cannot answer "is the engine sane"; LatencyRecorder
   exists but is only wired in benchmarks.
6. No ingress bounds — no max notional, no price ceiling, no per-connection rate limit.

## Decision

Five changes, each small, all behind a system-property toggle where behavior changes default
flow (so demos stay simple and tests stay deterministic):

### 1. Shard watchdog
`SymbolShard` consumer thread gets `setUncaughtExceptionHandler` that:
- logs the shard name + exception,
- marks a volatile `failed` flag on the shard,
- exits the JVM (`System.exit(2)`) — fail-fast beats silent partial service.
`GatewayServer` adds a cheap `ScheduledExecutor` health sweep (1 Hz): every shard `isAlive`
+ not failed; on degradation it logs `[clob-shard] DEAD symbolId=N` before exit.
Cold-path lifecycle monitors stay as-is.

### 2. Kill switch
`Gateway` gains a `volatile boolean halted`; when set, `submit()` returns
`GatewayResult` REJECTED (reason `HALTED`) for SUBMITs (CANCELs still accepted — matches
degraded recovery practice: cancel-only mode, per Coinbase May-2026 staged reopen).
Kill switch ops live on the admin HTTP surface: `POST /admin/halt` and
`POST /admin/resume` (no auth on localhost dev surface; document that real deployments
gate this). Also `POST /admin/cancelall?symbol=` mass-cancel for one symbol.

### 3. Durable journal + boot replay
`StripedEventJournal` gains optional file persistence: `new StripedEventJournal(dir)` writes
each stripe to `journal/shard-N.log` (buffered append of the binary `OrderEvent` encoding,
flush every `journal.flushIntervalEvents`=500 events and on shutdown hook; no per-event fsync —
documented trade: crash may lose ≤500 events per stripe; a production grade would use
 Chronicle Queue). On boot, `GatewayServer` (and `runGateway` runner) replays all stripe logs
via `EventReplayer.replay(...)` into fresh engines BEFORE opening the accept loop; the
replay count + per-stripe max eventId is logged and must exceed the last accepted ingress
sequence before ingress resumes (gap detection). Disabled via `-Dclob.journal=memory` for tests
(default tests stay in-memory/deterministic).

### 4. /health + /stats on :8080
The existing admin handler now answers:
- `GET /health` → `{ok, threadsAlive, halted, journalEvents, sessions}` 200 or 503.
- `GET /stats` → `{p50us,p99us,p999us tickToTrade (LatencyRecorder, wired: ingressNano at
  Gateway.submit → match-complete on shard consumer), shards[{ringDepth,depthCap,alive}],
  wsSessions, droppedSessions, msgsIn}` — counters implemented atomically, no allocation
  on hot path beyond the recorder (which is pre-existing).
Also wire LatencyRecorder into the shard consumer path (one `record(ingressNano)` call).

### 5. Ingress bounds (Gateway.validate)
Add (all pre-mutation, all REJECT reasons):
- `maxNotionalTicks`: priceTicks × quantity ≤ `clob.maxNotional` (default 100_000_000L),
- `maxPriceTicks`: 1 ≤ priceTicks ≤ `clob.maxPrice` (default 1_000_000L),
- duplicate client order-ID rejection already exists (production-grading pass) — keep,
- rate limit: token bucket per WsSession (`clob.clientMsgRate`, default 200 msg/s, burst 400);
  exceeded frames get a REJECT ack (not silent drop) and increment a per-session counter
  surfaced in /stats.

## Alternatives considered
- **fsync-every-event journal:** correctness-maximal, ~ms tax on the hot path; deferred until
  Chronicle Queue (or equivalent) is adopted; documented in ADR trade note.
- **Raft/hot standby:** Tier-2 per the research (Aeron Cluster) — needs another process; out
  of scope for a single-node engine; revisited if second instance is ever deployed.
- **Self-trade prevention / price collars against mid:** needs participant identity + real
  mark price; deferred until accounts/risk model exists.

## Consequences
- Restart loses ≤ 500 events/stripe in a hard kill (documented); clean shutdown preserves all.
- /health is authoritative for the wedge class of failure discovered 2026-10-10 (shard stall).
- Ingress bounds may reject absurd orders from the mock maker if its config drifts — the
  maker's price ranges must stay within defaults.
- Tests: watchdog (simulate throw in paint → JVM exit path asserted via a test double /
  injected exit hook), halt/resume semantics (SUBMIT rejected, CANCEL accepted), boot replay
  round-trip (write journal → new server → replay → book equality vs pre-crash), admin
  handlers (halt, resume, cancelall), rate-limit bucket, notional/price ceiling rejects.
- Existing suite (83) must stay green; coverage gate stays ≥0.60 INSTRUCTION.