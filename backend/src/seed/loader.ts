/**
 * Pilot roster loader — seeds ANY Repos implementation (in-memory or Postgres)
 * with the real Circle 1A85 DC roster. There is no demo/fixture seeding in src/:
 * test fixtures live in backend/test/ and are never loaded by the server.
 */
import { fileURLToPath } from "node:url";
import path from "node:path";
import type { Circle, User } from "../domain/types.js";
import type { Repos } from "../repos/types.js";
import { istDateOf } from "../geo.js";

export interface SeedOptions {
  /** "Today" used to date memberships; defaults to the real clock. */
  now?: Date;
}

// Guarded for CJS bundles (import.meta is empty there; the path is only used
// by local tooling, never by the serverless runtime).
const HERE = (() => {
  try {
    return path.dirname(fileURLToPath(import.meta.url));
  } catch {
    return process.cwd();
  }
})();

export interface PilotRoster {
  circle_name: string;
  dcs: Array<{ name: string; phone: string; dashboard_url: string }>;
}

/**
 * Where the real roster lives. It holds real people's phones + dashboard links,
 * so it is NEVER committed: pilot-data/ is gitignored. Override with
 * PILOT_ROSTER_FILE. Shape: see PilotRoster.
 */
async function readPilotRoster(): Promise<PilotRoster> {
  const { readFile } = await import("node:fs/promises");
  const file = process.env.PILOT_ROSTER_FILE ?? path.resolve(HERE, "../../../pilot-data/circle-1a85-roster.json");
  try {
    return JSON.parse(await readFile(file, "utf8")) as PilotRoster;
  } catch (e) {
    throw new Error(`Pilot roster not readable at ${file} (set PILOT_ROSTER_FILE): ${(e as Error).message}`);
  }
}

/**
 * The REAL Circle 1A85 pilot roster (spec §7 — Ganesh Kumar / Eko Bharat
 * Ventures): 7 DC users with their phones + per-user dashboard_url, in a
 * "Circle 1A85". Load into the pilot database only.
 * Load into the pilot DB only, after PILOT_OTP + JWT_SECRET are set:
 *   DATABASE_URL='postgres://…' npm run seed:pilot
 * CSPs, assignments, and the Circle Head come from real master data separately.
 * The roster comes from pilot-data/ (gitignored) or `opts.roster` (tests pass a synthetic one).
 */
export async function seedCircle1A85(
  repos: Repos,
  opts: SeedOptions & { roster?: PilotRoster } = {},
): Promise<{ circle: Circle; users: User[] }> {
  const now = opts.now ?? new Date();
  const today = istDateOf(now);
  const tenant_id = "eko";
  const roster = opts.roster ?? (await readPilotRoster());

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
