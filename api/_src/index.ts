/**
 * Vercel serverless entry for the Fastify API (see infra/DEPLOYMENT.md).
 * Prebundled to api/index.js via `npm run bundle:function` — C1 schemas and
 * Nandpur fixtures are inlined (JSON imports), so the bundle has no runtime
 * filesystem dependency. Vercel's /api file routing serves it; vercel.json
 * rewrites /api/* here with the original URL intact, matching Fastify routes.
 *
 * Storage:
 *  - DATABASE_URL set (Neon/Supabase/any Postgres+PostGIS) → durable pilot mode.
 *  - unset → DEMO MODE: seeded in-memory store per serverless instance; data
 *    resets on cold starts. Fine for a look-around, NOT for the pilot.
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import { buildServer } from "../../backend/src/server.js";
import { MemoryRepos } from "../../backend/src/repos/memory.js";
import { seedFixtures } from "../../backend/src/seed/loader.js";
import type { Repos } from "../../backend/src/repos/types.js";

async function makeRepos(): Promise<Repos> {
  const url = process.env.DATABASE_URL ?? process.env.POSTGRES_URL;
  if (url) {
    const { PgRepos } = await import("../../backend/src/repos/pg.js");
    return new PgRepos(url);
  }
  console.warn("[api] DEMO MODE: no DATABASE_URL — in-memory store, data resets on cold start");
  const mem = new MemoryRepos();
  await seedFixtures(mem);
  return mem;
}

const appPromise = (async () => {
  const app = buildServer({ repos: await makeRepos() });
  await app.ready();
  return app;
})();

export default async function handler(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const app = await appPromise;
  app.server.emit("request", req, res);
}
