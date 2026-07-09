/** Applies backend/migrations/*.sql in filename order against DATABASE_URL. */
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import pg from "pg";

const url = process.env.DATABASE_URL;
if (!url) {
  console.error("DATABASE_URL is required (in-memory mode needs no migrations)");
  process.exit(1);
}
const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../migrations");
const client = new pg.Client({ connectionString: url });
await client.connect();
try {
  for (const file of readdirSync(dir).filter((f) => f.endsWith(".sql")).sort()) {
    process.stdout.write(`applying ${file}... `);
    await client.query(readFileSync(path.join(dir, file), "utf8"));
    console.log("ok");
  }
} finally {
  await client.end();
}
