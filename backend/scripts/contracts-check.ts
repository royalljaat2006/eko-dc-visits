/**
 * contracts:check — CI gate (CONTRIBUTING: contract conformance).
 *
 * 1. Every fixture file validates against its C1 schema (fixtures are
 *    change-announced like contracts; schema-invalid fixtures break 3 lanes).
 * 2. Route-vs-spec diff, both directions: every implemented route exists in
 *    contracts/c2-api/openapi.yaml and every spec path is implemented.
 */
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { parse as parseYaml } from "yaml";
import {
  CONTRACTS_DIR,
  ajvErrorStrings,
  validateBeatPlan,
  validateLocation,
  validateUser,
} from "../src/validation/schemas.js";
import { FIXTURES_DIR } from "../src/seed/loader.js";
import { IMPLEMENTED_ROUTES } from "../src/server.js";
import type { ValidateFunction } from "ajv";

let failures = 0;
const fail = (msg: string): void => {
  failures += 1;
  console.error(`  ✗ ${msg}`);
};
const ok = (msg: string): void => console.log(`  ✓ ${msg}`);

// ---- 1. fixtures vs C1 schemas ---------------------------------------------
console.log("fixtures vs C1 schemas:");
const fixtureValidators: Record<string, ValidateFunction | null> = {
  "locations.json": validateLocation,
  "users.json": validateUser,
  "beat-plans.json": validateBeatPlan,
  "geo-assignments.json": null, // no C1 schema yet (fixture-defined; see domain/types.ts GeoAssignment)
};
for (const file of readdirSync(FIXTURES_DIR).sort()) {
  const validator = fixtureValidators[file];
  if (validator === undefined) {
    fail(`${file}: unexpected fixture file (add it to contracts-check explicitly)`);
    continue;
  }
  if (validator === null) {
    ok(`${file}: skipped (no C1 schema yet, documented)`);
    continue;
  }
  const rows = JSON.parse(readFileSync(path.join(FIXTURES_DIR, file), "utf8")) as unknown[];
  let bad = 0;
  rows.forEach((row, i) => {
    if (!validator(row)) {
      bad += 1;
      fail(`${file}[${i}]: ${ajvErrorStrings(validator).join("; ")}`);
    }
  });
  if (bad === 0) ok(`${file}: ${rows.length} rows valid`);
}

// ---- 2. implemented routes vs OpenAPI spec ----------------------------------
console.log("implemented routes vs contracts/c2-api/openapi.yaml:");
const spec = parseYaml(readFileSync(path.join(CONTRACTS_DIR, "c2-api", "openapi.yaml"), "utf8")) as {
  paths: Record<string, Record<string, unknown>>;
};
const specRoutes = new Set<string>();
for (const [p, methods] of Object.entries(spec.paths)) {
  for (const m of Object.keys(methods)) {
    if (["get", "post", "put", "patch", "delete"].includes(m)) specRoutes.add(`${m} ${p}`);
  }
}
const implemented = new Set(IMPLEMENTED_ROUTES.map((r) => `${r.method} ${r.path}`));
for (const r of implemented) {
  if (specRoutes.has(r)) ok(`implemented & specified: ${r}`);
  else fail(`implemented but NOT in spec: ${r} (spec-first: add to C2 via RFC before coding)`);
}
for (const r of specRoutes) {
  if (!implemented.has(r)) fail(`in spec but NOT implemented: ${r}`);
}

if (failures > 0) {
  console.error(`\ncontracts:check FAILED (${failures} problem${failures === 1 ? "" : "s"})`);
  process.exit(1);
}
console.log("\ncontracts:check passed");
