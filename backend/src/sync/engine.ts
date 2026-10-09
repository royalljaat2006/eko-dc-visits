/**
 * Sync ingestion engine — implements contracts/c3-sync/PROTOCOL.md §2–4 literally.
 *
 * - At-least-once, idempotent: dedupe on op_id; a replay returns a
 *   byte-identical disposition derived from the stored original, with zero
 *   writes (C3 §3 "duplicate").
 * - Schema-invalid or referentially impossible payloads are persisted raw in
 *   quarantine and acked `quarantined` — never discarded (ADR-0003).
 * - `rejected` only for ops missing an op_id entirely (C3 §3: unparseable
 *   garbage that cannot be quarantined with an op_id).
 * - Geofence is advisory (ADR-0004): outside effective_radius
 *   (= radius_m + accuracy_m) → `accepted-flagged` with flag OUTSIDE_RADIUS,
 *   never rejected. Derived values live on the Visit read model; the stored
 *   event is never mutated.
 * - Triple timestamps (C3 §4): server sets server_received_at; preserves
 *   device_wall_time and monotonic_ms as captured.
 * - Convergence (C3 §3): every write below is insert-if-absent keyed on
 *   client ids, so any permutation + duplication of batches converges.
 */
import { createHash } from "node:crypto";
import type {
  AttendanceEvent,
  CheckInEvent,
  CheckoutEvent,
  OpDisposition,
  Principal,
  StoredAttendanceEvent,
  StoredCheckoutEvent,
  StoredTrackChunk,
  StoredVisitPhoto,
  TrackChunk,
  StoredCheckInEvent,
  SyncBatch,
  Visit,
  VisitPhoto,
} from "../domain/types.js";
import type { Repos } from "../repos/types.js";
import { haversineMeters, istDateOf } from "../geo.js";
import {
  ajvErrorStrings,
  validateAttendanceEvent,
  validateCheckinEvent,
  validateCheckoutEvent,
  validateTrackChunk,
  validateVisitPhoto,
} from "../validation/schemas.js";

export type Clock = () => Date;

export interface BatchResponse {
  batch_id: string;
  results: OpDisposition[];
}

/** Disposition returned for a replayed op: result code `duplicate`, original flags preserved (deterministic → byte-identical on every replay). */
function duplicateOf(original: OpDisposition): OpDisposition {
  return original.flags !== undefined
    ? { op_id: original.op_id, result: "duplicate", flags: original.flags }
    : { op_id: original.op_id, result: "duplicate" };
}

export async function applySyncBatch(
  repos: Repos,
  principal: Principal,
  batch: SyncBatch,
  clock: Clock,
): Promise<BatchResponse> {
  const tenant = principal.tenant_id;
  const results: OpDisposition[] = [];

  for (const rawOp of batch.ops) {
    const op = (rawOp ?? {}) as { op_id?: unknown; seq?: unknown; type?: unknown; payload?: unknown };

    // C3 §3 `rejected`: no op_id → cannot even be quarantined under an identity.
    if (typeof op.op_id !== "string" || op.op_id.length === 0) {
      results.push({ op_id: "", result: "rejected" });
      continue;
    }
    const opId = op.op_id;

    // Idempotency: already-seen op_id → byte-identical disposition, zero writes.
    const existing = await repos.getOpDisposition(tenant, opId);
    if (existing) {
      results.push(duplicateOf(existing));
      continue;
    }

    const disposition = await applyNewOp(repos, principal, batch, opId, String(op.type ?? ""), op.payload, clock);
    await repos.putOpDispositionIfAbsent(tenant, opId, disposition);
    results.push(disposition);
  }

  return { batch_id: batch.batch_id, results };
}

