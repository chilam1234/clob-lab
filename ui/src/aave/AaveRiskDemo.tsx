import { useMemo, useState } from "react";
import { A2UIRenderer } from "./A2UIRenderer";
import fixtures from "./fixtures/baseline-schemas.json";
import type { A2UISchema } from "./types";
import { Button } from "@/components/ui/button";

type ScenarioKey = "critical" | "high" | "stable";

const LABELS: Record<ScenarioKey, string> = {
  critical: "CRITICAL (baseline whale)",
  high: "HIGH",
  stable: "STABLE",
};

export default function AaveRiskDemo() {
  const [scenario, setScenario] = useState<ScenarioKey>("critical");
  const [toasts, setToasts] = useState<string[]>([]);

  const bundle = fixtures[scenario];
  const schema = bundle.schema as A2UISchema;
  const assessment = bundle.assessment;

  const toastText = useMemo(() => toasts.slice(-4).reverse(), [toasts]);

  function onActionTriggered(actionId: string, payload: unknown) {
    // Demo / simulation only — never submit real bundles or liquidations.
    const line = `[demo] ${actionId} → ${JSON.stringify(payload)}`;
    console.info(line);
    setToasts((prev) => [...prev, line]);
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-6">
      <header className="mb-6 border-b border-border pb-4">
        <h1 className="font-masthead text-masthead font-bold tracking-masthead text-text">
          Aave Whale Risk Monitor
        </h1>
        <p className="mt-1 text-sm text-text-muted">
          A2UI declarative factory demo — ActionGroup callbacks are simulation/toast only.
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          {(Object.keys(LABELS) as ScenarioKey[]).map((key) => (
            <Button
              key={key}
              variant={scenario === key ? "default" : "outline"}
              size="sm"
              onClick={() => setScenario(key)}
            >
              {LABELS[key]}
            </Button>
          ))}
        </div>
        <dl className="mt-3 grid grid-cols-2 gap-2 text-xs text-text-muted md:grid-cols-4">
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
        </dl>
      </header>

      <A2UIRenderer schema={schema} onActionTriggered={onActionTriggered} />

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
