/**
 * dc_score_v1 (contracts/c7-kpis, design 0002) — pure computation over
 * existing evidence read models. Transparent by construction: the DC sees the
 * same card their managers see. NEVER feeds pay or enforcement.
 */
import type { AttendanceDay, VisitView } from "./domain/types.js";

export const SCORE_RULES = {
  VISIT_POINTS: 50,
  GEO_VERIFIED_BONUS: 20,
  ON_TIME_START_POINTS: 30,
  ON_TIME_CUTOFF_IST_MIN: 9 * 60 + 30, // 09:30 IST
  EARLY_BIRD_CUTOFF_IST_MIN: 9 * 60, // 09:00 IST
  STREAK_POINTS_PER_DAY: 10,
  STREAK_CAP: 7,
  PERFECT_DAY_MIN_VISITS: 3,
} as const;

export type Badge = "EARLY_BIRD" | "PERFECT_DAY" | "STREAK_3" | "STREAK_7";

export interface ScorecardRow {
  dc_user_id: string;
  dc_name: string;
  points: number;
  visits_done: number;
  geo_verified_visits: number;
  on_time_start: boolean;
  started_at: string | null;
  streak_days: number;
  badges: Badge[];
}

const IST_OFFSET_MS = 5.5 * 3600 * 1000;

/** Minutes past midnight IST for an ISO instant. */
export function istMinutesOfDay(iso: string): number {
  const shifted = new Date(new Date(iso).getTime() + IST_OFFSET_MS);
  return shifted.getUTCHours() * 60 + shifted.getUTCMinutes();
}

/**
 * Streak = consecutive calendar days ending today with a Start Day event.
 * `daysWithStart` is ordered today-first (index 0 = the requested date).
 */
export function streakDays(daysWithStart: readonly boolean[]): number {
  let streak = 0;
  for (const started of daysWithStart) {
    if (!started) break;
    streak += 1;
  }
  return streak;
}

export function computeScorecard(
  dc: { id: string; name: string },
  visits: readonly VisitView[],
  attendanceToday: AttendanceDay | null,
  daysWithStart: readonly boolean[],
): ScorecardRow {
  const own = visits.filter((v) => v.dc_user_id === dc.id);
  const geoVerified = own.filter((v) => v.geofence_result === "INSIDE").length;

  const startedAt = attendanceToday?.started_at ?? null;
  const startMin = startedAt === null ? null : istMinutesOfDay(startedAt);
  const onTime = startMin !== null && startMin <= SCORE_RULES.ON_TIME_CUTOFF_IST_MIN;
  const streak = streakDays(daysWithStart);

  const points =
    own.length * SCORE_RULES.VISIT_POINTS +
    geoVerified * SCORE_RULES.GEO_VERIFIED_BONUS +
    (onTime ? SCORE_RULES.ON_TIME_START_POINTS : 0) +
    Math.min(streak, SCORE_RULES.STREAK_CAP) * SCORE_RULES.STREAK_POINTS_PER_DAY;

  const badges: Badge[] = [];
  if (startMin !== null && startMin <= SCORE_RULES.EARLY_BIRD_CUTOFF_IST_MIN) badges.push("EARLY_BIRD");
  if (own.length >= SCORE_RULES.PERFECT_DAY_MIN_VISITS && geoVerified === own.length) badges.push("PERFECT_DAY");
  if (streak >= 7) badges.push("STREAK_7");
  else if (streak >= 3) badges.push("STREAK_3");

  return {
    dc_user_id: dc.id,
    dc_name: dc.name,
    points,
    visits_done: own.length,
    geo_verified_visits: geoVerified,
    on_time_start: onTime,
    started_at: startedAt,
    streak_days: streak,
    badges,
  };
}
