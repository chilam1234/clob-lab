import type { A2UISchema } from "./types";

export interface HistoryPoint {
  ts: number;
  block_number: number;
  baseline_hf: number;
  effective_hf: number;
  oracle_arbitrage_risk: number;
  pending_utilization: number;
  system_verdict: string;
  wsteth_oracle_usd: number;
  wsteth_offchain_usd: number;
}

export interface LiveFrame {
  ts: number;
  block_number: number;
  source: Record<string, string>;
  market: {
    market_size_usd: number;
    available_liquidity_usd: number;
    pending_utilization: number;
    pool_address: string;
  };
  prices: {
    wsteth_oracle_usd: number;
    wsteth_offchain_usd: number;
    oracle_deviation: number;
  };
  onchain_health_factor: number | null;
  stream: {
    whale_address: string;
    collateral_usd: number;
    debt_usd: number;
    liquidation_threshold: number;
    oracle_deviation: number;
    dex_slippage: number;
    pending_utilization: number;
  };
  assessment: {
    whale_address: string;
    baseline_hf: number;
    effective_hf: number;
    oracle_arbitrage_risk: number;
    liquidity_lock_risk: string;
    system_verdict: string;
  };
  schema: A2UISchema;
  history?: HistoryPoint[];
  error?: string;
}

export interface WhaleRow {
  whale_address: string;
  collateral_usd: number;
  debt_usd: number;
  liquidation_threshold: number;
  health_factor: number | null;
}
