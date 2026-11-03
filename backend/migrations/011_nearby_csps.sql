-- Per-person grant for the admin "CSPs near me" view (every CSP's coordinates).
-- Off for everyone; switched on per user by an operator, never by role alone.
ALTER TABLE users ADD COLUMN IF NOT EXISTS nearby_csps boolean NOT NULL DEFAULT false;
