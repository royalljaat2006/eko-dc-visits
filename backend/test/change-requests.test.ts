/**
 * CSP Visit Mobile App spec (docs/specs/csp-visit-mobile-app-draft.md):
 * DC change requests -> CH approval queue -> apply/reject; auto-close at
 * 21:00 IST; CH self-attendance; CSP Details with last-visit dates.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import type { FastifyInstance } from "fastify";
import { buildServer, DEV_OTP } from "../src/server.js";
import { MemoryRepos } from "../src/repos/memory.js";
import { seedFixtures } from "../src/seed/loader.js";
import { istDateOf } from "../src/geo.js";
import { deriveAttendance } from "../src/attendance.js";

const CSP_KISHANGANJ = "018f5a00-0000-7000-8000-000000000105";
const NOW = new Date();
const TODAY = istDateOf(NOW);

let n = 0;
const uuid = (): string => `018f5a00-4444-7000-8000-${String(++n).padStart(12, "0")}`;

async function makeApp(): Promise<{ app: FastifyInstance; repos: MemoryRepos }> {
  const repos = new MemoryRepos();
  await seedFixtures(repos, { now: NOW });
  return { app: buildServer({ repos, clock: () => NOW }), repos };
}
async function login(app: FastifyInstance, phone: string) {
  const res = await app.inject({
    method: "POST",
    url: "/api/v1/auth/otp/verify",
    payload: { phone, otp: DEV_OTP, device: { hardware: {} } },
  });
  assert.equal(res.statusCode, 200);
  const b = res.json() as { access_token: string; device_id: string; user: { id: string } };
  return { token: b.access_token, device_id: b.device_id, user_id: b.user.id };
}

test("auto-close derivation (spec FINALIZED): no End Day by 21:00 IST -> AUTO_CLOSED, hours capped at cutoff", () => {
  const day = { tenant_id: "eko", dc_user_id: "x", ist_date: "2026-07-10", started_at: "2026-07-10T03:30:00Z", ended_at: null };
  const before = deriveAttendance(day, "2026-07-10", new Date("2026-07-10T10:00:00Z")); // 15:30 IST
  assert.equal(before.status, "ON_DUTY");
  const after = deriveAttendance(day, "2026-07-10", new Date("2026-07-10T16:00:00Z")); // 21:30 IST
  assert.equal(after.status, "AUTO_CLOSED");
  assert.equal(after.auto_closed, true);
  assert.equal(after.ended_at, "2026-07-10T15:30:00.000Z", "cutoff = 21:00 IST");
  assert.equal(after.hours_worked, 12, "09:00->21:00 IST");
  const ended = deriveAttendance({ ...day, ended_at: "2026-07-10T12:30:00Z" }, "2026-07-10", new Date("2026-07-10T16:00:00Z"));
  assert.equal(ended.status, "ENDED");
  assert.equal(ended.hours_worked, 9);
});

test("Circle Head submits OWN attendance (spec §4); sees self row; cannot submit check-ins", async () => {
  const { app } = await makeApp();
  const ch = await login(app, "9800000003");
  const mkAtt = (kind: "START" | "END") => ({
    op_id: uuid(), seq: ++n, type: kind === "START" ? "attendance.start" : "attendance.end",
    payload: { id: uuid(), dc_user_id: ch.user_id, device_id: ch.device_id, kind,
      timestamps: { device_wall_time: NOW.toISOString(), monotonic_ms: n } },
  });
  const res = await app.inject({
    method: "POST", url: "/api/v1/sync/batches", headers: { authorization: `Bearer ${ch.token}` },
    payload: { batch_id: uuid(), device_id: ch.device_id, seq_from: 1, seq_to: 1,
      client_time: NOW.toISOString(), app_version: "t", contract_version: "0.8.0", ops: [mkAtt("START")] },
  });
  assert.equal((res.json() as { results: Array<{ result: string }> }).results[0]!.result, "accepted");

  const board = await app.inject({
    method: "GET", url: `/api/v1/dashboard/attendance?date=${TODAY}`, headers: { authorization: `Bearer ${ch.token}` },
  });
  const rows = (board.json() as { items: Array<{ dc_name: string; status: string; hours_worked: number | null }> }).items;
  const self = rows.find((r) => r.dc_name === "Priya Sharma");
  assert.ok(self, "CH sees own row on the board");
  assert.ok(self!.status === "ON_DUTY" || self!.status === "AUTO_CLOSED");

  // CH cannot submit a check-in (engine restricts to attendance.*)
  const bad = await app.inject({
    method: "POST", url: "/api/v1/sync/batches", headers: { authorization: `Bearer ${ch.token}` },
    payload: { batch_id: uuid(), device_id: ch.device_id, seq_from: 2, seq_to: 2,
      client_time: NOW.toISOString(), app_version: "t", contract_version: "0.8.0",
      ops: [{ op_id: uuid(), seq: ++n, type: "visit.checkin", payload: { id: uuid(), dc_user_id: ch.user_id,
        device_id: ch.device_id, location_id: CSP_KISHANGANJ, fix: { lat: 25.36, lng: 85.75 },
        timestamps: { device_wall_time: NOW.toISOString(), monotonic_ms: 1 } } }] },
  });
  assert.equal((bad.json() as { results: Array<{ result: string }> }).results[0]!.result, "quarantined");
});

test("impersonation hardening: payload dc_user_id must match the authenticated user", async () => {
  const { app } = await makeApp();
  const asha = await login(app, "9800000001");
  const res = await app.inject({
    method: "POST", url: "/api/v1/sync/batches", headers: { authorization: `Bearer ${asha.token}` },
    payload: { batch_id: uuid(), device_id: asha.device_id, seq_from: 1, seq_to: 1,
      client_time: NOW.toISOString(), app_version: "t", contract_version: "0.8.0",
      ops: [{ op_id: uuid(), seq: 1, type: "visit.checkin", payload: { id: uuid(),
        dc_user_id: "018f5a00-0000-7000-8000-000000000202", device_id: asha.device_id,
        location_id: CSP_KISHANGANJ, fix: { lat: 25.3602, lng: 85.7591, accuracy_m: 10 },
        timestamps: { device_wall_time: NOW.toISOString(), monotonic_ms: 1 } } }] },
  });
  assert.equal((res.json() as { results: Array<{ result: string }> }).results[0]!.result, "quarantined");
});

test("CSP Details (spec §3): assigned list with last-visit date; DC-only", async () => {
  const { app } = await makeApp();
  const asha = await login(app, "9800000001");
  await app.inject({
    method: "POST", url: "/api/v1/sync/batches", headers: { authorization: `Bearer ${asha.token}` },
    payload: { batch_id: uuid(), device_id: asha.device_id, seq_from: 1, seq_to: 1,
      client_time: NOW.toISOString(), app_version: "t", contract_version: "0.8.0",
      ops: [{ op_id: uuid(), seq: 1, type: "visit.checkin", payload: { id: uuid(), dc_user_id: asha.user_id,
        device_id: asha.device_id, location_id: CSP_KISHANGANJ, fix: { lat: 25.3602, lng: 85.7591, accuracy_m: 10 },
        timestamps: { device_wall_time: NOW.toISOString(), monotonic_ms: 1 } } }] },
  });
  const res = await app.inject({ method: "GET", url: "/api/v1/dc/csp-details", headers: { authorization: `Bearer ${asha.token}` } });
  assert.equal(res.statusCode, 200);
  const items = (res.json() as { items: Array<{ code: string; last_visit_date: string | null; lat: number }> }).items;
  assert.equal(items.length, 5);
  const visited = items.find((i) => i.code === "CSP-ND-1001")!;
  assert.equal(visited.last_visit_date, TODAY);
  assert.ok(items.some((i) => i.last_visit_date === null), "unvisited CSPs show null");
  const ch = await login(app, "9800000003");
  const forbidden = await app.inject({ method: "GET", url: "/api/v1/dc/csp-details", headers: { authorization: `Bearer ${ch.token}` } });
  assert.equal(forbidden.statusCode, 403);
});

test("change request lifecycle: DC proposes -> CH approves (applies to master) / rejects with reason", async () => {
  const { app, repos } = await makeApp();
  const asha = await login(app, "9800000001");

  // not-assigned CSP is rejected up front (vikram has none; use an RBO id)
  const notAssigned = await app.inject({
    method: "POST", url: "/api/v1/dc/csp-change-requests", headers: { authorization: `Bearer ${asha.token}` },
    payload: { csp_location_id: "018f5a00-0000-7000-8000-000000000102", changes: { name: "X" } },
  });
  assert.equal(notAssigned.statusCode, 422);

  const create = await app.inject({
    method: "POST", url: "/api/v1/dc/csp-change-requests", headers: { authorization: `Bearer ${asha.token}` },
    payload: { csp_location_id: CSP_KISHANGANJ, changes: { name: "CSP Kishanganj Chowk (New)", mobile_number: "9876543210", lat: 25.3605, hacker_field: "nope" } },
  });
  assert.equal(create.statusCode, 200);
  const request = (create.json() as { request: { id: string; changes: Record<string, { old: unknown; new: unknown }> } }).request;
  assert.deepEqual(Object.keys(request.changes).sort(), ["lat", "mobile_number", "name"], "whitelist drops unknown fields");
  assert.equal(request.changes.name!.old, "CSP Kishanganj Chowk", "old value snapshotted for side-by-side view");

  // CH queue shows it with names
  const ch = await login(app, "9800000003");
  const queue = await app.inject({
    method: "GET", url: "/api/v1/circle/csp-change-requests?status=PENDING", headers: { authorization: `Bearer ${ch.token}` },
  });
  const qItems = (queue.json() as { items: Array<{ id: string; csp_code: string; requested_by_name: string }> }).items;
  assert.equal(qItems.length, 1);
  assert.equal(qItems[0]!.requested_by_name, "Asha Kumari");

  // DC cannot decide
  const dcDecide = await app.inject({
    method: "POST", url: "/api/v1/circle/csp-change-requests/decide", headers: { authorization: `Bearer ${asha.token}` },
    payload: { id: request.id, decision: "APPROVED" },
  });
  assert.equal(dcDecide.statusCode, 403);

  // CH approves -> master updated, coords FIELD_CAPTURED
  const approve = await app.inject({
    method: "POST", url: "/api/v1/circle/csp-change-requests/decide", headers: { authorization: `Bearer ${ch.token}` },
    payload: { id: request.id, decision: "APPROVED" },
  });
  assert.equal(approve.statusCode, 200);
  const loc = await repos.getLocationById("eko", CSP_KISHANGANJ);
  assert.equal(loc!.name, "CSP Kishanganj Chowk (New)");
  assert.equal(loc!.coordinates.lat, 25.3605);
  assert.equal(loc!.coordinate_confidence, "FIELD_CAPTURED");
  assert.equal(loc!.csp_profile?.mobile_number, "9876543210");

  // second request -> reject with reason; master untouched; re-decide blocked
  const create2 = await app.inject({
    method: "POST", url: "/api/v1/dc/csp-change-requests", headers: { authorization: `Bearer ${asha.token}` },
    payload: { csp_location_id: CSP_KISHANGANJ, changes: { address: "New addr" } },
  });
  const req2 = (create2.json() as { request: { id: string } }).request;
  const reject = await app.inject({
    method: "POST", url: "/api/v1/circle/csp-change-requests/decide", headers: { authorization: `Bearer ${ch.token}` },
    payload: { id: req2.id, decision: "REJECTED", rejection_reason: "Address unverified — revisit and confirm" },
  });
  assert.equal((reject.json() as { request: { status: string; rejection_reason: string } }).request.rejection_reason, "Address unverified — revisit and confirm");
  assert.equal((await repos.getLocationById("eko", CSP_KISHANGANJ))!.address, "Kishanganj Chowk, Nandpur");
  const again = await app.inject({
    method: "POST", url: "/api/v1/circle/csp-change-requests/decide", headers: { authorization: `Bearer ${ch.token}` },
    payload: { id: req2.id, decision: "APPROVED" },
  });
  assert.equal(again.statusCode, 422, "decided requests are final");
});
