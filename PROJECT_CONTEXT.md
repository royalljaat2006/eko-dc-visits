# PROJECT CONTEXT — Eko DC Visit App

**Last updated:** 2026-10-10 · **Runs on:** Eko R730 (self-hosted Docker, real calling-sheet data — no demo mode) · **Repo:** `eko-dc-visits/`

This is the single onboarding document for the project: what it is, how it was
designed, what exists, how it works, and what remains. A new engineer (or AI
agent) should be able to read this file and be productive the same day.

---

## 1. What This Is

**Eko's field-force management app for SBI Kiosk banking District Coordinators
(DCs).** DCs travel daily to CSPs (Customer Service Points — kiosk banking
shops) to audit, support, and verify them. The system provides:

- GPS-verified visit evidence (check-ins with advisory geofencing)
- Attendance (check-in/check-out, hours worked, 21:00 IST auto-close)
- Daily kilometres from GPS tracks (provisional, reimbursement-grade later)
- CSP ↔ DC assignment management (single transfers + bulk Excel upload)
- DC-proposed CSP master-data edits with Circle Head approval
- Gamified scorecards (transparent formula, never affects pay)
- Role-scoped dashboards for Circle Heads, National Head, HR, and Admin

**Client:** Eko (eko.in) — the BC company; multi-bank-ready (SBI is bank #1).
**Pilot:** select CSPs over weeks–months; Patna is the locked pilot district.
**Users' devices:** BYOD low-end Android phones; the current web app works on
mobile browsers; a native Kotlin app is the M1 target (its two hardest modules
already exist, see §10).

## 2. How We Got Here (project history)

1. **PRD → multi-agent design debate** (2026-07): three AI personas (Field
   Operations Realist, Principal Systems Architect, Open-Source Delivery Lead)
   ran two structured debate rounds over the PRD. Output: `../BUILD_PLAN.md`
   (v1.2) — architecture, 10-lane parallel execution plan, milestones M0–M3,
   risk register, and 18 locked decisions.
2. **Product-owner rulings**: all 14 open questions answered and locked
   (BUILD_PLAN §14). Highlights that are easy to get wrong later:
   - Mid-day **face challenges are VETOED** (policy, not deferred).
   - **KM = reimbursement money**, consumed via in-system claims reports —
     never payroll integration. Only **attendance** touches HRMS.
   - **BYOD**; **Patna pilot is a hard gate**; **Hindi + English**;
     **multi-BC-company tenancy from day one**.
3. **Design 0001** (`docs/design/0001-hierarchy-circles-multibank.md`):
   hierarchy became **DC → Circle Head → National Head** (+ HR/Admin);
   auto check-in/out via dwell-matching on the GPS stream; **Bank** as a
   first-class entity; **CspAssignment** (effective-dated, CH-owned).
4. **Design 0002** (`docs/design/0002-gamification.md`): dc_score_v1 —
   transparent points/streaks/badges, parity with managers, never pay-linked.
5. **CSP Visit Mobile App spec** (`docs/specs/csp-visit-mobile-app-draft.md`,
   from Ganesh Kumar / Circle 1A85): attendance-first DC navigation, 21:00 IST
   auto-checkout, CSP Details with Get Directions, DC-edit → CH approval
   workflow, per-user My Dashboard links. Implemented as contracts v0.8.0.
6. Built incrementally with contracts-first discipline. Moved from a Vercel demo to
   production on the R730; all demo data/mode was then removed (2026-10-10).

## 3. Core Doctrines (why the code looks the way it does)

1. **The phone is a witness that might be lying; the server is the judge.**
   Devices ship raw signals (GPS accuracy, mock flags, timestamps) unfiltered;
   the server derives judgments. Client checks are UX, never security.
2. **Capture immutably, judge separately.** Field evidence is **append-only**
   — no UPDATE path exists for anyone, including admins. Judgments are
   separate records; corrections are amendments. Back-dated records are locked
   by construction (stricter than the spec's "fully locked" requirement).
3. **Advisory geofencing (ADR-0004).** Out-of-radius check-ins are ACCEPTED
   and FLAGGED, never rejected — judged against
   `effective_radius = radius_m + GPS accuracy` (never punish the phone).
   Morning attendance location is logged, never gated. The complete hard-gate
   list lives in `contracts/c6-permissions/matrix.yaml` and is closed.
4. **Contracts are law.** Everything in `contracts/` (C1 schemas, C2 OpenAPI,
   C3 sync protocol, C6 permissions, C7 KPI formulas) versions as one set
   (currently **v0.8.0**); `npm run contracts:check` diffs implemented routes
   against the spec both ways and validates fixtures against schemas.
5. **Sync convergence.** Applying any permutation + duplication of sync
   batches converges to identical DB state (standing property test). All
   derived values (daily km, attendance status) are computed at READ time or
   via commutative merges so arrival order never matters.
6. **KM is money (ADR-0005).** Distance is `track_straightline_v0`,
   labeled PROVISIONAL everywhere; the OSRM road-snapped + dispute-workflow
   financial layer is M2. Nothing provisional may feed reimbursement.
7. **DC dignity floor.** DCs see the same numbers their managers see
   (parity rule); score formulas are printed in the UI; scores never feed pay.

## 4. Architecture

```
Web app (Vite/TS SPA, Eko-branded, works on mobile browsers)
        │  /api/* (typed client only — raw fetch is banned outside client.ts)
        ▼
API (Fastify, Docker on the R730, loopback-bound, behind the server Nginx)
        │
        ▼
Storage (ADR-0009 repository pattern — same interface, two impls):
  • PgRepos      — plain SQL, Postgres + PostGIS (production; migrations/ applied)
  • MemoryRepos  — automated tests and an EMPTY local-dev store only. The server
                   never seeds demo data; in production it refuses to start without
                   DATABASE_URL.
```

- **Backend:** Node 22 + TypeScript + Fastify. Auth: phone + OTP (dev stub
  real SMS via the Eko gateway, or `PILOT_OTP`; the `000000` stub is local-dev/tests only and disabled in production) → HS256 JWT + device registration.
  Authorization: ONE choke point (`src/scope.ts`) resolves tenant → role →
  visible DC/location sets; every scoped query takes that Scope object.
- **Sync (C3):** batches of ops (`visit.checkin`, `attendance.start/end`,
  `track.chunk`) with client UUIDv7 ids, per-device seq, triple timestamps
  (device wall / monotonic / server-received). Result codes: accepted /
  accepted-flagged / duplicate / quarantined (never silently dropped) /
  rejected. Payload `dc_user_id` must match the authenticated user; Circle
  Heads may submit only their own attendance.
- **Web:** vanilla-TS SPA, hash routing, role-aware tabs, 10 s polling
  (WebSockets deliberately killed for v1). Leaflet maps. SheetJS for Excel.
- **Android (`android/`):** pure-JVM `:core` Gradle module with the two
  hardest components already built + tested (17 tests): the C3 outbox state
  machine and the design-0001 auto check-in/out **dwell matcher** (no OS
  geofence API; matches the tracking stream against the on-device CSP list).
  The `:app` UI module is not started (needs Android SDK).

## 5. Repo Map

| Path | What it is |
|------|-----------|
| `../BUILD_PLAN.md` | The master plan (v1.2): decisions, lanes, milestones, risks, rulings |
| `contracts/` | **The law.** C1 entity JSON Schemas · C2 `openapi.yaml` · C3 `PROTOCOL.md` · C6 `matrix.yaml` (incl. the closed hard-gate list) · C7 `KPIS.md` (formulas) · `CHANGELOG.md` (v0.1.0 → v0.8.0) |
| `backend/` | Fastify API + sync engine + repos (memory/pg) + `migrations/001–005.sql` |
| `web/` | The SPA. `src/api/client.ts` (only network module) · `src/views/*` · `src/lib/*` |
| `android/` | Kotlin `:core` (outbox + dwell matcher), `./gradlew :core:test` on JDK 17 |
| `backend/test/fixtures/` | Synthetic TEST fixtures (invented people/places) used only by automated tests + `contracts:check`; never loaded by the server |
| `pilot-data/circle-1a85-roster.json` (gitignored) | REAL Circle 1A85 pilot DCs (spec §7) — personal data: pilot DB only, never committed |
| `docs/adr/` | ADRs 0001–0009 (locked decisions as records) |
| `docs/design/` | 0001 circles/multibank · 0002 gamification |
| `docs/specs/` | The CSP Visit Mobile App working draft (implemented) |
| `infra/DEPLOYMENT.md` | Deploy runbook + pilot go-live checklist |

## 6. Roles & What Each Sees (C6-scoped, enforced server-side)

| Role | Tabs / capabilities |
|------|---------------------|
| **DC** | **Attendance first — other tabs LOCKED until Check-In** (spec). My-day card: ✅ Check In / 🌙 End Day (browser GPS optional, logged never gated). Visits (own, map). **My CSPs**: assigned list w/ address, last-visit date, distance-from-here, 🧭 Get Directions (Google Maps universal link), **Suggest edit** → pending approval. Scorecard w/ 📊 My Dashboard link (own card only). |
| **Circle Head** | Own attendance (same flow). Circle-scoped: Visits, Attendance board (+ own row), **CSP Workbench** (single transfer + **Upload Excel** bulk assign w/ per-row accept/reject), **Approvals** (DC edits, old-vs-proposed side-by-side, approve applies / reject w/ reason), Scorecards. |
| **National Head** | Tenant-wide read: Overview cockpit, Visits, Attendance board (hours + provisional KM per DC), Scorecards. Cannot write evidence. |
| **HR/Admin** | Attendance ONLY (PII minimisation — zero visit/photo visibility, tested). |
| **Corporate Admin** | Everything: Overview (attendance/visit/CSP-coverage rollups, per-circle + per-DC tables, per-bank counts), all tabs incl. Workbench + Approvals. |

Accounts are real: DCs come from the calling sheet (name + mobile); admin / Circle Head /
National Head / HR accounts are created with `npm run user:create`.

## 7. Key Mechanics (quick reference)

- **Attendance:** START/END are evidence ops; the day derives at read time —
  `NOT_STARTED / ON_DUTY / ENDED / AUTO_CLOSED` (no End Day by **21:00 IST**
  → auto-closed, "not confirmed by user", hours capped at cutoff). Hours =
  end − start (0.1 h). Commutative earliest-START/latest-END merges make
  late/out-of-order sync converge.
- **Daily KM:** `track.chunk` GPS points → read-time haversine sum, teleports
  (>800 km/h) excluded → shown on Attendance board + Overview as
  "KM today (provisional)".
- **Geofence:** INSIDE vs OUTSIDE_FLAGGED against accuracy-inflated radius;
  out-of-radius requires a reason code from the client UX but is never
  rejected server-side.
- **CSP assignments:** effective-dated; transfer = end-old(yesterday) +
  start-new(today) in one audited step; at most one active assignment per CSP
  (partial unique index). Bulk upload: rows of `csp_code, dc_phone` (xlsx/csv,
  parsed in-browser; header aliases + phone normalization; 500-row cap).
- **Change requests:** whitelisted fields (name/address/lat/lng + §3.1
  profile fields) → PENDING with old-value snapshot → CH/Admin decision;
  approval updates the master (coords → FIELD_CAPTURED confidence);
  rejection carries a reason; decisions are final.
- **Scorecard (dc_score_v1):** visit +50 · geo-verified +20 · on-time start
  (≤09:30 IST) +30 · streak-day +10 (cap 7). Badges: Early Bird (≤09:00),
  Perfect Day (≥3 all-INSIDE), 3/7-day streaks.
- **Master data bootstrap:** no authoritative SBI feed exists; CSP
  coordinates carry `coordinate_confidence` (UNVERIFIED → FIELD_CAPTURED →
  VERIFIED) and improve via approved DC edits / first-visit capture.

## 8. Deployment & Operations

- **Where:** Eko R730, Docker stack `infra/self-hosted/` (postgres+PostGIS + api, loopback `127.0.0.1:8210`),
  behind the server's Nginx. Runbook, update + rollback: `infra/DEPLOYMENT.md`; data loading:
  `infra/self-hosted/README.md`. Backups: `/home/deepanshu/backups/dc-visits/<timestamp>/`.
- **No demo mode.** No demo seed, no demo accounts, no `000000` OTP in production. The real roster
  and calling sheet live in the gitignored `pilot-data/` and are loaded with `seed:pilot` /
  `seed:calling-sheet`.
- **Go-live checklist:** see `infra/DEPLOYMENT.md` (real SMS OTP + rate limiting, fresh `JWT_SECRET`/`PILOT_OTP`,
  India-resident hosting per DPDP).

## 9. Quality State (as of last commit)

- Backend: **34/34 tests** (sync convergence property test, scoping matrix,
  geofence/effective-radius, quarantine, auto-close, change-request
  lifecycle, impersonation hardening, KM teleport filter, scorecard formula).
- Web: **12/12 tests**, typecheck + build green.
- Android `:core`: **17/17 JVM tests** (outbox invariants, dwell scenarios).
- `contracts:check` green (routes ⇄ spec both directions; fixtures ⇄ schemas).
- Every feature was additionally **verified live in a browser** and the
  production re-verified after each deploy (health, DB counts, auth-gated routes).
- CI (GitHub Actions config in `.github/workflows/ci.yml`) covers backend,
  contracts, web, android-core — note: repo has no GitHub remote yet.

## 10. Done vs Remaining

**Done:** everything in §6–§7, Eko branding with the real logo, admin
Overview cockpit, bulk Excel assignment, change-request approvals, gamified
scorecards, daily-KM visibility, R730 production deployment on real data, the
CSP-Visit-Mobile-App spec (v0.8.0) end to end.

**Remaining (in rough priority order):**
1. **Admin portal on the R730** — deploy the web build behind Nginx (`/dc-visits/`), create the first real admin
   (`user:create`), and fill the 501 CSP addresses still blank in the calling sheet.
2. **Android `:app` module** — wire the existing `:core` outbox + dwell
   matcher to Room persistence, foreground tracking service (Start→End Day
   only), CameraX + watermark photo pipeline (contracts C4 to be authored).
   Needs a machine with Android Studio/SDK; JDK-17 toolchain already set up.
3. **Real OTP via SMS gateway** + rate limiting (pilot hardening).
4. **M2 financial layer** — OSRM road-snapped KM, gap annotation, KM dispute
   workflow, month-end freeze (only then may reimbursement consume KM).
5. Photos/checklists/checkout ops (C3 already reserves the op names), leave/
   holiday calendar, HRMS attendance export, bank bulk-import pipeline,
   deferred spec items (home-location Excel + geofence question; P1 bulk
   CSP-details update).
6. M3: allocation optimizer (OSRM + clustering), drag-drop territory map,
   fraud analytics v1 (rules listed in BUILD_PLAN §7.6).

## 11. Provenance

Planned and built in a Claude Code session series (origin session
`6fd6a088-…`) via the BUILD_PLAN's own methodology: a 3-persona / 2-round
design debate, contracts-first parallel lanes, self-verifying tickets, and
browser-verified increments. Product-owner decisions were given by
Shantanu Upadhyay (Eko) and are recorded in BUILD_PLAN §1/§14 — treat those
tables as the source of truth; do not reopen locked decisions without the
product owner asking.
