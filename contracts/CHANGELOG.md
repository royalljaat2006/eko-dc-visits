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
