import { test } from 'node:test';
import assert from 'node:assert/strict';
import { geofenceChip, syncChip, plannedLabel } from './chips.ts';

test('geofenceChip INSIDE is green with no tooltip', () => {
  const chip = geofenceChip('INSIDE');
  assert.equal(chip.label, 'INSIDE');
  assert.ok(chip.className.includes('chip-green'));
  assert.equal(chip.title, '');
});

test('geofenceChip OUTSIDE_FLAGGED is amber and carries out_of_radius_reason as tooltip', () => {
  const chip = geofenceChip('OUTSIDE_FLAGGED', 'gate locked, met CSP outside');
  assert.equal(chip.label, 'OUTSIDE_FLAGGED');
  assert.ok(chip.className.includes('chip-amber'));
  assert.equal(chip.title, 'gate locked, met CSP outside');
});

test('geofenceChip OUTSIDE_FLAGGED tolerates null/absent reason (spec allows null)', () => {
  assert.equal(geofenceChip('OUTSIDE_FLAGGED', null).title, '');
  assert.equal(geofenceChip('OUTSIDE_FLAGGED').title, '');
});

test('syncChip LATE_SYNC is amber; SYNCED is neutral', () => {
  assert.ok(syncChip('LATE_SYNC').className.includes('chip-amber'));
  assert.equal(syncChip('LATE_SYNC').label, 'LATE_SYNC');
  assert.ok(syncChip('SYNCED').className.includes('chip-neutral'));
});

test('plannedLabel maps true/false/absent', () => {
  assert.equal(plannedLabel(true), 'Planned');
  assert.equal(plannedLabel(false), 'Unplanned');
  assert.equal(plannedLabel(undefined), '—');
});

import { attendanceChip } from './chips.ts';

test('attendanceChip: ON_DUTY green, ENDED neutral, NOT_STARTED amber with tooltip', () => {
  assert.equal(attendanceChip('ON_DUTY').className, 'chip chip-green');
  assert.equal(attendanceChip('ENDED').className, 'chip chip-neutral');
  const ns = attendanceChip('NOT_STARTED');
  assert.equal(ns.className, 'chip chip-amber');
  assert.ok(ns.title.length > 0, 'NOT_STARTED explains itself on hover');
});