async function applyNewOp(
  repos: Repos,
  principal: Principal,
  batch: SyncBatch,
  opId: string,
  opType: string,
  payload: unknown,
  clock: Clock,
): Promise<OpDisposition> {
  const tenant = principal.tenant_id;

  const quarantine = async (reason: "SCHEMA_INVALID" | "UNKNOWN_REFERENCE" | "UNSUPPORTED_TYPE", errors: string[]): Promise<OpDisposition> => {
    await repos.insertQuarantineIfAbsent({
      op_id: opId,
      tenant_id: tenant,
      batch_id: batch.batch_id,
      device_id: batch.device_id,
      submitted_by_user_id: principal.user_id,
      reason,
      errors,
      raw: payload ?? null, // persisted raw (ADR-0003: never discard)
      received_at: clock().toISOString(),
    });
    return { op_id: opId, result: "quarantined" };
  };

  // Spec §4 (v0.8.0): a Circle Head may submit only their OWN attendance.
  if (principal.role === "CIRCLE_HEAD" && !opType.startsWith("attendance.")) {
    return quarantine("UNSUPPORTED_TYPE", [`role CIRCLE_HEAD may only submit attendance evidence (got "${opType}")`]);
  }
  // Impersonation hardening (v0.8.0): every evidence payload must carry the
  // submitting principal's own dc_user_id. Deterministic per op → convergence-safe.
  const claimedDc = (payload as { dc_user_id?: unknown } | null)?.dc_user_id;
  if (typeof claimedDc === "string" && claimedDc !== principal.user_id) {
    return quarantine("UNKNOWN_REFERENCE", [`payload dc_user_id ${claimedDc} does not match the authenticated user`]);
  }

  // Dispatch by op type (C3 v0.3.0). Unknown types quarantine, never drop.
  if (opType === "attendance.start" || opType === "attendance.end") {
    return applyAttendanceOp(repos, principal, opId, opType, payload, clock, quarantine);
  }
  if (opType === "track.chunk") {
    if (!validateTrackChunk(payload)) {
      return quarantine("SCHEMA_INVALID", ajvErrorStrings(validateTrackChunk));
    }
    const chunk = payload as TrackChunk;
    const stored: StoredTrackChunk = {
      ...chunk,
      tenant_id: tenant,
      timestamps: { ...chunk.timestamps, server_received_at: clock().toISOString() },
    };
    // Append-only raw evidence; daily km is derived at READ time from the full
    // sorted point set (src/distance.ts), so chunk arrival order is irrelevant.
    await repos.insertTrackChunkIfAbsent(stored);
    // Derived live-position projection (migration 008) — newest point in this
    // chunk, only applied if newer than whatever's already stored; never
    // evidence itself, purely a read-optimization for the live map.
    if (chunk.points.length > 0) {
      const newest = chunk.points.reduce((a, b) => (b.t > a.t ? b : a));
      await repos.upsertDcLiveLocationIfNewer({
        tenant_id: tenant,
        dc_user_id: chunk.dc_user_id,
        device_id: chunk.device_id,
        lat: newest.lat,
        lng: newest.lng,
        accuracy_m: newest.accuracy_m ?? null,
        captured_at: newest.t,
        server_received_at: stored.timestamps.server_received_at,
      });
    }
    return { op_id: opId, result: "accepted" };
  }
  if (opType === "visit.photo") {
    if (!validateVisitPhoto(payload)) {
      return quarantine("SCHEMA_INVALID", ajvErrorStrings(validateVisitPhoto));
    }
    const photo = payload as VisitPhoto;
    // Re-hash the decoded bytes. A mismatch is FLAGGED, never rejected — an
    // audit system keeps every byte a device swears it captured (ADR-0003).
    // The visit_id is linked, never ordered: the photo may arrive before its
    // check-in and still converge (C3 §3).
    const actualSha = createHash("sha256").update(Buffer.from(photo.bytes_b64, "base64")).digest("hex");
    const matched = actualSha === photo.sha256;
    const stored: StoredVisitPhoto = {
      ...photo,
      tenant_id: tenant,
      upload_state: matched ? "STORED" : "HASH_MISMATCH",
      timestamps: { ...photo.timestamps, server_received_at: clock().toISOString() },
    };
    await repos.insertVisitPhotoIfAbsent(stored);
    return matched
      ? { op_id: opId, result: "accepted" }
      : { op_id: opId, result: "accepted-flagged", flags: ["HASH_MISMATCH"] };
  }

  if (opType === "visit.checkout") {
    if (!validateCheckoutEvent(payload)) {
      return quarantine("SCHEMA_INVALID", ajvErrorStrings(validateCheckoutEvent));
    }
    const event = payload as CheckoutEvent;
    const stored: StoredCheckoutEvent = {
      ...event,
      tenant_id: tenant,
      timestamps: { ...event.timestamps, server_received_at: clock().toISOString() },
    };
    // Append-only, linked by visit_id — may legitimately arrive before its
    // checkin op (different tiers can race). checked_out_at is derived at
    // READ time from the earliest such event, never mutates the checkin.
    await repos.insertCheckoutEventIfAbsent(stored);
    return { op_id: opId, result: "accepted" };
  }

  if (opType !== "visit.checkin") {
    return quarantine("UNSUPPORTED_TYPE", [`unknown op type "${opType}"`]);
  }

  // C1 schema validation (checkin-event.schema.json) — failures quarantine.
  if (!validateCheckinEvent(payload)) {
    return quarantine("SCHEMA_INVALID", ajvErrorStrings(validateCheckinEvent));
  }
  const event = payload as CheckInEvent;

  // Referential validation — unknown location/user quarantines (C3 §3
  // "referentially impossible").
  const [location, dcUser] = await Promise.all([
    repos.getLocationById(tenant, event.location_id),
    repos.getUserById(tenant, event.dc_user_id),
  ]);
  const refErrors: string[] = [];
  if (!location) refErrors.push(`unknown location_id ${event.location_id}`);
  if (!dcUser) refErrors.push(`unknown dc_user_id ${event.dc_user_id}`);
  if (!location || !dcUser) return quarantine("UNKNOWN_REFERENCE", refErrors);

  // Triple timestamps (C3 §4): server_received_at is server-set.
  const serverReceivedAt = clock().toISOString();

  // Geofence derivation (ADR-0004): advisory-with-evidence, never a gate.
  const distance = haversineMeters(event.fix, location.coordinates);
  const effectiveRadius = location.radius_m + (event.fix.accuracy_m ?? 0);
  const inside = distance <= effectiveRadius;

  // Append-only evidence write — the event as captured, plus server timestamp.
  const stored: StoredCheckInEvent = {
    ...event,
    tenant_id: tenant,
    timestamps: { ...event.timestamps, server_received_at: serverReceivedAt },
  };
  await repos.insertCheckinEventIfAbsent(stored);

  // Derived values live on the Visit read model, never mutate the event.
  const visit: Visit = {
    id: event.id,
    tenant_id: tenant,
    dc_user_id: event.dc_user_id,
    location_id: event.location_id,
    planned: event.planned_stop_id != null,
    occurred_at: event.timestamps.device_wall_time,
    server_received_at: serverReceivedAt,
    fix: event.fix,
    distance_from_master_m: Math.round(distance * 10) / 10,
    geofence_result: inside ? "INSIDE" : "OUTSIDE_FLAGGED",
    out_of_radius_reason: event.out_of_radius_reason ?? null,
    // LATE_SYNC when the server received it on a different IST calendar day
    // than the device wall-clock capture day.
    sync_state:
      istDateOf(event.timestamps.device_wall_time) === istDateOf(serverReceivedAt) ? "SYNCED" : "LATE_SYNC",
  };
  await repos.insertVisitIfAbsent(visit);

  return inside
    ? { op_id: opId, result: "accepted" }
    : { op_id: opId, result: "accepted-flagged", flags: ["OUTSIDE_RADIUS"] };
}

