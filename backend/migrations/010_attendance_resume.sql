-- End Day followed by Resume Day: keep the latest START so a resumed day reads ON_DUTY
-- instead of ENDED. Additive column; GREATEST merge keeps it commutative.
ALTER TABLE attendance_days ADD COLUMN IF NOT EXISTS last_started_at timestamptz;

-- Backfill from the append-only events (IST calendar date, same as istDateOf).
UPDATE attendance_days d
SET last_started_at = s.latest
FROM (
  SELECT tenant_id, dc_user_id, (device_wall_time AT TIME ZONE 'Asia/Kolkata')::date AS ist_date,
         max(device_wall_time) AS latest
  FROM attendance_events
  WHERE kind = 'START'
  GROUP BY 1, 2, 3
) s
WHERE d.tenant_id = s.tenant_id AND d.dc_user_id = s.dc_user_id AND d.ist_date = s.ist_date
  AND d.last_started_at IS NULL;
