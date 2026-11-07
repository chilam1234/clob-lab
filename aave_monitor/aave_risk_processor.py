"""Deterministic Aave whale position risk matrix (V3/V4 monitor prototype)."""

from __future__ import annotations

from typing import Any, Dict, Literal

from pydantic import BaseModel, Field, field_validator


class WhaleStreamInput(BaseModel):
    whale_address: str = Field(..., pattern=r"^0x[a-fA-F0-9]{40}$")
    collateral_usd: float = Field(..., gt=0.0)
    debt_usd: float = Field(..., gt=0.0)
    liquidation_threshold: float = Field(..., ge=0.0, le=1.0)
    oracle_deviation: float = Field(..., ge=0.0, le=1.0)
    dex_slippage: float = Field(..., ge=0.0, le=1.0)
    pending_utilization: float = Field(..., ge=0.0, le=1.5)

    @field_validator("pending_utilization")
    @classmethod
    def validate_utilization_limits(cls, value: float) -> float:
        if value > 1.2:
            raise ValueError(
                f"Extremely distorted pool utilization data frame input: {value}"
            )
        return value


class RiskAssessmentPayload(BaseModel):
    whale_address: str
    baseline_hf: float
    effective_hf: float
    oracle_arbitrage_risk: float
    liquidity_lock_risk: Literal["NORMAL", "HIGH_UTILIZATION", "CRITICAL_FREEZE"]
    system_verdict: Literal["STABLE", "HIGH", "CRITICAL"]


def compute_aave_position_risk_matrix(stream_data: Dict[str, Any]) -> RiskAssessmentPayload:
    stream = WhaleStreamInput(**stream_data)

    baseline_hf = (stream.collateral_usd * stream.liquidation_threshold) / stream.debt_usd
    oali = stream.oracle_deviation * 10.0 if stream.oracle_deviation > 0.02 else 0.0
    effective_hf = baseline_hf * (1.0 - stream.dex_slippage)

    if stream.pending_utilization >= 0.95:
        llr_state = "CRITICAL_FREEZE"
    elif stream.pending_utilization > 0.90:
        llr_state = "HIGH_UTILIZATION"
    else:
        llr_state = "NORMAL"

    if effective_hf <= 1.02 or oali > 0.30 or stream.pending_utilization >= 0.95:
        verdict = "CRITICAL"
    elif effective_hf <= 1.12 or stream.pending_utilization > 0.90:
        verdict = "HIGH"
    else:
        verdict = "STABLE"

    return RiskAssessmentPayload(
        whale_address=stream.whale_address,
        baseline_hf=round(baseline_hf, 3),
        effective_hf=round(effective_hf, 3),
        oracle_arbitrage_risk=round(oali, 2),
        liquidity_lock_risk=llr_state,
        system_verdict=verdict,
    )
