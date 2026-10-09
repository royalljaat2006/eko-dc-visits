/**
 * Pilot roster seed: the real Circle 1A85 DC roster (spec §7), read from the
 * gitignored pilot-data/circle-1a85-roster.json (or PILOT_ROSTER_FILE).
 * Postgres only — set PILOT_OTP/EKO_* + JWT_SECRET on the deployment first, then:
 *   DATABASE_URL='postgres://…' npm run seed:pilot
 * CSPs/DCs/assignments come from the calling sheet: `npm run seed:calling-sheet`.
 */
import { PgRepos } from "../src/repos/pg.js";
import { seedCircle1A85 } from "../src/seed/loader.js";

const url = process.env.DATABASE_URL;
if (!url) {
  console.error("DATABASE_URL is required");
  process.exit(1);
}
const pilot = await seedCircle1A85(new PgRepos(url));
console.log(`Circle 1A85: ${pilot.users.length} DCs (${pilot.users.map((u) => u.name).join(", ")})`);
process.exit(0);
