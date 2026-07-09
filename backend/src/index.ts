/**
 * Dev entrypoint. Storage selection per ADR-0009:
 *   - DATABASE_URL set  → Postgres repos (run `npm run migrate` first)
 *   - otherwise         → in-memory repos, seeded from fixtures/nandpur
 */
import { buildServer } from "./server.js";
import { MemoryRepos } from "./repos/memory.js";
import { seedFixtures } from "./seed/loader.js";
import type { Repos } from "./repos/types.js";

async function main(): Promise<void> {
  let repos: Repos;
  if (process.env.DATABASE_URL) {
    const { PgRepos } = await import("./repos/pg.js");
    repos = new PgRepos(process.env.DATABASE_URL);
    console.log("[backend] storage: postgres (DATABASE_URL set); seed with `npm run seed`");
  } else {
    const mem = new MemoryRepos();
    const seeded = await seedFixtures(mem);
    repos = mem;
    console.log(
      `[backend] storage: in-memory, seeded Nandpur fixtures (${seeded.locations.length} locations, ` +
        `${seeded.users.length} users, beat plan for ${seeded.today})`,
    );
  }

  const app = buildServer({ repos });
  const port = Number.parseInt(process.env.PORT ?? "3000", 10);
  await app.listen({ port, host: "127.0.0.1" });
  console.log(`[backend] listening on http://127.0.0.1:${port}/api/v1`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
