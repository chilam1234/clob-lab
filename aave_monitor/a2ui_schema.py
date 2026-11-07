"""Map RiskAssessmentPayload (+ stream inputs) → A2UI declarative schemas."""

from __future__ import annotations

from typing import Any, Dict, List

from .aave_risk_processor import RiskAssessmentPayload, WhaleStreamInput

WHALE_LOOP = "0x1111111254fb6c44bac0bed2854e76f90643097d"
AAVE_V3_POOL = "0x87870B9256fdC90E544834DE2bA981273C572a52"

BASELINE_STREAM: Dict[str, Any] = {
    "whale_address": WHALE_LOOP,
    "collateral_usd": 30_000_000.0,
    "debt_usd": 24_000_000.0,
    "liquidation_threshold": 0.82,
    # Baseline HF ≈ 1.025; slip + oracle + util push CRITICAL for the demo scenario.
    "oracle_deviation": 0.035,
    "dex_slippage": 0.01,
    "pending_utilization": 0.96,
}


def _grid12() -> Dict[str, int]:
    return {"sm": 12, "md": 12, "lg": 12}


def _grid6() -> Dict[str, int]:
    return {"sm": 12, "md": 6, "lg": 4}


def _critical_rows(
    assessment: RiskAssessmentPayload, stream: WhaleStreamInput
) -> List[Dict[str, Any]]:
    return [
        {
            "cells": [
                "Health Factor",
                f"{assessment.baseline_hf:.3f}",
                f"{assessment.effective_hf:.3f}",
                assessment.system_verdict,
            ],
            "meta": {"tone": "danger"},
        },
        {
            "cells": [
                "Oracle Arbitrage Index (OALI)",
                "—",
                f"{assessment.oracle_arbitrage_risk:.2f}",
                "ELEVATED" if assessment.oracle_arbitrage_risk > 0.30 else "WATCH",
            ],
            "meta": {"tone": "danger" if assessment.oracle_arbitrage_risk > 0.30 else "warning"},
        },
        {
            "cells": [
                "Pool Utilization (incl. mempool)",
                "—",
                f"{stream.pending_utilization:.1%}",
                assessment.liquidity_lock_risk,
            ],
            "meta": {"tone": "danger"},
        },
        {
            "cells": [
                "Collateral (USD)",
                f"${stream.collateral_usd:,.2f}",
                f"${stream.collateral_usd:,.2f}",
                "TRACKED",
            ],
            "meta": {"tone": "neutral"},
        },
        {
            "cells": [
                "Debt (USD)",
                f"${stream.debt_usd:,.2f}",
                f"${stream.debt_usd:,.2f}",
                "TRACKED",
            ],
            "meta": {"tone": "neutral"},
        },
        {
            "cells": [
                "DEX Slippage Penalty",
                "0%",
                f"{stream.dex_slippage:.1%}",
                "APPLIED",
            ],
            "meta": {"tone": "warning"},
        },
    ]


def _build_critical(
    assessment: RiskAssessmentPayload, stream: WhaleStreamInput
) -> Dict[str, Any]:
    repay = str(int(stream.debt_usd))
    return {
        "a2ui_version": "1.0.0",
        "layout": {"type": "grid", "columns": 12, "gap": "16px"},
        "components": [
            {
                "id": "whale-critical-alert",
                "type": "Banner",
                "grid_span": _grid12(),
                "props": {
                    "status": "danger",
                    "title": "SYSTEMIC INSOLVENCY ALERT: Whale Liquidation Hazard",
                    "description": (
                        "Whale position is tracking an effective market Health Factor "
                        "below threshold; oracle deviation and mempool utilization "
                        "drive liquidation risk."
                    ),
                },
            },
            {
                "id": "whale-position-data-table",
                "type": "DataTable",
                "grid_span": _grid12(),
                "props": {
                    "title": "Whale Account Stream Projections",
                    "headers": [
                        "Metric Field",
                        "On-Chain View (Stale)",
                        "Effective Real-Time Stream",
                        "Risk Assessment State",
                    ],
                    "rows": _critical_rows(assessment, stream),
                },
            },
            {
                "id": "whale-mitigation-actions",
                "type": "ActionGroup",
                "grid_span": _grid12(),
                "props": {
                    "alignment": "right",
                    "actions": [
                        {
                            "label": "Simulate Private Deleverage Bundle (Demo Only)",
                            "action_id": "simulate_private_deleverage",
                            "variant": "primary",
                            "payload": {
                                "target_user": assessment.whale_address,
                                "pool_address": AAVE_V3_POOL,
                                "repay_amount_base": repay,
                            },
                        }
                    ],
                },
            },
        ],
    }


