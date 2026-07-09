# Design 0001 — Circle Hierarchy, Auto Check-in/out, Multi-Bank

Status: PROPOSED (product-owner specification, 2026-07-09). Supersedes the
AM/RM/State-Head role ladder in BUILD_PLAN §roles. General architecture only.

## 1. What changes

| Area | Before (BUILD_PLAN v1.1) | Now |
|------|--------------------------|-----|
| Org hierarchy | DC → AM → RM → State Head → Corporate Admin | **DC → Circle Head → National Head**, plus HR/Admin (attendance visibility) and Corporate Admin (system administration) |
| Check-in/out | Manual, GPS-validated (advisory geofence) | **Automatic** on geofence entry/exit via dwell detection, manual fallback retained |
| CSP→DC mapping | GeoAssignment (AM→DC) | **CspAssignment** (CSP→DC, owned/edited by the Circle Head), plus Circle membership (DC→Circle) |
| Banks | SBI implicit, single tree LHO→RBO→Branch→CSP | **Bank is a first-class entity**; each bank brings its own CSP list; branch trees optional per bank |

Unchanged doctrines: append-only evidence, triple timestamps, advisory
geofence judgment, single authz choke point, tenant scoping (tenant = BC
company; banks live INSIDE a tenant), effective-dated assignments with
as-of-date history.

## 2. Roles & hierarchy

```
Tenant (BC company, e.g. Eko)
 └─ National Head ── real-time board: all circles, all DCs, attendance, live map
     └─ Circle Head (per Circle) ── owns CSP↔DC allocation + route plans for the circle
         └─ DC ── executes visits; auto check-in/out at assigned CSPs
 HR/Admin ── cross-tenant-scope attendance read (+ leave administration)
 Corporate Admin ── system administration (unchanged)
 Bank Official (per bank, optional) ── read-only, scoped to that bank's CSPs
```

- **Circle** = a named management unit (typically a geography) holding a set of
  DCs and a set of CSPs (possibly 100–300+, across one or more banks).
- **Reporting**: DC belongs to exactly one Circle (effective-dated membership);
  a Circle has exactly one Circle Head at a time (effective-dated).
- C6 scope rules: `CIRCLE_HEAD → circle` (all DCs + CSPs in the circle),
  `NATIONAL_HEAD → tenant-root (read)`, `HR_ADMIN → tenant-root (attendance +
  leave only)`, `BANK_OFFICIAL → bank (read-only)`. DC stays self-scoped.
- The M0 fixture AM (Priya) migrates to CIRCLE_HEAD; the resolveScope choke
  point changes only its lookup source (circle membership instead of
  GeoAssignment) — handlers untouched. `AM/RM/STATE_HEAD` enum values are
  removed pre-1.0 (contract minor bump; no production data exists).

## 3. Entity model deltas (C1)

**Bank** — id, tenant_id, name, code, license metadata (license number,
obtained date), status (`ONBOARDING → ACTIVE → SUSPENDED`), created_at.
Onboarding a new bank license = insert Bank + bulk-import its CSP list (§8).

**LocationNode** — gains `bank_id` (required for CSP/BRANCH/RBO/LHO rows).
`parent_id` stays optional above CSP: SBI keeps LHO→RBO→Branch→CSP; a new bank
may be flat (Bank → CSPs) until it defines branches. All existing invariants
(CSPLocationVersion effective-dated coordinates, coordinate_confidence
bootstrap states) apply per bank.

**Circle** — id, tenant_id, name, description, status, effective dating.

**CircleMembership** — circle_id × user_id × role-in-circle (`DC` |
`CIRCLE_HEAD`) × valid_from/valid_to. Never deleted; transfers between circles
are end-old + start-new (bitemporal-lite, ADR analog of GeoAssignment).

**CspAssignment** — the core new record: csp_location_id × dc_user_id ×
circle_id × valid_from/valid_to × assigned_by (Circle Head user) × reason
(`INITIAL_ALLOCATION | TRANSFER | REBALANCE | COVERAGE_GAP`). Rules:
- At most one ACTIVE assignment per CSP per point in time within a circle.
- Transfer = end current + start new in one transaction, audit-logged with
  actor + reason. Historic visits resolve against the assignment as-of-date.
- A DC's "my CSPs" list = active CspAssignments — this is what syncs to the
  DC app and drives auto check-in (§4) and what the Circle Head app displays.

**AttendanceSession** — unchanged, but gains a derived live-status projection
(`NOT_STARTED | ON_DUTY | ENDED | AUTO_CLOSED`) feeding the National Head /
HR boards (§7).

**GeoAssignment (M0)** — retired in favor of CircleMembership + CspAssignment.

## 4. Automatic check-in / check-out

The product-owner requirement: a DC arriving at an assigned CSP is checked in
automatically and checked out automatically on leaving.

