/**
 * Fixture loader — seeds ANY Repos implementation (in-memory or Postgres)
 * from fixtures/nandpur/*.json. Fixture files validate against the C1 schemas
 * (enforced by scripts/contracts-check.ts).
 *
 * The beat plan for dc-asha is re-dated to "today" (IST) at seed time so the
 * walking skeleton always has a plan for the current day (C3 §6: plans for
 * date D pullable from D-1 evening; M0 seeds D itself).
 */
import { fileURLToPath } from "node:url";
import path from "node:path";
import banksJson from "../../../fixtures/nandpur/banks.json" with { type: "json" };
import locationsJson from "../../../fixtures/nandpur/locations.json" with { type: "json" };
import usersJson from "../../../fixtures/nandpur/users.json" with { type: "json" };
import circlesJson from "../../../fixtures/nandpur/circles.json" with { type: "json" };
import membershipsJson from "../../../fixtures/nandpur/circle-memberships.json" with { type: "json" };
import cspAssignmentsJson from "../../../fixtures/nandpur/csp-assignments.json" with { type: "json" };
import beatPlansJson from "../../../fixtures/nandpur/beat-plans.json" with { type: "json" };
import circle1a85Json from "../../../fixtures/circle-1a85-roster.json" with { type: "json" };
import type { Bank, BeatPlan, Circle, CircleMembership, CspAssignment, LocationNode, User } from "../domain/types.js";
import type { Repos } from "../repos/types.js";
import { istDateOf } from "../geo.js";

// Guarded for CJS bundles (import.meta is empty there; the path is only used
// by local tooling like contracts-check, never by the serverless runtime).
const HERE = (() => {
  try {
    return path.dirname(fileURLToPath(import.meta.url));
  } catch {
    return process.cwd();
  }
})();
export const FIXTURES_DIR = path.resolve(HERE, "../../../fixtures/nandpur");

export interface SeedOptions {
  /** "Today" used to date the beat plan; defaults to the real clock. */
  now?: Date;
}

export interface SeedResult {
  banks: Bank[];
  locations: LocationNode[];
  users: User[];
  beatPlans: BeatPlan[];
  circles: Circle[];
  circleMemberships: CircleMembership[];
  cspAssignments: CspAssignment[];
  today: string;
}

export async function seedFixtures(repos: Repos, opts: SeedOptions = {}): Promise<SeedResult> {
  const now = opts.now ?? new Date();
  const today = istDateOf(now);

  const banks = banksJson as Bank[];
  const locations = locationsJson as LocationNode[];
  const users = usersJson as User[];
  const circles = circlesJson as Circle[];
  const circleMemberships = membershipsJson as CircleMembership[];
  const cspAssignments = cspAssignmentsJson as unknown as CspAssignment[];
  const beatPlans = (beatPlansJson as unknown as BeatPlan[]).map((p) => ({
    ...p,
    plan_date: today, // generated at seed time
    updated_at: now.toISOString(),
  }));

  for (const b of banks) await repos.insertBank(b);
  for (const l of locations) await repos.insertLocation(l);
  for (const u of users) await repos.insertUser(u);
  for (const c of circles) await repos.insertCircle(c);
  for (const m of circleMemberships) await repos.insertCircleMembership(m);
  for (const a of cspAssignments) await repos.insertCspAssignment(a);
  for (const p of beatPlans) await repos.insertBeatPlan(p);

  return { banks, locations, users, beatPlans, circles, circleMemberships, cspAssignments, today };
}

/**
 * The REAL Circle 1A85 pilot roster (spec §7 — Ganesh Kumar / Eko Bharat
 * Ventures): 7 DC users with their phones + per-user dashboard_url, in a
 * "Circle 1A85". NEVER part of the public demo seed (which uses a shared OTP).
 * Load into the pilot DB only, after PILOT_OTP + JWT_SECRET are set:
 *   DATABASE_URL='postgres://…' npm run seed:pilot
 * CSPs, assignments, and the Circle Head come from real master data separately.
 */
export async function seedCircle1A85(repos: Repos, opts: SeedOptions = {}): Promise<{ circle: Circle; users: User[] }> {
  const now = opts.now ?? new Date();
  const today = istDateOf(now);
  const tenant_id = "eko";
  const roster = circle1a85Json as { circle_name: string; dcs: Array<{ name: string; phone: string; dashboard_url: string }> };

  // Stable ids so re-running converges (insert-if-absent in the repos).
  const circleId = "018f5a85-0000-7000-8000-0000000000c1";
  const circle: Circle = {
    id: circleId, tenant_id, name: roster.circle_name, status: "ACTIVE", updated_at: now.toISOString(),
  };
  await repos.insertCircle(circle);

  const users: User[] = [];
  for (const [i, dc] of roster.dcs.entries()) {
    const id = `018f5a85-0000-7000-8000-${String(i + 1).padStart(12, "0")}`;
    const user: User = {
      id, tenant_id, name: dc.name, phone: dc.phone, role: "DC", status: "ACTIVE",
      scope_location_id: null, dashboard_url: dc.dashboard_url,
    };
    await repos.insertUser(user);
    await repos.insertCircleMembership({
      id: `018f5a85-0000-7000-8000-1${String(i + 1).padStart(11, "0")}`,
      tenant_id, circle_id: circleId, user_id: id, role_in_circle: "DC",
      valid_from: today, valid_to: null,
    });
    users.push(user);
  }
  return { circle, users };
}
