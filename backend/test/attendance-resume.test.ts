/**
 * End Day followed by Resume Day: the day must read ON_DUTY again (dashboard,
 * live tracking, nearest-CSP), and the merge must stay order-independent.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { deriveAttendance, effectiveEndedAt } from "../src/attendance.js";
import { MemoryRepos } from "../src/repos/memory.js";

const DATE = "2026-10-29";
const NOON = new Date("2026-10-29T06:30:00.000Z"); // 12:00 IST
const START = "2026-10-29T03:30:00.000Z"; // 09:00 IST
const END = "2026-10-29T04:30:00.000Z"; // 10:00 IST (ended by mistake)
const RESUME = "2026-10-29T04:35:00.000Z"; // 10:05 IST
const FINAL_END = "2026-10-29T12:30:00.000Z"; // 18:00 IST

async function dayAfter(ops: Array<["START" | "END", string]>) {
  const repos = new MemoryRepos();
  for (const [kind, at] of ops) await repos.mergeAttendanceDay("eko", "dc-1", DATE, kind, at);
  return repos.getAttendanceDay("eko", "dc-1", DATE);
}

test("start → end → resume reads ON_DUTY, started at the first start", async () => {
  const d = deriveAttendance(await dayAfter([["START", START], ["END", END], ["START", RESUME]]), DATE, NOON);
  assert.equal(d.status, "ON_DUTY");
  assert.equal(d.started_at, START);
  assert.equal(d.ended_at, null);
});

test("resumed day that ends again reads ENDED with full hours", async () => {
  const d = deriveAttendance(
    await dayAfter([["START", START], ["END", END], ["START", RESUME], ["END", FINAL_END]]),
    DATE,
    NOON,
  );
  assert.equal(d.status, "ENDED");
  assert.equal(d.ended_at, FINAL_END);
  assert.equal(d.hours_worked, 9);
});

test("merge is order-independent (late sync of the same ops)", async () => {
  const inOrder = await dayAfter([["START", START], ["END", END], ["START", RESUME]]);
  const shuffled = await dayAfter([["START", RESUME], ["END", END], ["START", START], ["START", RESUME]]);
  assert.deepEqual(shuffled, inOrder);
  assert.equal(effectiveEndedAt(shuffled), null);
});

test("plain start → end still reads ENDED; resumed day past 21:00 auto-closes", async () => {
  assert.equal(deriveAttendance(await dayAfter([["START", START], ["END", END]]), DATE, NOON).status, "ENDED");
  const late = new Date("2026-10-29T16:00:00.000Z"); // 21:30 IST
  assert.equal(deriveAttendance(await dayAfter([["START", START], ["END", END], ["START", RESUME]]), DATE, late).status, "AUTO_CLOSED");
});

test("rows written before migration 010 (no last_started_at) behave as before", () => {
  const legacy = { tenant_id: "eko", dc_user_id: "dc-1", ist_date: DATE, started_at: START, ended_at: END };
  assert.equal(effectiveEndedAt(legacy), END);
});
