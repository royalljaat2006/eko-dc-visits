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
