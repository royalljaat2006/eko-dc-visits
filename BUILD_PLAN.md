# SBI Kiosk DC Visit Management System — Build Plan

**Version 1.2 — 2026-07-09** *(v1.1: all 14 open questions answered and folded in — see §14. v1.2: org hierarchy redesigned to DC → Circle Head → National Head with auto check-in/out and multi-bank support — see eko-dc-visits/docs/design/0001-hierarchy-circles-multibank.md, which supersedes the AM/RM/State-Head role model in this document.)*

This plan was produced by a three-persona, two-round design debate (Field Operations Realist, Principal Systems Architect, Open-Source Delivery Lead) over the PRD, then synthesized with product-owner rulings. It is written to be executed by parallel AI coding agents in independent lanes. General architecture only — no code structures are prescribed.

---

## 1. Locked Decisions (do not relitigate — these become ADR-0001…)

| # | Decision | Ruling |
|---|----------|--------|
| 1 | Scale | National from day one: architecture for 5,000 DCs, launch at 1,000+ |
| 2 | Mobile | Native Kotlin Android |
| 3 | Backend | Node/TypeScript + Postgres (PostGIS). **Postgres-only backbone**: no Kafka, no Mongo, no OLAP engine, no mandatory Redis. pg-boss (Postgres-based) for async jobs. Object storage for media. |
| 4 | Paid APIs | Hybrid, OSS-first: self-hosted OSRM (OSM India extract) for routing/map-matching; on-device ML Kit for face; paid only for SMS-OTP (enrollment) and push (FCM). |
| 5 | Devices | **BYOD** — DCs' personal ₹8–15k Android phones (2–4GB RAM, Android 10+). App-level wipe only; battery frugality is existential; consent posture strict. |
| 6 | KM = money | **Yes** — distance drives travel reimbursement. Distance is a first-class financial subsystem (§7.3). |
| 7 | Pilot | **Hard gate.** ~20 DCs, one deliberately hard district, 6–8 weeks incl. one full monthly reporting + reimbursement cycle, written exit criteria. No national rollout spend before pilot exit. |
| 8 | Languages | Hindi + English for the DC app at launch (multilingual string architecture from M0; regional languages added per rollout state). Dashboards English. |
| 9 | Repo | One monorepo, trunk-based, contracts as CI-enforced executable artifacts, open-source quality throughout (§12). |
| 10 | Compliance | DPDP Act; all data (incl. backups, vendor processing) resident in India. |
| 11 | Client & tenancy | This is **Eko's** field-team app (DCs are Eko's workforce). **Multi-BC-company future confirmed**: the schema, contracts (C1/C6), and the authz choke point are tenant-scoped from day one; v1 operates single-tenant (Eko). |
| 12 | Face challenges | **Vetoed by product owner** — no mid-day biometric spot-checks. Attendance face verification (start/end day) stays. See §7.4 for compensating controls and residual risk. |
| 13 | Pilot district | **Patna** — urban-dense + rural mix, real Bihar connectivity conditions. |
| 14 | Integrations | No SBI SSO — standalone portal logins for SBI officials. Payroll/HRMS integration is **attendance-only** (export + attendance dispute workflow). KM reimbursement is consumed via in-system month-end claims reports, never via payroll integration. |
| 15 | Hosting | No bank-empanelment mandate — any India-region cloud; managed Postgres approved. |
| 16 | Master data | **No authoritative SBI CSP feed exists.** Coordinates are bootstrapped and verified via first-visit capture + a correction workflow; pilot-district cleanup is planned work, not a surprise. |
| 17 | Enforcement | Compliance consequences begin only after exception workflows are **field-proven in the pilot** — dashboards may show red before red carries consequences. |
| 18 | Support | No L1 helpdesk — AMs are the default support line, so the app's **self-service diagnostics screen is M1 scope**. Visit-duration norms per visit type will be supplied by ops (feeds dwell rules). Photo mandates are backed by CSP contracts (refusal flow stays but escalation is contractual). Retention uses our proposed defaults (§7.11) pending legal. |

---

## 2. System Thesis

> **This is an audit-grade evidence pipeline with a field workflow app bolted on — and the workflow app is what the DC touches 200 times a day.** Evidence-grade backend, field-grade frontend; both are first-class.

Two doctrines govern every design decision:

1. **The phone is a witness that might be lying; the server is the judge.** The device ships every raw signal (GPS accuracy, mock flags, battery, integrity verdicts, gap causes) to the server unfiltered and signed. On-device checks deter the lazy 80% of fraud; server-side correlation catches the clever 15%; human pattern review handles the last 5%.
2. **Capture immutably, judge separately, correct via amendment.** Field evidence is append-only. Admin/manager judgments are separate annotation records, never mutations. Corrections are attributable amendments. No UPDATE path exists on evidence — not for admins, not for DBAs.

---

## 3. Architecture Overview

### 3.1 Services (modular monolith + workers)

```
Android app (Kotlin, offline-first)          Web app (admin + dashboards, role-gated)
        │  signed sync batches / heartbeat            │  polling reads (10s live)
        ▼                                             ▼
   ┌─────────────────── API service (Node/TS, stateless, N replicas) ───────────────────┐
   │  auth · sync ingest · master-data · dashboard reads · report requests              │
   └──────┬──────────────────────────────────────────────────────────────┬──────────────┘
          │ transactional writes + job enqueue (pg-boss)                 │ pre-signed URLs
          ▼                                                              ▼
   Postgres + PostGIS (partitioned, managed, read replica)        Object storage (S3-compat,
          ▲                                                        India region, lifecycle tiers)
          │
   Worker service (same codebase, separate entrypoint):
   photo hash-verify/derivatives · OSRM map-matching · distance legs · anomaly rules ·
   rollups · reports (Excel/PDF) · notifications · audit-chain anchoring
          │
   OSRM ×2 (self-hosted, India extract, monthly refresh pipeline — owned ops task)
```

