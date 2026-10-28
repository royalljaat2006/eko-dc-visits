/**
 * Applies backend/migrations/*.sql in filename order against DATABASE_URL.
 *
 * Each file runs once, in its own transaction, and is recorded in
 * schema_migrations. Databases migrated before that table existed (the old
 * runner re-ran every file) are baselined on first run: a legacy file whose
 * objects already exist is recorded as applied without being re-run, because
 * 002/003/004/005 use plain CREATE/ALTER and would fail a second time.
 */
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import pg from "pg";

const url = process.env.DATABASE_URL;
if (!url) {
  console.error("DATABASE_URL is required (in-memory mode needs no migrations)");
  process.exit(1);
}

/** Legacy migrations (pre-schema_migrations) → a query that is true once that file has been applied. */
const LEGACY_MARKERS: Record<string, string> = {
  "001_init.sql": "SELECT to_regclass('public.locations') IS NOT NULL",
  "002_circles_banks.sql": "SELECT to_regclass('public.banks') IS NOT NULL",
  "003_attendance.sql": "SELECT to_regclass('public.attendance_events') IS NOT NULL",
  "004_track_chunks.sql": "SELECT to_regclass('public.track_chunks') IS NOT NULL",
  "005_change_requests.sql":
    "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'locations' AND column_name = 'csp_profile')",
  "006_visit_photos.sql": "SELECT to_regclass('public.visit_photos') IS NOT NULL",
  "007_visit_checkouts.sql": "SELECT to_regclass('public.visit_checkouts') IS NOT NULL",
  "008_dc_live_location.sql": "SELECT to_regclass('public.dc_live_location') IS NOT NULL",
};

const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../migrations");
const client = new pg.Client({ connectionString: url });
await client.connect();
try {
  await client.query(
    "CREATE TABLE IF NOT EXISTS schema_migrations (filename text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())",
  );
  const done = new Set(
    (await client.query<{ filename: string }>("SELECT filename FROM schema_migrations")).rows.map((r) => r.filename),
  );

  for (const file of readdirSync(dir).filter((f) => f.endsWith(".sql")).sort()) {
    if (done.has(file)) {
      console.log(`skip ${file} (already applied)`);
      continue;
    }
    const marker = LEGACY_MARKERS[file];
    if (marker && Object.values((await client.query(marker)).rows[0] ?? {})[0] === true) {
      await client.query("INSERT INTO schema_migrations (filename) VALUES ($1)", [file]);
      console.log(`baseline ${file} (already present, recorded)`);
      continue;
    }
    process.stdout.write(`applying ${file}... `);
    await client.query("BEGIN");
    try {
      await client.query(readFileSync(path.join(dir, file), "utf8"));
      await client.query("INSERT INTO schema_migrations (filename) VALUES ($1)", [file]);
      await client.query("COMMIT");
    } catch (err) {
      await client.query("ROLLBACK");
      throw err;
    }
    console.log("ok");
  }
} finally {
  await client.end();
}
