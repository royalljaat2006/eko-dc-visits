/**
 * Loads a calling-sheet CSV export into Postgres (pilot DB only — real personal data).
 *   DATABASE_URL='postgres://…' npm run seed:calling-sheet -- ../pilot-data/calling-sheet.csv
 * Re-running converges (deterministic ids, insert-if-absent).
 */
import { readFile } from "node:fs/promises";
import { PgRepos } from "../src/repos/pg.js";
import { seedCallingSheet } from "../src/seed/calling-sheet.js";

const url = process.env.DATABASE_URL;
const file = process.argv[2];
if (!url || !file) {
  console.error("usage: DATABASE_URL=… npm run seed:calling-sheet -- <calling-sheet.csv>");
  process.exit(1);
}
const s = await seedCallingSheet(new PgRepos(url), await readFile(file, "utf8"));
console.log(`CSPs ${s.csps} (${s.with_address} with address) · DCs ${s.dcs} · circles ${s.circles} · assigned ${s.assigned}`);
for (const k of s.skipped) console.log(`  skipped row ${k.sheet_row} ${k.csp_code}: ${k.reason}`);
process.exit(0);
