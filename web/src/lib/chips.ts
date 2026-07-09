/**
 * Chip mappings for Visit fields (C2 Visit.geofence_result / Visit.sync_state).
 * Pure functions — unit-tested via node:test.
 */
import type { GeofenceResult, SyncState } from '../api/client.ts';

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
