/**
 * Dev entrypoint. Storage selection per ADR-0009:
 *   - DATABASE_URL set  → Postgres repos (run `npm run migrate` first)
 *   - otherwise         → in-memory repos, seeded from fixtures/nandpur
 */
import "dotenv/config"; // loads backend/.env if present — local dev only; no-op if it's missing
// (never touches Vercel or the self-hosted container, which inject real env vars directly).
// Must run before ./server.js — its module-level `loadEkoConfig()` reads process.env at import time.
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
  // Default to loopback (dev). Set HOST=0.0.0.0 to accept connections from
  // other hosts — a reverse proxy in front of it (TLS termination) is expected
  // in that case; the app never speaks plain HTTP to real devices.
  const host = process.env.HOST ?? "127.0.0.1";
  await app.listen({ port, host });
  console.log(`[backend] listening on http://${host}:${port}/api/v1`);

  // Graceful shutdown: stop accepting connections, drain in-flight requests,
  // then exit. Container orchestrators (docker/k8s) send SIGTERM on stop/deploy.
  let closing = false;
  for (const signal of ["SIGTERM", "SIGINT"] as const) {
    process.on(signal, () => {
      if (closing) return;
      closing = true;
      console.log(`[backend] ${signal} — draining…`);
      app
        .close()
        .then(() => {
          console.log("[backend] closed");
          process.exit(0);
        })
        .catch((e) => {
          console.error("[backend] close failed", e);
          process.exit(1);
        });
      setTimeout(() => process.exit(1), 10_000).unref(); // hard cap
    });
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
