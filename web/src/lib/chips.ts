/**
 * Chip mappings for Visit fields (C2 Visit.geofence_result / Visit.sync_state).
 * Pure functions — unit-tested via node:test.
 */
import type { AttendanceStatus, GeofenceResult, LiveLocationState, SyncState } from '../api/client.ts';

export interface Chip {
  label: string;
  /** CSS class: chip-green | chip-amber */
  className: string;
  /** Rendered as the title attribute (hover tooltip); empty string = none. */
  title: string;
}

/**
 * Geofence is ADVISORY (ADR-0004, C6 explicit_non_gates): OUTSIDE_FLAGGED is a
 * flag for AM review, never a block. Amber, with out_of_radius_reason as tooltip.
 */
export function geofenceChip(result: GeofenceResult, outOfRadiusReason?: string | null): Chip {
  if (result === 'INSIDE') {
    return { label: 'INSIDE', className: 'chip chip-green', title: '' };
  }
  return {
    label: 'OUTSIDE_FLAGGED',
    className: 'chip chip-amber',
    title: outOfRadiusReason ?? '',
  };
}

/** LATE_SYNC renders amber; SYNCED is neutral. */
export function syncChip(state: SyncState): Chip {
  if (state === 'LATE_SYNC') {
    return { label: 'LATE_SYNC', className: 'chip chip-amber', title: 'Evidence synced late' };
  }
  return { label: 'SYNCED', className: 'chip chip-neutral', title: '' };
}

/** planned=true → "Planned", planned=false → "Unplanned", absent → "—" (spec marks it optional). */
export function plannedLabel(planned: boolean | undefined): string {
  if (planned === undefined) return '—';
  return planned ? 'Planned' : 'Unplanned';
}

/**
 * Attendance board chips (C2 /dashboard/attendance, design 0001 §7).
 * ON_DUTY green; ENDED neutral; NOT_STARTED amber — it's the row a National
 * Head scans the board for.
 */
export function attendanceChip(status: AttendanceStatus): Chip {
  switch (status) {
    case 'ON_DUTY':
      return { label: 'ON DUTY', className: 'chip chip-green', title: '' };
    case 'ENDED':
      return { label: 'ENDED', className: 'chip chip-neutral', title: '' };
    case 'NOT_STARTED':
      return { label: 'NOT STARTED', className: 'chip chip-amber', title: 'No Start Day event synced for this date' };
    case 'AUTO_CLOSED':
      return { label: 'AUTO-CLOSED', className: 'chip chip-amber', title: 'Auto-closed at 21:00 IST — not confirmed by user' };
  }
}

/**
 * Live-tracking map chips (C2 /dashboard/live-locations, v0.12.0).
 * live = green (fresh fix, on duty); stale = amber (on duty, fix older than
 * the threshold); off_duty / no_fix are neutral — nothing to flag, just status.
 */
export function liveLocationChip(state: LiveLocationState): Chip {
  switch (state) {
    case 'live':
      return { label: 'LIVE', className: 'chip chip-green', title: '' };
    case 'stale':
      return { label: 'STALE', className: 'chip chip-amber', title: 'Last fix is older than the freshness threshold' };
    case 'off_duty':
      return { label: 'OFF DUTY', className: 'chip chip-neutral', title: 'Not currently Checked In' };
    case 'no_fix':
      return { label: 'NO FIX', className: 'chip chip-neutral', title: 'No location reported yet' };
  }
}
