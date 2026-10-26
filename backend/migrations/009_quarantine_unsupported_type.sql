-- The sync engine quarantines unknown op types and Circle-Head non-attendance
-- ops with reason UNSUPPORTED_TYPE (engine.ts), but 001's CHECK only allowed
-- SCHEMA_INVALID / UNKNOWN_REFERENCE, so Postgres rejected the insert and the
-- whole sync batch failed with a 500 (the phone then retried it forever).
ALTER TABLE quarantined_ops DROP CONSTRAINT IF EXISTS quarantined_ops_reason_check;
ALTER TABLE quarantined_ops ADD CONSTRAINT quarantined_ops_reason_check
  CHECK (reason IN ('SCHEMA_INVALID','UNKNOWN_REFERENCE','UNSUPPORTED_TYPE'));
