/**
 * Typed API client for the C2 contract (contracts/c2-api/openapi.yaml, v0.1.0).
 *
 * THE ONLY MODULE ALLOWED TO PERFORM NETWORK I/O. No fetch/XHR anywhere else in web/
 * ("no raw HTTP to internal endpoints" — CONTRIBUTING.md, Definition of done).
 *
 * M0 deviation: types below are HAND-TRANSCRIBED from the OpenAPI spec. M1 replaces
 * this transcription with spec-driven codegen (see web/README.md).
 */

// ---------------------------------------------------------------------------
// Types transcribed from contracts/c2-api/openapi.yaml + contracts/c1-entities/*
// ---------------------------------------------------------------------------

/** RFC-7807 problem details (C2 components.schemas.Problem). */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail?: string;
}

/** c1-entities/common.schema.json#/$defs/geoPoint */
export interface GeoPoint {
  lat: number;
  lng: number;
  accuracy_m?: number;
  provider?: 'fused' | 'gps' | 'network' | 'unknown';
  is_mock?: boolean;
}

/** c1-entities/common.schema.json#/$defs/role */
export type Role = 'DC' | 'CIRCLE_HEAD' | 'NATIONAL_HEAD' | 'HR_ADMIN' | 'CORPORATE_ADMIN' | 'BANK_OFFICIAL'; // contracts v0.2.0 (design 0001)

/** c1-entities/user-device.schema.json#/$defs/user */
export interface User {
  id: string;
  tenant_id: string;
  name: string;
  phone: string;
  employee_code?: string;
  role: Role;
  scope_location_id?: string | null;
  status: 'INVITED' | 'ACTIVE' | 'SUSPENDED' | 'EXITED';
}

/** 200 response of POST /auth/otp/verify (C2). */
export interface LoginResponse {
  access_token: string;
  refresh_token: string;
  device_id: string;
  user: User;
}

/** c1-entities/location.schema.json (consumed via GET /master-data/locations). */
export interface Location {
  id: string;
  tenant_id: string;
  type: 'LHO' | 'RBO' | 'BRANCH' | 'CSP';
  parent_id?: string | null;
  name: string;
  code: string;
  address?: string;
  state: string;
  district: string;
  pin_code?: string;
  coordinates: GeoPoint;
  radius_m: number;
  coordinate_confidence: 'UNVERIFIED' | 'FIELD_CAPTURED' | 'VERIFIED';
  status?: 'ACTIVE' | 'SUSPENDED' | 'CLOSED' | 'RELOCATED';
  updated_at: string;
}

export type GeofenceResult = 'INSIDE' | 'OUTSIDE_FLAGGED';
export type SyncState = 'SYNCED' | 'LATE_SYNC';

/** C2 components.schemas.Visit (server read model; dc_name/location_name/etc. are optional per spec). */
export interface Visit {
  id: string;
  dc_user_id: string;
  dc_name?: string;
  location_id: string;
  location_name?: string;
  location_code?: string;
  planned?: boolean;
  checkin: {
    occurred_at: string; // device_wall_time (UTC ISO); rendered IST by the UI
    server_received_at?: string;
    fix: GeoPoint;
  };
  distance_from_master_m?: number;
  geofence_result: GeofenceResult;
  out_of_radius_reason?: string | null;
  sync_state: SyncState;
}

/** 200 response of GET /dashboard/visits (C2). */
export interface VisitsResponse {
  items: Visit[];
}

/** C2 GET /dashboard/attendance row (v0.3.0, design 0001 §7). */
export type AttendanceStatus = 'NOT_STARTED' | 'ON_DUTY' | 'ENDED';
export interface AttendanceRow {
  dc_user_id: string;
  dc_name: string;
  status: AttendanceStatus;
  started_at?: string | null;
  ended_at?: string | null;
}
export interface AttendanceResponse {
  items: AttendanceRow[];
}

/** c1-entities/csp-assignment.schema.json (v0.2.0, design 0001 §3). */
export type AssignmentReason = 'INITIAL_ALLOCATION' | 'TRANSFER' | 'REBALANCE' | 'COVERAGE_GAP';
export interface CspAssignment {
  id: string;
  tenant_id: string;
  circle_id: string;
  csp_location_id: string;
  dc_user_id: string;
  assigned_by_user_id: string;
  reason: AssignmentReason;
  valid_from: string;
  valid_to: string | null;
  updated_at: string;
}
export interface CspAssignmentsResponse {
  items: CspAssignment[];
}

/** 200 response of POST /circle/csp-assignments/transfer (C2 v0.3.0). */
export interface TransferResponse {
  assignment: CspAssignment;
  ended_assignment_id: string | null;
}

export interface LocationsResponse {
  items: Location[];
  next_cursor: string | null;
}

// ---------------------------------------------------------------------------
// Error type
// ---------------------------------------------------------------------------

