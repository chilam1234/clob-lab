import { useEffect, useMemo, useState } from "react";
import { A2UIRenderer } from "./A2UIRenderer";
import fixtures from "./fixtures/baseline-schemas.json";
import { SparklineChart } from "./SparklineChart";
import type { HistoryPoint, LiveFrame, WhaleRow } from "./liveTypes";
import type { A2UISchema } from "./types";
import { Button } from "@/components/ui/button";

type Mode = "live" | "critical" | "high" | "stable";

const FIXTURE_LABELS: Record<Exclude<Mode, "live">, string> = {
  critical: "Fixture CRITICAL",
  high: "Fixture HIGH",
  stable: "Fixture STABLE",
};

const DEFAULT_LIVE_WHALE = "0xB8a451107A9f87FDe481D4D686247D6e43Ed715e";
const RAZOR_WHALE = "0x564aC96B2B6bbFc12B566fc4Bf84Ee6AeFDa09cd";

export default function AaveRiskDemo() {
  const [mode, setMode] = useState<Mode>("live");
  const [whale, setWhale] = useState(DEFAULT_LIVE_WHALE);
  const [live, setLive] = useState<LiveFrame | null>(null);
  const [history, setHistory] = useState<HistoryPoint[]>([]);
  const [whales, setWhales] = useState<WhaleRow[]>([]);
  const [liveError, setLiveError] = useState<string | null>(null);
  const [toasts, setToasts] = useState<string[]>([]);
  const [connected, setConnected] = useState(false);

  const toastText = useMemo(() => toasts.slice(-4).reverse(), [toasts]);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/aave/whales")
      .then((r) => r.json())
      .then((data) => {
        if (!cancelled && Array.isArray(data.whales)) setWhales(data.whales);
      })
      .catch(() => {
        /* optional */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (mode !== "live") return;

    let es: EventSource | null = null;
    let pollTimer: number | undefined;
    let cancelled = false;

    async function pullSnapshot() {
      try {
        const res = await fetch(
          `/api/aave/snapshot?whale=${encodeURIComponent(whale)}&refresh=1`,
        );
        const data = (await res.json()) as LiveFrame;
        if (cancelled) return;
        if (!res.ok || data.error) {
          setLiveError(data.error || `HTTP ${res.status}`);
          setConnected(false);
          return;
        }
        setLive(data);
        setHistory(data.history ?? []);
        setLiveError(null);
        setConnected(true);
      } catch (err) {
        if (!cancelled) {
          setLiveError(err instanceof Error ? err.message : String(err));
          setConnected(false);
        }
      }
    }

    void pullSnapshot();

    try {
      es = new EventSource(
        `/api/aave/stream?whale=${encodeURIComponent(whale)}`,
      );
      es.addEventListener("risk", (ev) => {
        const data = JSON.parse((ev as MessageEvent).data) as LiveFrame;
        setLive(data);
        setHistory(data.history ?? []);
        setLiveError(null);
        setConnected(true);
      });
      es.addEventListener("error", (ev) => {
        const msg = (ev as MessageEvent).data;
        if (msg) {
          try {
            const parsed = JSON.parse(msg) as { error?: string };
            setLiveError(parsed.error || msg);
          } catch {
            setLiveError(String(msg));
          }
        }
      });
      es.onerror = () => {
        // Fall back to polling if SSE drops.
        setConnected(false);
        es?.close();
        es = null;
        pollTimer = window.setInterval(() => void pullSnapshot(), 12_000);
      };
    } catch {
      pollTimer = window.setInterval(() => void pullSnapshot(), 12_000);
    }

    return () => {
      cancelled = true;
      es?.close();
      if (pollTimer) window.clearInterval(pollTimer);
    };
  }, [mode, whale]);

  function onActionTriggered(actionId: string, payload: unknown) {
    const line = `[demo] ${actionId} → ${JSON.stringify(payload)}`;
    console.info(line);
    setToasts((prev) => [...prev, line]);
  }

  const schema: A2UISchema | null =
    mode === "live"
      ? live?.schema ?? null
      : (fixtures[mode].schema as A2UISchema);

  const assessment =
    mode === "live" ? live?.assessment : fixtures[mode].assessment;

  return (
    <div className="mx-auto max-w-6xl px-4 py-6">
      <header className="mb-6 border-b border-border pb-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="font-masthead text-masthead font-bold tracking-masthead text-text">
              Aave Whale Risk Monitor
            </h1>
            <p className="mt-1 text-sm text-text-muted">
              Live Ethereum Aave V3 telemetry → Python risk matrix → A2UI. ActionGroup is
              simulation/toast only.
            </p>
          </div>
          {mode === "live" ? (
            <div
              className={`rounded-[2px] border px-2 py-1 text-xs font-semibold uppercase tracking-h2 ${
                connected
                  ? "border-up text-up"
                  : "border-accent-alert text-accent-alert"
              }`}
            >
              {connected ? "● LIVE" : "○ RECONNECTING"}
            </div>
          ) : null}
        </div>

        <div className="mt-3 flex flex-wrap gap-2">
          <Button
            variant={mode === "live" ? "default" : "outline"}
            size="sm"
            onClick={() => setMode("live")}
          >
            LIVE on-chain
          </Button>
          {(Object.keys(FIXTURE_LABELS) as Exclude<Mode, "live">[]).map((key) => (
            <Button
              key={key}
              variant={mode === key ? "default" : "outline"}
              size="sm"
              onClick={() => setMode(key)}
            >
              {FIXTURE_LABELS[key]}
            </Button>
          ))}
        </div>

        {mode === "live" ? (
          <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
            <label className="text-text-muted uppercase tracking-h2">Whale</label>
            <select
              className="h-8 rounded-[2px] border border-border bg-surface-raised px-2 text-sm text-text"
              value={whale}
              onChange={(e) => setWhale(e.target.value)}
            >
              <option value={DEFAULT_LIVE_WHALE}>
                Large (~$37M debt) {DEFAULT_LIVE_WHALE.slice(0, 8)}…
              </option>
              <option value={RAZOR_WHALE}>
                Razor HF {RAZOR_WHALE.slice(0, 8)}…
              </option>
              {whales.map((w) => (
                <option key={w.whale_address} value={w.whale_address}>
                  {w.whale_address.slice(0, 10)}… debt $
                  {Math.round(w.debt_usd).toLocaleString()} HF{" "}
                  {w.health_factor?.toFixed(3) ?? "—"}
                </option>
              ))}
            </select>
            <span className="font-[ui-monospace,monospace] text-text-muted">
              {whale}
            </span>
          </div>
        ) : null}

        {assessment ? (
          <dl className="mt-3 grid grid-cols-2 gap-2 text-xs text-text-muted md:grid-cols-4 lg:grid-cols-6">
            <div>
              <dt className="uppercase tracking-h2">Verdict</dt>
              <dd className="text-sm text-text">{assessment.system_verdict}</dd>
            </div>
            <div>
              <dt className="uppercase tracking-h2">Baseline HF</dt>
              <dd className="text-sm text-text">{assessment.baseline_hf}</dd>
            </div>
            <div>
              <dt className="uppercase tracking-h2">Effective HF</dt>
              <dd className="text-sm text-text">{assessment.effective_hf}</dd>
            </div>
            <div>
              <dt className="uppercase tracking-h2">OALI</dt>
              <dd className="text-sm text-text">{assessment.oracle_arbitrage_risk}</dd>
            </div>
            {mode === "live" && live ? (
              <>
                <div>
                  <dt className="uppercase tracking-h2">Block</dt>
                  <dd className="text-sm text-text">{live.block_number}</dd>
                </div>
                <div>
                  <dt className="uppercase tracking-h2">wstETH oracle</dt>
                  <dd className="text-sm text-text">
                    ${live.prices.wsteth_oracle_usd.toFixed(2)}
                  </dd>
                </div>
              </>
            ) : null}
          </dl>
        ) : null}

        {liveError && mode === "live" ? (
          <p className="mt-2 text-sm text-accent-alert">Live feed error: {liveError}</p>
        ) : null}
      </header>

      {mode === "live" ? (
        <div className="mb-4">
          <SparklineChart points={history} title="Live HF / utilization / OALI" />
        </div>
      ) : null}

      {mode === "live" && live ? (
        <p className="mb-4 text-xs text-text-muted">
          On-chain HF {live.onchain_health_factor?.toFixed(4) ?? "—"} · collateral $
          {live.stream.collateral_usd.toLocaleString(undefined, { maximumFractionDigits: 0 })} ·
          debt ${live.stream.debt_usd.toLocaleString(undefined, { maximumFractionDigits: 0 })} ·
          util {(live.stream.pending_utilization * 100).toFixed(2)}% · oracle Δ{" "}
          {(live.prices.oracle_deviation * 100).toFixed(3)}% · sources: Ethereum RPC + Aave
          Oracle + CoinGecko + Aave GraphQL
        </p>
      ) : null}

      {schema ? (
        <A2UIRenderer schema={schema} onActionTriggered={onActionTriggered} />
      ) : (
        <p className="text-sm text-text-muted">Waiting for first live risk frame…</p>
      )}

      {toastText.length > 0 ? (
        <aside className="mt-6 rounded-[4px] border border-border bg-surface p-3 text-xs">
          <div className="mb-2 font-h2 text-h2 font-bold uppercase tracking-h2 text-text-muted">
            Simulation log
          </div>
          <ul className="space-y-1 font-[ui-monospace,monospace]">
            {toastText.map((t, i) => (
              <li key={`${i}-${t}`}>{t}</li>
            ))}
          </ul>
        </aside>
      ) : null}
    </div>
  );
}
