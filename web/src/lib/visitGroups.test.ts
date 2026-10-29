import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Visit } from '../api/client.ts';
import { groupRepeatVisits } from './visitGroups.ts';

const visit = (id: string, dc: string, loc: string, at: string): Visit => ({
  id,
  dc_user_id: dc,
  location_id: loc,
  checkin: { occurred_at: at, fix: { lat: 25.1, lng: 85.1 } },
  geofence_result: 'INSIDE',
  sync_state: 'SYNCED',
});

test('same DC at the same CSP twice reads as one row with the later check-in as a repeat', () => {
  const groups = groupRepeatVisits([
    visit('b', 'anish', '1A850967', '2026-10-29T09:07:00Z'), // 14:37 IST
    visit('a', 'anish', '1A850967', '2026-10-29T05:29:00Z'), // 10:59 IST
  ]);
  assert.equal(groups.length, 1);
  assert.equal(groups[0]!.first.id, 'a');
  assert.deepEqual(groups[0]!.repeats.map((v) => v.id), ['b']);
});

test('different CSPs, or different DCs at one CSP, stay separate rows in check-in order', () => {
  const groups = groupRepeatVisits([
    visit('c', 'balram', '1A850967', '2026-10-29T08:00:00Z'),
    visit('a', 'anish', '1A850967', '2026-10-29T05:00:00Z'),
    visit('b', 'anish', 'OTHER', '2026-10-29T06:00:00Z'),
  ]);
  assert.deepEqual(groups.map((g) => g.first.id), ['a', 'b', 'c']);
  assert.ok(groups.every((g) => g.repeats.length === 0));
});
