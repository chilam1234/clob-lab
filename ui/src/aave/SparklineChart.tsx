import type { HistoryPoint } from "./liveTypes";

interface Series {
  key: keyof HistoryPoint;
  label: string;
  color: string;
  axis: "left" | "right";
}

const SERIES: Series[] = [
  { key: "effective_hf", label: "eHF", color: "#2148b8", axis: "left" },
  { key: "baseline_hf", label: "HF", color: "#6b7075", axis: "left" },
  { key: "pending_utilization", label: "Util", color: "#c65f38", axis: "right" },
  { key: "oracle_arbitrage_risk", label: "OALI", color: "#c83232", axis: "right" },
];

/** Fixed Y domains — scales do not auto-zoom, so lines only move when values move. */
const LEFT_Y = { min: 0.9, max: 1.3 }; // Health Factor
const RIGHT_Y = { min: 0, max: 1 }; // Utilization / OALI
/** Fixed visible window so X positions stay stable as new ticks arrive. */
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

export function SparklineChart({
  points,
  title = "Live risk stream",
}: {
  points: HistoryPoint[];
  title?: string;
}) {
  const width = 720;
  const height = 260;
  const margin = { top: 16, right: 56, bottom: 44, left: 52 };
  const plotW = width - margin.left - margin.right;
  const plotH = height - margin.top - margin.bottom;
  const latest = points[points.length - 1];

  const tEnd = latest?.ts ?? Date.now() / 1000;
  const tStart = tEnd - WINDOW_SECONDS;
  const visible = points.filter((p) => p.ts >= tStart && p.ts <= tEnd);

  const xAt = (ts: number) =>
    margin.left + ((ts - tStart) / WINDOW_SECONDS) * plotW;
  const yLeft = (v: number) =>
    margin.top +
    plotH -
    ((v - LEFT_Y.min) / (LEFT_Y.max - LEFT_Y.min)) * plotH;
  const yRight = (v: number) =>
    margin.top +
    plotH -
    ((v - RIGHT_Y.min) / (RIGHT_Y.max - RIGHT_Y.min)) * plotH;

  function seriesPath(key: keyof HistoryPoint, axis: "left" | "right"): string {
    const yFn = axis === "left" ? yLeft : yRight;
    let started = false;
    const parts: string[] = [];
    for (const p of visible) {
      const v = Number(p[key]);
      if (!Number.isFinite(v)) continue;
      const x = xAt(p.ts);
      const y = yFn(v);
      parts.push(`${started ? "L" : "M"}${x.toFixed(1)},${y.toFixed(1)}`);
      started = true;
    }
    return parts.join(" ");
  }

  const xTickTs = [tStart, tStart + WINDOW_SECONDS / 2, tEnd];
  const leftTicks = ticks(LEFT_Y.min, LEFT_Y.max, 5);
  const rightTicks = ticks(RIGHT_Y.min, RIGHT_Y.max, 5);

  return (
    <section className="rounded-[4px] border border-border bg-surface-raised p-3">
      <div className="mb-2 flex items-baseline justify-between gap-2">
        <h2 className="font-h2 text-h2 font-bold uppercase tracking-h2 text-text-muted">
          {title}
        </h2>
        <span className="text-xs text-text-muted">
          {visible.length} pts · fixed 15m window
          {latest ? ` · block ${latest.block_number}` : ""}
        </span>
      </div>
      {visible.length < 2 ? (
        <p className="text-sm text-text-muted">
          Collecting live samples… charts appear after the second poll.
        </p>
      ) : (
        <>
          <svg
            viewBox={`0 0 ${width} ${height}`}
            className="h-64 w-full"
            role="img"
            aria-label="Live health factor and utilization chart with fixed axes"
          >
            <rect width={width} height={height} fill="#fafaf7" />
            <rect
              x={margin.left}
              y={margin.top}
              width={plotW}
              height={plotH}
              fill="#f4f4f1"
            />

            {leftTicks.map((t) => (
              <g key={`ly-${t}`}>
                <line
                  x1={margin.left}
                  x2={margin.left + plotW}
                  y1={yLeft(t)}
                  y2={yLeft(t)}
                  stroke="#c9ccc6"
                  strokeWidth={1}
                  strokeDasharray="3 3"
                />
                <text
                  x={margin.left - 8}
                  y={yLeft(t) + 3}
                  textAnchor="end"
                  fontSize="10"
                  fill="#6b7075"
                  fontFamily="ui-monospace, monospace"
                >
                  {t.toFixed(2)}
                </text>
              </g>
            ))}

            {rightTicks.map((t) => (
              <text
                key={`ry-${t}`}
                x={margin.left + plotW + 8}
                y={yRight(t) + 3}
                textAnchor="start"
                fontSize="10"
                fill="#6b7075"
                fontFamily="ui-monospace, monospace"
              >
                {t.toFixed(2)}
              </text>
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
              x1={margin.left + plotW}
              y1={margin.top}
              x2={margin.left + plotW}
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
              Health Factor (HF / eHF)
            </text>
            <text
              x={width - 14}
              y={margin.top + plotH / 2}
              textAnchor="middle"
              fontSize="11"
              fill="#30343a"
              fontFamily="Georgia, serif"
              transform={`rotate(90 ${width - 14} ${margin.top + plotH / 2})`}
            >
              Utilization / OALI
            </text>
            <text
              x={margin.left + plotW / 2}
              y={height - 4}
              textAnchor="middle"
              fontSize="11"
              fill="#30343a"
              fontFamily="Georgia, serif"
            >
              Time (Hong Kong) — fixed 15 min window
            </text>

            {SERIES.map((s) => {
              const d = seriesPath(s.key, s.axis);
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

          <ul className="mt-2 flex flex-wrap gap-3 text-xs text-text-muted">
            {SERIES.map((s) => (
              <li key={s.key} className="flex items-center gap-1">
                <span
                  className="inline-block size-2 rounded-[1px]"
                  style={{ background: s.color }}
                />
                {s.label}
                <span className="text-[10px] uppercase tracking-h2">
                  ({s.axis === "left" ? "Y-left" : "Y-right"})
                </span>
                {latest ? (
                  <span className="text-text">
                    {Number(latest[s.key]).toFixed(3)}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
