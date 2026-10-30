import type { Visit } from '../api/client.ts';

/** A DC's visits to one location on the viewed date: the earliest is shown, later ones listed. */
export interface VisitGroup {
  first: Visit;
  repeats: Visit[];
}

/**
 * Display-only grouping: every visit stays in the data (evidence is append-only),
 * but repeat check-ins by the same DC at the same location — e.g. the old app's
 * auto check-in firing again as the DC passed by — read as one row, not a duplicate.
 */
export function groupRepeatVisits(visits: readonly Visit[]): VisitGroup[] {
  const sorted = [...visits].sort((a, b) => a.checkin.occurred_at.localeCompare(b.checkin.occurred_at));
  const byKey = new Map<string, VisitGroup>();
  for (const v of sorted) {
    const key = `${v.dc_user_id}|${v.location_id}`;
    const g = byKey.get(key);
    if (g) g.repeats.push(v);
    else byKey.set(key, { first: v, repeats: [] });
  }
  return [...byKey.values()];
}
