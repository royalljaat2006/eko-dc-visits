/**
 * Server entrypoint. Storage selection per ADR-0009:
 *   - DATABASE_URL set  → Postgres repos (run `npm run migrate` first)
 *   - otherwise         → EMPTY in-memory repos, local development only.
 *
 * There is no demo data: the server never seeds fixtures. Real data comes from
 * the calling sheet / pilot roster (`npm run seed:calling-sheet`, `seed:pilot`),
 * and for local development against it set CALLING_SHEET_CSV (below).
 * In production (NODE_ENV=production) a database is mandatory — an in-memory
 * store would silently lose data on restart.
 */
import "dotenv/config"; // loads backend/.env if present — local dev only; no-op if it's missing
// (never touches Vercel or the self-hosted container, which inject real env vars directly).
// Must run before ./server.js — its module-level `loadEkoConfig()` reads process.env at import time.
import { buildServer } from "./server.js";
import { MemoryRepos } from "./repos/memory.js";
import type { Repos } from "./repos/types.js";

async function main(): Promise<void> {
  let repos: Repos;
  if (process.env.DATABASE_URL) {
    const { PgRepos } = await import("./repos/pg.js");
    repos = new PgRepos(process.env.DATABASE_URL);
    console.log("[backend] storage: postgres (DATABASE_URL set)");
  } else {
    if (process.env.NODE_ENV === "production") {
      console.error("[backend] DATABASE_URL is required in production — refusing to start on an in-memory store");
      process.exit(1);
    }
    const mem = new MemoryRepos();
    repos = mem;
    console.log("[backend] storage: EMPTY in-memory store (local development; nothing persists)");
    // Local dev against the REAL calling sheet (personal data — keep the CSV
    // under pilot-data/, which is gitignored): CALLING_SHEET_CSV=path/to.csv
    if (process.env.CALLING_SHEET_CSV) {
      const { readFile } = await import("node:fs/promises");
      const { seedCallingSheet } = await import("./seed/calling-sheet.js");
      const s = await seedCallingSheet(mem, await readFile(process.env.CALLING_SHEET_CSV, "utf8"));
      console.log(
        `[backend] calling sheet: ${s.csps} CSPs (${s.with_address} with address), ${s.dcs} DCs, ` +
          `${s.circles} circles, ${s.assigned} assigned, ${s.skipped.length} skipped`,
      );
    }
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