- **Scale math (why Postgres suffices):** 5,000 DCs × ~1,000 adaptive GPS points/day = 5M rows/day ≈ 58 writes/sec average; evening coverage-regain herds peak at ~5–10× that (~350–600/sec) — trivial for partitioned Postgres with batched inserts. GPS ≈ 220GB/yr at ceiling (monthly partitions, BRIN, archive to Parquet after hot window). Photos ≈ 40/DC/day at 150–300KB ≈ up to ~15–20TB/yr at ceiling in object storage. Any proposal to add a second datastore requires pilot-measured evidence.
- **Ingest hot path has no queue** — batched HTTPS upload → validate → one transaction → ack. The client's local journal is the durable buffer. Queues (pg-boss) are for async work only, enqueued transactionally with the triggering write.
- **Media never proxies through the API**: metadata syncs in-batch; binaries go direct to object storage via short-lived pre-signed resumable uploads, content-addressed by SHA-256, hash-verified server-side.
- **Live tracking is decoupled from the audit track**: a separate cheap, lossy heartbeat endpoint upserts a last-known-position hot table (one row per DC). Dashboards poll it at 10 seconds. WebSockets are killed for v1. The durable track (tier-3 sync) may be a day late; the live dot may be lossy; they never share a pipeline.

### 3.2 Android app (general shape)

- **Local store is the system of record until server ack.** Room/SQLite append-only event journal; every user action commits transactionally *before* the UI confirms. UI reads only the local store — indistinguishable online vs offline. Crash-resume to exact context in <3s. SQLCipher at rest, Keystore-held keys, app-private storage only (no gallery path exists at all).
- **Outbox sync engine** with priority tiers (§6.2), resumable uploads, jittered exponential backoff, honoring server `Retry-After`. Downstream (beat plans, master data, form templates, remote config) is versioned delta-pull, prefetched aggressively — *everything tomorrow needs is on the phone tonight*. On-device storage sized for **5 days of full offline activity** (government network shutdowns happen). Synced media auto-purged after N days.
- **Tracking**: foreground service (not WorkManager) bound to the attendance session, honest persistent notification ("On duty — tracking active"), **hard start at Start Day, hard stop at End Day** (DPDP + trust invariant). Adaptive sampling: ~15–30s moving (activity-recognition driven), slow/suspended when stationary with motion-triggered resume, tightened near planned stops for a warm fix at check-in. Every tracking gap carries a cause code (`OS_KILL / BATTERY_SAVER / PERMISSION_REVOKED / NO_FIX / NO_NETWORK`) + battery level at gap start. **Battery budget ≤15% per 10-hour day on named reference devices is a milestone exit gate; sampling intervals bend to the budget, never vice versa.** OEM "protect this app" guided onboarding (autostart whitelist, battery-optimization exemption for MIUI/FuntouchOS/One UI) ships as a first-run flow in M1.
- **Camera pipeline**: in-app CameraX only → per-category compression (ambience ~150–300KB; QR/device/branding shots up to ~500KB for audit zoom) → watermark burned into pixels (date/time+tz, lat/lng±accuracy, CSP code, branch code, DC name, device ID) → **plus a signed sidecar** (SHA-256 of image, monotonic timestamp, GPS fix, mock flag — signed with the Keystore device key at capture). Capture-to-saved <2s. Original + watermarked derivative both retained server-side; the evidentiary claim rests on the server-verified hash, never the watermark text.
- **Auth**: OTP for enrollment/re-bind only. Daily entry = local PIN/biometric. ~1h access JWT + 30–45 day rotating refresh token bound to the device key. **Offline grace**: an expired token never locks the local app; re-auth queues and resolves at next connectivity.
- **Self-health telemetry** on every event + periodic heartbeat: battery %, charging, GPS accuracy/provider, mock flag, Play Integrity verdict (cached daily), developer-options/mock-app flags, storage free, network type, permission states, why-tracking-stopped codes, monotonic clock anchor, per-tier queue depth + oldest-unsynced age. All signed.

### 3.3 AuthZ model

Role × scope-node on the geographic tree (Corporate→root, State Head→state, RM→RBO set, AM→assigned DCs, DC→self, SBI Official→read-only aggregate-first). Enforced at **one data-access choke point** (ADR + lint rule; no query path bypasses it). Assignments are time-bounded and never deleted; **all historical queries resolve as-of-date** (March reports use March's tree; a transferred DC's March visits remain visible to whoever managed them in March).

---

## 4. Domain Model (conceptual — Lane A owns the schema)

