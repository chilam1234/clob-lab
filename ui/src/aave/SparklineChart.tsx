import type { HistoryPoint } from "./liveTypes";

interface Series {
  key: keyof HistoryPoint;
  label: string;
  color: string;
}

const SERIES: Series[] = [
  { key: "effective_hf", label: "eHF", color: "#2148b8" },
  { key: "baseline_hf", label: "HF", color: "#6b7075" },
  { key: "pending_utilization", label: "Util", color: "#c65f38" },
  { key: "oracle_arbitrage_risk", label: "OALI", color: "#c83232" },
];

function pathFor(
  points: HistoryPoint[],
  key: keyof HistoryPoint,
  width: number,
  height: number,
  pad = 8,
): string {
  const nums = points
    .map((p) => Number(p[key]))
    .filter((n) => Number.isFinite(n));
  if (nums.length < 2) return "";
  const min = Math.min(...nums);
  const max = Math.max(...nums);
  const span = max - min || 1;
  return nums
    .map((n, i) => {
      const x = pad + (i / (nums.length - 1)) * (width - pad * 2);
      const y = height - pad - ((n - min) / span) * (height - pad * 2);
      return `${i === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
}

export function SparklineChart({
  points,
  title = "Live risk stream",
}: {
  points: HistoryPoint[];
  title?: string;
}) {
  const width = 640;
  const height = 160;
  const latest = points[points.length - 1];

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
            className="h-40 w-full"
            role="img"
            aria-label="Live health factor and utilization chart"
          >
            <rect width={width} height={height} fill="#f4f4f1" />
            {SERIES.map((s) => {
              const d = pathFor(points, s.key, width, height);
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
                {latest ? (
                  <span className="text-text">
                    {Number(latest[s.key]).toFixed(s.key.includes("utilization") ? 3 : 3)}
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
