/**
 * track_straightline_v0 — daily km from raw GPS points (C7 distance rules).
 * PROVISIONAL by decree (ADR-0005): displayed with that label, never consumed
 * for reimbursement. The M2 financial layer replaces the calculator (OSRM
 * map-matching, gap annotation, disputes); the evidence capture stays.
 *
 * Computed at READ time from the full sorted point set, so ingest order can
 * never change the result (C3 §3 convergence).
 */
import type { TrackPoint } from "./domain/types.js";
import { haversineMeters } from "./geo.js";

/** Segments implying > this speed are GPS teleports: excluded from km, per the plan's fraud physics. */
const MAX_PLAUSIBLE_KMH = 800;

export const DISTANCE_ALGORITHM = "track_straightline_v0" as const;

export function kmForPoints(points: readonly TrackPoint[]): number {
  if (points.length < 2) return 0;
  const sorted = [...points].sort((a, b) => a.t.localeCompare(b.t));
  let meters = 0;
  for (let i = 1; i < sorted.length; i++) {
    const a = sorted[i - 1]!;
    const b = sorted[i]!;
    const d = haversineMeters(a, b);
    const dtH = (new Date(b.t).getTime() - new Date(a.t).getTime()) / 3_600_000;
    if (dtH <= 0) continue; // duplicate/zero-dt fixes contribute nothing
    if (d / 1000 / dtH > MAX_PLAUSIBLE_KMH) continue; // teleport — excluded, event still stored
    meters += d;
  }
  return Math.round(meters / 100) / 10; // 0.1 km resolution
}
