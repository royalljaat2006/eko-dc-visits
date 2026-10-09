/**
 * Calling-sheet loader + GET /dashboard/csps (admin "All CSPs" list).
 * Synthetic data only — the real sheet holds personal data and is never in the repo.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildServer, DEV_OTP } from "../src/server.js";
import { MemoryRepos } from "../src/repos/memory.js";
import { seedFixtures } from "./support/fixtures.js";
import { normalizeDcPhone, parseCsv, seedCallingSheet } from "../src/seed/calling-sheet.js";

// Row 0 is a banner row (as in the real export); header repeats "Calling Status".
const CSV = [
  ",,,,,,,,,",
  "CSP ID ,CSP Name,CSP Mobile number,Circle Head Name,District Coordinator,Mobile Number DC,District,State,Circle (LHO),Population,Full Address,Calling Status,Calling Status",
  'ZZ000002,Secret Operator,9000000001,Head One,Dc Alpha,+91 98765 43210,Supaul,Bihar,Patna,Rural,"House 4, Main Rd\nNear School",x,y',
  "ZZ000001,Secret Operator Two,9000000002,Head One,Dc Alpha,9876543210,Giridih,Jharkhand,Patna,Urban,,x,y",
  "ZZ000003,Secret Operator Three,9000000003,Head Two,Dc Beta,not-a-phone,Ludhiana,Punjab,Chandigarh,Rural,,x,y",
  "ZZ000004,Secret Operator Four,9000000004,Head One,Dc Alpha,,Supaul,Bihar,Patna,Rural,,x,y", // blank phone -> filled from Dc Alpha
  "ZZ000005,Secret Operator Five,9000000005,Head One,TBA,,Supaul,Bihar,Patna,Rural,,x,y", // placeholder DC
  "",
].join("\n");

async function makeApp() {
  const repos = new MemoryRepos();
  const now = new Date();
  await seedFixtures(repos, { now });
  const summary = await seedCallingSheet(repos, CSV, { now });
  return { app: buildServer({ repos, clock: () => now }), summary };
}
async function tokenFor(app: ReturnType<typeof buildServer>, phone: string): Promise<string> {
  const res = await app.inject({
    method: "POST", url: "/api/v1/auth/otp/verify", payload: { phone, otp: DEV_OTP, device: { hardware: {} } },
  });
  assert.equal(res.statusCode, 200);
  return (res.json() as { access_token: string }).access_token;
}
const get = (app: ReturnType<typeof buildServer>, token: string) =>
  app.inject({ method: "GET", url: "/api/v1/dashboard/csps", headers: { authorization: `Bearer ${token}` } });

test("csv parser: quoted commas/newlines/quotes, BOM, CRLF", () => {
  assert.deepEqual(parseCsv('﻿a,"b,1","c ""q""\nx"\r\nd,e,f'), [["a", "b,1", 'c "q"\nx'], ["d", "e", "f"]]);
  assert.equal(normalizeDcPhone("+91 98765 43210"), "9876543210");
  assert.equal(normalizeDcPhone("12345"), "");
});

test("loader: CSPs keep sheet order, DCs de-duplicated by phone, unusable phone loaded but not assigned", async () => {
  const { summary } = await makeApp();
  assert.equal(summary.csps, 5);
  assert.equal(summary.dcs, 1, "Dc Alpha appears twice (with and without +91) -> one user");
  assert.equal(summary.circles, 2);
  assert.equal(summary.assigned, 3, "ZZ000004 inherits Dc Alpha's phone; TBA and a bad phone stay unassigned");
  assert.equal(summary.with_address, 1);
  assert.equal(summary.skipped.length, 2);
});

test("GET /dashboard/csps: admin sees sheet order, partial addresses as null, anonymous CSPs, named DC", async () => {
  const { app } = await makeApp();
  const res = await get(app, await tokenFor(app, "9800000004")); // Corporate Admin
  assert.equal(res.statusCode, 200);
  const items = (res.json() as { items: Array<Record<string, unknown>> }).items;
  const sheet = items.filter((i) => i.sheet_row != null);
  assert.deepEqual(sheet.map((i) => i.code), ["ZZ000002", "ZZ000001", "ZZ000003", "ZZ000004", "ZZ000005"], "sheet row order, not code order");
  assert.equal(items.at(-1)?.sheet_row === null, true, "non-sheet (fixture) CSPs sort after the sheet");
  assert.equal(sheet[0]!.address, "House 4, Main Rd\nNear School");
  assert.equal(sheet[1]!.address, null);
  assert.equal(sheet[0]!.circle, "Patna");
  assert.equal(sheet[0]!.dc_name, "Dc Alpha");
  assert.equal(sheet[0]!.dc_phone, "9876543210");
  assert.equal(sheet[2]!.dc_name, null, "bad DC phone -> unassigned");
  assert.equal(sheet[3]!.dc_name, "Dc Alpha", "blank phone filled from the same-named DC in the circle");
  assert.equal(sheet[4]!.dc_name, null, "TBA is not a DC");
  const blob = JSON.stringify(items);
  assert.ok(!blob.includes("Secret Operator"), "CSP operator names must never leave the loader");
  assert.ok(!blob.includes("9000000001"), "CSP operator phones must never leave the loader");
});

test("GET /dashboard/csps: DC and HR are 403; Circle Head only sees own circle", async () => {
  const { app } = await makeApp();
  assert.equal((await get(app, await tokenFor(app, "9800000001"))).statusCode, 403); // DC
  assert.equal((await get(app, await tokenFor(app, "9800000007"))).statusCode, 403); // HR
  const ch = await get(app, await tokenFor(app, "9800000003")); // Circle Head (fixture circle)
  assert.equal(ch.statusCode, 200);
  const items = (ch.json() as { items: Array<{ code: string }> }).items;
  assert.ok(items.length > 0 && items.every((i) => !i.code.startsWith("ZZ")), "sheet circles are not this head's");
});
