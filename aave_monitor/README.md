# Aave Whale Risk Monitor (prototype)

Python risk engine + A2UI schema builder for the Aave V3/V4 whale position & systemic risk monitor.

## Setup

```bash
# from repo root
python3 -m pip install -r aave_monitor/requirements.txt
```

## Run unit tests

```bash
PYTHONPATH=. python3 -m pytest aave_monitor/tests -v
```

## Live data server

Pulls real Ethereum Aave V3 account data (public RPC), Aave Oracle vs CoinGecko prices, and market utilization (Aave GraphQL), then streams risk frames over SSE:

```bash
PYTHONPATH=. python3 -m aave_monitor.live_server --port 8787
# GET /api/aave/snapshot?whale=0x...
# GET /api/aave/stream?whale=0x...   (SSE)
# GET /api/aave/whales
```

Default monitored whale is a live large borrower (not the handoff 1inch router placeholder, which has no Aave debt).

## Modules

| File | Role |
|------|------|
| `aave_risk_processor.py` | Deterministic HF / OALI / eHF / LLR / verdict matrix |
| `a2ui_schema.py` | Maps `RiskAssessmentPayload` → CRITICAL / HIGH / STABLE A2UI schemas |
| `live_feed.py` | On-chain + oracle + market telemetry → stream input |
| `live_server.py` | HTTP snapshot + SSE poller |
| `tests/` | CRITICAL / HIGH / STABLE paths + utilization validator rejection |

## Safety

`ActionGroup` actions are **demo/simulation only**. Do not wire them to Flashbots, private bundle submission, or live liquidation execution.
