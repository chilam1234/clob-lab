# ADR-0007: Multi-symbol mock market + engine-emitted klines and bookTicker

- Status: Accepted
- Date: 2026-10-05
- Deciders: Billy Chan (repo owner)
- Supersedes: the kline deferral in ADR-0006

## Context

After wiring trading-ui to the gateway (ADR-0006), Billy's e2e shows:
1. **One pair only** — the mock market maker trades a single symbol, so
   tabs for other pairs are dead.
2. **No candles** — trading-ui's chart consumes Binance `kline_1m`
   streams; the gateway emits aggTrade + depth only (explicit ADR-0006
   deferral, now reversed by usage).
3. **Low volatility** — ±3 ticks per 100ms walk reads flat on a 1m
   chart.

Binance frame contracts already parsed by trading-ui (verified in its
parse.ts): `kline` needs `{e,k:{i,t,x,o,h,l,c,c,v,...}}` with open /
high / low / close / volume as **strings** and `x` (isClosed) boolean;
`bookTicker` needs `{u,s,b,B,a,A}` strings + numeric update id. The
kline merge replaces by `openTime`, so re-emitting the live candle with
updated OHLC per print is sufficient — no historical backfill needed
(candles accumulate live).

## Decision

1. **Multi-symbol shards as real pairs.** `GatewayServer` boots N
   symbols (default 6: clob/usdt + five synthetics matching a watchlist
   `eth/sol/bnb/aave/blur`-style naming so tabs read naturally: each
   `<BASE>usdt` with distinct base prices). `CloudExchange` shards are
   per-symbol already; the mock maker instantiates one per symbol with
   staggered phase and per-symbol volatility.
2. **Kline emission from the engine.** Each shard aggregates its own
   trade prints into 1s buckets client-side of the WS — no: server-side
   the gateway keeps per-symbol 1m OHLCV (open = first print, updated
   hi/lo/last per print) and emits an **unclosed** `kline` frame on
   every print (throttled to ≥100ms) plus one final `x:true` frame at
   the minute boundary. `i:"1m"`, `t` = bucket open epoch ms. This
   makes trading-ui's chart live without historical candles.
3. **bookTicker per depth frame** (best bid/ask from the live book,
   same 500ms cadence) to power header/BBO UI consumers.
4. **Volatility.** Mock walk widened to ±(1..8) ticks per tick step with
   occasional ±25-tick jumps (5% probability) and doubled print size;
   100ms cadence stays. Per-symbol volatility multipliers spread across
   pairs (calm clob vs lively blur).

## Consequences

- All six tabs stream simultaneously; chart populates within seconds
  per pair (live accumulation, no depth of history).
- Gateway now holds mutable per-symbol 1m candle state; memory bounded
  (one live candle per symbol).
- Binance-dialect emitter grows two frame kinds; legacy protocol frames
  unchanged.
- Order entry (clob-lab ui/) still works; mock ids ≥1M never collide.

## Validation

- Probe: 6 symbols × aggTrade+depth+kline+bookTicker all parseable by
  trading-ui's parse functions; chart shows live candles for CLOB/USDT.
- Existing suite stays green.