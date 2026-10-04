# clob-lab Trade UI Plan

Status: Draft — sequencing doc; each milestone ships something runnable.
Prereq: ADR-0001 (threaded shards + gateway event stream) landed first.

## Why

clob-lab has a real matching engine but no way to *watch* it trade. A
trade UI turns the project from "engine + tests" into a demonstrable
trading system — and it is the strongest portfolio signal for
low-latency trading-systems roles: the same event-driven UI patterns
used at prop shops / exchanges (WebSocket L2 streams, delta application,
order-entry, pnl) rendered against a本地 engine.

## Architecture

```
JVM (clob-lab)
  CloudExchange (threaded shards, ADR-0001)
    └─ Gateway
        ├─ submit(cmd) -> GatewayResult          (JSON over WS)
        └─ subscribe: trades, L2 deltas, bbo      (push, per frame)
                 │
     HTTP/WS transport (Java 21 built-in HttpServer +
     com.sun.net.httpserver WebSocket, no framework deps)
                 │
Browser (single-page, no build step initially)
  ├─ BBO + depth ladder (L2, per symbol)
  ├─ Order entry: limit/market/IOC/FOK + cancel
  ├─ Fills / trades tape
  └─ Latency panel (ingress→release ns, from gateway stamping)
```

Key principle: the browser is a *thin consumer of the gateway event
stream*, never a poller of book state. If the UI ever needs to poll,
that's a gateway bug, not a UI feature.

## Milestones

### M1 — Gateway transport (server)
- `GatewayServer`: wraps CloudExchange, serves JSON events on
  WebSocket (Java's built-in `http.Server` + a minimal WS impl, or
  Jetty as a single dep — ADR-worthy fork decision: zero-dep vs Jetty).
- Wire format: `{t:"trade"|"l2"|"bbo", sym, seq, ...}` — seq = gateway
  frame, monotonic; client detects gaps and resyncs via snapshot
  request. (Same principle as a real MD feed.)
- Accept: POST /orders {symbol, side, type, qty, px} →
  {accepted|rejected reason}.
- Exit criteria: `wscat`-style client can submit an order and receive
  trade + l2 events for it.

### M2 — Minimal UI (frontend, no build step)
- Static `index.html` + vanilla JS (or Preact via single CDN script):
  connect WS, render BBO + 5-level ladder per symbol, submit order
  form, running trades tape.
- State: single `Map<sym, Book>` updated by l2 events; deltas replace
  levels wholesale (L2 snapshot-per-level from the engine — no client
  aggregation).
- Exit criteria: two browser windows act as two participants; one
  submits, the other sees the trade + ladder move.

### M3 — Order flow & latency observability
- Order entry panel with all four order types + cancels; fills badge
  client-side; positions/PnL per client id.
- Latency panel: ingress→execution→release breakdown (gateway already
  stamps `ingressNano`; add release lag). Render p50/p99 over rolling
  window, reusing the shape of LatencyRecorder's output.
- Exit criteria: the contended load test mode visibly raises the p99
  panel in the UI while running.

### M4 — Replay + hardening
- "Replay" toggle: stream historical journal events into the UI (uses
  EventReplayer); UI is identical, feed source differs — proves the
  journal/replay correctness work end to end.
- Reconnect + gap-fill: on WS drop, client re-requests snapshot and
  replays deltas; malformed frame handling; seq gap detection.
- Exit criteria: kill and restart the server mid-session; UI resyncs
  without manual reload.

## Non-goals (now)
- Real user auth / multi-tenant; persistence beyond the journal;
  charting libs; mobile layout; orderbook viz beyond 5 levels.

## Stack decisions (explicit)
- No npm/toolchain for M1-M2: single-file static frontend keeps the
  repo "single gradle project" and avoids CI complexity. Revisit if M3
  charting demands it (that fork gets its own ADR).
- Server transport lives in `com.cloblab.gateway` — same package
  boundary as ADR-0001, transport optional so headless CLI use stays
  possible.