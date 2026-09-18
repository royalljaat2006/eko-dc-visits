/**
 * Pilot seed: Nandpur base fixtures (banks + locations) PLUS the real
 * Circle 1A85 DC roster (spec §7). Postgres only — set PILOT_OTP + JWT_SECRET
 * on the deployment first, then:
 *   DATABASE_URL='postgres://…' npm run seed:pilot
 */
import { PgRepos } from "../src/repos/pg.js";
import { seedFixtures, seedCircle1A85 } from "../src/seed/loader.js";

const url = process.env.DATABASE_URL;
if (!url) {
  console.error("DATABASE_URL is required");
  process.exit(1);
}
const repos = new PgRepos(url);
const base = await seedFixtures(repos);
const pilot = await seedCircle1A85(repos);
console.log(
  `seeded ${base.locations.length} locations + Circle 1A85: ${pilot.users.length} DCs ` +
    `(${pilot.users.map((u) => u.name).join(", ")})`,
);
process.exit(0);
