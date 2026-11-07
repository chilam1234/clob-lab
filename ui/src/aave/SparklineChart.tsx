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

function niceRange(values: number[], fallbackMin: number, fallbackMax: number) {
  const finite = values.filter((n) => Number.isFinite(n));
  if (finite.length === 0) return { min: fallbackMin, max: fallbackMax };
  let min = Math.min(...finite);
  let max = Math.max(...finite);
  if (min === max) {
    min = Math.max(0, min - Math.abs(min) * 0.05 - 0.01);
    max = max + Math.abs(max) * 0.05 + 0.01;
  } else {
    const pad = (max - min) * 0.08;
    min -= pad;
    max += pad;
  }
  return { min, max };
}

function formatTime(ts: number): string {
  // Demo audience is Asia/Hong_Kong; VM clock is UTC.
  return new Date(ts * 1000).toLocaleTimeString("en-HK", {
    timeZone: "Asia/Hong_Kong",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}

function ticks(min: number, max: number, count: number): number[] {
  if (count < 2) return [min];
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

  const leftVals = points.flatMap((p) => [p.effective_hf, p.baseline_hf]);
  const rightVals = points.flatMap((p) => [
    p.pending_utilization,
    p.oracle_arbitrage_risk,
  ]);
  const left = niceRange(leftVals, 0.9, 1.2);
  const right = niceRange(rightVals, 0, 1);

  const xAt = (i: number) =>
    margin.left + (points.length <= 1 ? 0 : (i / (points.length - 1)) * plotW);
  const yLeft = (v: number) =>
    margin.top + plotH - ((v - left.min) / (left.max - left.min || 1)) * plotH;
  const yRight = (v: number) =>
    margin.top +
    plotH -
    ((v - right.min) / (right.max - right.min || 1)) * plotH;

  function seriesPath(key: keyof HistoryPoint, axis: "left" | "right"): string {
    const yFn = axis === "left" ? yLeft : yRight;
    return points
      .map((p, i) => {
        const v = Number(p[key]);
        if (!Number.isFinite(v)) return "";
        const cmd = i === 0 ? "M" : "L";
        return `${cmd}${xAt(i).toFixed(1)},${yFn(v).toFixed(1)}`;
      })
      .filter(Boolean)
      .join(" ");
  }

  const xTickIdx =
    points.length <= 1
      ? [0]
      : [
          0,
          Math.floor((points.length - 1) / 2),
          points.length - 1,
        ].filter((v, i, arr) => arr.indexOf(v) === i);

  const leftTicks = ticks(left.min, left.max, 5);
  const rightTicks = ticks(right.min, right.max, 5);

  return (
    <section className="rounded-[4px] border border-border bg-surface-raised p-3">
      <div className="mb-2 flex items-baseline justify-between gap-2">
        <h2 className="font-h2 text-h2 font-bold uppercase tracking-h2 text-text-muted">
          {title}
        </h2>
        <span className="text-xs text-text-muted">
          {points.length} ticks
          {latest ? ` · block ${latest.block_number}` : ""}
        </span>
      </div>
      {points.length < 2 ? (
        <p className="text-sm text-text-muted">
          Collecting live samples… charts appear after the second poll.
        </p>
      ) : (
        <>
          <svg
            viewBox={`0 0 ${width} ${height}`}
            className="h-64 w-full"
            role="img"
            aria-label="Live health factor and utilization chart with labeled axes"
          >
            <rect width={width} height={height} fill="#fafaf7" />

            {/* Plot background */}
            <rect
              x={margin.left}
              y={margin.top}
              width={plotW}
              height={plotH}
              fill="#f4f4f1"
            />

            {/* Horizontal grid + left Y ticks */}
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
                  {t.toFixed(3)}
                </text>
              </g>
            ))}

            {/* Right Y ticks */}
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
                {t.toFixed(3)}
              </text>
            ))}

            {/* Axes */}
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

            {/* X ticks */}
            {xTickIdx.map((i) => {
              const p = points[i];
              return (
                <g key={`x-${i}`}>
                  <line
                    x1={xAt(i)}
                    x2={xAt(i)}
                    y1={margin.top + plotH}
                    y2={margin.top + plotH + 5}
                    stroke="#30343a"
                    strokeWidth={1}
                  />
                  <text
                    x={xAt(i)}
                    y={margin.top + plotH + 18}
                    textAnchor="middle"
                    fontSize="10"
                    fill="#6b7075"
                    fontFamily="ui-monospace, monospace"
                  >
                    {formatTime(p.ts)}
                  </text>
                  <text
                    x={xAt(i)}
                    y={margin.top + plotH + 32}
                    textAnchor="middle"
                    fontSize="9"
                    fill="#6b7075"
                    fontFamily="ui-monospace, monospace"
                  >
                    blk {p.block_number}
                  </text>
                </g>
              );
            })}

            {/* Axis titles */}
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
              Time (Hong Kong) / Ethereum block
            </text>

            {/* Series */}
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
