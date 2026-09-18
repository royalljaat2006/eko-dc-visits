# Eko DC Visit App — agent notes

Full context: `PROJECT_CONTEXT.md` (read only when you need the "why"; don't load it for routine edits).
Architecture + locked decisions: `../BUILD_PLAN.md`. Designs: `docs/design/`, `docs/specs/`.

## Layout (npm workspaces, run commands from the package dir)

- `web/` — Vite + TS dashboard (Lane E). The real frontend.
- `backend/` — Fastify + pg API core (Lanes A/B). The real backend.
- `api/` — Vercel deploy shell. `api/index.js` is a **generated** esbuild bundle — never edit it; regenerate with `npm run bundle:function` after backend changes.
- `contracts/` — versioned API contracts (contracts-first: change here before code).
- `android/` — Kotlin native app (M1). 260 MB with build output — do not grep/scan it wholesale.

## Commands

- web: `npm --prefix web run dev` · `build` · `typecheck` · `test`
- backend: `cd backend && npm run dev` · `typecheck` · `test` · `migrate` · `seed:pilot` · `contracts:check`
- deploy bundle: `npm run bundle:function` (root)

## Doctrines (don't violate — see PROJECT_CONTEXT §3)

- Field evidence is **append-only**; no UPDATE path for anyone. Corrections are new amendment records.
- Client checks are UX only; the server is the judge. Out-of-radius check-ins are accepted + flagged, never rejected.
- KM → reimbursement claims only, never payroll. Only attendance touches HRMS.
- Multi-BC-company tenancy from day one; Hindi + English; BYOD low-end Android.

## Token hygiene

- Don't read: `node_modules/`, `android/` build output, `api/index.js`, `package-lock.json`.
- Prefer `typecheck` / targeted `node --test` files over full builds when checking work.
