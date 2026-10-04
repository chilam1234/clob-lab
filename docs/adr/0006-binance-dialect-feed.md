# ADR-0006: clob-lab gateway speaks the Binance dialect to feed trading-ui

- Status: Accepted
- Date: 2026-10-04
- Deciders: Billy Chan (repo owner)

## Context

clob-lab's own React UI (ui/, ADR-0003/0004) is functional but minimal.
A separate, more mature frontend exists:
`/Users/billchan/rust-project/trading-ui` — React 19 + TS, worker-based
parse pipeline, lightweight-charts candles, virtualized tape, perf HUD,
reconnect/backoff, plus an optional `market-fanout` coalescing service.
It currently subscribes to Binance combined streams.

Two integration options:

| Option | Work | Result |
|---|---|---|
| A. Gateway speaks Binance dialect server-side | Java-only change | trading-ui consumes it nearly unmodified (`VITE_MARKET_SOURCE=direct-binance`, host=ws://localhost:8081) |
| B. Adapter service translating frames | new TS service | cleaner protocol boundary later; more moving parts now |

## Decision

**Option A.** `com.cloblab.gateway.transport.GatewayServer` gains a
Binance-dialect event path alongside the existing hello/l2/trade/ack
protocol (which stays for the clob-lab ui/). New frame shapes emitted
per ADR-0002's per-session seq (trading-ui tolerates unknown frames by
ignoring them, so both protocols can coexist on one socket):

1. Aggregated trades as Binance `aggTrade` in a combined-stream envelope:
   `{"stream":"clobusdt@aggTrade","data":{"e":"aggTrade","a":<seq>,"s":"CLOBUSDT","p":"<px>","q":"<qty>","m":<makerIsBuy?false:true>,"E":<epochMs>}}`
   — px/qty as strings, `m` (buyer-is-maker) = taker is SELL.
2. Depth as `depth10` partial snapshots every ~500ms:
   `{"stream":"clobusdt@depth10@100ms","data":{"lastUpdateId":<seq>,"bids":[["px","qty"],...],"asks":[...]}}`
   — top 5 levels (matches trading-ui's depth10 usage).
3. Stream ids use synthetic symbol `clobusdt` so existing symbol
   pickers/filters work unmodified.

The `aggTrade` `a` field carries the per-session seq — dedupe stays
correct. Event time `E` = wall clock at emit.

The mock market maker (ADR-0005) supplies the trading flow; UI order
entry arrives in a later milestone (trading-ui has no order entry — out
of scope here; clob-lab ui/ remains the order-entry console).

## Consequences

- trading-ui runs against the local engine with one env var:
  `VITE_BINANCE_HOST=ws://localhost:8081` (a `wsHost` option already
  exists in `useBinanceMarket`) and zero source changes for the feed
  path.
- Binance kline-based candles are NOT emitted: trading-ui's chart
  merges `kline_1m` streams; until clob emit klines, the chart stays
  empty while ladder/tape run. Emitting coarse 1s klines
  (`{"e":"kline",...}` shaped as Binance kline payloads) is the
  follow-up if charted candles from the engine are wanted — deferred
  to keep this change minimal (client-side kline merge semantics are
  stricter).
- Protocol drift risk: Binance-shape frames only emulate the two streams
  the UI consumes now; if trading-ui grows fields, the gateway gains
  them deliberately (typed emitter in one place).

## Validation

- `curl ws + probe` script: envelopes parse with the exact
  `parseAggTrade` / `parsePartialDepth` helpers (run through the
  trading-ui vitest parser tests locally).
- trading-ui dev server pointed at the gateway: tape + ladder update,
  connection state green in the perf HUD.