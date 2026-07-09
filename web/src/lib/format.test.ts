import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatIstDateTime, todayIstDate } from './format.ts';

test('formatIstDateTime renders UTC instant in IST (+05:30) as DD-MM-YYYY HH:mm', () => {
  assert.equal(formatIstDateTime('2026-07-08T04:30:00Z'), '08-07-2026 10:00');
});

test('formatIstDateTime crosses the date line: late-UTC evening is next IST day', () => {
  assert.equal(formatIstDateTime('2026-07-07T20:00:00Z'), '08-07-2026 01:30');
});

test('formatIstDateTime handles IST midnight (never renders hour 24)', () => {
  assert.equal(formatIstDateTime('2026-07-07T18:30:00Z'), '08-07-2026 00:00');
});

test('formatIstDateTime respects explicit offsets in input', () => {
  // 09:00 +05:30 is already IST wall time
  assert.equal(formatIstDateTime('2026-07-08T09:00:00+05:30'), '08-07-2026 09:00');
});

test('formatIstDateTime returns em-dash for missing or invalid input', () => {
  assert.equal(formatIstDateTime(undefined), '—');
  assert.equal(formatIstDateTime(null), '—');
  assert.equal(formatIstDateTime(''), '—');
  assert.equal(formatIstDateTime('not-a-date'), '—');
});

test('todayIstDate returns the IST calendar date, not the UTC one', () => {
  // 2026-07-07 22:00 UTC = 2026-07-08 03:30 IST
  assert.equal(todayIstDate(new Date('2026-07-07T22:00:00Z')), '2026-07-08');
  // 2026-07-08 17:00 UTC = 2026-07-08 22:30 IST (same day)
  assert.equal(todayIstDate(new Date('2026-07-08T17:00:00Z')), '2026-07-08');
  // 2026-07-08 18:30 UTC = 2026-07-09 00:00 IST (next day)
  assert.equal(todayIstDate(new Date('2026-07-08T18:30:00Z')), '2026-07-09');
});
