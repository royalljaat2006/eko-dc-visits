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
