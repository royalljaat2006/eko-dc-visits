# Eko DC Visit App — agent notes

Full context: `PROJECT_CONTEXT.md` (read only when you need the "why"; don't load it for routine edits).
Architecture + locked decisions: `../BUILD_PLAN.md`. Designs: `docs/design/`, `docs/specs/`.

## Layout (npm workspaces, run commands from the package dir)

- `web/` — Vite + TS dashboard (Lane E). The real frontend.
- `backend/` — Fastify + pg API core (Lanes A/B). The real backend.
- `contracts/` — versioned API contracts (contracts-first: change here before code).
- `android/` — Kotlin native app (M1). 260 MB with build output — do not grep/scan it wholesale.

## Commands

- web: `npm --prefix web run dev` · `build` · `typecheck` · `test`
- backend: `cd backend && npm run dev` · `typecheck` · `test` · `migrate` · `seed:pilot` · `contracts:check`
- real data: `npm run seed:calling-sheet -- <csv>` · `seed:pilot` · `user:create` (all need `DATABASE_URL`)
- production: R730 self-hosted Docker stack (`infra/self-hosted/`); there is **no demo data or demo mode** — test fixtures live only in `backend/test/`

## Doctrines (don't violate — see PROJECT_CONTEXT §3)

- Field evidence is **append-only**; no UPDATE path for anyone. Corrections are new amendment records.
- Client checks are UX only; the server is the judge. Out-of-radius check-ins are accepted + flagged, never rejected.
- KM → reimbursement claims only, never payroll. Only attendance touches HRMS.
- Multi-BC-company tenancy from day one; Hindi + English; BYOD low-end Android.

## Token hygiene

- Don't read: `node_modules/`, `android/` build output, `package-lock.json`, `pilot-data/` (real personal data, gitignored).
- Prefer `typecheck` / targeted `node --test` files over full builds when checking work.
