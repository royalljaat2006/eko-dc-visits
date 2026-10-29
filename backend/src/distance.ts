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
/** Smallest move counted even when both fixes claim perfect accuracy. */
const MIN_MOVE_M = 25;

export const DISTANCE_ALGORITHM = "track_straightline_v0" as const;

/**
 * Sums real movement only. A phone lying still reports fixes that wander by up
 * to its accuracy radius every ~20 s; adding every hop turned a desk day into
 * 100+ km. So distance is measured from the last *accepted* position (the
 * anchor) and counted only once the DC has moved further than the two fixes'
 * combined uncertainty. Teleports re-anchor without counting, as before.
 */
export function kmForPoints(points: readonly TrackPoint[]): number {
  if (points.length < 2) return 0;
  const sorted = [...points].sort((a, b) => a.t.localeCompare(b.t));
  let meters = 0;
  let anchor = sorted[0]!;
  for (let i = 1; i < sorted.length; i++) {
    const prev = sorted[i - 1]!;
    const p = sorted[i]!;
    const dtH = (new Date(p.t).getTime() - new Date(prev.t).getTime()) / 3_600_000;
    if (dtH <= 0) continue; // duplicate/zero-dt fixes contribute nothing
    if (haversineMeters(prev, p) / 1000 / dtH > MAX_PLAUSIBLE_KMH) {
      anchor = p; // teleport — excluded, event still stored
      continue;
    }
    const d = haversineMeters(anchor, p);
    if (d < Math.max(MIN_MOVE_M, (anchor.accuracy_m ?? 0) + (p.accuracy_m ?? 0))) continue; // GPS jitter
    meters += d;
    anchor = p;
  }
  return Math.round(meters / 100) / 10; // 0.1 km resolution
}
