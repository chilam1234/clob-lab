"""Live Aave V3 Ethereum telemetry → risk matrix inputs.

Sources (no paid API keys required):
- On-chain Pool.getUserAccountData via public Ethereum RPC
- Aave Oracle getAssetPrice (wstETH) vs CoinGecko off-chain price → deviation / OALI
- Aave GraphQL market size / liquidity → pool utilization
- DEX slippage heuristic from market depth vs whale collateral
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional

from web3 import Web3

from .a2ui_schema import build_a2ui_schema
from .aave_risk_processor import RiskAssessmentPayload, compute_aave_position_risk_matrix

AAVE_V3_POOL = Web3.to_checksum_address("0x87870Bca3F3fD6335C3F4ce8392D69350B4fA4E2")
AAVE_ORACLE = Web3.to_checksum_address("0x54586bE62E3c3580375aE3723C145253060Ca0C2")
WSTETH = Web3.to_checksum_address("0x7f39C581F595B53c5cb19bD0b3f8dA6c935E2Ca0")

# Real large borrower observed via recent on-chain Borrow events (not the 1inch router
# placeholder from the handoff, which has no Aave position).
DEFAULT_WHALE = "0xB8a451107A9f87FDe481D4D686247D6e43Ed715e"
RAZOR_WHALE = "0x564aC96B2B6bbFc12B566fc4Bf84Ee6AeFDa09cd"

DEFAULT_RPCS = (
    "https://ethereum.publicnode.com",
    "https://eth.drpc.org",
)

AAVE_GQL = "https://api.v3.aave.com/graphql"
COINGECKO_PRICE = (
    "https://api.coingecko.com/api/v3/simple/price"
    "?ids=wrapped-steth&vs_currencies=usd"
)

POOL_ABI = [
    {
        "inputs": [{"name": "user", "type": "address"}],
        "name": "getUserAccountData",
        "outputs": [
            {"name": "totalCollateralBase", "type": "uint256"},
            {"name": "totalDebtBase", "type": "uint256"},
            {"name": "availableBorrowsBase", "type": "uint256"},
            {"name": "currentLiquidationThreshold", "type": "uint256"},
            {"name": "ltv", "type": "uint256"},
            {"name": "healthFactor", "type": "uint256"},
        ],
        "stateMutability": "view",
        "type": "function",
    }
]
ORACLE_ABI = [
    {
        "inputs": [{"name": "asset", "type": "address"}],
        "name": "getAssetPrice",
        "outputs": [{"name": "", "type": "uint256"}],
        "stateMutability": "view",
        "type": "function",
    }
]


def _http_json(url: str, payload: Optional[Dict[str, Any]] = None, timeout: float = 20.0) -> Any:
    data = None if payload is None else json.dumps(payload).encode()
    headers = {"Accept": "application/json", "User-Agent": "clob-lab-aave-monitor/1.0"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def _connect_web3(rpcs: List[str] | tuple[str, ...] = DEFAULT_RPCS) -> Web3:
    last_err: Exception | None = None
    for rpc in rpcs:
        try:
            w3 = Web3(Web3.HTTPProvider(rpc, request_kwargs={"timeout": 20}))
            if w3.is_connected() and w3.eth.block_number > 0:
                return w3
        except Exception as exc:  # noqa: BLE001 — try next RPC
            last_err = exc
    raise RuntimeError(f"No working Ethereum RPC among {rpcs}: {last_err}")


def fetch_coingecko_wsteth_usd() -> float:
    data = _http_json(COINGECKO_PRICE)
    return float(data["wrapped-steth"]["usd"])


def fetch_market_utilization() -> Dict[str, float]:
    """Core Aave V3 Ethereum market utilization from official GraphQL."""
    q = """
    {
      markets(request: { chainIds: [1] }) {
        name
        address
        totalMarketSize
        totalAvailableLiquidity
      }
    }
    """
    data = _http_json(AAVE_GQL, {"query": q})
    market = next(m for m in data["data"]["markets"] if m["name"] == "AaveV3Ethereum")
    size = float(market["totalMarketSize"])
    available = float(market["totalAvailableLiquidity"])
    utilization = max(0.0, min(1.5, 1.0 - (available / size))) if size > 0 else 0.0
    return {
        "market_size_usd": size,
        "available_liquidity_usd": available,
        "pending_utilization": utilization,
        "pool_address": market["address"],
    }


def estimate_dex_slippage(collateral_usd: float, available_liquidity_usd: float) -> float:
    """Heuristic: slippage dumping ~15% of whale collateral into DEX/pool books."""
    if available_liquidity_usd <= 0 or collateral_usd <= 0:
        return 0.0
    dump = 0.15 * collateral_usd
    # Piecewise: deeper books → lower penalty; clamp to [0, 0.25].
    raw = dump / available_liquidity_usd
    return float(max(0.0, min(0.25, raw * 0.35)))


def fetch_live_stream(
    whale_address: str = DEFAULT_WHALE,
    rpcs: List[str] | tuple[str, ...] = DEFAULT_RPCS,
) -> Dict[str, Any]:
    """Pull one live telemetry frame and map it to WhaleStreamInput fields."""
    w3 = _connect_web3(rpcs)
    block = w3.eth.block_number
    pool = w3.eth.contract(address=AAVE_V3_POOL, abi=POOL_ABI)
    oracle = w3.eth.contract(address=AAVE_ORACLE, abi=ORACLE_ABI)

    user = Web3.to_checksum_address(whale_address)
    coll_base, debt_base, _, lt_bps, _ltv, onchain_hf = pool.functions.getUserAccountData(user).call()
    if debt_base == 0:
        raise ValueError(
            f"Address {whale_address} has no Aave V3 Ethereum debt right now; "
            f"pick an active borrower (defaults: {DEFAULT_WHALE})."
        )

    collateral_usd = coll_base / 1e8
    debt_usd = debt_base / 1e8
    liquidation_threshold = lt_bps / 10_000.0
    onchain_hf_f = None if onchain_hf > 2**200 else onchain_hf / 1e18

    oracle_wsteth = oracle.functions.getAssetPrice(WSTETH).call() / 1e8
    try:
        offchain_wsteth = fetch_coingecko_wsteth_usd()
    except (urllib.error.URLError, KeyError, TimeoutError, json.JSONDecodeError):
        offchain_wsteth = oracle_wsteth

    if offchain_wsteth > 0:
        oracle_deviation = abs(oracle_wsteth - offchain_wsteth) / offchain_wsteth
    else:
        oracle_deviation = 0.0

    market = fetch_market_utilization()
    dex_slippage = estimate_dex_slippage(collateral_usd, market["available_liquidity_usd"])

    stream = {
        "whale_address": user.lower(),
        "collateral_usd": collateral_usd,
        "debt_usd": debt_usd,
        "liquidation_threshold": liquidation_threshold,
        "oracle_deviation": float(min(1.0, oracle_deviation)),
        "dex_slippage": dex_slippage,
        "pending_utilization": float(min(1.2, market["pending_utilization"])),
    }

    assessment: RiskAssessmentPayload = compute_aave_position_risk_matrix(stream)
    schema = build_a2ui_schema(assessment, stream)

    return {
        "ts": time.time(),
        "block_number": block,
        "source": {
            "chain": "ethereum",
            "pool": AAVE_V3_POOL,
            "oracle": AAVE_ORACLE,
            "rpc": w3.provider.endpoint_uri if hasattr(w3.provider, "endpoint_uri") else "ethereum-rpc",
            "market_api": AAVE_GQL,
            "price_api": "coingecko",
        },
        "market": market,
        "prices": {
            "wsteth_oracle_usd": oracle_wsteth,
            "wsteth_offchain_usd": offchain_wsteth,
            "oracle_deviation": stream["oracle_deviation"],
        },
        "onchain_health_factor": onchain_hf_f,
        "stream": stream,
        "assessment": assessment.model_dump(),
        "schema": schema,
    }


def discover_active_whales(lookback_blocks: int = 800, limit: int = 8) -> List[Dict[str, Any]]:
    """Scan recent Borrow events and rank by outstanding debt."""
    w3 = _connect_web3()
    pool = w3.eth.contract(address=AAVE_V3_POOL, abi=POOL_ABI)
    topic = "0x" + w3.keccak(text="Borrow(address,address,address,uint256,uint8,uint256,uint16)").hex()
    latest = w3.eth.block_number
    logs = w3.eth.get_logs(
        {
            "fromBlock": max(0, latest - lookback_blocks),
            "toBlock": latest,
            "address": AAVE_V3_POOL,
            "topics": [topic],
        }
    )
    users: List[str] = []
    for lg in logs:
        if len(lg["topics"]) >= 3:
            users.append(Web3.to_checksum_address("0x" + lg["topics"][2].hex()[-40:]))
    users = list(dict.fromkeys(users))

    ranked: List[Dict[str, Any]] = []
    for u in users:
        coll, debt, _, lt, _, hf = pool.functions.getUserAccountData(u).call()
        if debt <= 0:
            continue
        ranked.append(
            {
                "whale_address": u,
                "collateral_usd": coll / 1e8,
                "debt_usd": debt / 1e8,
                "liquidation_threshold": lt / 10_000,
                "health_factor": None if hf > 2**200 else hf / 1e18,
            }
        )
    ranked.sort(key=lambda r: r["debt_usd"], reverse=True)
    return ranked[:limit]


if __name__ == "__main__":
    frame = fetch_live_stream()
    print(json.dumps({k: frame[k] for k in ("block_number", "prices", "assessment", "stream")}, indent=2))
