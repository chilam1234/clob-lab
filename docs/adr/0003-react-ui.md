# ADR-0003: Trade UI frontend — React (supersedes ADR-0002 UI scope)

- Status: Accepted
- Date: 2026-10-04
- Deciders: Billy Chan (repo owner)
- Amends: docs/trade-ui-plan.md M2 ("no build step" dropped); ADR-0002
  server transport unchanged.

## Context

ADR-0002 specified a zero-build static JS UI so the repo would stay
"single gradle project". Billy has directed React instead — consistent
with his production stack (React/Next.js at Galaxy) and with M3+ needs
(latency panel, replay toggle, positions/PnL): state-dense UIs benefit
from component decomposition and hooks.

## Decision

**React 18+ with Vite, in `ui/` at the repo root**, built to static
assets that `GatewayServer` serves — no backend framework, no SSR.

- Toolchain: Vite (`ui/` package.json, vite.config with
  `build.outDir: "dist"`). Node/npm required only for UI work, not for
  the Java build — CI splits into a `ui` job (npm ci + build + upload
  artifact) and keeps Java jobs unchanged; `GatewayServer` falls back
  to a stub page when `ui/dist` is absent, so Java CI passes with or
  without a UI build.
- State: plain hooks (useState/useRef/useEffect) + one WS store
  module; no Redux/Zustand at this scope. L2 ladder and trades tape
  update via refs to avoid re-render storms on hot frames.
- Styling: plain CSS (the existing dark theme), noTailwind/UI-lib.
- Serving: `GatewayServer` maps `/` → `ui/dist/index.html`,
  `/assets/*` → built files; `docs/ui/*` files are superseded and get
  removed.

## Consequences

- UI dev flow: `cd ui && npm run dev` (Vite dev server with WS proxy to
  :8080) alongside `./gradlew runUi`.
- Adds `ui/` to .gitignore for `node_modules`, `dist`.
- CI gains ~30s `ui-build` job only when `ui/**` changed (path filter).
- M2 acceptance criteria unchanged; exit criteria still "two browser
  windows, one submits, other sees ladder move".

## Validation

- `npm run build` produces `ui/dist`; `./gradlew runUi` serves it;
  WS contract identical to ADR-0002 (frames unchanged).