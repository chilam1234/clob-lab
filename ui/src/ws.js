/**
 * WS store: single connection to the gateway, frames per ADR-0002.
 * Publishes hello/l2/trade/ack frames; consumers subscribe with useSyncExternalStore.
 * Seq-gap detection triggers a reconnect (resync) per the ADR reconnect policy.
 */
let socket = null;
let seq = 0;
let listeners = new Set();
let snapshot = {
  status: "connecting",
  bestBid: null,
  bestAsk: null,
  bids: [],
  asks: [],
  trades: [],
  ack: null,
  seqGap: false,
};

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function getSnapshot() {
  return snapshot;
}

function publish(patch) {
  snapshot = { ...snapshot, ...patch };
  for (const fn of listeners) fn();
}

export function sendOrder(order) {
  if (!socket || socket.readyState !== 1) {
    publish({ ack: { accepted: false, reason: "not connected" } });
    return;
  }
  socket.send(JSON.stringify(order));
}

export function connectWs() {
  if (socket) {
    socket.close();
    socket = null;
  }
  const proto = location.protocol === "https:" ? "wss" : "ws";
  socket = new WebSocket(`${proto}://${location.host}/ws?symbol=0`);

  socket.onopen = () => publish({ status: "connected", seqGap: false });
  socket.onclose = () => {
    publish({ status: "disconnected" });
    setTimeout(connectWs, 1000);
  };
  socket.onerror = () => publish({ status: "error" });
  socket.onmessage = (ev) => {
    let msg;
    try {
      msg = JSON.parse(ev.data);
    } catch {
      return; // malformed frame: ignore
    }
    switch (msg.t) {
      case "hello": {
        seq = msg.seq;
        publish({
          status: "connected",
          bestBid: msg.bid,
          bestAsk: msg.ask,
          bids: msg.bids || [],
          asks: msg.asks || [],
          seqGap: false,
        });
        return;
      }
      case "ack": {
        publish({ ack: msg });
        return;
      }
      case "l2": {
        if (msg.seq !== seq + 1) {
          publish({ seqGap: true });
          setTimeout(connectWs, 250);
        }
        seq = msg.seq;
        publish({
          bids: msg.bids || [],
          asks: msg.asks || [],
          bestBid: msg.bids?.length ? msg.bids[0].px : null,
          bestAsk: msg.asks?.length ? msg.asks[0].px : null,
        });
        return;
      }
      case "trade": {
        if (msg.seq !== seq + 1) {
          publish({ seqGap: true });
          setTimeout(connectWs, 250);
        }
        seq = msg.seq;
        publish({ trades: [msg, ...snapshot.trades].slice(0, 50) });
        return;
      }
      default:
        return;
    }
  };
}