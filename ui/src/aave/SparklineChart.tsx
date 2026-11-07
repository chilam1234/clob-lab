import type { HistoryPoint } from "./liveTypes";

interface PanelSeries {
  key: keyof HistoryPoint;
  label: string;
  color: string;
}

interface PanelDef {
  id: string;
  title: string;
  yLabel: string;
  yMin: number;
  yMax: number;
  yDigits: number;
  series: PanelSeries[];
}

/** One panel per scale family — no dual-axis packing when metrics don't fit. */
const PANELS: PanelDef[] = [
  {
    id: "hf",
    title: "Health Factor",
    yLabel: "HF / eHF",
    yMin: 0.9,
    yMax: 1.3,
    yDigits: 2,
    series: [
      { key: "effective_hf", label: "eHF", color: "#2148b8" },
      { key: "baseline_hf", label: "HF", color: "#6b7075" },
    ],
  },
  {
    id: "util",
    title: "Pool Utilization",
    yLabel: "Utilization",
    yMin: 0,
    yMax: 1,
    yDigits: 2,
    series: [
      { key: "pending_utilization", label: "Util", color: "#c65f38" },
    ],
  },
  {
    id: "oali",
    title: "Oracle Arbitrage Index",
    yLabel: "OALI",
    yMin: 0,
    yMax: 0.5,
    yDigits: 2,
    series: [
      { key: "oracle_arbitrage_risk", label: "OALI", color: "#c83232" },
    ],
  },
];

const WINDOW_SECONDS = 15 * 60;

