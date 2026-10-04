import { useEffect, useRef, useState } from "react";
import { getSnapshot, subscribe } from "./ws.js";

const RING = 300;
const PRINT_CAP = 4000;
const BUCKETS = [
  { id: "250ms", ms: 250, label: "250ms" },
  { id: "1s", ms: 1000, label: "1s" },
  { id: "5s", ms: 5000, label: "5s" },
];

const UP = "#2148B8";
const DOWN = "#C65F38";
const WICK = "#30343A";
const INK = "#30343A";
const MUTED = "#6B7075";
const PAPER = "#FAFAF7";

function bucketStart(ts, size) {
  return Math.floor(ts / size) * size;
}

function niceTicks(min, max, target = 6) {
  const span = Math.max(1, max - min);
  const raw = span / target;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const residual = raw / mag;
  let nice;
  if (residual <= 1) nice = 1;
  else if (residual <= 2) nice = 2;
  else if (residual <= 5) nice = 5;
  else nice = 10;
  const step = nice * mag;
  const first = Math.ceil(min / step) * step;
  const ticks = [];
  for (let v = first; v <= max + 1e-9; v += step) {
    ticks.push(v);
  }
  return ticks.length ? ticks : [min, max];
}

function formatTime(ts) {
  const d = new Date(ts);
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  const ss = String(d.getSeconds()).padStart(2, "0");
  return `${hh}:${mm}:${ss}`;
}

function aggregate(prints, size) {
  const candles = [];
  let lastClose = null;
  for (const p of prints) {
    const t0 = bucketStart(p.ts, size);
    const last = candles.length ? candles[candles.length - 1] : null;
    if (last && last.t === t0) {
      last.h = Math.max(last.h, p.px);
      last.l = Math.min(last.l, p.px);
      last.c = p.px;
      last.empty = false;
      lastClose = p.px;
      continue;
    }
    if (last) {
      let gap = last.t + size;
      while (gap < t0 && candles.length < RING) {
        candles.push({ t: gap, o: lastClose, h: lastClose, l: lastClose, c: lastClose, empty: true });
        gap += size;
      }
      if (candles.length > RING) {
        candles.splice(0, candles.length - RING);
      }
    }
    candles.push({ t: t0, o: p.px, h: p.px, l: p.px, c: p.px, empty: false });
    lastClose = p.px;
    if (candles.length > RING) {
      candles.splice(0, candles.length - RING);
    }
  }
  return candles;
}

function updateLast(candles, px, ts, size) {
  const t0 = bucketStart(ts, size);
  const last = candles.length ? candles[candles.length - 1] : null;
  if (!last) {
    candles.push({ t: t0, o: px, h: px, l: px, c: px, empty: false });
    return;
  }
  if (last.t === t0) {
    last.h = Math.max(last.h, px);
    last.l = Math.min(last.l, px);
    last.c = px;
    last.empty = false;
    return;
  }
  if (t0 > last.t) {
    let gap = last.t + size;
    const close = last.c;
    while (gap < t0) {
      candles.push({ t: gap, o: close, h: close, l: close, c: close, empty: true });
      gap += size;
      if (candles.length > RING) {
        candles.shift();
      }
    }
    candles.push({ t: t0, o: px, h: px, l: px, c: px, empty: false });
    while (candles.length > RING) {
      candles.shift();
    }
  }
}

function layout(w, h) {
  return {
    left: 4,
    right: w - 52,
    top: 8,
    bottom: h - 22,
  };
}

function yOf(px, lo, hi, box) {
  const span = Math.max(1e-9, hi - lo);
  return box.top + ((hi - px) / span) * (box.bottom - box.top);
}

