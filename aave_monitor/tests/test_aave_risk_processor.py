"""Unit tests for Aave risk matrix + A2UI schema builder."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from aave_monitor.a2ui_schema import BASELINE_STREAM, build_a2ui_schema
from aave_monitor.aave_risk_processor import compute_aave_position_risk_matrix

WHALE = "0x1111111254fb6c44bac0bed2854e76f90643097d"


def _base(**overrides):
    data = {
        "whale_address": WHALE,
        "collateral_usd": 30_000_000.0,
        "debt_usd": 24_000_000.0,
        "liquidation_threshold": 0.82,
        "oracle_deviation": 0.0,
        "dex_slippage": 0.0,
        "pending_utilization": 0.5,
    }
    data.update(overrides)
    return data


def test_baseline_hf_matches_scenario_profile():
    # 30M * 0.82 / 24M = 1.025
    result = compute_aave_position_risk_matrix(_base())
    assert result.baseline_hf == 1.025


def test_critical_path_ehf_oali_and_util():
    stream = _base(
        oracle_deviation=0.035,  # OALI = 0.35 > 0.30
        dex_slippage=0.01,  # eHF = 1.025 * 0.99 ≈ 1.015 ≤ 1.02
        pending_utilization=0.96,
    )
    result = compute_aave_position_risk_matrix(stream)
    assert result.system_verdict == "CRITICAL"
    assert result.effective_hf == 1.015
    assert result.oracle_arbitrage_risk == 0.35
    assert result.liquidity_lock_risk == "CRITICAL_FREEZE"

    schema = build_a2ui_schema(result, stream)
    types = [c["type"] for c in schema["components"]]
    assert types == ["Banner", "DataTable", "ActionGroup"]
    assert schema["components"][0]["props"]["status"] == "danger"
    action = schema["components"][2]["props"]["actions"][0]
    assert action["action_id"] == "simulate_private_deleverage"
    assert "Demo Only" in action["label"]


def test_high_path_ehf_band():
    # Baseline HF 1.025 sits in HIGH when no CRITICAL triggers fire.
    stream = _base(oracle_deviation=0.01, dex_slippage=0.0, pending_utilization=0.85)
    result = compute_aave_position_risk_matrix(stream)
    assert result.system_verdict == "HIGH"
    assert result.liquidity_lock_risk == "NORMAL"
    assert 1.02 < result.effective_hf <= 1.12

    schema = build_a2ui_schema(result, stream)
    assert schema["components"][0]["props"]["status"] == "warning"
    assert any(c["type"] == "MetricCard" for c in schema["components"])


def test_high_path_utilization_band():
    stream = _base(
        collateral_usd=40_000_000.0,  # HF well above 1.12
        oracle_deviation=0.0,
        dex_slippage=0.0,
        pending_utilization=0.92,
    )
    result = compute_aave_position_risk_matrix(stream)
    assert result.system_verdict == "HIGH"
    assert result.liquidity_lock_risk == "HIGH_UTILIZATION"


def test_stable_path():
    stream = _base(
        collateral_usd=40_000_000.0,
        oracle_deviation=0.01,
        dex_slippage=0.0,
        pending_utilization=0.80,
    )
    result = compute_aave_position_risk_matrix(stream)
    assert result.system_verdict == "STABLE"
    assert result.oracle_arbitrage_risk == 0.0
    assert result.effective_hf > 1.12

    schema = build_a2ui_schema(result, stream)
    assert schema["components"][0]["props"]["status"] == "success"


def test_oali_zero_when_deviation_at_or_below_threshold():
    result = compute_aave_position_risk_matrix(_base(oracle_deviation=0.02))
    assert result.oracle_arbitrage_risk == 0.0


def test_validator_rejects_utilization_above_1_2():
    with pytest.raises(ValidationError) as exc:
        compute_aave_position_risk_matrix(_base(pending_utilization=1.25))
    assert "Extremely distorted pool utilization" in str(exc.value)


def test_invalid_whale_address_rejected():
    with pytest.raises(ValidationError):
        compute_aave_position_risk_matrix(_base(whale_address="not-an-address"))


def test_baseline_stream_fixture_is_critical():
    result = compute_aave_position_risk_matrix(BASELINE_STREAM)
    assert result.system_verdict == "CRITICAL"
    schema = build_a2ui_schema(result, BASELINE_STREAM)
    assert schema["a2ui_version"] == "1.0.0"
    assert schema["layout"]["columns"] == 12
