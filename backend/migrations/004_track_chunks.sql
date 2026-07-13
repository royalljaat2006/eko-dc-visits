-- v0.7.0: raw GPS track chunks (append-only). Pilot-scale jsonb storage; the
-- M2 scale step is row-per-point monthly partitions per BUILD_PLAN §3.1.
CREATE TABLE track_chunks (
  id uuid PRIMARY KEY,
  tenant_id text NOT NULL,
  dc_user_id uuid NOT NULL REFERENCES users (id),
  device_id uuid NOT NULL,
  points jsonb NOT NULL,
  device_wall_time timestamptz NOT NULL,
  monotonic_ms bigint NOT NULL,
  server_received_at timestamptz NOT NULL
);
CREATE INDEX track_chunks_dc_idx ON track_chunks (tenant_id, dc_user_id, device_wall_time);