**Tenancy**: Tenant (the BC company — Eko is tenant #1). Every org, user, location, and evidence entity is tenant-scoped; the authz choke point filters by tenant before anything else. v1 runs single-tenant; the scoping exists so onboarding a second BC company is configuration, not migration.

**Organisation & geography**: LHO → RBO → Branch → CSP. Shared location shape: name, code (external identifier, never a primary key), address, lat/lng, radius, district, state, PIN. **CSPLocationVersion** — effective-dated coordinates/radius; historic visits resolve against coordinates valid at visit time; CSP status `ACTIVE/SUSPENDED/CLOSED/RELOCATED`. **GeoAssignment** — time-bounded DC↔CSP-set and manager↔territory mappings, never deleted.

**People, devices, identity**: User (PII-minimised; soft-terminate + redact-in-place, never hard-delete), Role, Device (binding lifecycle `PENDING→BOUND→REPLACED/REVOKED`, one active per DC, Keystore public key registered, full history), DeviceBindingEvent (approvals audited), FaceEnrollment (versioned; **embedding on device only**; server stores one encrypted reference photo + match results only).

**Planning**: BeatPlanTemplate (versioned, `DRAFT→PUBLISHED→RETIRED`), BeatPlanAssignment (template-version × DC × date/recurrence), PlannedStop (`PLANNED→VISITED→SKIPPED(reason)→MISSED`), Holiday calendar (state-specific), LeaveRecord (approval workflow). Compliance denominators exclude holidays/leave/force-majeure.

**Execution (evidence — all append-only, client-UUIDv7, triple-timestamped)**: AttendanceSession (start/end events with GPS fix logged-not-gated, face result, telemetry snapshot; `STARTED→ENDED/AUTO_CLOSED/DISPUTED`), Visit (separate field-lifecycle vs server-verification state machines; planned-stop ref nullable — unplanned visits are first-class, flagged; outcome enums include the exception outcomes §7.5), VisitPhoto (category, capture-time hash, watermark payload, upload states incl. `HASH_MISMATCH`), ChecklistTemplate (versioned, immutable once published, multilingual content) + ChecklistResponse (pins template version; immutable; per-item timing telemetry), VisitForm (action items with owners/due dates), Complaint (lifecycle + SLA timestamps; photo + voice note; **no video in v1**). *(FaceChallenge was designed and then removed per locked decision #12 — do not reintroduce without a product-owner RFC.)*

**Movement & distance**: GpsTrackPoint (append-only, highest-volume, monthly partitions), TrackSegment/DistanceLeg (**derived, versioned**: `algorithm_version`, raw + map-matched distance, states `COMPUTED→RECOMPUTED→DISPUTED→MANUALLY_ADJUSTED(with trail)`), DailyTrackSummary (per DC-day rollup incl. gap count/causes, idempotently re-runnable).

**Sync & integrity**: SyncBatch (the unit of the sync KPI), AuditLogEvent (append-only, **hash-chained**), AnomalyFlag (`RAISED→UNDER_REVIEW→CONFIRMED/DISMISSED(reviewer+reason)`), QuarantinedRecord (review queue).

**Platform**: Notification, AppVersion/RemoteConfig (audit-logged changes; min-version floor + force-upgrade flag), ReportJob, ConsentRecord (versioned text, timestamp, user).

---

## 5. The Contracts (Lane 0 — the backbone that enables parallel lanes)

All lanes consume frozen contract artifacts, not conversations. One semver for the contract set; additive-only within minors; breaking changes via RFC + ADR. **Mobile skew rule**: server accepts payloads ≥2 contract minor-versions old, paired with a server-enforced min-version floor.

| Contract | Content |
|----------|---------|
| **C1** Entity schemas | JSON Schemas for every entity in §4. Client-generated UUIDv7 mandatory on field-originated records; three timestamps (device wall, monotonic anchor, server receive) on evidence. All entities tenant-scoped from day one (decision #11). |
| **C2** OpenAPI 3.1 | Hand-written spec-first. Auth, master-data delta-pull, dashboard reads, report requests. Shared problem-details error schema. Every endpoint declares its C6 role mapping. **Machine-readable hard-gate table** (§7.2) lives here/C6. |
| **C3** Sync protocol | The most important artifact in the project. See §6. |
| **C4** Watermark spec | Exact fields, layout zones, font-size floor, per-category compression targets, sidecar hash binding, **golden-image test kit** (pixel-diff + OCR legibility checks) so Android and server verify against the same reference. |
| **C5** Checklist/form template language | Versioned; question types (boolean/choice/scale/text/photo-required/geo-assertion); conditional visibility; per-location-type applicability; **multilingual content fields (Hindi+English) from v1**. Submissions pin template_id+version; offline drafts complete on the version they started. |
| **C6** Role/permission matrix | Machine-readable YAML: role × resource × action × scope-rule, with **tenant as the outermost scope**. Generates backend middleware and dashboard route guards; contract test sweeps every endpoint. The closed hard-gate list lives here — no lane may add a gate without an RFC. |
| **C7** Events + KPI formulas | Domain-event taxonomy and the **written, versioned formulas** for every KPI: compliance % (counts `VISIT_ATTEMPTED` as compliant effort; excludes holidays/leave/force-majeure), productive vs total KM, idle time, sync-success definition (§10), productivity score (published weighted formula, cohort-normalized, visible to the DC). Formula changes are governance events. **Only Lane F computes; dashboards display, never recompute.** |

Consumption: CI codegen (OpenAPI → Kotlin client + TS client; schemas → types + runtime validators). Server validates its own responses against spec in test mode. Lint bans raw HTTP to internal endpoints. Weekly automated drift report. Prose explainers pair every schema; examples in prose are validated against schemas in CI.

---

## 6. Unified Sync Protocol (C3 — signed by all three personas)

1. **Identity & ordering**: client UUIDv7 per record; per-device monotonic sequence number (one counter across types). Sequence gaps = primary sync-KPI telemetry.
2. **Envelope**: `{device_id, batch_uuid, seq_from, seq_to, records[], client_time, app_version, contract_version, queue_depth_by_tier, oldest_unsynced_age, health_telemetry, signature}` — Keystore-signed; the batch itself is evidence.
3. **Timestamps**: three on every evidence record — device wall, monotonic anchor, server receive. Events signed at creation, not at sync (back-dating defense).
4. **Apply semantics**: idempotent at-least-once upsert; per-op result codes `accepted / accepted-flagged / duplicate / quarantined / rejected`. **Validation failures are QUARANTINED (persisted raw, acked so the client stops retrying, human-reviewed), never discarded** — an audit system never drops received evidence. Rejection reserved for unparseable garbage. Partial failure never poisons a batch.
5. **Priority tiers**: T1 tiny records (attendance, check-ins/outs, outcomes, forms) → T2 photos → T3 track batches → T4 voice notes. Separate outboxes; T1 preempts. Dashboards run on T1, so compliance is visible even when photos lag a day.
6. **Backlog & herd**: 5-day offline backlog is a specified, simulator-tested mode. Oldest-first within tier on resume. Jittered exponential backoff mandatory in spec; server sheds load via 429+Retry-After (server-tunable without app release); per-device rate caps. Design case: a district's towers restoring at 19:00.
7. **GPS compaction**: delta-encoded chunk arrays; **gap annotations with cause codes are first-class protocol elements**.
8. **Media**: metadata op in-batch (hash, size, category, watermark payload); binary via pre-signed resumable chunked upload; server hash verification; "orphan photo metadata" (metadata without binary after N hours) is a monitored state with an ops dashboard.
9. **Directionality**: evidence client→server append-only (single-writer → conflicts structurally impossible); master data/templates/policies server→client via versioned delta-pull cursors; admin judgments = annotation records. The tiny mutable surface is enumerated in-spec.
10. **Live heartbeat**: separate lossy endpoint, outside durability guarantees.
11. **Acceptance criterion**: property-based test — any permutation + duplication of a batch set converges to identical DB state. This test gates every C3 change forever.

---

## 7. Feature Specifications & Policy Rulings

### 7.1 Attendance
Face verify (on-device ML Kit vs enrolled template) + timestamp; **GPS logged, never gated** (cold-start indoors = 100–500m fixes; homes move). Start/End Day fully offline. Late attendance allowed with auto-flag; visits before attendance remain valid. Auto-end-day prompt after idle-at-home; tracking hard-stops at End Day or max-session timeout. Supervised enrollment (AM present/video-verified, N poses, quality gates); re-enrollment on device replacement; embedding never leaves the device. Fallback for repeated honest face-match failure: AM-override path with flag (AMs are the support line per decision #18). **Attendance is the one payroll/HRMS touchpoint** (decision #14): an attendance export integration + attendance dispute/correction workflow ships in M2; nothing else in the system integrates with payroll.

### 7.2 Check-in/out & the closed hard-gate list
**Geofencing is advisory-with-evidence, never a hard gate.** Out-of-radius check-in always proceeds: requires reason code (4 canned options + optional voice note, ≤3 taps) + one photo, flows to AM review, feeds master-data healing. The gate multiplier compares against **effective radius = stored radius + reported GPS accuracy** (never punish the phone). Check-out blocked until mandatory photo slots + form complete (this is what defeats drive-by check-ins).

**The complete hard-gate list (closed; additions require RFC):**
1. No gallery/file-picker photo path exists — in-app camera only.
2. No visit submission without mandatory photo *slots* filled (content judged later).
3. No session from a `REVOKED` device binding.
4. No check-in without an open AttendanceSession.
5. No evidence record without client UUID + monotonic seq (storage-layer invariant, invisible to the DC).
6. No check-in with location services fully off (some fix required, however poor).

Everything else — geofence distance, mock flags, time skew, face scores, integrity verdicts — is capture-flag-corroborate feeding trust scores and the AM review workbench. Client-side fraud signals never block locally; integrity verdicts may gate only enrollment/session establishment, with human-readable remediation.

### 7.3 Distance/KM — a financial subsystem (confirmed: drives reimbursement)
- **M1**: OSRM map-matched legs ship (`osrm_v1`, versioned), displayed to DC and AM labeled **"provisional — not for reimbursement"**. Full financial schema (algorithm_version, dispute states, amendment trail) from day one — the calculator is swappable; the schema is not.
- **M2**: published methodology doc (contract artifact): map-matching rules, gap-filling via OSRM route estimates (flagged as estimates), snap-to-road rules. **KM dispute workflow**: DC contests a day → system shows track + gaps + OSRM estimate → AM approves adjustment → audit-logged. Month-end freeze + reconciliation report reaching AMs *before* reimbursement runs. Closed months are never silently recomputed — reopening is a versioned, audit-logged event.
- **Reimbursement consumption** (per decisions #6/#14): KM figures are consumed via **in-system month-end claims reports** — there is no payroll/HRMS integration for KM. The gate stands regardless: reimbursement may rely on app KM only after M2 exit + **one full reconciled pilot month** of parallel-run against Eko's incumbent claims process in Patna.

### 7.4 Random in-day face challenges — KILLED (product-owner ruling, decision #12)
The design debate rated mid-day face challenges the highest-ROI control against phone-parking and buddy-punching — the two frauds no GPS signal catches. The product owner has ruled mid-day biometric spot-checks unacceptable for the workforce; the feature, its contract types, and its plumbing are removed. **This leaves a named residual risk** (risk register #11) with these compensating controls:
- **Attendance face verify at start and end of day stays** — bounding impersonation to within-day windows.
- **Activity-recognition motion signatures promoted from M3 to pilot-tuned M2/M3**: a phone sitting on a CSP counter all day has a distinctly different motion profile from one riding a motorcycle between stops; calibrated on pilot data.
- **Dwell-pattern correlation** (same-device stationarity across multiple claimed visits) weighted higher in the trust score.
- **AM-directed verification** remains possible as a human process (AM video-calls the DC), outside the app's biometric machinery.
Reintroducing challenges requires a product-owner RFC, not an engineering decision.

### 7.5 Exception workflows (M1 — "they're not edge cases, they're Tuesday")
10–20% of rural visit events hit one of these in week one. Each is an outcome code + optional photo + canned reason on the existing visit event:
- **`VISIT_ATTEMPTED_CSP_CLOSED`** — exterior shutter photo, geo-verified, counts as compliant effort (C7 formula), auto-suggests reschedule.
- **`OFFICIAL_UNAVAILABLE`** — meeting photo waived with reason; other photos still required; AM-visible.
- **`PHOTO_REFUSED_BY_CSP`** — refusal recorded + exterior photo + remark; contractual escalation to AM; the DC is never forced into a shopfront confrontation.
- **Late attendance with flag** (§7.1).

M2 adds: KM dispute (§7.3), master-data correction request (DC flags wrong pin / dead CSP → review queue), leave/holiday calendar with denominator exclusion (must land before compliance numbers reach RM dashboards), training/sandbox mode for new DCs (fake CSPs, zero data pollution). M3 adds: force-majeure region flags (RM-level), transfer workflows, automatic master-data healing (§7.7).

### 7.6 Anti-fraud analytics v1 (M2) — score-and-surface into the AM workbench
Gate: **zero false positives on honest-but-noisy fixture personas** (tuned against the connectivity-starved persona before the cheater personas), plus minimum detection rates per scripted cheat persona. The v1 rule list (~11 rules): min-dwell violation corroborated by track; **photo pHash reuse vs that CSP's full history across all DCs** (the workhorse); photo-time/GPS outside visit window; check-in with no track approach; mock-GPS composite (isMock ∨ zero-accuracy-variance ∨ teleport; >800km/h fixes excluded from KM but stored+flagged); Play Integrity failure (weighted, not dispositive — low-end devices degrade verdicts); check-in bursts; time-skew patterns; checklist speedrun (per-field timing); chronic KM-ratio outliers (actual/OSRM >1.3 monthly — feeds dispute flow, never auto-docks); activity-recognition motion signatures (promoted per §7.4 — shipped flag-only in M2, thresholds tuned on pilot data). **M3**: cell/WiFi-vs-GPS cross-signal correlation, screen/moiré photo ML, behavioral clustering. Every DC-day gets a trust score; high trust auto-verifies; low scores hit the AM review workbench (that review *is* the AM's job — give them a workbench, not an inbox).

### 7.7 Master-data bootstrap & healing (elevated — no authoritative SBI feed exists, decision #16)
Since coordinates cannot be imported from SBI, the system builds its own location truth:
- **M1 — first-visit coordinate capture**: on a DC's first check-in at a CSP whose coordinates are unverified/missing, the app captures a supervised fix (best-accuracy GPS + confirmation photo) that seeds or flags the master coordinate for admin approval. Location records carry a `coordinate_confidence` state (`UNVERIFIED → FIELD_CAPTURED → VERIFIED`).
- **M2 — manual correction request**: DC flags wrong pin / dead CSP → review queue (Patna's data will be dirty on day one; this is planned work).
- **M3 — automatic healing**: clustered out-of-radius check-ins at a consistent offset, ≥N occurrences across **≥2 DCs** (so one spoofer can't drag a geofence) → system *proposes* a new CSPLocationVersion → admin approves → effective-dated, history preserved. Never auto-apply.

### 7.8 Audit integrity
Append-only evidence + amendment/annotation model from M1 (Lane G — retrofitting immutability is miserable). **Hash-chained audit log from M1** (prev-hash column + verification job, ~2–3 days); daily chain-head **WORM anchoring from M2** (chain first, anchor target second — a chain started at M1 is retroactively verifiable forever). Auditor-facing verification tooling + **photo-audit bundle export** ("all photos for RBO X for March, with metadata sheet") in M2 — it buys enormous auditor goodwill.

### 7.9 Dashboards & reports
Raw → daily rollups (idempotently re-runnable per DC-day; late sync is normal) → hierarchy aggregates, all plain Postgres, nightly + incremental. Live map: 10s polling on the hot table. Heat maps: nightly PostGIS grid aggregates. Reports always async (job → Excel/exceljs / PDF/headless-Chromium → object storage → expiring link); never rendered in-request. **DC self-view parity is a contract-level rule: no metric endpoint is AM-visible without a DC-visible counterpart** — the DC sees his visits, KM, sync queue, compliance, and his score formula inputs. Surveillance systems that hide data from the surveilled get sabotaged.

### 7.10 "AI" features — de-hyped (M3, advisory only, never gating)
Route optimization: OSRM travel-time matrix + OR-Tools VRP as an *admin-side beat-plan drafting suggestion* only — **the DC-facing version is killed** (a DC knows his own roads). Missed-visit detection: scheduled job **with track corroboration** (distinguishes "went but couldn't check in" from "never went"). Productivity score: published, versioned, cohort-normalized weighted formula, visible in-app to the DC with his own inputs — opaque scores are killed. Compliance %: pure versioned arithmetic. No ML in v1 anywhere; v2 ML (post 12+ months of data) improves solver cost matrices and anomaly review queues only.

### 7.11 Security & DPDP
TLS 1.2+ with cert pinning (+ remote-config rotation escape hatch). Field-level encryption only for face reference photos + phone numbers (KMS keys); volume/SSE encryption elsewhere. Versioned recorded consent at onboarding for tracking + biometrics; **tracking strictly Start Day→End Day — not one ping outside it**. Data-principal rights: access/correction workflow; audit records retained under legal-obligation exemption; biometric artifacts + non-essential PII erased on exit + retention expiry; users redacted-in-place. Retention (PO to confirm): GPS hot 6mo → Parquet 3yr; photos 5yr with lifecycle tiering; visits/attendance/audit log 7–8yr; telemetry 90d. Backups: WAL/PITR, cross-AZ replica, **quarterly restore drills**. Offline-first is the DR story for capture; the runbook must state dashboards lose freshness during backend outages.

---

## 8. Lanes (parallel agent work streams)

Notation: scope / consumes / produces / mocks / depends / phase. **Critical path: Lane 0 → M0 spine (A+B+C+E-minimal) → Lane C seams → Lane D + Lane F distance (parallel critical legs) → M1 device-lab gate → M2 → pilot → rollout.** Strongest agents + most human review go to C3/Lane B and Lane C's seams.

**Lane 0 — Contracts & Domain Design** *(small, senior, never disbanded)*. Authors C1–C7; runs the contract RFC process; arbitrates interface disputes. ~60% of effort lands before M1, then gatekeeping. Head of the critical path.

**Lane A — Backend Core Domain & API.** Postgres/PostGIS schema + migrations (**sole migration owner**); **tenant scoping baked into the schema and the authz choke point from M0** (decision #11); master-data services incl. CSPLocationVersion, coordinate-confidence states + first-visit capture approval (M1) and correction workflow (M2); beat plans; checklist template mgmt; auth/OTP/device binding; C6-generated permission middleware at the single choke point; all C2 endpoints except sync. Mocks only the SMS gateway. M0 onward; critical path through M1.

**Lane B — Sync & Ingestion.** Unified C3 server side: batch endpoint, dedupe, quarantine store + review queue, per-entity apply + ordering, delta-pull cursors, GPS chunk decode + partitioned writes, media pre-signed/resumable service + hash verification, heartbeat endpoint, per-tier lag metrics. **Key deliverable: the sync simulator** — configurable fake fleet (honest, honest-but-noisy, connectivity-starved, 4-day-dead-zone, thundering-herd, and one cheater persona per fraud vector in §7.6). The simulator is how Lanes E/F/G get data without waiting for Lane D. M0 minimal → heavy M1. Critical path.

**Lane C — Android Shell & Offline Store.** App architecture, Room/SQLDelight journal, outbox engine (tiers, backoff, cursors, media queue), login/binding, **tracking foreground service + adaptive sampling** (moved into C — it's shell infrastructure), OEM-onboarding flow (M1), multilingual string architecture (M0), 5-day storage ADR, sync-status UX, **self-service diagnostics screen (M1** — GPS/permission/battery/sync health with plain-Hindi fix-it guidance, since AMs are the support line). **Produces the internal seams** — `LocationProvider`, `CameraCapture`, `FaceVerifier`, `FormRenderer` interfaces + fakes — which is what lets Lane D fan out without merge collisions. Standing acceptance criteria on every C/D ticket: transactional-commit-before-UI-confirm; cold-start restore <3s. Mocks the backend via spec-generated mock server. M0 onward; critical path through M1.

**Lane D — Android Capture Features.** Four parallel sub-lanes behind C's seams: **D1** camera + watermark + signed sidecar (golden-kit verified, <2s, per-category compression); **D2** GPS check-in/out UX (effective-radius logic, accuracy handling, gap cause codes); **D3** attendance face + on-device fraud signal capture incl. activity-recognition motion telemetry (flag-and-sync, never block); **D4** dynamic form/checklist renderer (C5, Hindi+English) + **exception outcome flows (M1)** + complaint capture (photo+voice) + training mode (M2). Never talks to the network directly — everything through the outbox. Starts M1.

**Lane E — Web Admin & Dashboards.** One role-gated app: admin portal (locations, DCs, beat-plan builder, template builder), AM/RM/State/Corporate/SBI dashboards (live map via polling, KPI views per C7, heat maps), **AM anomaly workbench** (M2), KM dispute UI (M2), complaint management, **DC self-view parity rule enforced**. Develops entirely against the spec mock server + simulator data; never blocks on Lane D. E2e must assert scoping happened server-side (send unfiltered requests, verify the API filtered). M0 one screen → heavy M1–M2.

**Lane F — Reporting & Aggregation Workers.** OSRM map-matching + DistanceLeg construction (M1, versioned, provisional-labeled), the M2 financial layer (methodology, disputes, month-end freeze, amendments), rollups + hierarchy aggregates per C7 (recomputability for late data is a core requirement, ground-truth tested against hand-verified fixture months — no tolerance on compliance %), all report types + Excel/PDF export (M2), **attendance HRMS/payroll export + attendance dispute workflow (M2** — the system's only payroll touchpoint), missed-visit job with track corroboration (M3). Never mocks OSRM with straight lines outside unit tests.

**Lane G — Anti-Fraud, Audit & Security.** Append-only audit subsystem + **hash chain from M1**; WORM anchoring M2; fraud analytics v1 rule set (M2, two-sided acceptance: zero FP on honest personas + min detection per cheat persona); trust scoring; healing detection analytics (M3; manual-correction support M2); photo hash/watermark verification pipeline; auditor export bundle (M2); DPDP mechanics (consent records, retention jobs, export/erasure, residency assertions); security hardening.

**Lane H — Infra, CI & Fixtures.** Compose dev stack (Postgres+PostGIS, API, workers, MinIO, OSRM with pre-built district-sized extract container — full India only in staging); path-filtered contract-gated CI; codegen-diff gate; release automation + staged APK rollout + remote config; **the fixture universe** (P0 product — if budget pressure comes, cut features, not fixtures): 2–3 synthetic districts ("Nandpur" dense semi-urban, "Betwa Rural" sparse) with full hierarchies on real roads, ~15 DC personas (punctual, chronically-late, connectivity-starved, one cheater per fraud vector, a CSP closed every Tuesday), deterministic seeded GPS track generator (route via OSRM, noise model, signal-loss windows, battery-death truncation, spoof modes), golden kits (watermark images, canonical sync batches, hand-verified KPI months), state holiday calendars. Fixtures schema-validated in CI, versioned, change-announced like contracts. **Sub-lane H2 — physical device lab** (co-owned with field-ops review): ~8 real low-end handsets (Redmi, Vivo Y-series, Samsung M-series — 4GB, battery-saver on) **procured during M0**; battery-budget measurement protocol; OEM-onboarding verification; per-milestone checklist for what emulators cannot verify (real GPS drift, camera latency, face accuracy, thermal, OEM kills).

**Lane I — Advisory Intelligence (M3 only, shrunken).** OR-Tools beat-plan suggestion (admin-side), published productivity formula, missed-visit prediction nudges, anomaly surfacing. Advisory only; never on a critical path; never gates a compliance record. Offline-evaluated against fixture ground truth.

---

## 9. Milestones

### M0 — Walking Skeleton *(lanes 0, A, B, C, E-minimal, H)*
One seeded DC: stub-OTP login → pulls seeded beat plan → airplane mode → GPS check-in at one CSP (client radius check for UX, PostGIS server check for truth) → event queues in journal → connectivity returns → idempotent sync → visit pin on a minimal AM dashboard. All from `docker compose up` + one emulator, in CI.
Includes: contracts v0.1, real idempotency + empty quarantine table, tenant-scoped schema + choke-point authz pattern proven, multilingual string plumbing, codegen pipeline CI-enforced, fixture v0.1, the e2e sync script (seed of the simulator), root/territory READMEs + first ADRs. **Excludes (enforced list):** photos, face, tracking, check-out, checklists, OSRM, reports, real OTP, push, fraud, all other roles.
**Exit gate:** CI e2e sync simulation green; device-lab hardware ordered.

### M1 — Field-Usable Core *(A, B, C, D all sub-lanes, E, F, G-audit, H; the debate's real cost landed here)*
One real DC could run one real compliant day. Includes: attendance (face + logged GPS + device binding, start/end day, late-with-flag); full visit lifecycle (check-in → watermarked photos + signed sidecars → form → check-out); **exception outcomes (CSP-closed / official-unavailable / photo-refused)**; tracking foreground service with gap cause codes + hard stop at End Day; OEM onboarding flow; **OSRM map-matched provisional KM on the full financial schema**; beat plan day view; media pipeline (tiers, resumable); admin-portal minimum; AM dashboard v1 (live map via polling, visits, photo viewer with pending-binary states); **DC self-view**; **self-service diagnostics screen**; **first-visit coordinate capture** (§7.7); **hash-chained append-only audit log**; Hindi content; real OTP. Sheds to M2: report exports, complaint media beyond photo/voice.
**Demo:** a full simulated DC day replayed on the AM dashboard; the same day executed with airplane mode toggled throughout converging to the identical dashboard state.
**Exit gate:** battery ≤15%/10hr and cold-start <3s measured on H2 reference devices; nightly simulator green.

### M2 — Compliance, Money & Management *(E, F, G heavy; slip risk concentrates here, knowingly)*
Includes: CSP audit checklists end-to-end (builder → renderer → scoring); **KM financial subsystem** (methodology doc, dispute workflow, month-end freeze/reconciliation feeding in-system claims reports); **attendance HRMS export + attendance dispute workflow**; fraud analytics v1 + trust scores + **AM workbench**; complaint management end-to-end; full role-hierarchy dashboards + heat maps + drill-down; all PRD reports with Excel/PDF export; leave/holiday calendar + denominator exclusions; master-data correction requests; training mode; WORM anchoring + auditor verification tooling + photo-audit bundle; DPDP mechanics complete; notifications/escalations.
**Demo:** a State Head drills from district heat map to a flagged DC, replays the route, reviews photo evidence and a fraud flag, exports the monthly compliance report — on 90 simulated days of fixture-fleet data.
**Exit gate:** fraud rules pass two-sided fixture acceptance; KPI ground-truth tests exact-match; contract freeze held.

### PILOT — Hard Gate *(mandatory; on the critical path; district: **Patna**)*
~20 real Eko DCs in Patna district (urban-dense markets + rural blocks, real Bihar connectivity, master data bootstrapped in-app per §7.7, ≥3 known GPS-hostile CSPs deliberately included), 6–8 weeks including one full monthly reporting + reimbursement parallel-run cycle. **Enforcement stays off during the pilot** (decision #17): compliance dashboards run, but consequences begin only after the exception workflows are field-proven here.
**Written exit criteria:** crash-free session rate target; battery ≤15% on the pilot fleet's actual devices; sync ≥99% D+1 (per §10 definition); zero data-loss incidents; KM reconciliation delta vs incumbent process understood and accepted; fraud false-positive review clean; DC satisfaction check. No M3/national spend before exit.

### M3 — Scale Hardening & Advisory Intelligence
Ingestion load tests at 2–3× fleet (herd-shaped, not mean-shaped); GPS lifecycle (downsampling, Parquet archival); Android optimization passes; automatic master-data healing; M3 fraud rules; force-majeure + transfer workflows; Lane I advisory features; regional languages per rollout states; ops runbooks + alerting; staged national rollout tooling.

---

## 10. KPI Redefinitions (PRD → measurable; goes in C7)

| PRD KPI | Redefined |
|---------|-----------|
| Beat plan compliance >95% | Set-based (not sequence-based) visited-as-planned / planned; `VISIT_ATTEMPTED` counts as compliant effort; excludes holidays/leave/force-majeure; formula versioned, changes are governance events. |
| Geo-verified visits 100% | **Graded**: verified / verified-with-evidence / under-review. Binary 100% is achievable only by theater. |
| Geo-tagged photos 100% | 100% of photos carry burned watermark + server-verified capture hash. |
| GPS route capture 100% | 100% of sessions produce a complete **annotated** track (fixes + cause-coded gaps). Continuous 1Hz capture is killed as physically incompatible with the battery budget. |
| Data sync success >99% | **99% of day-D tier-1 records server-acked by end of D+1 (photos D+2), measured per-record** via sequence accounting + client queue-depth telemetry; per-device staleness alerting (>24h silent on a working day pages a human); per-(DC,day) missing-data reconciliation report reaches AMs before month-end reimbursement. |
| Attendance compliance >98% | Unchanged; denominators exclude approved leave/holidays. |

---

## 11. Kill List (killed for v1, not deferred)

1. DC-facing AI route optimization (admin-side OR-Tools suggestion survives, M3).
2. Opaque productivity scores (published, DC-visible formula only, M3).
3. Complaint video (photos + voice notes; video post-M3 only if the pilot proves need).
4. Continuous 1Hz tracking as stated (→ adaptive + annotated gaps).
5. Hard geofence gating (→ §7.2 closed gate list).
6. Daily OTP login (→ enrollment-only OTP + local PIN/biometric).
7. WebSockets/real-time push for dashboards (→ 10s polling).
8. Any second datastore without pilot-measured evidence.
9. Home-geofence attendance gating (doctrinally dead).
10. Binary "100% geo-verified" KPI (→ graded verification).
11. **Random in-day face challenges** (product-owner policy veto, decision #12 — killed with named residual risk and compensating controls, §7.4).
12. SBI SSO integration (standalone logins suffice, decision #14).

---

## 12. Delivery Discipline (how agents work in this repo)

- **Monorepo territories**: contracts / backend / android / web / fixtures / infra / docs. Each territory: its ecosystem's boring, dominant convention; its own README (purpose, build/test in isolation, contracts consumed, what it mocks). Root README: system diagram + "run everything locally in one command" + 15-minute stranger test. ADRs numbered and immutable; every locked decision in §1 becomes an ADR on day one. CONTRIBUTING is an agent operating manual. Everything runs locally with zero credentials (MinIO, stub OTP, district-sized OSRM).
- **Trunk-based**: branches ≤2 days, one ticket = one branch = one PR, feature flags not branches. Merge gates: path-filtered builds, contract conformance, codegen-diff clean, nightly full-stack simulator green (red nightly freezes non-fix merges — *keep the skeleton walking*). Contract changes: RFC micro-process, adoption tickets auto-created, freeze windows before milestone demos, breaking C3 changes post-M1 require an ADR.
- **Ticket format** (every ticket self-verifiable by the executing agent):

```
TICKET: <lane>-<number> — <imperative title>
LANE / PHASE
CONTEXT: 2–5 sentences + links to contract sections and ADRs
CONTRACT REFERENCES: exact artifacts + versions
SCOPE — IN / SCOPE — OUT (explicit non-goals; agents over-build without this)
INTERFACES: seams/fixtures/endpoints exposed or consumed
ACCEPTANCE CRITERIA: numbered, observable, fixture-referenced
SELF-VERIFICATION: exact commands; all must pass (incl. standing gates)
```

- **Standing acceptance criteria** inherited by every ticket in scope: never block on network (C/D), never lose work + crash-resume (C/D), responses validate against spec (A/B), DC-visible parity for metrics (E/F), zero FP on honest personas (G), fixtures schema-valid (H).
- **Testing spine**: property-based sync convergence (the project's most valuable test); permission-matrix sweep auto-generated from C6; watermark golden kit; KPI ground-truth months (hand-verified, exact-match); recomputation tests (Thursday-sync-of-Tuesday); simulator scenarios as a shared vocabulary (`checkin-dup-storm`, `cheater-week seed 4471`, `monsoon-connectivity`, `4-day-dead-zone`) reproducible by every lane; two-tier Android testing — emulator (mock-location playback, virtual camera) + H2 physical checklist per milestone. CI green is never claimable as field verification.

---

## 13. Risk Register (top 10)

| # | Risk | Mitigation |
|---|------|------------|
| 1 | C3 sync-protocol churn post-M1 invalidates B/C/D/G simultaneously | Over-invest pre-M0; append-only modeling shrinks the mutable surface; M0 breaks it cheaply; RFC+ADR friction; skew rule forces versioned coexistence |
| 2 | Emulators lie about field reality (OEM kills, battery, camera, face, thermal) | H2 device lab from M0 procurement; per-milestone physical checklist gates exits; named reference devices in an ADR |
| 3 | KPI formula divergence across lanes | C7 formulas are contracts; only Lane F computes; ground-truth fixture months |
| 4 | Agent drift from contracts (invented fields/shapes) | Codegen-only access, response validation, drift report, contract-citation-required tickets |
| 5 | KM under/over-count → field revolt or bank distrust | Financial subsystem rigor (§7.3), pilot parallel-run, dispute flow before enforcement |
| 6 | Fraud false positives poison field trust | Zero-FP gate on honest-noisy personas; flags are review items, never auto-punitive, until pilot-validated |
| 7 | Dirty CSP master data (est. 20–40% of rural coords >75m off) | Advisory geofence + effective radius; correction requests M2; healing M3; pilot in a known-dirty district |
| 8 | Media pipeline underestimated (cross-cutting B/C/D/E) | Named C3 §media surface, simulator scenarios, partial-sync UI states specified in C2 |
| 9 | Android merge contention (C + 4 D sub-lanes) | C-owned seams + disjoint feature modules; C exclusively owns shared shell files |
| 10 | M-scope creep (esp. M0 and the fat M2) | Written exclusion lists enforced in review; cuts land as next-milestone tickets same day |
| 11 | **Residual phone-parking/buddy-punching risk** after the face-challenge veto — the strongest control against the phone traveling without the DC is gone | Compensating controls (§7.4): start/end-day face verify, motion-signature telemetry from M1 with M2 flagging, dwell correlation weighting; measure this fraud class explicitly in the pilot; if pilot data shows material leakage, present the evidence to the product owner for an RFC |

Watch list: OSRM extract pipeline ops, SMS gateway procurement lead time (paperwork gates M1's "real OTP"), Play Integrity degradation on low-end cohorts, DPDP legal review of biometrics, WORM storage procurement, Patna master-data cleanup effort (no SBI feed — §7.7 bootstrap is load-bearing).

---

## 14. Product-Owner Rulings — All Questions Resolved (2026-07-08)

Earlier rulings: national scale; Kotlin; Node+Postgres; hybrid APIs; **BYOD**; **KM = reimbursement**; **pilot = hard gate**; **Hindi + English**.

Final round of rulings (interpretations of terse answers are flagged — correct them if wrong):

| # | Question | Ruling | Plan impact |
|---|----------|--------|-------------|
| 1 | Mid-day face challenges acceptable? | **No** | Feature killed (§7.4); compensating controls + residual risk #11. *Interpreted as a veto on mid-day biometric spot-checks generally.* |
| 2 | Payroll cut-over commitment for KM? | **No** | *Interpreted with #11:* KM never integrates with payroll/HRMS; reimbursement is consumed via in-system claims reports; the reconciled-pilot-month gate still applies to reimbursement reliance (§7.3). |
| 3 | Pilot district | **Patna** | Pilot section updated; master-data bootstrap and connectivity assumptions set for Bihar conditions. |
| 4 | Enforcement grace | **After exception workflows are field-proven** | Decision #17: enforcement off during pilot; consequences begin post-pilot validation. |
| 5 | Employment relationship | **Eko** (DCs are Eko's field workforce) | Eko is the data fiduciary for DPDP consent; Eko disciplines fraud; app branding/tenancy anchored on Eko as tenant #1. |
| 6 | Authoritative SBI CSP feed? | **No** | §7.7 elevated: first-visit coordinate capture in M1, correction workflow M2, healing M3; Patna cleanup is planned work. |
| 7 | CSP contracts permit photos? | **Yes** | Photo mandates stand; refusal flow (§7.5) stays as the rare-case path with contractual escalation. |
| 8 | Prescribed retention periods? | **No** | Our proposed defaults stand (§7.11) pending any later legal input. |
| 9 | SBI SSO? | **Not required** | Standalone portal logins for SBI officials; a timeline risk removed. |
| 10 | Hosting mandate? | **No — Eko's own app** | Any India-region cloud; managed Postgres confirmed. |
| 11 | Payroll/HRMS integration | **Attendance only** | Attendance export + attendance dispute workflow in M2 (Lane F); nothing else touches payroll. |
| 12 | Visit-duration norms from ops? | **Yes** | Ops supplies per-visit-type minimums; they parameterize the dwell fraud rules (§7.6) — collect before M2 rule tuning. |
| 13 | Support model | **AMs by default** | Self-service diagnostics screen promoted into M1 (Lane C); AM-override flows sized accordingly. |
| 14 | Multi-tenancy | **Multiple BC companies** | Decision #11: tenant scoping in schema, contracts, and the authz choke point from M0; v1 operates single-tenant (Eko). |

---

## 15. The Ten Consolidated Non-Negotiables

1. **No field action ever blocks on network**; the local journal is the system of record until server ack; work is never lost; crash-resume is exact.
2. **Evidence is append-only, client-UUIDv7'd, signed at creation, triple-timestamped**; the server quarantines, never discards; judgments are annotations; corrections are amendments; the audit log is hash-chained from the first day of real data.
3. **Postgres + object storage + pg-boss is the entire backend state**; modular monolith + workers; one docker-compose.
4. **The hard-gate list is closed and machine-readable** (§7.2); everything else is score-and-surface into trust scores and the AM workbench; geofencing is advisory-with-evidence with accuracy-inflated effective radius.
5. **KM is money**: versioned algorithms, published methodology, dispute workflow, frozen months amended never recomputed, payroll only after a reconciled pilot month.
6. **Tracking exists only between Start Day and End Day**, hard stop, honest notification, cause-coded gaps; **≤15% battery per 10-hour day on named real devices is a milestone exit gate**.
7. **Exception workflows ship with the features they except** (M1), with fixture personas exercising them — every mandate gets a legitimate exception path or the field invents a fraudulent one.
8. **Contracts are CI-enforced law**: monorepo, trunk-based, codegen-only API access, drift fails the build, the nightly simulator stays green or merges freeze.
9. **The fixture universe with hand-verified ground truth is a P0 deliverable**; fraud rules ship only with zero false positives on honest-but-noisy personas.
10. **DC dignity floor as acceptance criteria**: Hindi-first, one-screen-one-decision, near-zero typing, errors say what to do, and the DC always sees his own numbers — the same ones his managers see.