function formatTime(ts: number): string {
  return new Date(ts * 1000).toLocaleTimeString("en-HK", {
    timeZone: "Asia/Hong_Kong",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}

function ticks(min: number, max: number, count: number): number[] {
  const out: number[] = [];
  for (let i = 0; i < count; i++) {
    out.push(min + ((max - min) * i) / (count - 1));
  }
  return out;
}

function MetricPanel({
  panel,
  points,
  tStart,
  tEnd,
}: {
  panel: PanelDef;
  points: HistoryPoint[];
  tStart: number;
  tEnd: number;
}) {
  const width = 720;
  const height = 200;
  const margin = { top: 12, right: 16, bottom: 40, left: 52 };
  const plotW = width - margin.left - margin.right;
  const plotH = height - margin.top - margin.bottom;
  const latest = points[points.length - 1];
  const windowSec = Math.max(1, tEnd - tStart);

  const xAt = (ts: number) =>
    margin.left + ((ts - tStart) / windowSec) * plotW;
  const yAt = (v: number) =>
    margin.top +
    plotH -
    ((v - panel.yMin) / (panel.yMax - panel.yMin || 1)) * plotH;

  function seriesPath(key: keyof HistoryPoint): string {
    let started = false;
    const parts: string[] = [];
    for (const p of points) {
      const v = Number(p[key]);
      if (!Number.isFinite(v)) continue;
      // Clip to panel domain so out-of-range values don't warp the plot.
      const clipped = Math.min(panel.yMax, Math.max(panel.yMin, v));
      const cmd = started ? "L" : "M";
      parts.push(`${cmd}${xAt(p.ts).toFixed(1)},${yAt(clipped).toFixed(1)}`);
      started = true;
    }
    return parts.join(" ");
  }

  const yTicks = ticks(panel.yMin, panel.yMax, 5);
  const xTickTs = [tStart, tStart + windowSec / 2, tEnd];

  return (
    <section className="rounded-[4px] border border-border bg-surface-raised p-3">
      <div className="mb-1 flex items-baseline justify-between gap-2">
        <h3 className="font-h2 text-h2 font-bold uppercase tracking-h2 text-text-muted">
          {panel.title}
        </h3>
        <ul className="flex flex-wrap gap-3 text-xs text-text-muted">
          {panel.series.map((s) => (
            <li key={s.key} className="flex items-center gap-1">
              <span
                className="inline-block size-2 rounded-[1px]"
                style={{ background: s.color }}
              />
              {s.label}
              {latest ? (
                <span className="text-text">
                  {Number(latest[s.key]).toFixed(3)}
                </span>
              ) : null}
            </li>
          ))}
        </ul>
      </div>

      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="h-48 w-full"
        role="img"
        aria-label={`${panel.title} over time`}
      >
        <rect width={width} height={height} fill="#fafaf7" />
        <rect
          x={margin.left}
          y={margin.top}
          width={plotW}
          height={plotH}
          fill="#f4f4f1"
        />

        {yTicks.map((t) => (
          <g key={`y-${t}`}>
            <line
              x1={margin.left}
              x2={margin.left + plotW}
              y1={yAt(t)}
              y2={yAt(t)}
              stroke="#c9ccc6"
              strokeWidth={1}
              strokeDasharray="3 3"
            />
            <text
              x={margin.left - 8}
              y={yAt(t) + 3}
              textAnchor="end"
              fontSize="10"
              fill="#6b7075"
              fontFamily="ui-monospace, monospace"
            >
              {t.toFixed(panel.yDigits)}
            </text>
          </g>
        ))}

        <line
          x1={margin.left}
          y1={margin.top}
          x2={margin.left}
          y2={margin.top + plotH}
          stroke="#30343a"
          strokeWidth={1.5}
        />
        <line
          x1={margin.left}
          y1={margin.top + plotH}
          x2={margin.left + plotW}
          y2={margin.top + plotH}
          stroke="#30343a"
          strokeWidth={1.5}
        />

        {xTickTs.map((ts) => (
          <g key={`x-${ts}`}>
            <line
              x1={xAt(ts)}
              x2={xAt(ts)}
              y1={margin.top + plotH}
              y2={margin.top + plotH + 5}
              stroke="#30343a"
              strokeWidth={1}
            />
            <text
              x={xAt(ts)}
              y={margin.top + plotH + 18}
              textAnchor="middle"
              fontSize="10"
              fill="#6b7075"
              fontFamily="ui-monospace, monospace"
            >
              {formatTime(ts)}
            </text>
          </g>
        ))}

        <text
          x={14}
          y={margin.top + plotH / 2}
          textAnchor="middle"
          fontSize="11"
          fill="#30343a"
          fontFamily="Georgia, serif"
          transform={`rotate(-90 14 ${margin.top + plotH / 2})`}
        >
          {panel.yLabel}
        </text>
        <text
          x={margin.left + plotW / 2}
          y={height - 4}
          textAnchor="middle"
          fontSize="10"
          fill="#6b7075"
          fontFamily="Georgia, serif"
        >
          Time (Hong Kong)
        </text>

        {panel.series.map((s) => {
          const d = seriesPath(s.key);
          return d ? (
            <path
              key={s.key}
              d={d}
              fill="none"
              stroke={s.color}
              strokeWidth={2}
            />
          ) : null;
        })}
      </svg>
    </section>
  );
}

export function SparklineChart({
  points,
  title = "Live risk stream",
}: {
  points: HistoryPoint[];
  title?: string;
}) {
  const latest = points[points.length - 1];
  const tEnd = latest?.ts ?? Date.now() / 1000;
  const tStart = tEnd - WINDOW_SECONDS;
  const visible = points.filter((p) => p.ts >= tStart && p.ts <= tEnd);

  return (
    <div className="space-y-3">
      <div className="flex items-baseline justify-between gap-2 px-0.5">
        <h2 className="font-h2 text-h2 font-bold uppercase tracking-h2 text-text-muted">
          {title}
        </h2>
        <span className="text-xs text-text-muted">
          {visible.length} pts · fixed 15m window · separate scales
          {latest ? ` · block ${latest.block_number}` : ""}
        </span>
      </div>

      {visible.length < 2 ? (
        <p className="rounded-[4px] border border-border bg-surface-raised p-3 text-sm text-text-muted">
          Collecting live samples… charts appear after the second poll.
        </p>
      ) : (
        PANELS.map((panel) => (
          <MetricPanel
            key={panel.id}
            panel={panel}
            points={visible}
            tStart={tStart}
            tEnd={tEnd}
          />
        ))
      )}
    </div>
  );
}
