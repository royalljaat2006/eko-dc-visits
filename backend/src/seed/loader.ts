/**
 * Fixture loader — seeds ANY Repos implementation (in-memory or Postgres)
 * from fixtures/nandpur/*.json. Fixture files validate against the C1 schemas
 * (enforced by scripts/contracts-check.ts).
 *
 * The beat plan for dc-asha is re-dated to "today" (IST) at seed time so the
 * walking skeleton always has a plan for the current day (C3 §6: plans for
 * date D pullable from D-1 evening; M0 seeds D itself).
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import type { Bank, BeatPlan, Circle, CircleMembership, CspAssignment, LocationNode, User } from "../domain/types.js";
import type { Repos } from "../repos/types.js";
import { istDateOf } from "../geo.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const FIXTURES_DIR = path.resolve(HERE, "../../../fixtures/nandpur");

function readJson<T>(file: string): T {
  return JSON.parse(readFileSync(path.join(FIXTURES_DIR, file), "utf8")) as T;
}

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

  const banks = readJson<Bank[]>("banks.json");
  const locations = readJson<LocationNode[]>("locations.json");
  const users = readJson<User[]>("users.json");
  const circles = readJson<Circle[]>("circles.json");
  const circleMemberships = readJson<CircleMembership[]>("circle-memberships.json");
  const cspAssignments = readJson<CspAssignment[]>("csp-assignments.json");
  const beatPlans = readJson<BeatPlan[]>("beat-plans.json").map((p) => ({
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
