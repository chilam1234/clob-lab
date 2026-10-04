# clob-lab

A small Java learning project for **Central Limit Order Book (CLOB)** matching and **low-latency trading concepts**.

## What this implements

- Price-time priority matching (FIFO within a price level)
- **Limit**, **Market**, **IOC**, **FOK** order types
- **FastOrderBook** — array-backed ticks + order pool (default matcher)
- **L2 depth snapshots** and **fair market-data publisher**
- **Event journal + replay**
- **Cloud-exchange pipeline** (2024+ research): sequencer, FancyPQ ingress, ring buffers, sharded matchers
- JMH + load testing

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
docs/guide.html   Visual guide + 2024+ reading list
```

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
