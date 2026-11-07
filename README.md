# clob-lab

A small Java learning project for **Central Limit Order Book (CLOB)** matching and **low-latency trading concepts**, plus an **Aave V3/V4 whale risk monitor** prototype (Python risk engine → A2UI React factory).

## What this implements

- Price-time priority matching (FIFO within a price level)
- **Limit**, **Market**, **IOC**, **FOK** order types
- **FastOrderBook** — array-backed ticks + order pool (default matcher)
- **L2 depth snapshots** and **fair market-data publisher**
- **Event journal + replay**
- **Cloud-exchange pipeline** (2024+ research): sequencer, FancyPQ ingress, ring buffers, sharded matchers
- JMH + load testing
- **Aave whale risk monitor** — HF / OALI / eHF / LLR verdict matrix + A2UI declarative UI

## Project layout

```
src/main/java/com/cloblab/
  book/           FastOrderBook (default), OrderBook (tree, learning)
  engine/         MatchingEngine
  exchange/       CloudExchange, SymbolShard, ExchangeRouter
  pipeline/       Sequencer, PriorityIngress, RingBuffer, PipelineRunner
  protocol/       InboundCommand
  journal/        EventJournal, EventReplayer
  marketdata/     L2Snapshot, FairMarketDataPublisher
  loadtest/       LoadTestRunner
aave_monitor/     Python risk engine + A2UI schema builder + tests
ui/src/aave/      A2UIRenderer + whale risk demo page
docs/guide.html   Visual guide + 2024+ reading list
```

## Aave whale risk monitor

### Backend tests (Python)

```bash
python3 -m pip install -r aave_monitor/requirements.txt
PYTHONPATH=. python3 -m pytest aave_monitor/tests -v
```

### Live data demo (on-chain + charts)

```bash
# terminal 1 — live risk feed (Ethereum RPC + Aave Oracle/GraphQL + CoinGecko)
python3 -m pip install -r aave_monitor/requirements.txt
PYTHONPATH=. python3 -m aave_monitor.live_server --port 8787

# terminal 2 — UI
cd ui
npm install
npm run dev:aave
# open http://localhost:5173/aave-risk.html  (default tab: LIVE on-chain)
```

The LIVE tab streams real Aave V3 positions, oracle/off-chain wstETH deviation, pool utilization, and plots HF / util / OALI over time. Fixture CRITICAL / HIGH / STABLE buttons remain for offline schema demos. **ActionGroup buttons only log/toast** — they do not submit Flashbots bundles or execute liquidations.

## Run

```bash
./bootstrap.sh
source ./env.sh         # if needed

./gradlew test
./gradlew runDemo       # order types demo
./gradlew runPipeline   # cloud pipeline (Jasper/Onyx patterns)
./gradlew runLoadTest
./gradlew runLoadTest --args="--mode=multi --threads=8 --duration=10"
./gradlew jmh   # MatchEngineBenchmark + BookKindBenchmark (FAST vs TREE)

open docs/guide.html
```

## Benchmarking

| Command | What it tells you |
|---------|-------------------|
| `./gradlew run` | Quick latency histogram (~125 ns p50) |
| `./gradlew jmh` | Isolated hot-path ns/op; `BookKindBenchmark` compares FAST vs TREE |
| `runLoadTest --mode=single` | One shard, mixed workload |
| `runLoadTest --mode=multi` | Sharded symbols — production pattern (~7M ops/sec aggregate) |
| `runLoadTest --mode=contended` | Shared-book anti-pattern — expect ms tail latency |

## Pipeline (2024+ research applied)

| Pattern | Source | Implementation |
|---------|--------|----------------|
| Global ingress sequencing | [Jasper (2024)](https://arxiv.org/html/2402.09527v6) | `Sequencer` |
| Burst priority near mid | Jasper FancyPQ | `PriorityIngress` |
| SPSC ring buffers | LMAX / Chronicle | `RingBuffer` → `SymbolShard` |
| Sharded matchers | Industry standard | `ExchangeRouter` |
| Fair MD fanout | [Onyx (SIGCOMM 2025)](https://anirudhsk.github.io/papers/cloud_exchange_sigcomm2025.pdf) | `FairMarketDataPublisher` |

## Matcher implementations

| `BookKind` | Use |
|------------|-----|
| **FAST** (default) | Array ticks + order pool |
| **TREE** | TreeMap learning book |

```java
MatchingEngine.create(BookKind.TREE);
```

## Visual guide

**`docs/guide.html`** — CLOB, order types, fast matcher, pipeline, 2024+ research links.
