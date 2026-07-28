/**
 * Compiles the C1 entity schemas (contracts/c1-entities) with ajv 2020-12.
 * The schema files are the source of truth — statically imported (JSON import
 * attributes) so serverless bundles carry them with zero runtime filesystem
 * dependency; tsx/Node 22 and esbuild both inline them.
 */
import { fileURLToPath } from "node:url";
import path from "node:path";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { ValidateFunction } from "ajv";
import addFormatsModule from "ajv-formats";
import commonSchema from "../../../contracts/c1-entities/common.schema.json" with { type: "json" };
import locationSchema from "../../../contracts/c1-entities/location.schema.json" with { type: "json" };
import userDeviceSchema from "../../../contracts/c1-entities/user-device.schema.json" with { type: "json" };
import beatPlanSchema from "../../../contracts/c1-entities/beat-plan.schema.json" with { type: "json" };
import checkinEventSchema from "../../../contracts/c1-entities/checkin-event.schema.json" with { type: "json" };
import attendanceEventSchema from "../../../contracts/c1-entities/attendance-event.schema.json" with { type: "json" };
import bankSchema from "../../../contracts/c1-entities/bank.schema.json" with { type: "json" };
import circleSchema from "../../../contracts/c1-entities/circle.schema.json" with { type: "json" };
import cspAssignmentSchema from "../../../contracts/c1-entities/csp-assignment.schema.json" with { type: "json" };
import trackChunkSchema from "../../../contracts/c1-entities/track-chunk.schema.json" with { type: "json" };
import cspChangeRequestSchema from "../../../contracts/c1-entities/csp-change-request.schema.json" with { type: "json" };

// NodeNext/CJS interop: ajv-formats ships `module.exports.default = fn`.
type AddFormats = (ajv: InstanceType<typeof Ajv2020>) => void;
const addFormats: AddFormats = (((addFormatsModule as { default?: unknown }).default ?? addFormatsModule) as AddFormats);

// Guarded for CJS bundles (import.meta empty there; path used by local tooling only).
const HERE = (() => {
  try {
    return path.dirname(fileURLToPath(import.meta.url));
  } catch {
    return process.cwd();
  }
})();
/** Filesystem path for local-only tooling (contracts-check); unused in serverless bundles. */
export const CONTRACTS_DIR = path.resolve(HERE, "../../../contracts");

export const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);

for (const schema of [
  commonSchema,
  locationSchema,
  userDeviceSchema,
  beatPlanSchema,
  checkinEventSchema,
  attendanceEventSchema,
  bankSchema,
  circleSchema,
  cspAssignmentSchema,
  trackChunkSchema,
  cspChangeRequestSchema,
]) {
  ajv.addSchema(schema as Record<string, unknown>);
}

const BASE = "https://contracts.eko-dc-visits/c1/";

function getValidator(ref: string): ValidateFunction {
  const v = ajv.getSchema(BASE + ref);
  if (!v) throw new Error(`C1 schema not found: ${ref}`);
  return v;
}

export const validateCheckinEvent = getValidator("checkin-event.schema.json");
export const validateAttendanceEvent = getValidator("attendance-event.schema.json");
export const validateTrackChunk = getValidator("track-chunk.schema.json");
export const validateLocation = getValidator("location.schema.json");
export const validateUser = getValidator("user-device.schema.json#/$defs/user");
export const validateDevice = getValidator("user-device.schema.json#/$defs/device");
export const validateBeatPlan = getValidator("beat-plan.schema.json");
export const validateBank = getValidator("bank.schema.json");
export const validateCircle = getValidator("circle.schema.json#/$defs/circle");
export const validateCircleMembership = getValidator("circle.schema.json#/$defs/membership");
export const validateCspAssignment = getValidator("csp-assignment.schema.json");

export function ajvErrorStrings(v: ValidateFunction): string[] {
  return (v.errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "invalid"}`);
}
