/**
 * Timestamp rendering. ADR-0009: timestamps are stored UTC, rendered IST (Asia/Kolkata).
 * Pure functions — unit-tested via node:test (no DOM).
 */

const IST_TZ = 'Asia/Kolkata';

const dateTimeFmt = new Intl.DateTimeFormat('en-GB', {
  timeZone: IST_TZ,
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

function parts(d: Date, fmt: Intl.DateTimeFormat): Record<string, string> {
  const out: Record<string, string> = {};
  for (const p of fmt.formatToParts(d)) out[p.type] = p.value;
  return out;
}

/**
 * Render an ISO-8601 instant as IST "DD-MM-YYYY HH:mm".
 * Returns '—' for missing/unparseable input (render honestly, never fabricate).
 */
export function formatIstDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  const p = parts(d, dateTimeFmt);
  // Intl 'en-GB' can render midnight as "24"; normalize to "00".
  const hour = p['hour'] === '24' ? '00' : p['hour'];
  return `${p['day']}-${p['month']}-${p['year']} ${hour}:${p['minute']}`;
}

const dateOnlyFmt = new Intl.DateTimeFormat('en-CA', {
  // en-CA yields YYYY-MM-DD ordering
  timeZone: IST_TZ,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

/**
 * Today's calendar date in IST as "YYYY-MM-DD" (the format C2 expects for
 * GET /dashboard/visits?date= and what <input type=date> uses).
 */
export function todayIstDate(now: Date = new Date()): string {
  const p = parts(now, dateOnlyFmt);
  return `${p['year']}-${p['month']}-${p['day']}`;
}
