/** track_straightline_v0 (C7): read-time km derivation + convergence with track ops. */
import { test } from "node:test";
import assert from "node:assert/strict";
import { kmForPoints } from "../src/distance.js";
import { MemoryRepos } from "../src/repos/memory.js";
import { seedFixtures } from "./support/fixtures.js";
import { applySyncBatch, type Clock } from "../src/sync/engine.js";
import type { Principal, SyncBatch, TrackPoint } from "../src/domain/types.js";

const DC_ASHA = "018f5a00-0000-7000-8000-000000000201";
const DEVICE = "018f5a00-0000-7000-8000-00000000d001";
const NOW = new Date("2026-07-08T06:30:00Z");
const clock: Clock = () => NOW;
const principal: Principal = { user_id: DC_ASHA, tenant_id: "eko", role: "DC", device_id: DEVICE };

// ~1.113 km per 0.01° latitude
const pt = (latOffset: number, minute: number): TrackPoint => ({
  lat: 25.36 + latOffset,
  lng: 85.75,
  t: `2026-07-08T05:${String(minute).padStart(2, "0")}:00Z`,
});

test("kmForPoints: sums consecutive segments, order-independent, 0.1 km resolution", () => {
  const points = [pt(0, 0), pt(0.01, 10), pt(0.02, 20)]; // ~2.23 km
  const km = kmForPoints(points);
  assert.ok(Math.abs(km - 2.2) <= 0.1, `expected ~2.2, got ${km}`);
  assert.equal(kmForPoints([...points].reverse()), km, "sorting by t makes ingest order irrelevant");
  assert.equal(kmForPoints([pt(0, 0)]), 0, "single fix = 0 km");
});

test("kmForPoints: GPS teleports (>800 km/h) are excluded from the sum", () => {
  // 111 km jump in 1 minute (~6660 km/h) between two normal segments
  const points = [pt(0, 0), pt(0.01, 10), { lat: 26.36, lng: 85.75, t: "2026-07-08T05:11:00Z" }, { lat: 26.37, lng: 85.75, t: "2026-07-08T05:21:00Z" }];
  const km = kmForPoints(points);
  assert.ok(km < 3, `teleport must not count (got ${km})`);
});

test("track.chunk op: accepted, idempotent, km derivable per IST date", async () => {
  const repos = new MemoryRepos();
  await seedFixtures(repos, { now: NOW });
  const chunk = {
    id: "018f5a00-3333-7000-8000-000000000001",
    dc_user_id: DC_ASHA,
    device_id: DEVICE,
    points: [pt(0, 0), pt(0.01, 10), pt(0.02, 20)],
    timestamps: { device_wall_time: NOW.toISOString(), monotonic_ms: 1 },
  };
  const batch: SyncBatch = {
    batch_id: "018f5a00-3333-7000-8000-000000000002",
    device_id: DEVICE,
    seq_from: 1,
    seq_to: 1,
    client_time: NOW.toISOString(),
    app_version: "t",
    contract_version: "0.7.0",
    ops: [{ op_id: chunk.id, seq: 1, type: "track.chunk", payload: chunk }],
  };
  const first = await applySyncBatch(repos, principal, batch, clock);
  assert.equal(first.results[0]!.result, "accepted");
  const replay = await applySyncBatch(repos, principal, batch, clock);
  assert.equal(replay.results[0]!.result, "duplicate");

  const points = await repos.listTrackPointsForDcDate("eko", DC_ASHA, "2026-07-08");
  assert.equal(points.length, 3);
  assert.ok(kmForPoints(points) > 2);
  assert.equal((await repos.listTrackPointsForDcDate("eko", DC_ASHA, "2026-07-09")).length, 0, "IST date filter");
});

test("track.chunk also upserts the live-location projection — newest point wins, order-independent", async () => {
  const repos = new MemoryRepos();
  await seedFixtures(repos, { now: NOW });

  const send = (id: string, points: TrackPoint[]) =>
    applySyncBatch(
      repos,
      principal,
      {
        batch_id: `018f5a00-4444-7000-8000-${id}`,
        device_id: DEVICE,
        seq_from: 1,
        seq_to: 1,
        client_time: NOW.toISOString(),
        app_version: "t",
        contract_version: "0.7.0",
        ops: [{
          op_id: `018f5a00-4444-7000-8000-${id}`,
          seq: 1,
          type: "track.chunk",
          payload: { id: `018f5a00-4444-7000-8000-${id}`, dc_user_id: DC_ASHA, device_id: DEVICE, points, timestamps: { device_wall_time: NOW.toISOString(), monotonic_ms: 1 } },
        }],
      },
      clock,
    );

  await send("000000000001", [pt(0, 0), pt(0.01, 10)]); // newest so far: minute 10
  let live = (await repos.listLiveLocationsForDcs("eko", new Set([DC_ASHA]))).get(DC_ASHA);
  assert.equal(live?.captured_at, "2026-07-08T05:10:00Z");

  // A late-arriving chunk with an OLDER point must not regress the projection.
  await send("000000000002", [pt(0.005, 5)]); // minute 5 — older than what's stored
  live = (await repos.listLiveLocationsForDcs("eko", new Set([DC_ASHA]))).get(DC_ASHA);
  assert.equal(live?.captured_at, "2026-07-08T05:10:00Z", "older point must not overwrite the newer one");

  // A genuinely newer point does advance it.
  await send("000000000003", [pt(0.02, 20)]); // minute 20
  live = (await repos.listLiveLocationsForDcs("eko", new Set([DC_ASHA]))).get(DC_ASHA);
  assert.equal(live?.captured_at, "2026-07-08T05:20:00Z");
  assert.equal(live?.lat, 25.38);

  assert.equal((await repos.listLiveLocationsForDcs("eko", new Set(["no-such-dc"]))).size, 0);
});
