-- 007 — visit checkouts (contracts v0.10.0, sync op visit.checkout, tier T1).
-- Append-only evidence (ADR-0003): INSERT ... ON CONFLICT DO NOTHING only.
-- Linked to its checkin by visit_id, never ordered — checked_out_at is derived
-- at READ time (backend/src/repos/pg.ts#checkoutTimesForVisits) as the
-- earliest (device_wall_time, id) pair per visit, so arrival order and
-- duplicate emits never change the result.

CREATE TABLE IF NOT EXISTS visit_checkouts (
  id                 uuid PRIMARY KEY,
  tenant_id          text NOT NULL,
  visit_id           uuid NOT NULL,
  dc_user_id         uuid NOT NULL,
  device_id          uuid NOT NULL,
  fix                jsonb,
  trigger            text NOT NULL DEFAULT 'MANUAL' CHECK (trigger IN ('MANUAL','AUTO_GEOFENCE')),
  device_wall_time   timestamptz NOT NULL,
  monotonic_ms       bigint NOT NULL,
  server_received_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS visit_checkouts_visit_idx ON visit_checkouts (tenant_id, visit_id);