/** Thrown for any non-2xx response or network failure. Always carries a Problem. */
export class ApiError extends Error {
  readonly problem: Problem;
  /** True when the request never reached the server (offline / backend down). */
  readonly networkFailure: boolean;

  constructor(problem: Problem, networkFailure = false) {
    super(`${problem.title} (${problem.status})${problem.detail ? `: ${problem.detail}` : ''}`);
    this.name = 'ApiError';
    this.problem = problem;
    this.networkFailure = networkFailure;
  }
}

// ---------------------------------------------------------------------------
// Session (tokens in memory, mirrored to sessionStorage so a reload survives)
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'eko-dc-visits.session';

export interface Session {
  access_token: string;
  refresh_token: string;
  device_id: string;
  user: User;
}

let session: Session | null = null;

function loadFromStorage(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function getSession(): Session | null {
  if (session === null) session = loadFromStorage();
  return session;
}

export function clearSession(): void {
  session = null;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    /* storage unavailable — in-memory copy already cleared */
  }
}

function storeSession(s: Session): void {
  session = s;
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(s));
  } catch {
    /* storage unavailable — keep in-memory only */
  }
}

// ---------------------------------------------------------------------------
// Core request helper
// ---------------------------------------------------------------------------

const BASE = '/api/v1'; // C2 servers[0].url; vite dev-proxies /api → http://localhost:3000

function problemFromStatus(status: number, title: string): Problem {
  return { type: 'about:blank', title, status };
}

interface RequestOpts {
  method: 'GET' | 'POST';
  path: string;
  body?: unknown;
  auth?: boolean;
  signal?: AbortSignal;
}

async function request(opts: RequestOpts): Promise<unknown> {
  const headers: Record<string, string> = {};
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (opts.auth) {
    const s = getSession();
    if (!s) {
      onUnauthorized();
      throw new ApiError(problemFromStatus(401, 'Not signed in'));
    }
    headers['Authorization'] = `Bearer ${s.access_token}`;
  }

  let res: Response;
  try {
    res = await fetch(BASE + opts.path, {
      method: opts.method,
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      signal: opts.signal ?? null,
    });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw new ApiError(
      {
        type: 'about:blank',
        title: 'API unreachable',
        status: 0,
        detail: err instanceof Error ? err.message : 'Network request failed',
      },
      true,
    );
  }

  if (res.status === 401 && opts.auth) {
    // Session expired/revoked. C2 v0.1.0 defines no refresh endpoint → re-login.
    clearSession();
    onUnauthorized();
    throw new ApiError(await problemFromResponse(res));
  }

  if (!res.ok) throw new ApiError(await problemFromResponse(res));

  if (res.status === 204) return undefined;
  return (await res.json()) as unknown;
}

async function problemFromResponse(res: Response): Promise<Problem> {
  try {
    const body = (await res.json()) as Partial<Problem>;
    if (typeof body?.title === 'string' && typeof body?.status === 'number') {
      return { type: body.type ?? 'about:blank', title: body.title, status: body.status, detail: body.detail };
    }
  } catch {
    /* non-JSON error body — fall through to synthetic problem */
  }
  return problemFromStatus(res.status, res.statusText || `HTTP ${res.status}`);
}

/** 401 hook — main.ts installs the redirect to #/login. */
let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void): void {
  onUnauthorized = fn;
}

// ---------------------------------------------------------------------------
// Endpoints (C2 operationIds)
// ---------------------------------------------------------------------------

/** POST /auth/otp/request → 204. Dev gateway stub always sends OTP 000000. */
export async function requestOtp(phone: string): Promise<void> {
  await request({ method: 'POST', path: '/auth/otp/request', body: { phone } });
}

/** POST /auth/otp/verify → session. Sends browser-derived device info (spec requires device.hardware). */
export async function verifyOtp(phone: string, otp: string): Promise<LoginResponse> {
  const body = {
    phone,
    otp,
    device: {
      hardware: {
        manufacturer: 'web',
        model: navigator.userAgent.slice(0, 120),
        os_version: navigator.platform || 'unknown',
      },
    },
  };
  const data = (await request({ method: 'POST', path: '/auth/otp/verify', body })) as LoginResponse;
  storeSession({
    access_token: data.access_token,
    refresh_token: data.refresh_token,
    device_id: data.device_id,
    user: data.user,
  });
  return data;
}

/** GET /dashboard/visits?date=YYYY-MM-DD (IST calendar date). Server-side role scoping (C6). */
export async function listVisits(date: string, signal?: AbortSignal): Promise<VisitsResponse> {
  const qs = new URLSearchParams({ date });
  return (await request({
    method: 'GET',
    path: `/dashboard/visits?${qs.toString()}`,
    auth: true,
    signal,
  })) as VisitsResponse;
}