/**
 * attendance.start / attendance.end (C3 v0.3.0). Fix is LOGGED, never gated
 * (ADR-0004). The AttendanceDay read model uses commutative merges, so the
 * disposition and final state are independent of op order — deliberately no
 * cross-op judgment here (e.g. "check-in before attendance") because
 * order-dependent flags would break the C3 §3 convergence invariant; that
 * correlation is an M2 server-side analytics rule over the stored evidence.
 */
async function applyAttendanceOp(
  repos: Repos,
  principal: Principal,
  opId: string,
  opType: "attendance.start" | "attendance.end",
  payload: unknown,
  clock: Clock,
  quarantine: (reason: "SCHEMA_INVALID" | "UNKNOWN_REFERENCE", errors: string[]) => Promise<OpDisposition>,
): Promise<OpDisposition> {
  if (!validateAttendanceEvent(payload)) {
    return quarantine("SCHEMA_INVALID", ajvErrorStrings(validateAttendanceEvent));
  }
  const event = payload as AttendanceEvent;
  const expectedKind = opType === "attendance.start" ? "START" : "END";
  if (event.kind !== expectedKind) {
    return quarantine("SCHEMA_INVALID", [`op type ${opType} carries kind ${event.kind}`]);
  }
  const dcUser = await repos.getUserById(principal.tenant_id, event.dc_user_id);
  if (!dcUser) return quarantine("UNKNOWN_REFERENCE", [`unknown dc_user_id ${event.dc_user_id}`]);

  const serverReceivedAt = clock().toISOString();
  const stored: StoredAttendanceEvent = {
    ...event,
    tenant_id: principal.tenant_id,
    timestamps: { ...event.timestamps, server_received_at: serverReceivedAt },
  };
  await repos.insertAttendanceEventIfAbsent(stored);
  await repos.mergeAttendanceDay(
    principal.tenant_id,
    event.dc_user_id,
    istDateOf(event.timestamps.device_wall_time),
    event.kind,
    event.timestamps.device_wall_time,
  );
  return { op_id: opId, result: "accepted" };
}
