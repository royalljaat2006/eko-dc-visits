/**
 * Test-only fixture seeding. Seeds ANY Repos implementation from the synthetic
 * universe in ../fixtures (validated against the C1 schemas by contracts:check).
 * Never imported by src/ — the server has no demo data.
 *
 * The beat plan for dc-asha is re-dated to "today" (IST) at seed time so tests
 * always have a plan for the current day (C3 §6).
 */
import { fileURLToPath } from "node:url";
import path from "node:path";
import banksJson from "../fixtures/banks.json" with { type: "json" };
import locationsJson from "../fixtures/locations.json" with { type: "json" };
import usersJson from "../fixtures/users.json" with { type: "json" };
import circlesJson from "../fixtures/circles.json" with { type: "json" };
import membershipsJson from "../fixtures/circle-memberships.json" with { type: "json" };
import cspAssignmentsJson from "../fixtures/csp-assignments.json" with { type: "json" };
import beatPlansJson from "../fixtures/beat-plans.json" with { type: "json" };
import type { Bank, BeatPlan, Circle, CircleMembership, CspAssignment, LocationNode, User } from "../../src/domain/types.js";
import type { Repos } from "../../src/repos/types.js";
import { istDateOf } from "../../src/geo.js";

export const FIXTURES_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../fixtures");

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
    plan_date: today,
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
