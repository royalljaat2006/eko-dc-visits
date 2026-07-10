# C7 — KPI formulas (contracts v0.1.0)

Formulas are contracts: versioned, governance-gated, computed ONLY by the reporting lane
(Lane F). Dashboards display; they never recompute. Ground-truth fixture months verify
exact-match (no tolerance).

## sync_success_v1
99% of day-D tier-1 records server-acknowledged by end of day D+1 (photos: D+2).
Measured per-record via per-device sequence accounting + envelope queue-depth telemetry.

## beat_compliance_v1  (M1+)
compliant_stops / planned_stops per DC-day, where:
- VISIT_ATTEMPTED_CSP_CLOSED and OFFICIAL_UNAVAILABLE (with evidence) count as compliant effort
- set-based: order deviations are not violations
- denominator excludes holidays, approved leave, force-majeure windows
- unplanned genuine visits do not inflate the numerator (tracked separately)

## geo_verification_v1  (graded, never binary)
Per visit: VERIFIED (inside effective radius) | VERIFIED_WITH_EVIDENCE (outside + reason +
evidence, AM-reviewed) | UNDER_REVIEW. Effective radius = radius_m + reported accuracy_m.

## distance_v0 → distance_osrm_v1  (M1)
Every DistanceLeg carries algorithm_version. Provisional until the methodology doc +
dispute workflow land (M2). Reimbursement consumes KM only via in-system claims reports
after one reconciled pilot month (ADR-0005).

## Event taxonomy (M0 subset)
visit.checkin.accepted | visit.checkin.flagged | sync.batch.received | sync.op.quarantined

## dc_score_v1  (gamification — design 0002; NEVER feeds pay/enforcement)
points = visits_done × 50
       + geo_verified_visits × 20     (geofence INSIDE only; flagged still earns visit points)
       + on_time_start × 30           (attendance START ≤ 09:30 IST)
       + min(streak_days, 7) × 10     (consecutive calendar days with a Start Day event)
Badges: EARLY_BIRD (START ≤ 09:00 IST) · PERFECT_DAY (≥3 visits, all INSIDE) ·
STREAK_3 · STREAK_7. Same numbers to the DC and their managers (parity rule).
