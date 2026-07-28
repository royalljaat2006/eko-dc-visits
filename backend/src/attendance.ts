/**
 * Attendance day derivation (spec FINALIZED decision + C2 v0.8.0):
 * status NOT_STARTED | ON_DUTY | ENDED | AUTO_CLOSED, where AUTO_CLOSED means
 * no End Day by the 21:00 IST cutoff — surfaced as "Auto-closed, not confirmed
 * by user". Pure derivation at read time (no scheduler needed, serverless-safe,
 * and consistent with the commutative AttendanceDay merges).
 */
import type { AttendanceDay, AttendanceStatus } from "./domain/types.js";

/** 21:00 IST = 15:30 UTC on the same IST calendar date. */
export function autoCloseCutoffIso(istDate: string): string {
  return `${istDate}T15:30:00.000Z`;
}

export interface DerivedAttendance {
  status: AttendanceStatus;
  started_at: string | null;
  ended_at: string | null;
  auto_closed: boolean;
  /** Check-out − check-in (cutoff-capped when auto-closed), 0.1 h resolution. */
  hours_worked: number | null;
}

export function deriveAttendance(day: AttendanceDay | null, istDate: string, now: Date): DerivedAttendance {
  const started = day?.started_at ?? null;
  const ended = day?.ended_at ?? null;
  const hours = (from: string, to: string): number =>
    Math.max(0, Math.round(((new Date(to).getTime() - new Date(from).getTime()) / 3_600_000) * 10) / 10);

  if (!started) return { status: "NOT_STARTED", started_at: null, ended_at: null, auto_closed: false, hours_worked: null };
  if (ended) return { status: "ENDED", started_at: started, ended_at: ended, auto_closed: false, hours_worked: hours(started, ended) };

  const cutoff = autoCloseCutoffIso(istDate);
  if (now.getTime() >= new Date(cutoff).getTime()) {
    return { status: "AUTO_CLOSED", started_at: started, ended_at: cutoff, auto_closed: true, hours_worked: hours(started, cutoff) };
  }
  return { status: "ON_DUTY", started_at: started, ended_at: null, auto_closed: false, hours_worked: null };
}
