/**
 * Spec (DC navigation): Attendance is first and the other sections stay
 * locked until the day is started — enforced, not just visual. The flag is
 * per-IST-date so it resets every morning; the server remains the authority
 * (this is UX ordering, not a security boundary).
 */
import { todayIstDate } from './format.ts';

const KEY = 'eko.day-started';

export function setDayStarted(started: boolean): void {
  try {
    if (started) sessionStorage.setItem(KEY, todayIstDate());
    else sessionStorage.removeItem(KEY);
  } catch {
    /* storage unavailable */
  }
}

export function isDayStarted(): boolean {
  try {
    return sessionStorage.getItem(KEY) === todayIstDate();
  } catch {
    return false;
  }
}
