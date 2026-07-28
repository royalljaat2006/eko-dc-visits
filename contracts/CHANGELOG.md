# Contract set changelog

## v0.1.0 — M0 walking-skeleton scope
- C1: common defs (uuidv7, triple timestamps, geoPoint), Location (+coordinate_confidence),
  User/Device, BeatPlanAssignment, CheckInEvent.
- C2: auth (stub OTP), master-data delta pull (locations, beat plans), sync batches,
  dashboard visits read.
- C3: envelope, idempotency, five result codes incl. quarantined, tiers, triple timestamps,
  convergence invariant, KPI binding.
- C6: DC/AM/CORPORATE_ADMIN, closed hard-gate list (6 gates), explicit non-gates.
- C7: sync_success_v1, beat_compliance_v1, geo_verification_v1 (graded), distance versioning.
- Removed pre-freeze: FaceChallenge types (product-owner veto, ADR-0007).

## v0.2.0 — design 0001 (circle hierarchy, multi-bank)
- C1: role enum → DC / CIRCLE_HEAD / NATIONAL_HEAD / HR_ADMIN / CORPORATE_ADMIN / BANK_OFFICIAL
  (AM/RM/STATE_HEAD/SBI_OFFICIAL removed pre-1.0; no production data existed).
- C1: + bank.schema.json, circle.schema.json (circle + membership), csp-assignment.schema.json;
  LocationNode + required bank_id; checkin-event + trigger (MANUAL|AUTO_GEOFENCE) + nearby_candidates.
- C2: 0.2.0; + GET /master-data/csp-assignments (delta-pull; drives the on-device dwell matcher).
- C3: unchanged (deliberately — auto check-in rides existing evidence semantics).
- C6: roles rewritten per design 0001 §2; hard-gate list UNCHANGED.
- Migration notes: GeoAssignment (fixture-defined, M0) is retired in favor of
  CircleMembership + CspAssignment. Historic scope resolution stays as-of-date.

## v0.3.0 — attendance evidence, national/HR board, CSP transfer
- C1: + attendance-event.schema.json (START/END, face_match result, fix logged-never-gated;
  AttendanceDay derived via commutative earliest-START/latest-END merges → order-independent).
- C2: 0.3.0; + POST /circle/csp-assignments/transfer (Circle Head only; end-old + start-new,
  target DC must be in the head's circle); + GET /dashboard/attendance (NH/HR tenant-wide,
  CH circle, DC self; rows include NOT_STARTED DCs).
- C3: op types now visit.checkin | attendance.start | attendance.end; unknown types quarantine.
- Note: check-in↔attendance correlation is deliberately NOT judged at ingest (order-dependent
  flags would break the C3 §3 convergence invariant); it is an M2 server-side analytics rule.

## v0.4.0 — gamification (design 0002)
- C2: + GET /dashboard/scorecard (dc_score_v1 cards; DC self / CH circle / NH tenant).
- C7: + dc_score_v1 published formula + badge definitions. Scores never feed
  pay or enforcement (BUILD_PLAN kill-list #2 stands: no opaque scores).

## v0.5.0 — admin overview
- C2: + GET /dashboard/overview (CORPORATE_ADMIN + NATIONAL_HEAD): tenant-wide
  attendance/visit/CSP-coverage rollups, per-circle and per-DC tables, per-bank counts.

## v0.6.0 — bulk CSP assignment import
- C2: + POST /circle/csp-assignments/import (Circle Head/admin): spreadsheet rows
  (csp_code, dc_phone) applied via the audited end-old + start-new path; per-row
  accepted/rejected-with-reason results (staged-import doctrine, design 0001 §8).

## v0.7.0 — GPS track chunks + daily km visibility
- C1: + track-chunk.schema.json (raw duty-session points; append-only; daily km
  derived at read time — order-independent, convergence-preserving).
- C3: + track.chunk op type (tier T3).
- C2: attendance board rows + overview per-DC rows gain km_today
  (track_straightline_v0 — PROVISIONAL, never for reimbursement per C7/ADR-0005).

## v0.8.0 — CSP Visit Mobile App spec (docs/specs/csp-visit-mobile-app-draft.md)
- C1: user + dashboard_url (per-user "My Dashboard", self-only) + home_lat/home_lng
  (logged reference, never a gate — ADR-0004; spec's geofence question stays open);
  location + csp_profile (master template §3.1); + csp-change-request schema.
- C2: attendance rows gain AUTO_CLOSED status (21:00 IST cutoff, "not confirmed by
  user"), auto_closed flag, hours_worked; + GET /dc/csp-details (with last-visit
  date); + DC change-request create, CH approval queue list + decide endpoints.
- Sync: CIRCLE_HEAD may submit OWN attendance ops (spec §4); all evidence payloads
  must carry the submitter's own dc_user_id (impersonation hardening).
- Already satisfied by construction: locked back-dated records (append-only
  evidence, no edit paths); Circle→CH→DC→CSP single mapping source (C6 + choke point).
