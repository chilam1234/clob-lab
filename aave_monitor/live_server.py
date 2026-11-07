"""HTTP + SSE live risk feed for the Aave whale monitor demo.

Endpoints:
  GET /health
  GET /api/aave/snapshot?whale=0x...
  GET /api/aave/whales
  GET /api/aave/history?whale=0x...
  GET /api/aave/stream?whale=0x...   (text/event-stream)

Run:
  PYTHONPATH=. python3 -m aave_monitor.live_server --port 8787
"""

from __future__ import annotations

import argparse
import json
import threading
import time
import traceback
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Deque, Dict, Optional
from urllib.parse import parse_qs, urlparse

from .live_feed import DEFAULT_WHALE, RAZOR_WHALE, discover_active_whales, fetch_live_stream

HISTORY: Dict[str, Deque[Dict[str, Any]]] = defaultdict(lambda: deque(maxlen=120))
CACHE: Dict[str, Dict[str, Any]] = {}
CACHE_LOCK = threading.Lock()
POLL_SECONDS = 12.0


def _normalize_whale(raw: Optional[str]) -> str:
    if not raw:
        return DEFAULT_WHALE
    w = raw.strip()
    if w.lower() == "razor":
        return RAZOR_WHALE
    if not w.startswith("0x"):
        w = "0x" + w
    return w


def _record(frame: Dict[str, Any]) -> None:
    whale = frame["stream"]["whale_address"]
    point = {
        "ts": frame["ts"],
        "block_number": frame["block_number"],
        "baseline_hf": frame["assessment"]["baseline_hf"],
        "effective_hf": frame["assessment"]["effective_hf"],
        "oracle_arbitrage_risk": frame["assessment"]["oracle_arbitrage_risk"],
        "pending_utilization": frame["stream"]["pending_utilization"],
        "system_verdict": frame["assessment"]["system_verdict"],
        "wsteth_oracle_usd": frame["prices"]["wsteth_oracle_usd"],
        "wsteth_offchain_usd": frame["prices"]["wsteth_offchain_usd"],
    }
    with CACHE_LOCK:
        HISTORY[whale.lower()].append(point)
        CACHE[whale.lower()] = frame


def refresh_whale(whale: str) -> Dict[str, Any]:
    frame = fetch_live_stream(whale_address=whale)
    _record(frame)
    return frame


def poller(whales: list[str], interval: float) -> None:
    while True:
        for whale in whales:
            try:
                refresh_whale(whale)
                print(f"[live] refreshed {whale} block={CACHE[whale.lower()]['block_number']}", flush=True)
            except Exception as exc:  # noqa: BLE001
                print(f"[live] refresh failed {whale}: {exc}", flush=True)
                traceback.print_exc()
        time.sleep(interval)


class Handler(BaseHTTPRequestHandler):
    server_version = "AaveLiveRisk/1.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[http] {self.address_string()} {fmt % args}", flush=True)

    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _json(self, code: int, payload: Any) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        if path in ("/health", "/api/aave/health"):
            self._json(200, {"ok": True, "default_whale": DEFAULT_WHALE, "razor_whale": RAZOR_WHALE})
            return

        if path == "/api/aave/whales":
            try:
                whales = discover_active_whales()
                self._json(200, {"whales": whales, "default": DEFAULT_WHALE, "razor": RAZOR_WHALE})
            except Exception as exc:  # noqa: BLE001
                self._json(500, {"error": str(exc)})
            return

        if path == "/api/aave/history":
            whale = _normalize_whale((qs.get("whale") or [None])[0])
            with CACHE_LOCK:
                hist = list(HISTORY.get(whale.lower(), []))
            self._json(200, {"whale": whale, "points": hist})
            return

        if path == "/api/aave/snapshot":
            whale = _normalize_whale((qs.get("whale") or [None])[0])
            force = (qs.get("refresh") or ["0"])[0] in ("1", "true", "yes")
            try:
                with CACHE_LOCK:
                    cached = CACHE.get(whale.lower())
                if force or cached is None:
                    frame = refresh_whale(whale)
                else:
                    frame = cached
                with CACHE_LOCK:
                    hist = list(HISTORY.get(whale.lower(), []))
                out = dict(frame)
                out["history"] = hist
                self._json(200, out)
            except Exception as exc:  # noqa: BLE001
                self._json(500, {"error": str(exc), "whale": whale})
            return

        if path == "/api/aave/stream":
            whale = _normalize_whale((qs.get("whale") or [None])[0])
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self._cors()
            self.end_headers()
            try:
                while True:
                    try:
                        frame = refresh_whale(whale)
                        with CACHE_LOCK:
                            hist = list(HISTORY.get(whale.lower(), []))
                        payload = dict(frame)
                        payload["history"] = hist
                        msg = f"event: risk\ndata: {json.dumps(payload)}\n\n"
                    except Exception as exc:  # noqa: BLE001
                        msg = f"event: error\ndata: {json.dumps({'error': str(exc)})}\n\n"
                    self.wfile.write(msg.encode())
                    self.wfile.flush()
                    time.sleep(POLL_SECONDS)
            except (BrokenPipeError, ConnectionResetError):
                return

        self._json(404, {"error": "not found", "path": path})


def main() -> None:
    parser = argparse.ArgumentParser(description="Aave live risk SSE server")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--interval", type=float, default=POLL_SECONDS)
    parser.add_argument(
        "--whale",
        action="append",
        default=[],
        help="Whale address to pre-poll (repeatable). Defaults to DEFAULT + razor.",
    )
    args = parser.parse_args()

    whales = args.whale or [DEFAULT_WHALE, RAZOR_WHALE]
    # Warm cache once before serving.
    for w in whales:
        try:
            refresh_whale(w)
            print(f"[live] warm cache ok {w}", flush=True)
        except Exception as exc:  # noqa: BLE001
            print(f"[live] warm cache failed {w}: {exc}", flush=True)

    t = threading.Thread(target=poller, args=(whales, args.interval), daemon=True)
    t.start()

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[live] serving on http://{args.host}:{args.port}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
