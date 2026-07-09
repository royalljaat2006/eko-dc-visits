/**
 * Compiles the C1 entity schemas (contracts/c1-entities) with ajv 2020-12.
 * The schema files are the source of truth — loaded from contracts/, never copied.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { ValidateFunction } from "ajv";
import addFormatsModule from "ajv-formats";

// NodeNext/CJS interop: ajv-formats ships `module.exports.default = fn`.
type AddFormats = (ajv: InstanceType<typeof Ajv2020>) => void;
const addFormats: AddFormats = (((addFormatsModule as { default?: unknown }).default ?? addFormatsModule) as AddFormats);

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const CONTRACTS_DIR = path.resolve(HERE, "../../../contracts");
const C1_DIR = path.join(CONTRACTS_DIR, "c1-entities");

function loadSchema(file: string): Record<string, unknown> {
  return JSON.parse(readFileSync(path.join(C1_DIR, file), "utf8")) as Record<string, unknown>;
}

export const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);

for (const file of [
  "common.schema.json",
  "location.schema.json",
  "user-device.schema.json",
  "beat-plan.schema.json",
  "checkin-event.schema.json",
]) {
  ajv.addSchema(loadSchema(file));
}

const BASE = "https://contracts.eko-dc-visits/c1/";

function getValidator(ref: string): ValidateFunction {
  const v = ajv.getSchema(BASE + ref);
  if (!v) throw new Error(`C1 schema not found: ${ref}`);
  return v;
}

export const validateCheckinEvent = getValidator("checkin-event.schema.json");
export const validateLocation = getValidator("location.schema.json");
export const validateUser = getValidator("user-device.schema.json#/$defs/user");
export const validateDevice = getValidator("user-device.schema.json#/$defs/device");
export const validateBeatPlan = getValidator("beat-plan.schema.json");

export function ajvErrorStrings(v: ValidateFunction): string[] {
  return (v.errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "invalid"}`);
}
