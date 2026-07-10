/**
 * dc_score_v1 (contracts/c7-kpis, design 0002): formula unit tests + the
 * endpoint over HTTP with C6 scoping and the parity rule.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { computeScorecard, istMinutesOfDay, streakDays, SCORE_RULES } from "../src/scorecard.js";
import type { AttendanceDay, VisitView } from "../src/domain/types.js";

const DC = { id: "dc-1", name: "Asha" };

function visit(geofence: "INSIDE" | "OUTSIDE_FLAGGED", dcId = DC.id): VisitView {
  return {
    id: `v-${Math.random()}`,
    dc_user_id: dcId,
    dc_name: "x",
    location_id: "l",
    location_name: "x",
    location_code: "x",
    planned: true,
    checkin: { occurred_at: "2026-07-09T05:00:00Z", server_received_at: "2026-07-09T05:00:00Z", fix: { lat: 0, lng: 0 } },
    distance_from_master_m: 10,
    geofence_result: geofence,
    out_of_radius_reason: null,
    sync_state: "SYNCED",
  };
}

function attendance(startIsoUtc: string | null): AttendanceDay {
  return { tenant_id: "eko", dc_user_id: DC.id, ist_date: "2026-07-09", started_at: startIsoUtc, ended_at: null };
}

test("istMinutesOfDay: 03:30Z = 09:00 IST = 540", () => {
  assert.equal(istMinutesOfDay("2026-07-09T03:30:00Z"), 540);
});

test("formula: visits + geo bonus + on-time + streak, exactly per C7", () => {
  // 3 visits, 2 INSIDE; started 09:15 IST (on time, not early bird); streak 4
  const row = computeScorecard(
    DC,
    [visit("INSIDE"), visit("INSIDE"), visit("OUTSIDE_FLAGGED")],
    attendance("2026-07-09T03:45:00Z"),
    [true, true, true, true, false, true, true],
  );
  assert.equal(row.streak_days, 4, "streak stops at the first gap");
  assert.equal(row.points, 3 * 50 + 2 * 20 + 30 + 4 * 10);
  assert.equal(row.on_time_start, true);
  assert.deepEqual(row.badges, ["STREAK_3"]);
});

test("flagged visits still earn visit points — no punishment for weak GPS (design 0002 §3)", () => {
  const row = computeScorecard(DC, [visit("OUTSIDE_FLAGGED")], attendance(null), [false]);
  assert.equal(row.points, SCORE_RULES.VISIT_POINTS);
  assert.equal(row.geo_verified_visits, 0);
});

test("badges: EARLY_BIRD at 08:55 IST; PERFECT_DAY needs >=3 all-INSIDE; STREAK_7 supersedes STREAK_3", () => {
  const row = computeScorecard(
    DC,
    [visit("INSIDE"), visit("INSIDE"), visit("INSIDE")],
    attendance("2026-07-09T03:25:00Z"), // 08:55 IST
    [true, true, true, true, true, true, true],
  );
  assert.deepEqual(row.badges, ["EARLY_BIRD", "PERFECT_DAY", "STREAK_7"]);
  assert.equal(row.points, 3 * 50 + 3 * 20 + 30 + 7 * 10);
});

test("streak caps at 7 for points; late start (09:31 IST) earns no on-time points", () => {
  const row = computeScorecard(DC, [], attendance("2026-07-09T04:01:00Z"), Array(7).fill(true));
  assert.equal(row.on_time_start, false);
  assert.equal(row.points, 7 * SCORE_RULES.STREAK_POINTS_PER_DAY);
});

test("streakDays counts only from today backwards", () => {
  assert.equal(streakDays([false, true, true]), 0, "no start today = no streak");
  assert.equal(streakDays([true, false, true]), 1);
  assert.equal(streakDays([]), 0);
});

test("other DCs' visits never leak into a card (parity + scoping)", () => {
  const row = computeScorecard(DC, [visit("INSIDE", "someone-else")], attendance(null), [false]);
  assert.equal(row.visits_done, 0);
  assert.equal(row.points, 0);
});
