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

## v0.11.0 — close the spec's data gaps

- C2: 0.11.0; + POST /circle/csp-details/import (Circle Head/admin bulk-update
  of CSP master details — address + §3.1 profile fields — for own-circle CSPs;
  `dry_run` returns the per-row diff so the web upload previews before commit;
  spec §4 P1); + POST /circle/home-locations/import ("Excel sheet for Lat Long",
  spec §3 — bulk-set DC/Circle-Head reference home locations; attendance is
  logged against home, never gated per ADR-0004).
- C1: the §3.1 CSP master template whitelist grows to the full set —
  `branch_code`, `branch_name`, `rbo_name`, `state`, `circle_head_name`,
  `lho_name`, `lho_mail_id`, `pin_code` join the free-form `csp_profile` map
  (used by both DC change-requests and the CH bulk import). No schema bump —
  `csp_profile` is `additionalProperties: string` by design.
- C2: /dc/csp-details `csp_profile` now backfills `branch_code`/`branch_name`/
  `rbo_name`/`lho_name` from the CSP→Branch→RBO→LHO hierarchy when not
  explicitly overridden.
- Seed: `npm run seed:pilot` loads the real Circle 1A85 roster
  (fixtures/circle-1a85-roster.json — spec §7) into a "Circle 1A85": 7 DC users
  with their phones + per-user dashboard_url, DC memberships. Never in the
  public demo seed.

## v0.10.0 — visit checkout

- C1: + checkout-event.schema.json (sync op `visit.checkout`, tier T1).
  Append-only; links a checkin by `visit_id`, never mutates it; may arrive
  before its checkin (different batches can race).
- C2: 0.10.0; `/dashboard/visits` Visit gains `checked_out_at` +
  `duration_minutes` (derived at read time, like `photo_count`/`km_today` —
  earliest (device_wall_time, id) checkout per visit, order-independent);
  `/sync/batches` op-type enum += `visit.checkout`.
- C3: `visit.checkout` documented; op-types line updated to v0.10.0.
- Android `:app`: manual "Check out" action (replaces the old "Done/Skip
  photos" button — checking out is now the visit's natural close, with or
  without photos attached first); the design-0001 DwellMatcher's `CheckOut`
  event now actually emits `visit.checkout` (`trigger=AUTO_GEOFENCE`) instead
  of being logged-only, correlated to the matcher's own `visit.checkin` by a
  local cspId→visit_id map kept for the tracking-service session.
- Migration: 007_visit_checkouts.sql.

## v0.9.0 — native app M1 slice: photos, route capture, signed envelopes

- C1: + visit-photo.schema.json (sync op `visit.photo`, tier T2). Append-only;
  watermarked JPEG inline as base64 (M1 interim — object-storage + pre-signed
  upload is the M2 hardening, C3 §7). Server re-hashes bytes: mismatch is
  `accepted-flagged` HASH_MISMATCH, never rejected (ADR-0003).
- C2: 0.9.0; `/dashboard/visits` Visit gains `photo_count` (derived at read
  time, like `km_today`); `/sync/batches` op-type enum widened to the real set
  (`visit.checkin` | `visit.photo` | `attendance.start` | `attendance.end` |
  `track.chunk`) — the engine already accepted these, the spec now says so.
- C3: `visit.photo` op documented; op-types line updated to v0.9.0.
- Android `:app` (BUILD_PLAN M1): foreground route-capture service (Start→End
  Day only, 21:00 IST hard stop) emitting `track.chunk`; `:core` DwellMatcher
  wired to the live fix stream → auto `visit.checkin` (AUTO_GEOFENCE); CameraX +
  in-pixel watermark + signed sidecar → `visit.photo`; every sync envelope
  ECDSA-signed with a per-device AndroidKeyStore key (public key registered at
  enrollment; server records, enforces later).
- Migration: 006_visit_photos.sql.

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
