import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import CandleChart from "./CandleChart.jsx";
import { connectWs, getSnapshot, sendOrder, subscribe } from "./ws.js";

function statusClass(status) {
  if (status === "connected") return "border-primary text-primary";
  if (status === "connecting") return "border-border text-text-muted";
  return "border-accent-alert text-accent-alert";
}

function Ladder({ bids, asks, bestBid, bestAsk }) {
  const pad = (rows) => {
    const out = rows.slice(0, 5);
    while (out.length < 5) out.push(null);
    return out;
  };
  const askRows = pad([...asks].reverse());
  const bidRows = pad(bids);
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>Ladder</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="mb-2 flex items-baseline justify-between font-[ui-monospace,monospace] [font-variant-numeric:tabular-nums]">
          <span className="text-up">{bestBid ?? "—"}</span>
          <span className="text-xs text-text-muted">
            {bestBid != null && bestAsk != null ? `spread ${bestAsk - bestBid}` : "BBO"}
          </span>
          <span className="text-down">{bestAsk ?? "—"}</span>
        </div>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>px</TableHead>
              <TableHead>qty</TableHead>
              <TableHead>n</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {askRows.map((l, i) => (
              <TableRow key={`a${i}`}>
                <TableCell>{l ? l.px : "—"}</TableCell>
                <TableCell className="text-down">{l ? l.qty : ""}</TableCell>
                <TableCell>{l ? l.n : ""}</TableCell>
              </TableRow>
            ))}
            <TableRow>
              <TableCell colSpan={3} className="py-1 text-center text-xs text-text-muted">
                — spread —
              </TableCell>
            </TableRow>
            {bidRows.map((l, i) => (
              <TableRow key={`b${i}`}>
                <TableCell>{l ? l.px : "—"}</TableCell>
                <TableCell className="text-up">{l ? l.qty : ""}</TableCell>
                <TableCell>{l ? l.n : ""}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function OrderEntry({ ack }) {
  const [type, setType] = useState("limit");
  const [side, setSide] = useState("BUY");
  const counter = useRef(1000);
  const priceRef = useRef();
  const qtyRef = useRef();
  const cancelIdRef = useRef();

  const submit = (ev) => {
    ev.preventDefault();
    if (type === "cancel") {
      sendOrder({ op: "cancel", orderId: Number(cancelIdRef.current?.value) });
      return;
    }
    const qty = Number(qtyRef.current?.value);
    const orderId = side === "BUY" ? counter.current : counter.current + 1;
    counter.current += 2;
    const order = { op: type, side, qty, orderId };
    if (type !== "market") {
      order.px = Number(priceRef.current?.value);
    }
    sendOrder(order);
  };

  const sellish = type === "cancel" || side === "SELL";

  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>Order entry</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="flex flex-col gap-2" onSubmit={submit}>
          <div className="grid grid-cols-2 gap-2">
            <Select value={type} onChange={(e) => setType(e.target.value)}>
              <option value="limit">limit</option>
              <option value="market">market</option>
              <option value="ioc">ioc</option>
              <option value="fok">fok</option>
              <option value="cancel">cancel</option>
            </Select>
            <Select
              value={side}
              onChange={(e) => setSide(e.target.value)}
              disabled={type === "cancel"}
            >
              <option value="BUY">BUY</option>
              <option value="SELL">SELL</option>
            </Select>
          </div>
          {type !== "market" && type !== "cancel" && (
            <Input ref={priceRef} type="number" placeholder="price (ticks)" required />
          )}
          {type !== "cancel" && (
            <Input ref={qtyRef} type="number" placeholder="quantity" required />
          )}
          {type === "cancel" && (
            <Input ref={cancelIdRef} type="number" placeholder="order id to cancel" required />
          )}
          <Button
            type="submit"
            variant={sellish ? "destructive" : "default"}
            className={sellish ? "bg-tertiary hover:bg-down" : ""}
          >
            {type === "cancel" ? "cancel order" : `submit ${side}`}
          </Button>
        </form>
        <div
          className={
            ack
              ? ack.accepted
                ? "mt-2 text-xs text-up"
                : "mt-2 text-xs text-accent-alert"
              : "mt-2 text-xs text-text-muted"
          }
        >
          {ack
            ? ack.accepted
              ? `accepted (seq ${ack.seq})`
              : `REJECTED: ${ack.reason || "unknown"}`
            : "awaiting ack"}
        </div>
      </CardContent>
    </Card>
  );
}

function Tape({ trades }) {
  const vwapQty = trades.reduce((a, t) => a + t.qty, 0);
  const vwap =
    vwapQty > 0
      ? (trades.reduce((a, t) => a + t.px * t.qty, 0) / vwapQty).toFixed(1)
      : "—";
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>Trades</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>px</TableHead>
              <TableHead>qty</TableHead>
              <TableHead>taker</TableHead>
              <TableHead>maker</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {trades.map((t, i) => (
              <TableRow key={`${t.seq}-${i}`}>
                <TableCell className={t.taker % 2 === 0 ? "text-up" : "text-down"}>
                  {t.px}
                </TableCell>
                <TableCell>{t.qty}</TableCell>
                <TableCell>{t.taker}</TableCell>
                <TableCell>{t.maker}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <div className="mt-2 text-xs text-text-muted">
          {trades.length} trades · vol {vwapQty} · vwap {vwap}
        </div>
      </CardContent>
    </Card>
  );
}

export default function App() {
  const snap = useSyncExternalStore(subscribe, getSnapshot);
  const [bucketMs, setBucketMs] = useState(1000);

  useEffect(() => {
    connectWs();
  }, []);

  return (
    <div className="min-h-svh bg-background text-text">
      <header className="flex items-baseline gap-4 border-b border-border px-4 py-3">
        <h1 className="font-masthead text-masthead font-bold tracking-masthead text-text">
          clob-lab
        </h1>
        <span className="text-sm text-text-muted">symbol 0</span>
        <span
          className={`ml-auto rounded-[2px] border px-2 py-0.5 text-xs ${statusClass(snap.status)}`}
        >
          {snap.status}
        </span>
      </header>
      {snap.seqGap && (
        <div className="border-b border-accent-alert bg-surface-raised px-4 py-2 text-sm text-accent-alert">
          sequence gap detected — resyncing…
        </div>
      )}
      <main className="grid grid-cols-1 gap-3 p-3 lg:grid-cols-3">
        <Ladder bids={snap.bids} asks={snap.asks} bestBid={snap.bestBid} bestAsk={snap.bestAsk} />
        <OrderEntry ack={snap.ack} />
        <Tape trades={snap.trades} />
        <Card className="lg:col-span-3">
          <CardHeader>
            <CardTitle>Candles</CardTitle>
          </CardHeader>
          <Separator />
          <CardContent>
            <CandleChart bucketMs={bucketMs} onBucketMs={setBucketMs} />
          </CardContent>
        </Card>
      </main>
    </div>
  );
}
