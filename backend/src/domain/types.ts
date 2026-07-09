/**
 * Domain types mirroring contracts/c1-entities (contracts v0.1.0).
 * These are hand-written TypeScript projections of the C1 JSON Schemas; the
 * schemas remain the source of truth and every ingested payload is
 * ajv-validated against them (src/validation/schemas.ts).
 */

export type TenantId = string;

export type LocationType = "LHO" | "RBO" | "BRANCH" | "CSP";
export type Role = "DC" | "CIRCLE_HEAD" | "NATIONAL_HEAD" | "HR_ADMIN" | "CORPORATE_ADMIN" | "BANK_OFFICIAL";
export type CoordinateConfidence = "UNVERIFIED" | "FIELD_CAPTURED" | "VERIFIED";
export type LocationStatus = "ACTIVE" | "SUSPENDED" | "CLOSED" | "RELOCATED";
export type UserStatus = "INVITED" | "ACTIVE" | "SUSPENDED" | "EXITED";
export type BindingState = "PENDING" | "BOUND" | "REPLACED" | "REVOKED";
export type GeofenceResult = "INSIDE" | "OUTSIDE_FLAGGED";
export type SyncState = "SYNCED" | "LATE_SYNC";
export type OutOfRadiusReason =
  | "INSIDE_PREMISES_GPS_WEAK"
  | "CSP_RELOCATED"
  | "MASTER_PIN_WRONG"
  | "OTHER";

/** c1-entities/common.schema.json#/$defs/geoPoint */
export interface GeoPoint {
  lat: number;
  lng: number;
  accuracy_m?: number;
  provider?: "fused" | "gps" | "network" | "unknown";
  is_mock?: boolean;
}

/** c1-entities/common.schema.json#/$defs/evidenceTimestamps (ADR-0003 triple timestamps) */
export interface EvidenceTimestamps {
  device_wall_time: string;
  monotonic_ms: number;
  server_received_at?: string; // server-set, never the client
}

/** c1-entities/bank.schema.json (design 0001 §8) */
export interface Bank {
  id: string;
  tenant_id: TenantId;
  name: string;
  code: string;
  license_no?: string;
  license_obtained_on?: string;
  status: "ONBOARDING" | "ACTIVE" | "SUSPENDED";
  updated_at: string;
}

/** c1-entities/location.schema.json */
export interface LocationNode {
  id: string;
  tenant_id: TenantId;
  bank_id: string;
  type: LocationType;
  parent_id?: string | null;
  name: string;
  code: string;
  address?: string;
  state?: string;
  district?: string;
  pin_code?: string;
  coordinates: GeoPoint;
  radius_m: number;
  coordinate_confidence: CoordinateConfidence;
  status?: LocationStatus;
  updated_at: string;
}

/** c1-entities/user-device.schema.json#/$defs/user */
export interface User {
  id: string;
  tenant_id: TenantId;
  name: string;
  phone: string;
  employee_code?: string;
  role: Role;
  scope_location_id?: string | null;
  status: UserStatus;
}

/** c1-entities/user-device.schema.json#/$defs/device */
export interface Device {
  id: string;
  tenant_id: TenantId;
  user_id: string;
  hardware?: { manufacturer?: string; model?: string; os_version?: string };
  public_key?: string;
  binding_state: BindingState;
}

/** c1-entities/beat-plan.schema.json */
export interface BeatPlanStop {
  id: string;
  seq: number;
  location_id: string;
  visit_type: "CSP_AUDIT" | "CSP_FOLLOWUP" | "BRANCH_MEETING" | "RBO_MEETING" | "LHO_MEETING";
}

export interface BeatPlan {
  id: string;
  tenant_id: TenantId;
  dc_user_id: string;
  plan_date: string; // IST calendar date
  version: number;
  stops: BeatPlanStop[];
  updated_at: string;
}

/** c1-entities/checkin-event.schema.json — append-only evidence (ADR-0003) */
export interface CheckInEvent {
  id: string;
  dc_user_id: string;
  device_id: string;
  location_id: string;
  planned_stop_id?: string | null;
  beat_plan_version?: number;
  fix: GeoPoint;
  timestamps: EvidenceTimestamps;
  out_of_radius_reason?: OutOfRadiusReason;
  /** design 0001 §4: AUTO_GEOFENCE = dwell-matcher-emitted; trigger mix is an analytics signal. */
  trigger?: "MANUAL" | "AUTO_GEOFENCE";
  /** Other assigned CSPs whose effective radius also contained the fix (dense-market overlap). */
  nearby_candidates?: string[];
  remarks?: string;
}

