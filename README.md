# Eko DC Visit Management System

Field-visit management for Eko's SBI Kiosk District Coordinators (DCs): GPS-verified visits,
geo-tagged photo evidence, offline-first Android capture, KM/reimbursement tracking, and
audit-grade records — designed for 1,000+ DCs nationally.

The full plan (architecture, lanes, milestones, locked decisions) lives in
[`../BUILD_PLAN.md`](../BUILD_PLAN.md). This repo is its execution.

## System shape

```
Android app (Kotlin, offline-first)      Web (admin + AM/RM/State dashboards)
        │ signed sync batches                     │ polling reads
        ▼                                         ▼
   API service (Node/TS, stateless) ── pre-signed URLs ──► Object storage (media)
        │ transactional writes + pg-boss jobs
        ▼
   Postgres + PostGIS  ◄── Worker service (rollups, distance legs, fraud rules, reports)
                            └── OSRM (self-hosted routing/map-matching)
```

Doctrines (see ADRs): the phone is a witness that might be lying — the server is the judge;
evidence is append-only, judged by annotation, corrected by amendment, never edited.

## Territories

| Directory | Purpose |
|-----------|---------|
| `contracts/` | **The law.** C1 entity schemas, C2 OpenAPI, C3 sync protocol, C4 watermark, C5 form templates, C6 permissions, C7 KPI formulas. Nothing here depends on anything else; everything depends on this. |
| `backend/` | API service + workers + Postgres migrations (npm workspace). |
| `android/` | Kotlin DC app (own Gradle project; requires Android SDK to build). |
| `web/` | Admin portal + dashboards (one role-gated app). |
| `fixtures/` | Synthetic-India fixture universe + seed scripts. Shared by every territory's tests. |
| `infra/` | docker-compose dev stack, CI definitions. |
| `docs/` | ADRs, runbooks, milestone notes. |

## Run locally (M0 walking skeleton)

There is no demo data. Real data comes from the calling sheet (gitignored `pilot-data/`).

```sh
docker compose -f infra/docker-compose.yml up -d   # Postgres+PostGIS
cd backend && npm install && npm run migrate
npm run seed:calling-sheet -- ../pilot-data/calling-sheet.csv   # CSPs, DCs, circles, assignments
npm run user:create -- --name "Full Name" --phone 9XXXXXXXXX --role CORPORATE_ADMIN
npm run dev
cd web && npm install && npm run dev               # admin portal on :5173
```

Without a database (`DATABASE_URL` unset) the backend starts EMPTY in memory — for
development only; set `CALLING_SHEET_CSV=../pilot-data/calling-sheet.csv` to load the real sheet.
Tests use synthetic fixtures in `backend/test/fixtures/` — `cd backend && npm install && npm test`.

## Full context

Read [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) first — history, doctrines, roles, mechanics, deployment, and what remains, in one file.

## Status

Running in production on the R730 (self-hosted Docker stack) with real calling-sheet data. Backend 50 tests, web 12 tests, `contracts:check` green. Contract set v0.13.0 (see `contracts/CHANGELOG.md`).