def _build_high(
    assessment: RiskAssessmentPayload, stream: WhaleStreamInput
) -> Dict[str, Any]:
    return {
        "a2ui_version": "1.0.0",
        "layout": {"type": "grid", "columns": 12, "gap": "16px"},
        "components": [
            {
                "id": "whale-high-alert",
                "type": "Banner",
                "grid_span": _grid12(),
                "props": {
                    "status": "warning",
                    "title": "Elevated Risk: Whale Position Near Liquidation Boundary",
                    "description": (
                        f"Effective HF {assessment.effective_hf:.3f} is inside the HIGH band "
                        f"(≤ 1.12). Utilization {stream.pending_utilization:.1%}; "
                        f"LLR={assessment.liquidity_lock_risk}."
                    ),
                },
            },
            {
                "id": "metric-ehf",
                "type": "MetricCard",
                "grid_span": _grid6(),
                "props": {
                    "label": "Effective Health Factor",
                    "value": f"{assessment.effective_hf:.3f}",
                    "helper_text": f"Baseline HF {assessment.baseline_hf:.3f}",
                    "change_value": f"{assessment.effective_hf - assessment.baseline_hf:+.3f}",
                },
            },
            {
                "id": "metric-oali",
                "type": "MetricCard",
                "grid_span": _grid6(),
                "props": {
                    "label": "Oracle Arbitrage Index",
                    "value": f"{assessment.oracle_arbitrage_risk:.2f}",
                    "helper_text": f"Deviation {stream.oracle_deviation:.2%}",
                },
            },
            {
                "id": "metric-util",
                "type": "MetricCard",
                "grid_span": _grid6(),
                "props": {
                    "label": "Pool Utilization",
                    "value": f"{stream.pending_utilization:.1%}",
                    "helper_text": assessment.liquidity_lock_risk,
                },
            },
            {
                "id": "whale-high-actions",
                "type": "ActionGroup",
                "grid_span": _grid12(),
                "props": {
                    "alignment": "right",
                    "actions": [
                        {
                            "label": "Simulate Collateral Top-Up (Demo Only)",
                            "action_id": "simulate_collateral_topup",
                            "variant": "primary",
                            "payload": {
                                "target_user": assessment.whale_address,
                                "note": "simulation_only",
                            },
                        }
                    ],
                },
            },
        ],
    }


def _build_stable(
    assessment: RiskAssessmentPayload, stream: WhaleStreamInput
) -> Dict[str, Any]:
    return {
        "a2ui_version": "1.0.0",
        "layout": {"type": "grid", "columns": 12, "gap": "16px"},
        "components": [
            {
                "id": "whale-stable-banner",
                "type": "Banner",
                "grid_span": _grid12(),
                "props": {
                    "status": "success",
                    "title": "Stable: Whale Position Within Safe Envelope",
                    "description": (
                        f"Effective HF {assessment.effective_hf:.3f} clear of HIGH/CRITICAL "
                        f"bands. OALI {assessment.oracle_arbitrage_risk:.2f}; "
                        f"utilization {stream.pending_utilization:.1%}."
                    ),
                },
            },
            {
                "id": "metric-ehf",
                "type": "MetricCard",
                "grid_span": _grid6(),
                "props": {
                    "label": "Effective Health Factor",
                    "value": f"{assessment.effective_hf:.3f}",
                    "helper_text": f"Baseline HF {assessment.baseline_hf:.3f}",
                },
            },
            {
                "id": "metric-oali",
                "type": "MetricCard",
                "grid_span": _grid6(),
                "props": {
                    "label": "Oracle Arbitrage Index",
                    "value": f"{assessment.oracle_arbitrage_risk:.2f}",
                    "helper_text": "Within tolerance",
                },
            },
            {
                "id": "metric-util",
                "type": "MetricCard",
                "grid_span": _grid6(),
                "props": {
                    "label": "Pool Utilization",
                    "value": f"{stream.pending_utilization:.1%}",
                    "helper_text": assessment.liquidity_lock_risk,
                },
            },
        ],
    }


def build_a2ui_schema(
    assessment: RiskAssessmentPayload,
    stream_data: Dict[str, Any] | None = None,
) -> Dict[str, Any]:
    """Build an A2UI schema for CRITICAL / HIGH / STABLE verdicts."""
    raw = stream_data if stream_data is not None else BASELINE_STREAM
    # Keep address consistent with the assessment payload.
    merged = {**raw, "whale_address": assessment.whale_address}
    stream = WhaleStreamInput(**merged)

    if assessment.system_verdict == "CRITICAL":
        return _build_critical(assessment, stream)
    if assessment.system_verdict == "HIGH":
        return _build_high(assessment, stream)
    return _build_stable(assessment, stream)
