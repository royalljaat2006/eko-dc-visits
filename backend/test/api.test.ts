/**
 * HTTP-layer tests against contracts/c2-api/openapi.yaml + C6 scoping
 * (contracts v0.2.0 — design 0001 circle hierarchy).
 * Uses Fastify inject (no ports). The critical assertion: a Circle Head sees
 * ONLY their circle's DCs' visits, filtered in the repository query layer via
 * the single choke point (ADR-0006) — proven here end-to-end over HTTP.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import type { FastifyInstance } from "fastify";
import { buildServer, DEV_OTP } from "../src/server.js";
import { MemoryRepos } from "../src/repos/memory.js";
import { seedFixtures } from "../src/seed/loader.js";
import { istDateOf } from "../src/geo.js";

const PHONES = { asha: "9800000001", vikram: "9800000002", amPriya: "9800000003", admin: "9800000004", nationalHead: "9800000005" };
const CSP_KISHANGANJ = "018f5a00-0000-7000-8000-000000000105";
// Real clock: jose validates JWT exp against real time, so a pinned past clock
// would mint already-expired tokens (engine-level time semantics are covered
// with a pinned clock in sync.test.ts instead).
const NOW = new Date();
const TODAY_IST = istDateOf(NOW);

let uuidN = 0;
const uuid = (): string => `018f5a00-2222-7000-8000-${String(++uuidN).padStart(12, "0")}`;

async function makeApp(): Promise<{ app: FastifyInstance; repos: MemoryRepos }> {
  const repos = new MemoryRepos();
  await seedFixtures(repos, { now: NOW });
  const app = buildServer({ repos, clock: () => NOW });
  return { app, repos };
}

async function login(app: FastifyInstance, phone: string): Promise<{ token: string; device_id: string; user_id: string }> {
  const req = await app.inject({ method: "POST", url: "/api/v1/auth/otp/request", payload: { phone } });
  assert.equal(req.statusCode, 204);
  const res = await app.inject({
    method: "POST",
    url: "/api/v1/auth/otp/verify",
    payload: { phone, otp: DEV_OTP, device: { hardware: { manufacturer: "Test", model: "Inject", os_version: "14" } } },
  });
  assert.equal(res.statusCode, 200);
  const body = res.json() as { access_token: string; device_id: string; user: { id: string } };
  return { token: body.access_token, device_id: body.device_id, user_id: body.user.id };
}

function syncBatch(dcUserId: string, deviceId: string, locationId: string): object {
  return {
    batch_id: uuid(),
    device_id: deviceId,
    seq_from: 1,
    seq_to: 1,
    client_time: NOW.toISOString(),
    app_version: "0.1.0-test",
    contract_version: "0.1.0",
    ops: [
      {
        op_id: uuid(),
        seq: 1,
        type: "visit.checkin",
        payload: {
          id: uuid(),
          dc_user_id: dcUserId,
          device_id: deviceId,
          location_id: locationId,
          planned_stop_id: null,
          fix: { lat: 25.3602, lng: 85.7591, accuracy_m: 12, provider: "fused" },
          timestamps: { device_wall_time: NOW.toISOString(), monotonic_ms: 42 },
        },
      },
    ],
  };
}

test("auth: wrong OTP → 401 problem-details; bad phone → 400", async () => {
  const { app } = await makeApp();
  const bad = await app.inject({
    method: "POST",
    url: "/api/v1/auth/otp/verify",
    payload: { phone: PHONES.asha, otp: "999999", device: {} },
  });
  assert.equal(bad.statusCode, 401);
  assert.match(bad.headers["content-type"] ?? "", /application\/problem\+json/);
  const badPhone = await app.inject({ method: "POST", url: "/api/v1/auth/otp/request", payload: { phone: "1234" } });
  assert.equal(badPhone.statusCode, 400);
});

test("HG3: REVOKED device binding → 401 on every authed endpoint", async () => {
  const { app, repos } = await makeApp();
  const dc = await login(app, PHONES.asha);
  await repos.setDeviceBindingState("eko", dc.device_id, "REVOKED");
  const res = await app.inject({
    method: "GET",
    url: `/api/v1/dashboard/visits?date=${TODAY_IST}`,
    headers: { authorization: `Bearer ${dc.token}` },
  });
  assert.equal(res.statusCode, 401);
});

test("DC pulls own beat plan for today (seeded night-before per C3 §6)", async () => {
  const { app } = await makeApp();
  const dc = await login(app, PHONES.asha);
  const res = await app.inject({
    method: "GET",
    url: `/api/v1/master-data/beat-plans?from_date=${TODAY_IST}`,
    headers: { authorization: `Bearer ${dc.token}` },
  });
  assert.equal(res.statusCode, 200);
  const { items } = res.json() as { items: Array<{ plan_date: string; stops: unknown[] }> };
  assert.equal(items.length, 1);
  assert.equal(items[0]!.plan_date, TODAY_IST);
  assert.equal(items[0]!.stops.length, 3);
});

test("C6 scoping over HTTP: Circle Head sees own circle DCs only; tenant-root sees all; Circle Head cannot sync", async () => {
  const { app } = await makeApp();
  const asha = await login(app, PHONES.asha);
  const vikram = await login(app, PHONES.vikram);

  for (const dc of [asha, vikram]) {
    const res = await app.inject({
      method: "POST",
      url: "/api/v1/sync/batches",
      headers: { authorization: `Bearer ${dc.token}` },
      payload: syncBatch(dc.user_id, dc.device_id, CSP_KISHANGANJ),
    });
    assert.equal(res.statusCode, 200);
    const { results } = res.json() as { results: Array<{ result: string }> };
    assert.equal(results[0]!.result, "accepted");
  }

  // Circle Head Priya heads Nandpur Circle, whose only DC is asha; vikram is in Betwa Circle (fixtures/circle-memberships.json)
  const am = await login(app, PHONES.amPriya);
  const amView = await app.inject({
    method: "GET",
    url: `/api/v1/dashboard/visits?date=${TODAY_IST}`,
    headers: { authorization: `Bearer ${am.token}` },
  });
  assert.equal(amView.statusCode, 200);
  const amItems = (amView.json() as { items: Array<{ dc_user_id: string; dc_name: string }> }).items;
  assert.equal(amItems.length, 1, "Circle Head must see exactly her circle DC's visit");
  assert.equal(amItems[0]!.dc_user_id, asha.user_id);
  assert.ok(amItems.every((v) => v.dc_user_id !== vikram.user_id), "unassigned DC's visits must be filtered server-side");

  const admin = await login(app, PHONES.admin);
  const adminView = await app.inject({
    method: "GET",
    url: `/api/v1/dashboard/visits?date=${TODAY_IST}`,
    headers: { authorization: `Bearer ${admin.token}` },
  });
  assert.equal((adminView.json() as { items: unknown[] }).items.length, 2, "tenant root sees both");

  // National Head: tenant-root READ visibility (design 0001 §7)
  const nh = await login(app, PHONES.nationalHead);
  const nhView = await app.inject({
    method: "GET",
    url: `/api/v1/dashboard/visits?date=${TODAY_IST}`,
    headers: { authorization: `Bearer ${nh.token}` },
  });
  assert.equal((nhView.json() as { items: unknown[] }).items.length, 2, "National Head sees all circles' visits");
  const nhSync = await app.inject({
    method: "POST",
    url: "/api/v1/sync/batches",
    headers: { authorization: `Bearer ${nh.token}` },
    payload: syncBatch(nh.user_id, nh.device_id, CSP_KISHANGANJ),
  });
  assert.equal(nhSync.statusCode, 403, "National Head visibility is read-only for evidence");

  // C6: sync-batch create is DC-only (Circle Head gets 403)
  const amSync = await app.inject({
    method: "POST",
    url: "/api/v1/sync/batches",
    headers: { authorization: `Bearer ${am.token}` },
    payload: syncBatch(am.user_id, am.device_id, CSP_KISHANGANJ),
  });
  assert.equal(amSync.statusCode, 403);
});

test("csp-assignments delta pull (design 0001 §5): DC gets own; Circle Head gets circle's; scoped in the query layer", async () => {
  const { app } = await makeApp();
  const asha = await login(app, PHONES.asha);
  const ashaList = await app.inject({
    method: "GET",
    url: "/api/v1/master-data/csp-assignments",
    headers: { authorization: `Bearer ${asha.token}` },
  });
  assert.equal(ashaList.statusCode, 200);
  const ashaItems = (ashaList.json() as { items: Array<{ dc_user_id: string; csp_location_id: string; assigned_by_user_id: string }> }).items;
  assert.equal(ashaItems.length, 5, "asha holds all 5 Nandpur CSPs (fixtures/csp-assignments.json)");
  assert.ok(ashaItems.every((a) => a.dc_user_id === asha.user_id));

  const ch = await login(app, PHONES.amPriya);
  const chList = await app.inject({
    method: "GET",
    url: "/api/v1/master-data/csp-assignments",
    headers: { authorization: `Bearer ${ch.token}` },
  });
  const chItems = (chList.json() as { items: Array<{ assigned_by_user_id: string }> }).items;
  assert.equal(chItems.length, 5, "Circle Head sees her circle's assignments");
  assert.ok(chItems.every((a) => a.assigned_by_user_id === ch.user_id), "assignments record the assigning Circle Head");

  const vikram = await login(app, PHONES.vikram);
  const vList = await app.inject({
    method: "GET",
    url: "/api/v1/master-data/csp-assignments",
    headers: { authorization: `Bearer ${vikram.token}` },
  });
  assert.equal((vList.json() as { items: unknown[] }).items.length, 0, "Betwa DC has no assignments yet");
});

test("DC self-view parity (BUILD_PLAN §7.9): DC reads own visits from the same endpoint", async () => {
  const { app } = await makeApp();
  const asha = await login(app, PHONES.asha);
  await app.inject({
    method: "POST",
    url: "/api/v1/sync/batches",
    headers: { authorization: `Bearer ${asha.token}` },
    payload: syncBatch(asha.user_id, asha.device_id, CSP_KISHANGANJ),
  });
  const own = await app.inject({
    method: "GET",
    url: `/api/v1/dashboard/visits?date=${TODAY_IST}`,
    headers: { authorization: `Bearer ${asha.token}` },
  });
  assert.equal(own.statusCode, 200);
  assert.equal((own.json() as { items: unknown[] }).items.length, 1);
});
