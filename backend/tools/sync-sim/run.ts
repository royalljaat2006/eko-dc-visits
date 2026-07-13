/**
 * Sync simulator — a Node client emulating the Android DC device against a
 * LIVE server (the plan's M0 e2e proof, and the seed of the M1 simulator fleet).
 *
 * Story (BUILD_PLAN §9 M0): dc-asha logs in (stub OTP) → pulls master data +
 * today's beat plan → performs an "offline day" of 3 check-ins (one INSIDE,
 * one outside-radius-with-reason, one at the UNVERIFIED-coordinates CSP) →
 * submits the batch TWICE (lost-ack replay) proving all-duplicate on resend →
 * AM Priya's dashboard shows the 3 visits with correct geofence results.
 *
 * Usage: BASE_URL=http://127.0.0.1:3000/api/v1 npm run sync-sim
 * Exits non-zero on any assertion failure.
 */
import { randomUUID } from "node:crypto";

const BASE = process.env.BASE_URL ?? "http://127.0.0.1:3000/api/v1";
const OTP = process.env.PILOT_OTP ?? "000000";

let failures = 0;
function check(cond: boolean, msg: string): void {
  if (cond) console.log(`  ✓ ${msg}`);
  else {
    failures += 1;
    console.error(`  ✗ ${msg}`);
  }
}

async function api<T>(path: string, init: RequestInit = {}, token?: string): Promise<{ status: number; body: T }> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      "content-type": "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {}),
    },
  });
  const text = await res.text();
  return { status: res.status, body: (text ? JSON.parse(text) : null) as T };
}

async function login(phone: string): Promise<{ token: string; device_id: string; user_id: string }> {
  await api(`/auth/otp/request`, { method: "POST", body: JSON.stringify({ phone }) });
  const { status, body } = await api<{ access_token: string; device_id: string; user: { id: string; name: string } }>(
    `/auth/otp/verify`,
    {
      method: "POST",
      body: JSON.stringify({ phone, otp: OTP, device: { hardware: { manufacturer: "SimCo", model: "Sim-1", os_version: "14" } } }),
    },
  );
  if (status !== 200) throw new Error(`login failed for ${phone}: ${status}`);
  console.log(`logged in: ${body.user.name}`);
  return { token: body.access_token, device_id: body.device_id, user_id: body.user.id };
}

interface Loc {
  id: string;
  name: string;
  code: string;
  coordinates: { lat: number; lng: number };
  radius_m: number;
  coordinate_confidence: string;
}

