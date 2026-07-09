-- 001_init.sql — M0 walking skeleton schema (contracts v0.1.0)
-- Tenancy from day one (ADR-0006): tenant_id on EVERY table.
-- Evidence is append-only (ADR-0003): checkin_events, quarantined_ops and
-- visits (read model rows keyed by client event ids) receive INSERTs only.
-- Operationally, revoke UPDATE from the application role, e.g.:
--   REVOKE UPDATE, DELETE ON checkin_events, quarantined_ops, sync_op_dispositions FROM eko_app;
-- The pg repository (src/repos/pg.ts) contains no UPDATE statement for these tables.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS users (
  id                uuid PRIMARY KEY,
  tenant_id         text NOT NULL,
  name              text NOT NULL,
  phone             text NOT NULL,           -- PII: field-encrypt at rest before pilot (C1 user schema note)
  employee_code     text,
  role              text NOT NULL CHECK (role IN ('DC','AM','RM','STATE_HEAD','CORPORATE_ADMIN','SBI_OFFICIAL')),
  scope_location_id uuid,
  status            text NOT NULL CHECK (status IN ('INVITED','ACTIVE','SUSPENDED','EXITED')),
  UNIQUE (tenant_id, phone)
);

CREATE TABLE IF NOT EXISTS devices (
  id            uuid PRIMARY KEY,
  tenant_id     text NOT NULL,
  user_id       uuid NOT NULL REFERENCES users(id),
  hardware      jsonb,
  public_key    text,                        -- recorded in M0, signature verification later (C3 §2)
  binding_state text NOT NULL CHECK (binding_state IN ('PENDING','BOUND','REPLACED','REVOKED'))
);
CREATE INDEX IF NOT EXISTS devices_tenant_user_idx ON devices (tenant_id, user_id);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  token      text PRIMARY KEY,
  tenant_id  text NOT NULL,
  user_id    uuid NOT NULL,
  device_id  uuid NOT NULL,
  expires_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS locations (
  id                     uuid PRIMARY KEY,
  tenant_id              text NOT NULL,
  type                   text NOT NULL CHECK (type IN ('LHO','RBO','BRANCH','CSP')),
  parent_id              uuid REFERENCES locations(id),
  name                   text NOT NULL,
  code                   text NOT NULL,
  address                text,
  state                  text,
  district               text,
  pin_code               text,
  coord_lat              double precision NOT NULL,
  coord_lng              double precision NOT NULL,
  coordinates            geography(Point, 4326) NOT NULL,  -- PostGIS; kept in sync with coord_lat/lng by the repository insert
  radius_m               double precision NOT NULL,
  coordinate_confidence  text NOT NULL CHECK (coordinate_confidence IN ('UNVERIFIED','FIELD_CAPTURED','VERIFIED')),
  status                 text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED','RELOCATED')),
  updated_at             timestamptz NOT NULL              -- delta-pull cursor (C3 §6)
);
CREATE INDEX IF NOT EXISTS locations_tenant_updated_idx ON locations (tenant_id, updated_at, id);
CREATE INDEX IF NOT EXISTS locations_gix ON locations USING GIST (coordinates);

