# ADR-0005: Mock market maker as a built-in data source

- Status: Accepted
- Date: 2026-10-04
- Deciders: Billy Chan (repo owner)

## Context

The trade UI has no data when nobody trades: the engine only moves on
submitted orders. For demos and e2e development the UI needs a steady,
realistic feed without a manual submit loop.

## Decision

Add `com.cloblab.gateway.MockMarketMaker`: a daemon thread that drives
the real pipeline (Gateway → CloudExchange → shards → MD publisher) —
not a fake UI data source, so the chart, ladder, tape, and reject paths
all get exercised exactly as with human orders.

Behavior: random-walk mid (±3 ticks/step), two-sided resting quotes at
mid+2..20 (qty 1–10), a ~30%-per-tick chance of a market order (qty
5–30) that crosses and prints trades, 10% cancel of an old resting id.
Order IDs from 1,000,000+ (never colliding with UI-assigned ids).
Tick interval 100ms → ~50 ops/sec, ~3 prints/sec at default; rate
tunable via CLI (`--mock-rate`).

`runUi` starts the mock by default (demo-first); pass `--no-mock` for a
quiet book.

## Consequences

- Trades arrive continuously — candles fill even untouched.
- The mock shares the SPSC producer role with UI submissions: both go
  through `Gateway.submit`, which is the CloudExchange router thread —
  single producer per shard ring is preserved (all submits serialize
  through the router).
- Mock orders appear in the journal/replay like any others (label ids
  ≥ 1,000,000 as synthetic in later analytics).

## Validation

- WS probe: frames flow continuously with no client submits; candles
  populate within seconds.