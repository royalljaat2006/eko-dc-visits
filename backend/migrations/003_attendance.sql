-- v0.3.0: attendance evidence + commutative day read model (design 0001 §7).

CREATE TABLE attendance_events (
  id uuid PRIMARY KEY,
  tenant_id text NOT NULL,
  dc_user_id uuid NOT NULL REFERENCES users (id),
  device_id uuid NOT NULL,
  kind text NOT NULL CHECK (kind IN ('START','END')),
  fix jsonb,
  face_match jsonb,
  device_wall_time timestamptz NOT NULL,
  monotonic_ms bigint NOT NULL,
  server_received_at timestamptz NOT NULL
);
CREATE INDEX attendance_events_dc_idx ON attendance_events (tenant_id, dc_user_id, device_wall_time);

-- Derived read model; upserts use LEAST/GREATEST so any op order converges (C3 §3).
CREATE TABLE attendance_days (
  tenant_id text NOT NULL,
  dc_user_id uuid NOT NULL REFERENCES users (id),
  ist_date date NOT NULL,
  started_at timestamptz,
  ended_at timestamptz,
  PRIMARY KEY (tenant_id, dc_user_id, ist_date)
);