**Mechanism — tracking-stream dwell matching, not OS geofences.** Android's
Geofencing API caps at 100 registered fences per app and behaves poorly under
OEM battery killers. Instead, the already-running duty-session tracking
service (foreground, adaptive sampling) matches each fix against the
**on-device table of the DC's assigned + planned CSPs** (synced via delta
pull). This is fully offline, has no fence-count limit, and reuses the
existing battery budget.

- **Auto check-in**: N consecutive fixes (default 2) spanning ≥ T_in (default
  120 s) inside a CSP's effective radius (radius_m + accuracy_m) → emit
  `visit.checkin` with `trigger: AUTO_GEOFENCE` at the first-entry timestamp.
  The DC gets a notification ("Checked in at CSP Mahua Tola") and the app
  opens the visit's evidence flow (photos/checklist remain human work — auto
  check-in never fabricates evidence, it only timestamps presence).
- **Auto check-out**: sustained exit — fixes ≥ max(2× effective radius, 300 m)
  away for ≥ T_out (default 180 s), or Start of a different CSP's dwell →
  emit `visit.checkout(trigger: AUTO_GEOFENCE)` back-dated to last-inside fix.
  If mandatory photo slots are unfilled at auto check-out, the visit is marked
  `EVIDENCE_INCOMPLETE` (flag, never a block — the DC may be driven out by a
  network-dead phone or an emergency; the Circle Head sees the flag).
- **Overlap resolution** (dense markets have 3 CSPs within 200 m): the dwell
  matcher scores candidates by distance ÷ effective radius; ties go to the
  planned stop, then to the nearest; the losing candidates are recorded as
  `NEARBY_CANDIDATES` on the event for fraud analytics (drive-by/burst rules).
- **Manual fallback stays** (GPS-hostile interiors, ADR-0004 scenarios): a
  manual check-in supersedes a pending auto detection; `trigger: MANUAL` vs
  `AUTO_GEOFENCE` is recorded on every event and is itself an analytics signal.
- **Evidence semantics unchanged**: auto events are client-generated evidence
  records (UUIDv7, monotonic seq, triple timestamps, tier-1 sync). The server
  judges them exactly like manual ones. C1 `checkin-event` gains `trigger` and
  `nearby_candidates[]`; a `visit.checkout` op type is added to C3 (already
  M1-planned).
- **DPDP boundary unchanged**: dwell matching runs only inside Start Day → End
  Day, inside the existing foreground service.
- Dwell thresholds (T_in, T_out, fix counts, exit distance) are remote-config
  values, tuned in the Patna pilot; too-eager auto check-out during in-shop
  GPS drift is the #1 expected failure mode and is pilot-measured.

## 5. CSP lists in the apps (delta pull, C3 §6 — already built)

- **DC app**: pulls active CspAssignments + those CSPs' LocationNodes (with
  coordinates, radius, bank). Present offline; drives the dwell matcher, the
  day view ("your CSPs / today's plan"), and unplanned-visit capture.
- **Circle Head app/portal**: pulls the circle's full CSP list (100–300 rows —
  trivial payload) with per-CSP: assigned DC, last-visit date, visit frequency
  compliance, coordinate confidence. This is the workbench for §6.
- Both use the existing `updated_since` cursor endpoints; new collections:
  `csp-assignments`, `circles`, `banks`.

## 6. Allocation, transfer & route planning (Circle Head workbench)

- **Allocate**: for a circle with K DCs and N CSPs (100/200/300), the system
  proposes a partition of CSPs into K balanced territories: capacitated
  clustering on the OSRM travel-time matrix (k-medoids seeded by DC home
  locations; balance target N/K ± tolerance), then within each DC's set,
  suggested visit-day sequencing (the M3 OR-Tools beat-plan aid, pulled
  forward for the Circle Head as ADVISORY output — never auto-published).
- **Adjust**: map UI — CSP pins colored by DC; Circle Head drags a CSP to
  another DC or uses "add CSP to DC" / "transfer CSP" actions. Every change
  previews the km/time impact per affected DC (OSRM estimate).
- **Publish**: one transaction — effective-dated CspAssignment changes +
  regenerated beat-plan assignments from the new territories, audit-logged.
  Changes apply forward-only (in-flight offline days execute against the plan
  version they pulled — existing C3 rule).
- **Guardrails**: transfers can't orphan a CSP (must land on an active DC in
  the circle); imbalance beyond a configurable ratio warns; all mutations are
  admin records (server-authoritative), never touched by the sync protocol.

## 7. National Head & HR real-time visibility