/** Stored form: tenant-stamped, server_received_at set at ingest. Never updated. */
export interface StoredCheckInEvent extends CheckInEvent {
  tenant_id: TenantId;
  timestamps: EvidenceTimestamps & { server_received_at: string };
}

/**
 * Visit read model (C2 #/components/schemas/Visit). Derived from evidence at
 * ingest; the underlying event is never mutated (checkin-event.schema.json
 * description, ADR-0004).
 */
export interface Visit {
  id: string; // = check-in event id
  tenant_id: TenantId;
  dc_user_id: string;
  location_id: string;
  planned: boolean;
  occurred_at: string; // device_wall_time
  server_received_at: string;
  fix: GeoPoint;
  distance_from_master_m: number;
  geofence_result: GeofenceResult;
  out_of_radius_reason: OutOfRadiusReason | null;
  sync_state: SyncState;
}

/** Visit joined with display names for the dashboard (C2 Visit response). */
export interface VisitView {
  id: string;
  dc_user_id: string;
  dc_name: string;
  location_id: string;
  location_name: string;
  location_code: string;
  planned: boolean;
  checkin: {
    occurred_at: string;
    server_received_at: string;
    fix: GeoPoint;
  };
  distance_from_master_m: number;
  geofence_result: GeofenceResult;
  out_of_radius_reason: OutOfRadiusReason | null;
  sync_state: SyncState;
}

/** c1-entities/circle.schema.json#/$defs/circle (design 0001 §3) */
export interface Circle {
  id: string;
  tenant_id: TenantId;
  name: string;
  description?: string;
  status: "ACTIVE" | "RETIRED";
  updated_at: string;
}

/** c1-entities/circle.schema.json#/$defs/membership — effective-dated, never deleted. */
export interface CircleMembership {
  id: string;
  tenant_id: TenantId;
  circle_id: string;
  user_id: string;
  role_in_circle: "DC" | "CIRCLE_HEAD";
  valid_from: string; // date
  valid_to: string | null;
}

/** c1-entities/csp-assignment.schema.json — CSP↔DC, Circle-Head-owned (design 0001 §3). */
export interface CspAssignment {
  id: string;
  tenant_id: TenantId;
  circle_id: string;
  csp_location_id: string;
  dc_user_id: string;
  assigned_by_user_id: string;
  reason: "INITIAL_ALLOCATION" | "TRANSFER" | "REBALANCE" | "COVERAGE_GAP";
  valid_from: string; // date
  valid_to: string | null;
  updated_at: string;
}

export interface RefreshToken {
  token: string;
  tenant_id: TenantId;
  user_id: string;
  device_id: string;
  expires_at: string;
}

/** C3 §2 batch envelope (subset typing; ajv on op payloads is the gate). */
export interface SyncOp {
  op_id: string;
  seq: number;
  type: "visit.checkin";
  payload: unknown;
}

export interface SyncBatch {
  batch_id: string;
  device_id: string;
  seq_from: number;
  seq_to: number;
  client_time: string;
  app_version: string;
  contract_version: string;
  queue_depth_by_tier?: { t1?: number; t2?: number; t3?: number; t4?: number };
  oldest_unsynced_age_s?: number;
  health?: { battery_pct?: number; network?: string; storage_free_mb?: number };
  ops: unknown[];
  signature?: string;
}

export type OpResultCode = "accepted" | "accepted-flagged" | "duplicate" | "quarantined" | "rejected";

/** Per-op disposition (C2 sync response item). Stored to guarantee byte-identical replays (C3 §3). */
export interface OpDisposition {
  op_id: string;
  result: OpResultCode;
  flags?: string[];
}

export interface QuarantinedOp {
  op_id: string;
  tenant_id: TenantId;
  batch_id: string;
  device_id: string;
  submitted_by_user_id: string;
  reason: "SCHEMA_INVALID" | "UNKNOWN_REFERENCE";
  errors: string[];
  raw: unknown; // persisted raw, never discarded (ADR-0003)
  received_at: string;
}

export interface Principal {
  user_id: string;
  tenant_id: TenantId;
  role: Role;
  device_id: string;
}
