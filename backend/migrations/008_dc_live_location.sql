-- v0.12.0: latest-known DC position — a derived, overwritable READ projection,
-- not evidence (the append-only track_chunks table remains the source of
-- truth; this is purely an O(1) "where is this DC right now" cache so the
-- live map never has to scan/aggregate jsonb chunks on every poll).
-- One row per (tenant, dc_user) — upserted from the same track.chunk op the
-- sync engine already processes, keeping only the newest point by
-- captured_at so out-of-order batch delivery can never regress it
-- (C3 §3 convergence: the read model is a pure function of the max, order
-- of arrival doesn't matter).
CREATE TABLE IF NOT EXISTS dc_live_location (
  tenant_id text NOT NULL,
  dc_user_id uuid NOT NULL REFERENCES users (id),
  device_id uuid NOT NULL,
  lat double precision NOT NULL,
  lng double precision NOT NULL,
  accuracy_m double precision,
  captured_at timestamptz NOT NULL,      -- device wall time of the fix (track point's own `t`)
  server_received_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, dc_user_id)
);
