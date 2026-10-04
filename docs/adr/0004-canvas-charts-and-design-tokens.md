# ADR-0004: Canvas charts for candles/signals + mono-color DESIGN.md tokens

- Status: Accepted
- Date: 2026-10-04
- Deciders: Billy Chan (repo owner)
- Amends: ADR-0002/0003 remain in force; this adds the charting layer
  and the design-token source.

## Context

The trade UI needs price candles (up/down), and later signal overlay
drawing. Two rendering approaches:

| Option | Pros | Cons |
|--------|------|------|
| A. SVG/DOM per candle | easy hit-testing, CSS styling | one node per candle × re-render per frame; hundreds of DOM nodes updated at trade rate → GC churn, jank |
| B. `<canvas>` 2D, redrawn per animation frame | constant node count; single draw pass; trivially handles candles + depth bars + overlays; scales to hundreds of candles | manual hit-testing; no free CSS |

The UI receives a live trade/L2 push stream — candles aggregate from
trades client-side at high frequency. Performance is the decision
driver. For look-and-feel, Billy specified shadcn/ui components plus
the mono-color editorial print system
(github.com/yanliudesign/mono-color-skill) as the design source, with
Google's DESIGN.md as the token format.

## Decision

1. **Option B — `<canvas>` 2D for all chart layers** (candles, depth
   histogram, later signals). Components around it (panels, forms,
   tables) remain shadcn/ui React components. Candles aggregate from
   the WS trade stream into 1-second OHLC buckets client-side; the
   last bucket updates live, closed buckets are immutable. Redraw on
   `requestAnimationFrame`, coalescing bursts; devicePixelRatio-aware
   to stay crisp on retina.
2. **Design tokens from mono-color → DESIGN.md** (Google spec, linted
   with `npx @google/design.md lint`). Palette: controlled two-ink,
   dominant **Cobalt `#2148B8`** (chart structure, bid/up, chrome),
   accent **Terracotta `#C65F38`** (ask/down, alerts, accents) — the
   skill's default complementary duotone. Substrate Cool Gray
   `#E9E9E5` for the trading chrome (ADR-0002's dark theme is
   replaced: editorial print look, not terminal look). Typography:
   system ui-monospace for numerals (tabular), serif display for the
   masthead per the skill's type system.
3. **Candle semantics**: OHLC derived from trade prints (px in ticks);
   no trades in a bucket → carry-forward close, no candle drawn (gap
   visible). Up candle = Cobalt, down = Terracotta, wick = substrate
   ink outline.
4. **Bucket size**: 1s default, user-switchable 250ms / 1s / 5s in the
   UI (one aggregation variable). Aggregation is O(1) update of the
   last bucket per trade; closed buckets are immutable. Ring buffer of
   300 buckets — bounded memory, oldest bucket evicted.
5. **Price axis hysteresis**: the visible price window rescales only
   when a price escapes the current range padded by 2% — not on every
   trade — preventing y-axis "pumping" during bursts. Axis ticks round
   to nice multiples (5/10 ticks).
6. **Two-layer canvas**: a price/candle canvas redrawn per
   `requestAnimationFrame` (trade bursts coalesced), plus a separate
   transparent crosshair canvas redrawn only on pointer move —
   interaction redraws never re-draw candles.

## Consequences

- Chart perf scales with candles drawn, not trades received; DOM stays
  flat. Hit-testing (crosshair) added in M3.
- DESIGN.md is the single source for colors/typography/spacing in the
  React app + canvas draws; exports to CSS variables for shadcn theming.
- Trade-off accepted: no free SSR for the chart (canvas only); SEO
  irrelevant for a trading console.
- Deferred: volume histogram (M3 option), depth-imbalance overlay
  (post-M3, needs a second data path), chart libraries (only if
  indicators/log-axis/zoom polish are demanded later).

## Validation

- Load test driving `runLoadTest`-style traffic: browser stays at 60fps
  with 300+ candles on screen (manual check; canvas is flat-cost).
- `npx @google/design.md lint DESIGN.md` passes with no errors.