export default function CandleChart({ bucketMs, onBucketMs }) {
  const wrapRef = useRef(null);
  const mainRef = useRef(null);
  const hairRef = useRef(null);
  const stateRef = useRef({
    prints: [],
    candles: [],
    ingested: new WeakSet(),
    viewLo: null,
    viewHi: null,
    dirty: false,
    raf: 0,
    size: bucketMs,
  });
  const [hover, setHover] = useState(null);

  useEffect(() => {
    stateRef.current.size = bucketMs;
    stateRef.current.candles = aggregate(stateRef.current.prints, bucketMs);
    stateRef.current.viewLo = null;
    stateRef.current.viewHi = null;
    stateRef.current.dirty = true;
    schedule();
  }, [bucketMs]);

  function schedule() {
    const st = stateRef.current;
    if (st.raf) return;
    st.raf = requestAnimationFrame(() => {
      st.raf = 0;
      if (!st.dirty) return;
      st.dirty = false;
      drawMain();
    });
  }

  function ingestPx(px, ts) {
    const st = stateRef.current;
    st.prints.push({ ts, px });
    if (st.prints.length > PRINT_CAP) {
      st.prints.splice(0, st.prints.length - PRINT_CAP);
    }
    updateLast(st.candles, px, ts, st.size);
    st.dirty = true;
    schedule();
  }

  useEffect(() => {
    const onSnap = () => {
      const snap = getSnapshot();
      const now = Date.now();
      const st = stateRef.current;
      for (let i = snap.trades.length - 1; i >= 0; i--) {
        const t = snap.trades[i];
        if (st.ingested.has(t)) continue;
        st.ingested.add(t);
        ingestPx(t.px, now);
      }
    };
    onSnap();
    return subscribe(onSnap);
  }, []);

  useEffect(() => {
    const wrap = wrapRef.current;
    if (!wrap) return;
    const ro = new ResizeObserver(() => {
      stateRef.current.dirty = true;
      schedule();
    });
    ro.observe(wrap);
    stateRef.current.dirty = true;
    schedule();
    return () => ro.disconnect();
  }, []);

  function fitAxis(candles) {
    const st = stateRef.current;
    let lo = Infinity;
    let hi = -Infinity;
    for (const c of candles) {
      if (c.empty) continue;
      lo = Math.min(lo, c.l);
      hi = Math.max(hi, c.h);
    }
    if (!Number.isFinite(lo)) return;
    const pad = Math.max(1, (hi - lo) * 0.02);
    st.viewLo = lo - pad;
    st.viewHi = hi + pad;
  }

  function maybeRescale(candles) {
    const st = stateRef.current;
    if (st.viewLo == null) {
      fitAxis(candles);
      return;
    }
    for (const c of candles) {
      if (c.empty) continue;
      if (c.l < st.viewLo || c.h > st.viewHi) {
        fitAxis(candles);
        return;
      }
    }
  }

  function sizeCanvas(canvas, wrap) {
    const dpr = window.devicePixelRatio || 1;
    const w = Math.max(1, wrap.clientWidth);
    const h = Math.max(1, wrap.clientHeight);
    const bw = Math.round(w * dpr);
    const bh = Math.round(h * dpr);
    if (canvas.width !== bw || canvas.height !== bh) {
      canvas.width = bw;
      canvas.height = bh;
    }
    canvas.style.width = `${w}px`;
    canvas.style.height = `${h}px`;
    const ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return { ctx, w, h };
  }

  function drawMain() {
    const wrap = wrapRef.current;
    const canvas = mainRef.current;
    if (!wrap || !canvas) return;
    const { ctx, w, h } = sizeCanvas(canvas, wrap);
    const hair = hairRef.current;
    if (hair) sizeCanvas(hair, wrap);

    const st = stateRef.current;
    const candles = st.candles;
    maybeRescale(candles);
    const lo = st.viewLo;
    const hi = st.viewHi;

    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = PAPER;
    ctx.fillRect(0, 0, w, h);

    const box = layout(w, h);
    ctx.strokeStyle = "#C9CCC6";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(box.left, box.top);
    ctx.lineTo(box.right, box.top);
    ctx.lineTo(box.right, box.bottom);
    ctx.lineTo(box.left, box.bottom);
    ctx.closePath();
    ctx.stroke();

    if (lo == null || !candles.length) {
      ctx.fillStyle = MUTED;
      ctx.font = "12px ui-monospace, monospace";
      ctx.fillText("waiting for prints", box.left + 8, (box.top + box.bottom) / 2);
      return;
    }

    const ticks = niceTicks(lo, hi);
    ctx.font = "11px ui-monospace, monospace";
    ctx.fillStyle = MUTED;
    ctx.textAlign = "left";
    ctx.textBaseline = "middle";
    for (const tick of ticks) {
      const y = yOf(tick, lo, hi, box);
      ctx.strokeStyle = "#C9CCC6";
      ctx.beginPath();
      ctx.moveTo(box.left, y);
      ctx.lineTo(box.right, y);
      ctx.stroke();
      ctx.fillStyle = MUTED;
      ctx.fillText(String(Math.round(tick * 10) / 10), box.right + 6, y);
    }

    const n = candles.length;
    const slot = (box.right - box.left) / RING;
    const bodyW = Math.max(1, slot * 0.7);
    const startX = box.right - n * slot;

    for (let i = 0; i < n; i++) {
      const c = candles[i];
      if (c.empty) continue;
      const x = startX + i * slot + slot / 2;
      const yO = yOf(c.o, lo, hi, box);
      const yC = yOf(c.c, lo, hi, box);
      const yH = yOf(c.h, lo, hi, box);
      const yL = yOf(c.l, lo, hi, box);
      const up = c.c >= c.o;
      ctx.strokeStyle = WICK;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(x, yH);
      ctx.lineTo(x, yL);
      ctx.stroke();
      const top = Math.min(yO, yC);
      const bot = Math.max(yO, yC);
      const bh = Math.max(1, bot - top);
      ctx.fillStyle = up ? UP : DOWN;
      ctx.fillRect(x - bodyW / 2, top, bodyW, bh);
    }

    ctx.fillStyle = MUTED;
    ctx.textAlign = "center";
    ctx.textBaseline = "top";
    for (let i = 0; i < n; i += 10) {
      const c = candles[i];
      const x = startX + i * slot + slot / 2;
      ctx.fillText(formatTime(c.t), x, box.bottom + 4);
    }
  }

  function drawHair(ev) {
    const wrap = wrapRef.current;
    const canvas = hairRef.current;
    if (!wrap || !canvas) return;
    const { ctx, w, h } = sizeCanvas(canvas, wrap);
    ctx.clearRect(0, 0, w, h);
    const rect = wrap.getBoundingClientRect();
    const x = ev.clientX - rect.left;
    const y = ev.clientY - rect.top;
    const box = layout(w, h);
    if (x < box.left || x > box.right || y < box.top || y > box.bottom) {
      setHover(null);
      return;
    }
    const st = stateRef.current;
    if (st.viewLo == null) return;
    const span = st.viewHi - st.viewLo;
    const px = st.viewHi - ((y - box.top) / (box.bottom - box.top)) * span;

    ctx.strokeStyle = INK;
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.moveTo(x, box.top);
    ctx.lineTo(x, box.bottom);
    ctx.moveTo(box.left, y);
    ctx.lineTo(box.right, y);
    ctx.stroke();
    ctx.setLineDash([]);

    const label = String(Math.round(px * 10) / 10);
    ctx.font = "11px ui-monospace, monospace";
    const tw = ctx.measureText(label).width;
    const lx = Math.min(box.right - tw - 8, x + 8);
    const ly = Math.max(box.top + 12, y - 6);
    ctx.fillStyle = PAPER;
    ctx.fillRect(lx - 2, ly - 11, tw + 6, 14);
    ctx.strokeStyle = "#C9CCC6";
    ctx.strokeRect(lx - 2, ly - 11, tw + 6, 14);
    ctx.fillStyle = INK;
    ctx.textAlign = "left";
    ctx.textBaseline = "alphabetic";
    ctx.fillText(label, lx, ly);
    setHover(label);
  }

  function clearHair() {
    const wrap = wrapRef.current;
    const canvas = hairRef.current;
    if (!wrap || !canvas) return;
    const { ctx, w, h } = sizeCanvas(canvas, wrap);
    ctx.clearRect(0, 0, w, h);
    setHover(null);
  }

  return (
    <div className="flex h-full min-h-[240px] flex-col">
      <div className="mb-2 flex items-center gap-1">
        {BUCKETS.map((b) => (
          <button
            key={b.id}
            type="button"
            onClick={() => onBucketMs(b.ms)}
            className={
              bucketMs === b.ms
                ? "h-7 rounded-[2px] border border-primary bg-primary px-2 text-xs text-primary-foreground"
                : "h-7 rounded-[2px] border border-border bg-surface-raised px-2 text-xs text-text"
            }
          >
            {b.label}
          </button>
        ))}
        {hover && <span className="ml-auto text-xs text-text-muted">px {hover}</span>}
      </div>
      <div ref={wrapRef} className="relative min-h-[220px] flex-1">
        <canvas ref={mainRef} className="absolute inset-0 block" />
        <canvas
          ref={hairRef}
          className="absolute inset-0 block"
          onPointerMove={drawHair}
          onPointerLeave={clearHair}
        />
      </div>
    </div>
  );
}
