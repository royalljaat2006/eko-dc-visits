/** Seeds Nandpur fixtures into Postgres (in-memory dev mode seeds itself). */
import { PgRepos } from "../src/repos/pg.js";
import { seedFixtures } from "../src/seed/loader.js";

const url = process.env.DATABASE_URL;
if (!url) {
  console.error("DATABASE_URL is required (in-memory mode seeds itself at startup)");
  process.exit(1);
}
const repos = new PgRepos(url);
const r = await seedFixtures(repos);
console.log(`seeded ${r.locations.length} locations, ${r.users.length} users, beat plan for ${r.today}`);
process.exit(0);