- **National board** (NATIONAL_HEAD, read): per-circle rollup — DCs on duty /
  expected, attendance %, visits today (planned/done/flagged), live map of
  last-known positions. Built on the existing lossy heartbeat + hot table +
  10-second polling; "real-time" = the already-designed 10 s freshness, and
  attendance state transitions (start/end day) additionally ride tier-1 sync
  so they appear within one sync cycle even where the heartbeat is dead.
- **Drill-down**: circle → DC → day timeline (attendance, visits, track
  replay) — same visit read models, wider scope node.
- **HR/Admin**: attendance-only projection of the same data (C6 keeps HR out
  of visit evidence/photos — PII minimization), with month-view per employee
  and leave/holiday administration (feeds the C7 compliance denominators).

## 8. Multi-bank onboarding

Flow when a new bank license is obtained (Corporate Admin or National Head):
1. Create Bank (name, code, license metadata) → status ONBOARDING.
2. **Bulk CSP import**: CSV/XLSX upload (code, name, address, PIN, district,
   state, optional lat/lng, optional branch mapping) → staged import with
   per-row validation report (reject nothing silently; quarantine-style row
   review). Rows without coordinates enter as `coordinate_confidence:
   UNVERIFIED` → the existing first-visit capture bootstrap (§7.7 of the
   BUILD_PLAN) builds the geo truth.
3. Assign imported CSPs to circles (bulk, by district default + manual), then
   Circle Heads allocate to DCs (§6).
4. Bank → ACTIVE. All KPIs/reports gain a bank dimension (C7: per-bank
   compliance slices); a DC's day may mix CSPs of several banks transparently
   (watermark/evidence records carry the CSP's bank).

Tenancy note: banks are data WITHIN a BC tenant (Eko serves SBI + new banks).
A different BC company is still a different tenant (ADR-0006 unchanged).

## 9. Contract deltas (RFC to contracts v0.2.0)

- **C1**: + Bank, Circle, CircleMembership, CspAssignment schemas;
  LocationNode + `bank_id`; checkin-event + `trigger`, `nearby_candidates[]`;
  + checkout-event. Remove AM/RM/STATE_HEAD from role enum; add CIRCLE_HEAD,
  NATIONAL_HEAD, HR_ADMIN, BANK_OFFICIAL.
- **C2**: + delta-pull collections (banks, circles, csp-assignments);
  + Circle Head workbench endpoints (allocation proposal, assignment mutations
  with transfer semantics, impact preview); + national board + HR attendance
  reads; + bank onboarding + bulk-import endpoints (async ReportJob-style).
- **C3**: + `visit.checkout` op; auto-trigger fields ride the same evidence
  path; no protocol-semantics change (deliberately — C3 churn is the top risk).
- **C6**: rewrite roles table per §2; hard-gate list UNCHANGED (auto check-in
  does not add gates; HG4 "open attendance session" applies to auto events too
  — no dwell matching off duty).
- **C7**: attendance formulas get live-status definitions; KPI slices by bank
  and by circle; route-efficiency metric (actual km vs optimized estimate) for
  Circle Head dashboards.

## 10. Milestone placement

- **M1 (unchanged core + renames)**: role/scope rename to Circle/National in
  contracts + choke point + fixtures; Bank entity + `bank_id` (SBI seeded);
  CspAssignment replaces GeoAssignment; DC app pulls assigned-CSP list;
  checkout op (already M1). Manual check-in remains the M1 field behavior.
- **M2**: auto check-in/out dwell matcher (device) + trigger-aware analytics;
  Circle Head workbench v1 (list + manual assign/transfer + publish);
  national board + HR attendance views; bank bulk-import pipeline.
- **M2.5 Patna pilot**: dwell thresholds tuned; auto-vs-manual trigger rates
  and false check-out rate are explicit pilot exit metrics.
- **M3**: allocation optimizer (capacitated clustering + OSRM matrix),
  route-efficiency KPI, drag-and-drop territory map.

## 11. Risks & open questions for the product owner

1. **Auto check-out under in-shop GPS drift** is the #1 field risk (a DC
   standing at the counter "leaves" per GPS). Mitigated by T_out + exit
   distance + pilot tuning; residual risk documented. Fully-silent auto
   check-in vs one-tap confirm is pilot-decidable via remote config.
2. **Battery**: dwell matching adds CPU to the tracking loop, not extra GPS —
   budget impact expected ≈ nil, but it's measured on the H2 device lab like
   everything else (ADR-0008 gate unchanged).
3. Does any middle layer between Circle Head and National Head exist today
   (e.g., zonal heads)? Design assumes NO; adding one later is a new role +
   scope node, cheap under the choke-point model.
4. May one DC ever serve CSPs in two circles (border coverage)? Design
   assumes NO (one circle per DC); exceptions would be modeled as a temporary
   second CircleMembership with a warning flag.
5. Bank Officials' read access: required at launch, or later? Modeled but
   deferrable.
