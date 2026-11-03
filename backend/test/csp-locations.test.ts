/**
 * GET /dashboard/csp-locations — super admin "CSPs near me": granted per person
 * (users.nearby_csps), not by role. Synthetic data only.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { buildServer, DEV_OTP } from "../src/server.js";
import { MemoryRepos } from "../src/repos/memory.js";
import { seedFixtures } from "./support/fixtures.js";

const GRANTED = "9811100001";

async function makeApp() {
  const repos = new MemoryRepos();
  const now = new Date();
  await seedFixtures(repos, { now });
  await repos.insertUser({
    id: randomUUID(), tenant_id: "eko", name: "Granted Admin", phone: GRANTED,
    role: "CORPORATE_ADMIN", status: "ACTIVE", scope_location_id: null, nearby_csps: true,
  });
  return { app: buildServer({ repos, clock: () => now }), repos };
}
async function tokenFor(app: ReturnType<typeof buildServer>, phone: string): Promise<string> {
  const res = await app.inject({
    method: "POST", url: "/api/v1/auth/otp/verify", payload: { phone, otp: DEV_OTP, device: { hardware: {} } },
  });
  assert.equal(res.statusCode, 200);
  return (res.json() as { access_token: string }).access_token;
}
const get = (app: ReturnType<typeof buildServer>, token: string) =>
  app.inject({ method: "GET", url: "/api/v1/dashboard/csp-locations", headers: { authorization: `Bearer ${token}` } });

test("GET /dashboard/csp-locations: granted super admin gets every CSP with coordinates and its DC", async () => {
  const { app, repos } = await makeApp();
  const res = await get(app, await tokenFor(app, GRANTED));
  assert.equal(res.statusCode, 200);
  const items = (res.json() as { items: Array<Record<string, unknown>> }).items;
  const csps = (await repos.listAllLocations("eko")).filter((l) => l.type === "CSP");
  assert.equal(items.length, csps.length, "every CSP, not just one circle");
  for (const i of items) {
    assert.equal(typeof i.lat, "number");
    assert.equal(typeof i.lng, "number");
    assert.equal(typeof i.coordinates_verified, "boolean");
  }
});

test("GET /dashboard/csp-locations: role alone is not enough — ungranted admin and DC are 403", async () => {
  const { app } = await makeApp();
  assert.equal((await get(app, await tokenFor(app, "9800000004"))).statusCode, 403, "Corporate Admin without the grant");
  assert.equal((await get(app, await tokenFor(app, "9800000001"))).statusCode, 403, "DC");
});

test("GET /dashboard/csp-locations: operator name when known, null while it is just the code", async () => {
  const { app, repos } = await makeApp();
  const csps = (await repos.listAllLocations("eko")).filter((l) => l.type === "CSP");
  const named = { ...csps[0]!, name: "Ramesh Kumar" };
  await repos.insertLocation(named); // same id: overwrites
  const items = (await get(app, await tokenFor(app, GRANTED))).json().items as Array<{ csp_location_id: string; code: string; name: string | null }>;
  assert.equal(items.find((i) => i.csp_location_id === named.id)?.name, "Ramesh Kumar");
  for (const i of items.filter((i) => i.csp_location_id !== named.id)) {
    const loc = csps.find((l) => l.id === i.csp_location_id)!;
    assert.equal(i.name, loc.name === loc.code ? null : loc.name);
  }
});
