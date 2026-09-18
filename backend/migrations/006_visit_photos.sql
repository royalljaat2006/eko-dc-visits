-- 006 — visit photos (contracts v0.9.0, sync op visit.photo, tier T2).
-- Append-only evidence (ADR-0003): INSERT ... ON CONFLICT DO NOTHING only.
-- M1 interim: the binary is stored inline (bytes_b64). The M2 hardening moves
-- it to object storage + pre-signed resumable upload (C3 §7) — the row stays,
-- bytes_b64 becomes a storage key.

CREATE TABLE IF NOT EXISTS visit_photos (
  id                 uuid PRIMARY KEY,
  tenant_id          text NOT NULL,
  visit_id           uuid NOT NULL,
  dc_user_id         uuid NOT NULL,
  device_id          uuid NOT NULL,
  category           text NOT NULL CHECK (category IN ('SHOPFRONT','INSIDE','QR_DEVICE','BRANDING','OTHER')),
  sha256             text NOT NULL,
  width              int,
  height             int,
  bytes_b64          text NOT NULL,
  watermark          jsonb,
  sidecar_signature  text,
  fix                jsonb,
  upload_state       text NOT NULL CHECK (upload_state IN ('STORED','HASH_MISMATCH')),
  device_wall_time   timestamptz NOT NULL,
  monotonic_ms       bigint NOT NULL,
  server_received_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS visit_photos_visit_idx ON visit_photos (tenant_id, visit_id);
