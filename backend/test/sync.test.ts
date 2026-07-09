/**
 * Sync engine tests against contracts/c3-sync/PROTOCOL.md §3–4 and ADR-0004,
 * including the standing convergence property test (C3 §3: any permutation +
 * duplication of batches converges to identical store state).
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { MemoryRepos } from "../src/repos/memory.js";
import { seedFixtures } from "../src/seed/loader.js";
import { applySyncBatch, type Clock } from "../src/sync/engine.js";
import type { CheckInEvent, Principal, SyncBatch } from "../src/domain/types.js";
import type { Scope } from "../src/repos/types.js";

const DC_ASHA = "018f5a00-0000-7000-8000-000000000201";
const CSP_KISHANGANJ = "018f5a00-0000-7000-8000-000000000105"; // radius 100m @ 25.3602,85.7591
const CSP_MAHUA = "018f5a00-0000-7000-8000-000000000106"; // radius 120m @ 25.3721,85.7488
const DEVICE = "018f5a00-0000-7000-8000-00000000d001";

const NOW = new Date("2026-07-08T06:30:00Z"); // 12:00 IST
const clock: Clock = () => NOW;
const principal: Principal = { user_id: DC_ASHA, tenant_id: "eko", role: "DC", device_id: DEVICE };
const ADMIN_SCOPE: Scope = { tenant_id: "eko", dc_user_ids: "ALL", location_ids: "ALL" };

let opCounter = 0;
function uuidish(n: number): string {
  return `018f5a00-1111-7000-8000-${String(n).padStart(12, "0")}`;
}
function checkin(overrides: Partial<CheckInEvent> = {}): CheckInEvent {
  opCounter += 1;
  return {
    id: uuidish(opCounter),
    dc_user_id: DC_ASHA,
    device_id: DEVICE,
    location_id: CSP_KISHANGANJ,
    planned_stop_id: null,
    fix: { lat: 25.3602, lng: 85.7591, accuracy_m: 10, provider: "fused" },
    timestamps: { device_wall_time: "2026-07-08T05:30:00Z", monotonic_ms: 1_000_000 },
    ...overrides,
  };
}
function batchOf(events: (CheckInEvent | Record<string, unknown>)[], batchN = 1): SyncBatch {
  return {
    batch_id: uuidish(900000 + batchN),
    device_id: DEVICE,
    seq_from: 1,
    seq_to: events.length,
    client_time: NOW.toISOString(),
    app_version: "0.1.0-test",
    contract_version: "0.1.0",
    ops: events.map((payload, i) => ({
      op_id: (payload as CheckInEvent).id ?? uuidish(800000 + batchN * 100 + i),
      seq: i + 1,
      type: "visit.checkin",
      payload,
    })),
  };
}
async function seeded(): Promise<MemoryRepos> {
  const repos = new MemoryRepos();
  await seedFixtures(repos, { now: NOW });
  return repos;
}

test("inside radius → accepted, visit INSIDE, SYNCED", async () => {
  const repos = await seeded();
  const res = await applySyncBatch(repos, principal, batchOf([checkin()]), clock);
  assert.equal(res.results[0]!.result, "accepted");
  const visits = await repos.listVisitsByIstDate(ADMIN_SCOPE, "2026-07-08");
  assert.equal(visits.length, 1);
  assert.equal(visits[0]!.geofence_result, "INSIDE");
  assert.equal(visits[0]!.sync_state, "SYNCED");
});

test("effective radius = radius + accuracy (ADR-0004): 161m away with 80m accuracy vs 100m radius → INSIDE", async () => {
  const repos = await seeded();
  // +0.00145° latitude ≈ 161m north of CSP_KISHANGANJ (radius 100m)
  const ev = checkin({ fix: { lat: 25.3602 + 0.00145, lng: 85.7591, accuracy_m: 80 } });
  const res = await applySyncBatch(repos, principal, batchOf([ev]), clock);
  assert.equal(res.results[0]!.result, "accepted");
  const v = (await repos.listVisitsByIstDate(ADMIN_SCOPE, "2026-07-08"))[0]!;
  assert.equal(v.geofence_result, "INSIDE");
  assert.ok(v.distance_from_master_m > 100, "must actually be outside the raw radius");
});

test("outside effective radius → accepted-flagged OUTSIDE_RADIUS, never rejected", async () => {
  const repos = await seeded();
  // ~888m north, tight accuracy
  const ev = checkin({
    fix: { lat: 25.3602 + 0.008, lng: 85.7591, accuracy_m: 5 },
    out_of_radius_reason: "INSIDE_PREMISES_GPS_WEAK",
  });
  const res = await applySyncBatch(repos, principal, batchOf([ev]), clock);
  assert.equal(res.results[0]!.result, "accepted-flagged");
  assert.deepEqual(res.results[0]!.flags, ["OUTSIDE_RADIUS"]);
  const v = (await repos.listVisitsByIstDate(ADMIN_SCOPE, "2026-07-08"))[0]!;
  assert.equal(v.geofence_result, "OUTSIDE_FLAGGED");
  assert.equal(v.out_of_radius_reason, "INSIDE_PREMISES_GPS_WEAK");
});

test("schema-invalid payload → quarantined (persisted raw, never discarded)", async () => {
  const repos = await seeded();
  const bad = { id: uuidish(700001), dc_user_id: DC_ASHA }; // missing device_id/location_id/fix/timestamps
  const res = await applySyncBatch(repos, principal, batchOf([bad]), clock);
  assert.equal(res.results[0]!.result, "quarantined");
  assert.equal(await repos.countQuarantined("eko"), 1);
  assert.equal(await repos.countCheckinEvents("eko"), 0);
});

test("unknown location → quarantined UNKNOWN_REFERENCE; op without op_id → rejected", async () => {
  const repos = await seeded();
  const unknownLoc = checkin({ location_id: "018f5a00-0000-7000-8000-0000000000ff" });
  const res1 = await applySyncBatch(repos, principal, batchOf([unknownLoc]), clock);
  assert.equal(res1.results[0]!.result, "quarantined");

  const batch = batchOf([checkin()]);
  delete (batch.ops[0] as Record<string, unknown>).op_id;
  const res2 = await applySyncBatch(repos, principal, batch, clock);
  assert.equal(res2.results[0]!.result, "rejected");
});

test("duplicate replay: byte-identical dispositions, zero writes, flags preserved (C3 §3)", async () => {
  const repos = await seeded();
  const outside = checkin({ fix: { lat: 25.3602 + 0.008, lng: 85.7591, accuracy_m: 5 }, out_of_radius_reason: "OTHER" });
  const batch = batchOf([checkin(), outside]);

  const first = await applySyncBatch(repos, principal, batch, clock);
  const eventsAfterFirst = await repos.countCheckinEvents("eko");

  const second = await applySyncBatch(repos, principal, batch, clock);
  const third = await applySyncBatch(repos, principal, batch, clock);

  assert.equal(await repos.countCheckinEvents("eko"), eventsAfterFirst, "replay must write nothing");
  for (const [i, r] of second.results.entries()) {
    assert.equal(r.result, "duplicate");
    assert.deepEqual(r.flags, first.results[i]!.flags, "original flags preserved on replay");
  }
  assert.equal(JSON.stringify(second.results), JSON.stringify(third.results), "byte-identical across replays");
});

test("CONVERGENCE (C3 §3): any permutation + duplication of batches → identical store state", async () => {
  // Seeded RNG so a failure names its seed and is reproducible (fixture doctrine).
  const SEED = 4471;
  let s = SEED;
  const rand = (): number => {
    s = (s * 1664525 + 1013904223) % 2 ** 32;
    return s / 2 ** 32;
  };
  const shuffle = <T>(arr: T[]): T[] => {
    const a = [...arr];
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(rand() * (i + 1));

      const tmp = a[i]!;
      a[i] = a[j]!;
      a[j] = tmp;
    }
    return a;
  };

  // 4 batches × mixed ops: inside, outside, accuracy-inflated, schema-invalid, unknown-ref
  opCounter = 10_000;
  const batches: SyncBatch[] = [];
  for (let b = 1; b <= 4; b++) {
    const events: (CheckInEvent | Record<string, unknown>)[] = [];
    for (let i = 0; i < 10; i++) {
      const kind = (b * 10 + i) % 5;
      if (kind === 0) events.push(checkin({ location_id: CSP_MAHUA, fix: { lat: 25.3721, lng: 85.7488, accuracy_m: 8 } }));
      else if (kind === 1) events.push(checkin({ fix: { lat: 25.3602 + 0.008, lng: 85.7591, accuracy_m: 5 }, out_of_radius_reason: "OTHER" }));
      else if (kind === 2) events.push(checkin({ fix: { lat: 25.3602 + 0.00145, lng: 85.7591, accuracy_m: 80 } }));
      else if (kind === 3) events.push({ id: uuidish(++opCounter), broken: true });
      else events.push(checkin({ location_id: "018f5a00-0000-7000-8000-0000000000ff" }));
    }
    batches.push(batchOf(events, b));
  }
  const allOpIds = batches.flatMap((b) => b.ops.map((o) => (o as { op_id: string }).op_id));

  async function snapshot(repos: MemoryRepos): Promise<string> {
    const visits = await repos.listVisitsByIstDate(ADMIN_SCOPE, "2026-07-08");
    const dispositions: Record<string, unknown> = {};
    for (const id of allOpIds) dispositions[id] = await repos.getOpDisposition("eko", id);
    return JSON.stringify({
      visits: visits.sort((a, b) => a.id.localeCompare(b.id)),
      events: await repos.countCheckinEvents("eko"),
      quarantined: await repos.countQuarantined("eko"),
      dispositions,
    });
  }

  let reference: string | null = null;
  for (let run = 0; run < 5; run++) {
    const repos = await seeded();
    // permute batches and duplicate a random subset (at-least-once delivery)
    const sequence = shuffle([...batches, ...batches.filter(() => rand() < 0.5)]);
    for (const b of sequence) await applySyncBatch(repos, principal, b, clock);
    const snap = await snapshot(repos);
    if (reference === null) reference = snap;
    else assert.equal(snap, reference, `run ${run} diverged (seed ${SEED})`);
  }
});

test("attendance merge is commutative: START/END in any order converge (C3 §3, v0.3.0)", async () => {
  const mkAttendance = (n: number, kind: "START" | "END", wall: string) => ({
    op_id: uuidish(600000 + n),
    seq: n,
    type: kind === "START" ? ("attendance.start" as const) : ("attendance.end" as const),
    payload: {
      id: uuidish(600000 + n),
      dc_user_id: DC_ASHA,
      device_id: DEVICE,
      kind,
      timestamps: { device_wall_time: wall, monotonic_ms: n },
    },
  });
  const mkBatch = (ops: unknown[], n: number): SyncBatch => ({
    batch_id: uuidish(910000 + n),
    device_id: DEVICE,
    seq_from: 1,
    seq_to: ops.length,
    client_time: NOW.toISOString(),
    app_version: "0.1.0-test",
    contract_version: "0.3.0",
    ops,
  });
  // START 03:30Z (09:00 IST), a duplicate later START, END 12:30Z (18:00 IST)
  const opsA = [mkAttendance(1, "START", "2026-07-08T03:30:00Z"), mkAttendance(2, "END", "2026-07-08T12:30:00Z")];
  const opsB = [mkAttendance(3, "START", "2026-07-08T05:00:00Z")]; // late second START must NOT move started_at forward

  const orders: SyncBatch[][] = [
    [mkBatch(opsA, 1), mkBatch(opsB, 2)],
    [mkBatch(opsB, 2), mkBatch(opsA, 1)],
    [mkBatch(opsB, 2), mkBatch(opsA, 1), mkBatch(opsA, 1)], // with duplication
  ];
  let reference: string | null = null;
  for (const order of orders) {
    const repos = await seeded();
    for (const b of order) await applySyncBatch(repos, principal, b, clock);
    const day = await repos.getAttendanceDay("eko", DC_ASHA, "2026-07-08");
    const snap = JSON.stringify(day);
    assert.equal(day?.started_at, "2026-07-08T03:30:00Z", "earliest START wins");
    assert.equal(day?.ended_at, "2026-07-08T12:30:00Z", "latest END wins");
    if (reference === null) reference = snap;
    else assert.equal(snap, reference);
  }
});

test("unknown op type → quarantined UNSUPPORTED_TYPE, never dropped (C3 v0.3.0)", async () => {
  const repos = await seeded();
  const batch = batchOf([checkin()]);
  (batch.ops[0] as Record<string, unknown>).type = "visit.teleport";
  const res = await applySyncBatch(repos, principal, batch, clock);
  assert.equal(res.results[0]!.result, "quarantined");
  assert.equal(await repos.countQuarantined("eko"), 1);
});