CREATE TABLE IF NOT EXISTS beat_plans (
  id         uuid PRIMARY KEY,
  tenant_id  text NOT NULL,
  dc_user_id uuid NOT NULL REFERENCES users(id),
  plan_date  date NOT NULL,                  -- IST calendar date (C1 beat-plan schema)
  version    integer NOT NULL CHECK (version >= 1),
  updated_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS beat_plans_tenant_dc_date_idx ON beat_plans (tenant_id, dc_user_id, plan_date);

CREATE TABLE IF NOT EXISTS beat_plan_stops (
  id           uuid PRIMARY KEY,
  tenant_id    text NOT NULL,
  beat_plan_id uuid NOT NULL REFERENCES beat_plans(id),
  seq          integer NOT NULL CHECK (seq >= 1),
  location_id  uuid NOT NULL REFERENCES locations(id),
  visit_type   text NOT NULL CHECK (visit_type IN ('CSP_AUDIT','CSP_FOLLOWUP','BRANCH_MEETING','RBO_MEETING','LHO_MEETING'))
);
CREATE INDEX IF NOT EXISTS beat_plan_stops_plan_idx ON beat_plan_stops (tenant_id, beat_plan_id, seq);

CREATE TABLE IF NOT EXISTS geo_assignments (
  id         uuid PRIMARY KEY,
  tenant_id  text NOT NULL,
  am_user_id uuid NOT NULL REFERENCES users(id),
  dc_user_id uuid NOT NULL REFERENCES users(id),
  valid_from date NOT NULL,
  valid_to   date                            -- NULL = open-ended (time-bounded scope, C6)
);
CREATE INDEX IF NOT EXISTS geo_assignments_am_idx ON geo_assignments (tenant_id, am_user_id, valid_from, valid_to);

-- APPEND-ONLY EVIDENCE (ADR-0003). No UPDATE path, ever.
CREATE TABLE IF NOT EXISTS checkin_events (
  id                  uuid PRIMARY KEY,     -- client UUIDv7; idempotency key for life
  tenant_id           text NOT NULL,
  dc_user_id          uuid NOT NULL,
  device_id           uuid NOT NULL,
  location_id         uuid NOT NULL,
  planned_stop_id     uuid,
  beat_plan_version   integer,
  fix                 jsonb NOT NULL,        -- geoPoint as captured (lat, lng, accuracy_m, provider, is_mock)
  device_wall_time    timestamptz NOT NULL,  -- triple timestamps (C3 §4)
  monotonic_ms        bigint NOT NULL,
  server_received_at  timestamptz NOT NULL,
  out_of_radius_reason text CHECK (out_of_radius_reason IN ('INSIDE_PREMISES_GPS_WEAK','CSP_RELOCATED','MASTER_PIN_WRONG','OTHER')),
  remarks             text
);
CREATE INDEX IF NOT EXISTS checkin_events_tenant_dc_idx ON checkin_events (tenant_id, dc_user_id, device_wall_time);

-- Visit read model (C2 Visit): derived at ingest, keyed by event id.
-- Insert-if-absent only in M0 (re-derivation would be a rebuild, not an UPDATE of evidence).
CREATE TABLE IF NOT EXISTS visits (
  id                     uuid PRIMARY KEY,   -- = checkin_events.id
  tenant_id              text NOT NULL,
  dc_user_id             uuid NOT NULL,
  location_id            uuid NOT NULL,
  planned                boolean NOT NULL,
  occurred_at            timestamptz NOT NULL,  -- device_wall_time
  server_received_at     timestamptz NOT NULL,
  fix                    jsonb NOT NULL,
  distance_from_master_m double precision NOT NULL,
  geofence_result        text NOT NULL CHECK (geofence_result IN ('INSIDE','OUTSIDE_FLAGGED')),
  out_of_radius_reason   text,
  sync_state             text NOT NULL CHECK (sync_state IN ('SYNCED','LATE_SYNC')),
  occurred_ist_date      date NOT NULL           -- IST calendar date of occurred_at (dashboard filter)
);
CREATE INDEX IF NOT EXISTS visits_tenant_date_dc_idx ON visits (tenant_id, occurred_ist_date, dc_user_id);

-- Sync op dedupe (C3 §3): stored dispositions guarantee byte-identical duplicate acks.
CREATE TABLE IF NOT EXISTS sync_op_dispositions (
  tenant_id     text NOT NULL,
  op_id         uuid NOT NULL,
  disposition   jsonb NOT NULL,
  first_seen_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, op_id)
);

-- Quarantine (ADR-0003): raw payloads persisted verbatim, never discarded.
CREATE TABLE IF NOT EXISTS quarantined_ops (
  tenant_id            text NOT NULL,
  op_id                uuid NOT NULL,
  batch_id             uuid,
  device_id            uuid,
  submitted_by_user_id uuid,
  reason               text NOT NULL CHECK (reason IN ('SCHEMA_INVALID','UNKNOWN_REFERENCE')),
  errors               jsonb NOT NULL,
  raw                  jsonb,
  received_at          timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, op_id)
);

-- M1 stub: GPS track points, monthly-partition-ready (declared, no partitions yet).
CREATE TABLE IF NOT EXISTS gps_track_points (
  tenant_id   text NOT NULL,
  id          uuid NOT NULL,
  dc_user_id  uuid NOT NULL,
  device_id   uuid NOT NULL,
  fix         jsonb NOT NULL,
  captured_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, id, captured_at)
) PARTITION BY RANGE (captured_at);
-- Monthly partitions created by an M1 migration, e.g.:
--   CREATE TABLE gps_track_points_2026_08 PARTITION OF gps_track_points
--     FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
