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

## Modules

| File | Role |
|------|------|
| `aave_risk_processor.py` | Deterministic HF / OALI / eHF / LLR / verdict matrix |
| `a2ui_schema.py` | Maps `RiskAssessmentPayload` → CRITICAL / HIGH / STABLE A2UI schemas |
| `tests/` | CRITICAL / HIGH / STABLE paths + utilization validator rejection |

## Safety

`ActionGroup` actions are **demo/simulation only**. Do not wire them to Flashbots, private bundle submission, or live liquidation execution.