/** GET /dashboard/attendance?date= (C2 v0.3.0). NH/HR tenant-wide, CH circle, DC self — scoped server-side. */
export async function listAttendance(date: string, signal?: AbortSignal): Promise<AttendanceResponse> {
  const qs = new URLSearchParams({ date });
  return (await request({
    method: 'GET',
    path: `/dashboard/attendance?${qs.toString()}`,
    auth: true,
    signal,
  })) as AttendanceResponse;
}

/** C2 GET /dashboard/scorecard (v0.4.0, design 0002 — dc_score_v1). */
export type Badge = 'EARLY_BIRD' | 'PERFECT_DAY' | 'STREAK_3' | 'STREAK_7';
export interface ScorecardRow {
  dc_user_id: string;
  dc_name: string;
  points: number;
  visits_done: number;
  geo_verified_visits: number;
  on_time_start: boolean;
  started_at?: string | null;
  streak_days: number;
  badges: Badge[];
}
export interface ScorecardResponse {
  formula_version: 'dc_score_v1';
  items: ScorecardRow[];
}

/** GET /dashboard/scorecard?date= (C2 v0.4.0). DC self, CH circle, NH tenant — scoped server-side. */
export async function listScorecards(date: string, signal?: AbortSignal): Promise<ScorecardResponse> {
  const qs = new URLSearchParams({ date });
  return (await request({
    method: 'GET',
    path: `/dashboard/scorecard?${qs.toString()}`,
    auth: true,
    signal,
  })) as ScorecardResponse;
}

/** C2 GET /dashboard/overview (v0.5.0) — admin cockpit. */
export interface OverviewResponse {
  date: string;
  attendance: { total_dcs: number; on_duty: number; ended: number; not_started: number };
  visits: { total: number; geo_verified: number; flagged: number; late_sync: number; unplanned: number };
  csps: { total: number; assigned: number; unassigned: number; coordinates_unverified: number };
  circles: Array<{
    circle_id: string;
    circle_name: string;
    circle_head: string | null;
    dc_count: number;
    on_duty: number;
    csp_count: number;
    visits_today: number;
    flagged_today: number;
  }>;
  banks: Array<{ name: string; code: string; status: string; csp_count: number }>;
  assignments_by_dc: Array<{
    dc_user_id: string;
    dc_name: string;
    csp_count: number;
    attendance: AttendanceStatus;
    visits_today: number;
  }>;
}

/** GET /dashboard/overview?date= (CORPORATE_ADMIN + NATIONAL_HEAD only, enforced server-side). */
export async function getOverview(date: string, signal?: AbortSignal): Promise<OverviewResponse> {
  const qs = new URLSearchParams({ date });
  return (await request({
    method: 'GET',
    path: `/dashboard/overview?${qs.toString()}`,
    auth: true,
    signal,
  })) as OverviewResponse;
}

/** GET /master-data/csp-assignments (C2 v0.2.0). DC own; Circle Head circle's. */
export async function listCspAssignments(signal?: AbortSignal): Promise<CspAssignmentsResponse> {
  return (await request({
    method: 'GET',
    path: '/master-data/csp-assignments',
    auth: true,
    signal,
  })) as CspAssignmentsResponse;
}

/** C2 POST /circle/csp-assignments/import (v0.6.0) — bulk spreadsheet assignment. */
export interface ImportResultRow {
  row: number;
  csp_code: string;
  dc_phone: string;
  result: 'assigned' | 'transferred' | 'unchanged' | 'rejected';
  reason?: string;
  dc_name?: string;
}
export interface ImportResponse {
  summary: { total: number; assigned: number; transferred: number; unchanged: number; rejected: number };
  results: ImportResultRow[];
}

export async function importCspAssignments(rows: Array<{ csp_code: string; dc_phone: string }>): Promise<ImportResponse> {
  return (await request({
    method: 'POST',
    path: '/circle/csp-assignments/import',
    body: { rows },
    auth: true,
  })) as ImportResponse;
}

/** GET /master-data/locations (C2). Single page is sufficient for a circle-sized territory. */
export async function listLocations(signal?: AbortSignal): Promise<LocationsResponse> {
  return (await request({
    method: 'GET',
    path: '/master-data/locations?limit=1000',
    auth: true,
    signal,
  })) as LocationsResponse;
}

/** POST /circle/csp-assignments/transfer (C2 v0.3.0). Circle Head only; server enforces circle guardrails. */
export async function transferCsp(
  cspLocationId: string,
  toDcUserId: string,
  reason: AssignmentReason,
): Promise<TransferResponse> {
  return (await request({
    method: 'POST',
    path: '/circle/csp-assignments/transfer',
    body: { csp_location_id: cspLocationId, to_dc_user_id: toDcUserId, reason },
    auth: true,
  })) as TransferResponse;
}
