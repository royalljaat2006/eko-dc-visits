-- v0.8.0: CSP change requests + master template profile + per-user dashboard link.
ALTER TABLE locations ADD COLUMN csp_profile jsonb;
ALTER TABLE users ADD COLUMN dashboard_url text;
ALTER TABLE users ADD COLUMN home_lat double precision;
ALTER TABLE users ADD COLUMN home_lng double precision;

CREATE TABLE csp_change_requests (
  id uuid PRIMARY KEY,
  tenant_id text NOT NULL,
  csp_location_id uuid NOT NULL REFERENCES locations (id),
  requested_by_user_id uuid NOT NULL REFERENCES users (id),
  changes jsonb NOT NULL,
  status text NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  rejection_reason text,
  decided_by_user_id uuid REFERENCES users (id),
  decided_at timestamptz,
  created_at timestamptz NOT NULL
);
CREATE INDEX csp_change_requests_pending_idx ON csp_change_requests (tenant_id, status, created_at);