async function main(): Promise<void> {
  console.log(`sync-sim against ${BASE}\n`);

  // ---- DC side: pull, capture offline, sync -------------------------------
  const dc = await login("9800000001"); // dc-asha

  const locs = await api<{ items: Loc[] }>(`/master-data/locations`, {}, dc.token);
  check(locs.status === 200 && locs.body.items.length > 0, `pulled ${locs.body.items.length} locations (delta pull)`);
  const byId = new Map(locs.body.items.map((l) => [l.id, l]));

  const today = new Date(Date.now() + 5.5 * 3600 * 1000).toISOString().slice(0, 10); // IST date
  const plans = await api<{ items: Array<{ plan_date: string; version: number; stops: Array<{ id: string; location_id: string }> }> }>(
    `/master-data/beat-plans?from_date=${today}`,
    {},
    dc.token,
  );
  check(plans.status === 200 && plans.body.items.length === 1, `pulled today's beat plan (${plans.body.items[0]?.stops.length ?? 0} stops)`);
  const plan = plans.body.items[0];
  if (!plan || plan.stops.length < 3) {
    console.error("beat plan with 3 stops not found — is the server seeded with Nandpur fixtures?");
    process.exit(1);
  }
  const [stop1, stop2, stop3] = plan.stops as [(typeof plan.stops)[number], (typeof plan.stops)[number], (typeof plan.stops)[number]];
  const l1 = byId.get(stop1.location_id)!;
  const l2 = byId.get(stop2.location_id)!;
  const l3 = byId.get(stop3.location_id)!;
  check(l3.coordinate_confidence === "UNVERIFIED", `stop 3 (${l3.name}) has UNVERIFIED coordinates — bootstrap case`);

  // IST (UTC+5:30) wall time → UTC ISO instant on `today`.
  const wall = (h: number, m: number): string => {
    const total = h * 60 + m - 330;
    return `${today}T${String(Math.floor(total / 60)).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}:00Z`;
  };
  const mkOp = (seq: number, loc: Loc, stopId: string, fix: object, reason?: string) => ({
    op_id: randomUUID(),
    seq,
    type: "visit.checkin" as const,
    payload: {
      id: randomUUID(),
      dc_user_id: dc.user_id,
      device_id: dc.device_id,
      location_id: loc.id,
      planned_stop_id: stopId,
      beat_plan_version: plan.version,
      fix,
      timestamps: { device_wall_time: wall(10 + seq, 15), monotonic_ms: seq * 60_000 },
      ...(reason ? { out_of_radius_reason: reason } : {}),
    },
  });

  const mkAttendance = (seq: number, kind: "START" | "END", wallTime: string) => ({
    op_id: randomUUID(),
    seq,
    type: kind === "START" ? ("attendance.start" as const) : ("attendance.end" as const),
    payload: {
      id: randomUUID(),
      dc_user_id: dc.user_id,
      device_id: dc.device_id,
      kind,
      fix: { lat: 25.35, lng: 85.75, accuracy_m: 150, provider: "fused" }, // logged, never gated
      face_match: { result: "PASS" as const, score: 0.93 },
      timestamps: { device_wall_time: wallTime, monotonic_ms: seq * 1000 },
    },
  });

  // Duty-session GPS track: interpolated route home-ish -> CSP1 -> CSP2 -> CSP3
  // (track_straightline_v0 demo data; ~17 km given the fixture geography).
  const trackPoints: Array<{ lat: number; lng: number; t: string; accuracy_m: number }> = [];
  const waypoints = [
    { lat: l1.coordinates.lat - 0.02, lng: l1.coordinates.lng - 0.02 }, // start of day, en route
    l1.coordinates,
    l2.coordinates,
    l3.coordinates,
  ];
  let minuteCursor = 9 * 60 + 30; // 09:30 IST
  for (let leg = 1; leg < waypoints.length; leg++) {
    const a = waypoints[leg - 1]!;
    const b = waypoints[leg]!;
    for (let i = 0; i <= 5; i++) {
      const f = i / 5;
      const total = minuteCursor + i * 12;
      trackPoints.push({
        lat: a.lat + (b.lat - a.lat) * f,
        lng: a.lng + (b.lng - a.lng) * f,
        t: wall(Math.floor(total / 60), total % 60),
        accuracy_m: 15,
      });
    }
    minuteCursor += 75;
  }
  const trackOp = {
    op_id: randomUUID(),
    seq: 5,
    type: "track.chunk" as const,
    payload: {
      id: randomUUID(),
      dc_user_id: dc.user_id,
      device_id: dc.device_id,
      points: trackPoints,
      timestamps: { device_wall_time: wall(18, 1), monotonic_ms: 5_000 },
    },
  };

  const batch = {
    batch_id: randomUUID(),
    device_id: dc.device_id,
    seq_from: 1,
    seq_to: 6,
    client_time: new Date().toISOString(),
    app_version: "0.1.0-sim",
    contract_version: "0.3.0",
    queue_depth_by_tier: { t1: 5, t2: 0, t3: 0, t4: 0 },
    oldest_unsynced_age_s: 7200,
    health: { battery_pct: 63, network: "3g", storage_free_mb: 4096 },
    ops: [
      // Start Day at 09:00 IST (face verified, GPS logged)
      mkAttendance(0, "START", wall(9, 0)),
      // INSIDE: at the CSP, good fix
      mkOp(1, l1, stop1.id, { lat: l1.coordinates.lat, lng: l1.coordinates.lng, accuracy_m: 12, provider: "fused" }),
      // OUTSIDE with reason: ~550m off, tight accuracy (market-lane reality)
      mkOp(2, l2, stop2.id, { lat: l2.coordinates.lat + 0.005, lng: l2.coordinates.lng, accuracy_m: 15, provider: "fused" }, "INSIDE_PREMISES_GPS_WEAK"),
      // UNVERIFIED-coordinates CSP: at master pin, wide accuracy
      mkOp(3, l3, stop3.id, { lat: l3.coordinates.lat, lng: l3.coordinates.lng, accuracy_m: 35, provider: "fused" }),
      // End Day at 18:00 IST — tracking hard stop (DPDP)
      mkAttendance(4, "END", wall(18, 0)),
      // The day's GPS track (tier T3 in the real client; one chunk here)
      trackOp,
    ],
  };

  console.log(`\nsubmitting batch (start day + 3 offline check-ins + end day)…`);
  const first = await api<{ results: Array<{ op_id: string; result: string; flags?: string[] }> }>(
    `/sync/batches`,
    { method: "POST", body: JSON.stringify(batch) },
    dc.token,
  );
  check(first.status === 200, `batch accepted (HTTP ${first.status})`);
  check(first.body.results[0]!.result === "accepted", `attendance.start → ${first.body.results[0]!.result}`);
  check(first.body.results[1]!.result === "accepted", `checkin 1 → ${first.body.results[1]!.result} (expected accepted)`);
  check(
    first.body.results[2]!.result === "accepted-flagged" && (first.body.results[2]!.flags ?? []).includes("OUTSIDE_RADIUS"),
    `checkin 2 → ${first.body.results[2]!.result} [${(first.body.results[2]!.flags ?? []).join(",")}] (advisory geofence, ADR-0004)`,
  );
  check(first.body.results[3]!.result === "accepted", `checkin 3 → ${first.body.results[3]!.result} (expected accepted)`);
  check(first.body.results[4]!.result === "accepted", `attendance.end → ${first.body.results[4]!.result}`);
  check(first.body.results[5]!.result === "accepted", `track.chunk (${trackPoints.length} pts) → ${first.body.results[5]!.result}`);

  console.log(`\nre-submitting the SAME batch (lost-ack replay)…`);
  const second = await api<{ results: Array<{ result: string }> }>(`/sync/batches`, { method: "POST", body: JSON.stringify(batch) }, dc.token);
  check(second.body.results.every((r) => r.result === "duplicate"), `all ops → duplicate on replay (idempotency, C3 §3)`);

  // ---- AM side: dashboard --------------------------------------------------
  console.log(`\nAM dashboard check…`);
  const am = await login("9800000003"); // am-priya
  const view = await api<{ items: Array<{ dc_name: string; location_name: string; location_code: string; geofence_result: string; checkin: { occurred_at: string } }> }>(
    `/dashboard/visits?date=${today}`,
    {},
    am.token,
  );
  check(view.status === 200 && view.body.items.length === 3, `AM sees ${view.body.items.length} visits (expected 3)`);
  const flagged = view.body.items.filter((v) => v.geofence_result === "OUTSIDE_FLAGGED");
  check(flagged.length === 1, `exactly 1 visit is OUTSIDE_FLAGGED`);
  console.log(`\n  DC          LOCATION                    CODE           GEOFENCE          TIME(UTC)`);
  for (const v of view.body.items) {
    console.log(
      `  ${v.dc_name.padEnd(12)}${v.location_name.padEnd(28)}${v.location_code.padEnd(15)}${v.geofence_result.padEnd(18)}${v.checkin.occurred_at}`,
    );
  }

  // ---- National Head: attendance board (design 0001 §7) --------------------
  console.log(`\nNational Head attendance board…`);
  const nh = await login("9800000005"); // national head Arjun
  const board = await api<{ items: Array<{ dc_name: string; status: string; started_at: string | null; ended_at: string | null; km_today: number }> }>(
    `/dashboard/attendance?date=${today}`,
    {},
    nh.token,
  );
  check(board.status === 200 && board.body.items.length === 3, `board lists ${board.body.items.length} DCs tenant-wide (expected 3)`);
  const statuses = new Map(board.body.items.map((r) => [r.dc_name, r.status]));
  check(statuses.get("Asha Kumari") === "ENDED", `Asha Kumari → ${statuses.get("Asha Kumari")} (expected ENDED)`);
  check(statuses.get("Vikram Singh") === "NOT_STARTED", `Vikram Singh → ${statuses.get("Vikram Singh")} (expected NOT_STARTED)`);
  const ashaKm = board.body.items.find((r) => r.dc_name === "Asha Kumari")?.km_today ?? 0;
  check(ashaKm > 5, `Asha's provisional km_today = ${ashaKm} (expected > 5 from the simulated route)`);
  console.log(`\n  DC             STATUS        KM(prov)   START(UTC)             END(UTC)`);
  for (const r of board.body.items) {
    console.log(`  ${r.dc_name.padEnd(15)}${r.status.padEnd(14)}${String(r.km_today).padEnd(11)}${(r.started_at ?? "—").padEnd(23)}${r.ended_at ?? "—"}`);
  }

  if (failures > 0) {
    console.error(`\nsync-sim FAILED (${failures} assertion${failures === 1 ? "" : "s"})`);
    process.exit(1);
  }
  console.log(`\nsync-sim PASSED — offline day → idempotent sync → AM dashboard, end to end.`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
