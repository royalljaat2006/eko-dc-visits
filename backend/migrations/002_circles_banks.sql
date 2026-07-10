-- Design 0001: circle hierarchy + multi-bank. Retires geo_assignments.

CREATE TABLE banks (
  id uuid PRIMARY KEY,
  tenant_id text NOT NULL,
  name text NOT NULL,
  code text NOT NULL,
  license_no text,
  license_obtained_on date,
  status text NOT NULL CHECK (status IN ('ONBOARDING','ACTIVE','SUSPENDED')),
  updated_at timestamptz NOT NULL
);
CREATE INDEX banks_tenant_idx ON banks (tenant_id);

ALTER TABLE locations ADD COLUMN bank_id uuid REFERENCES banks (id);
-- backfill strategy for pre-0.2.0 rows is a deploy-time concern; fresh envs seed with bank_id set
CREATE INDEX locations_bank_idx ON locations (tenant_id, bank_id);

CREATE TABLE circles (
  id uuid PRIMARY KEY,
  tenant_id text NOT NULL,
  name text NOT NULL,
  description text,
  status text NOT NULL CHECK (status IN ('ACTIVE','RETIRED')),
  updated_at timestamptz NOT NULL
);
CREATE INDEX circles_tenant_idx ON circles (tenant_id);

-- Effective-dated, never deleted (design 0001 §3).
CREATE TABLE circle_memberships (
  id uuid PRIMARY KEY,
  tenant_id text NOT NULL,
  circle_id uuid NOT NULL REFERENCES circles (id),
  user_id uuid NOT NULL REFERENCES users (id),
  role_in_circle text NOT NULL CHECK (role_in_circle IN ('DC','CIRCLE_HEAD')),
  valid_from date NOT NULL,
  valid_to date
);
CREATE INDEX circle_memberships_lookup_idx ON circle_memberships (tenant_id, user_id, role_in_circle);
CREATE INDEX circle_memberships_circle_idx ON circle_memberships (tenant_id, circle_id);

-- CSP<->DC, Circle-Head-owned; transfers end+start rows, never UPDATE-in-place of history.
CREATE TABLE csp_assignments (
  id uuid PRIMARY KEY,
  tenant_id text NOT NULL,
  circle_id uuid NOT NULL REFERENCES circles (id),
  csp_location_id uuid NOT NULL REFERENCES locations (id),
  dc_user_id uuid NOT NULL REFERENCES users (id),
  assigned_by_user_id uuid NOT NULL REFERENCES users (id),
  reason text NOT NULL CHECK (reason IN ('INITIAL_ALLOCATION','TRANSFER','REBALANCE','COVERAGE_GAP')),
  valid_from date NOT NULL,
  valid_to date,
  updated_at timestamptz NOT NULL
);
CREATE INDEX csp_assignments_dc_idx ON csp_assignments (tenant_id, dc_user_id, valid_from, valid_to);
CREATE INDEX csp_assignments_csp_idx ON csp_assignments (tenant_id, csp_location_id, valid_from, valid_to);
-- At most one ACTIVE (valid_to IS NULL) assignment per CSP (design 0001 §3).
CREATE UNIQUE INDEX csp_assignments_one_active_per_csp
  ON csp_assignments (tenant_id, csp_location_id) WHERE valid_to IS NULL;

DROP TABLE geo_assignments;

-- Role enum change (contracts v0.2.0): 001's CHECK predates the circle hierarchy.
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
  CHECK (role IN ('DC','CIRCLE_HEAD','NATIONAL_HEAD','HR_ADMIN','CORPORATE_ADMIN','BANK_OFFICIAL'));
