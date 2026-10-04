# ADR-0002: Trade UI transport — zero-dependency WebSocket server

- Status: Accepted
- Date: 2026-10-04
- Deciders: Billy Chan (repo owner), Hermes agent (proposal)
- Implements: M1 of docs/trade-ui-plan.md

## Context

ADR-0001 landed the `Gateway` API (submit → GatewayResult, per-frame
trade/L2 subscriptions over in-JVM callbacks). The trade UI needs a
wire transport between the JVM and a browser. Options considered:

| Option | Pros | Cons |
|--------|------|------|
| A. JDK built-in `com.sun.net.httpserver` + hand-rolled WS framing | zero new deps, no version drift, CI stays lean | manual RFC 6455 frame handling (~150 lines: handshake, opcode, mask, close), no permessage-deflate |
| B. Jetty WebSocket server | battle-tested, standards-complete | new dependency family (jetty-server, jetty-ws), config surface, larger benchmark surface |
| C. Undertow / Netty | high performance | overkill for a lab; heavyweight dep |
| D. SSE (Server-Sent Events) only | trivial on JDK httpserver | one-way: order entry still needs POST, no true bidirectional, no binary frames |

Requirements: browser submits orders and receives trades/L2 with low
overhead; single-gradle-project ethos (M1/M2 = no npm); headless use
must remain possible (transport optional, behind `runGateway`).

## Decision

**Option A — JDK built-in HTTP server plus a small hand-rolled
RFC 6455 WebSocket implementation**, confined to
`com.cloblab.gateway.transport`.

Rationale:
- Keeps the zero-dependency property that the repo's CI and the
  benchmark story rely on (no dep drift between bench runs).
- The gateway's traffic profile is tiny (≤ a few hundred frames/sec to
  one browser); protocol completeness (compression, extensions) adds
  nothing here. A ~150-line well-tested WS layer with seq-gap
  detection (already in the UI plan) is small, auditable, and
  replaceable — Jetty remains the documented upgrade path if M3+
  demands it.
- SSE alone fails the bidirectional requirement (order entry is part
  of the same session).

## Specification

- `GatewayServer` wraps a `CloudExchange`-backed `Gateway`:
  - `GET /` → static `index.html` from `docs/ui/` (classpath fallback)
  - `GET /ws?symbol=0` → WebSocket upgrade; server→client frames:
    `{t:"hello",sym,seq}`, `{t:"l2",bids,asks,seq}`,
    `{t:"trade",maker,taker,px,qty,seq}`, `{t:"p99",ns,seq}` later.
    Client→server: `{op:"limit"|"market"|"ioc"|"fok"|"cancel",...}`.
  - `POST /orders` alternate JSON entry (parity with WS ops) for
    curl-based testing.
- Frame sequencing: every server→client event carries a monotonic
  `seq`; a WS (re)connect starts a new stream with a full L2 snapshot
  (`hello` includes best bid/ask + top 5 levels) — gap detection is a
  client-side responsibility (M4 formalizes reconnect).
- Back-pressure policy: WS send failing or slow → drop the connection
  and let the client reconnect (fair-release frames are recomputable
  from snapshots; no unbounded per-client queue).
- Shutdown: `GatewayServer.stop()` closes sockets, then gateway
  shutdown (drains shards per ADR-0001).

## Consequences

- Upgrades needed on the ADR-0001 surface: none — the Gateway API is
  already push-shaped; the WS layer adapts it, it does not fork it.
- The browser UI (M2) lives in `docs/ui/index.html` + one JS file, no
  build step; served from the JVM in `runGateway` mode.
- If permessage-deflate or subprotocol routing is ever required, migrate
  to Jetty under this same `transport` package (swap, not rewrite).

## Validation

- Integration test: WS client (handshake over loopback) submits a
  limit order, receives `l2` + `trade` frames with increasing `seq`.
- POST /orders parity test: same reject mapping as Gateway.submit.
- Existing suite stays green; Gateway/transport tested without sockets
  where logic allows (frame coding table-tested).