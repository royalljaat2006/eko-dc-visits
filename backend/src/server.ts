/**
 * HTTP layer — implements exactly the paths in contracts/c2-api/openapi.yaml
 * (v0.1.0, M0 scope). scripts/contracts-check.ts diffs IMPLEMENTED_ROUTES
 * against the spec in both directions.
 */
import { randomUUID } from "node:crypto";
import Fastify, { type FastifyError, type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import helmet from "@fastify/helmet";
import rateLimit from "@fastify/rate-limit";
import type { LocationNode, Principal, SyncBatch } from "./domain/types.js";
import type { Repos } from "./repos/types.js";
import { resolveScope } from "./scope.js";
import { applySyncBatch, type Clock } from "./sync/engine.js";
import { DEV_TOKEN_CONFIG, newRefreshToken, signAccessToken, verifyAccessToken, type TokenConfig } from "./auth/tokens.js";
import { ekoSendOtp, ekoVerifyOtp, loadEkoConfig } from "./auth/eko.js";
import { istDateOf } from "./geo.js";
import { computeScorecard } from "./scorecard.js";
import { kmForPoints } from "./distance.js";
import { deriveAttendance } from "./attendance.js";

/** Route inventory consumed by contracts:check (paths relative to servers[0].url = /api/v1). */
export const IMPLEMENTED_ROUTES: ReadonlyArray<{ method: string; path: string }> = [
  { method: "post", path: "/auth/otp/request" },
  { method: "post", path: "/auth/otp/verify" },
  { method: "post", path: "/auth/token/refresh" },
  { method: "get", path: "/master-data/locations" },
  { method: "get", path: "/master-data/csp-assignments" },
  { method: "get", path: "/master-data/beat-plans" },
  { method: "post", path: "/sync/batches" },
  { method: "post", path: "/circle/csp-assignments/transfer" },
  { method: "post", path: "/circle/csp-assignments/import" },
  { method: "post", path: "/circle/csp-details/import" },
  { method: "post", path: "/circle/home-locations/import" },
  { method: "get", path: "/dashboard/visits" },
  { method: "get", path: "/dashboard/attendance" },
  { method: "get", path: "/dashboard/scorecard" },
  { method: "get", path: "/dashboard/overview" },
  { method: "get", path: "/dc/csp-details" },
  { method: "post", path: "/dc/csp-change-requests" },
  { method: "get", path: "/circle/csp-change-requests" },
  { method: "post", path: "/circle/csp-change-requests/decide" },
];

export const DEV_OTP = "000000"; // C2: M0 stub gateway always sends '000000' in dev
/**
 * Pilot hardening: on public deployments PILOT_OTP overrides the dev stub —
 * a per-deployment secret distributed to enrolled pilot users out-of-band.
 * Superseded by the real Eko SMS-OTP gateway (below) once EKO_* env vars are set.
 */
const OTP_CODE = process.env.PILOT_OTP ?? DEV_OTP;
/**
 * Real SMS OTP (Eko Mobile/OTP Verification API). null when EKO_DEVELOPER_KEY
 * / EKO_ACCESS_KEY / EKO_INITIATOR_ID aren't all set, in which case the routes
 * below fall back to OTP_CODE — same as every environment before this.
 */
const EKO = loadEkoConfig();
const PHONE_PATTERN = /^[6-9][0-9]{9}$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
/** Tenant a self-registered DC lands in — matches the seed fixtures' tenant so scoping stays consistent. */
const SELF_REGISTER_TENANT_ID = "eko";

/**
 * Editable CSP master fields (spec §3.1 template). CORE = first-class Location
 * columns; PROFILE = the free-form `csp_profile` string map (template growth
 * never needs a schema bump — location.schema.json). The same whitelist gates
 * DC change-requests and the Circle Head bulk detail import; anything not
 * listed is silently ignored, never applied.
 */
const CSP_CORE_FIELDS = ["name", "address", "lat", "lng"] as const;
const CSP_PROFILE_FIELDS = [
  "gender", "csp_mail_id", "mobile_number", "alternative_mobile_number",
  "relationship_manager", "district", "ao", "ao_email", "branch_code", "branch_name",
  "branch_email", "rbo_name", "rbo_email", "state", "circle_head_name",
  "lho_name", "lho_mail_id", "population", "pin_code",
] as const;

type CspFieldDiff = Record<string, { old: string | number | null; new: string | number }>;

/** Diff a proposed field map against a Location — whitelist-filtered, only real changes. */
function diffCspFields(
  loc: { name: string; address?: string; coordinates: { lat: number; lng: number }; csp_profile?: Record<string, string> },
  proposed: Record<string, unknown>,
): CspFieldDiff {
  const changes: CspFieldDiff = {};
  for (const [field, value] of Object.entries(proposed)) {
    if (typeof value !== "string" && typeof value !== "number") continue;
    if ((CSP_CORE_FIELDS as readonly string[]).includes(field)) {
      const old =
        field === "name" ? loc.name
        : field === "address" ? (loc.address ?? null)
        : field === "lat" ? loc.coordinates.lat
        : loc.coordinates.lng;
      if (String(old ?? "") !== String(value)) changes[field] = { old, new: value };
    } else if ((CSP_PROFILE_FIELDS as readonly string[]).includes(field)) {
      const old = loc.csp_profile?.[field] ?? null;
      if (String(old ?? "") !== String(value)) changes[field] = { old, new: value };
    }
  }
  return changes;
}

/** Turn an approved/committed diff into an updateLocationFields patch. */
function patchFromDiff(changes: CspFieldDiff, updated_at: string) {
  const patch: { name?: string; address?: string; lat?: number; lng?: number; profile?: Record<string, string>; updated_at: string } = { updated_at };
  for (const [field, ch] of Object.entries(changes)) {
    if (field === "name") patch.name = String(ch.new);
    else if (field === "address") patch.address = String(ch.new);
    else if (field === "lat") patch.lat = Number(ch.new);
    else if (field === "lng") patch.lng = Number(ch.new);
    else (patch.profile ??= {})[field] = String(ch.new);
  }
  return patch;
}

export interface ServerDeps {
  repos: Repos;
  clock?: Clock;
  tokens?: TokenConfig;
}

interface AuthedRequest extends FastifyRequest {
  principal?: Principal;
}

function problem(reply: FastifyReply, status: number, title: string, detail?: string): FastifyReply {
  return reply
    .code(status)
    .type("application/problem+json")
    .send({ type: "about:blank", title, status, ...(detail ? { detail } : {}) });
}

export function buildServer(deps: ServerDeps): FastifyInstance {
  const repos = deps.repos;
  const clock: Clock = deps.clock ?? (() => new Date());
  const tokens = deps.tokens ?? DEV_TOKEN_CONFIG;

  const app = Fastify({
    logger: process.env.LOG_LEVEL ? { level: process.env.LOG_LEVEL } : false,
    // Behind a TLS-terminating reverse proxy (infra/self-hosted Caddy) the real
    // client IP arrives in X-Forwarded-For — opt in per deployment.
    trustProxy: process.env.TRUST_PROXY === "1" || process.env.TRUST_PROXY === "true",
    // Sync batches carry base64 photos (visit.photo, T2). 16 MiB matches the
    // client's per-photo ceiling with headroom.
    bodyLimit: Number.parseInt(process.env.BODY_LIMIT_BYTES ?? "", 10) || 16 * 1024 * 1024,
  });

  // Security headers. CSP is off — this is a JSON API, not an HTML origin.
  app.register(helmet, { contentSecurityPolicy: false, crossOriginResourcePolicy: false });

  // Rate limiting is opt-in per route (global:false) so the only limited
  // surface is credential entry. Loopback is exempt: local dev + the test
  // suite's app.inject calls, and (without TRUST_PROXY) a same-host proxy.
  app.register(rateLimit, {
    global: false,
    allowList: ["127.0.0.1", "::1"],
    max: Number.parseInt(process.env.RATE_LIMIT_MAX ?? "", 10) || 20,
    timeWindow: process.env.RATE_LIMIT_WINDOW ?? "1 minute",
  });

  // Uncaught handler errors return problem+json (not Fastify's default shape)
  // and are logged with the request id for correlation.
  app.setErrorHandler((err: FastifyError, req, reply) => {
    if (reply.statusCode === 429 || err.statusCode === 429) {
      return problem(reply, 429, "Too Many Requests", "Slow down and retry shortly");
    }
    req.log.error({ err, reqId: req.id }, "unhandled route error");
    return problem(reply, err.statusCode && err.statusCode < 500 ? err.statusCode : 500, "Internal Server Error");
  });

  // Liveness/readiness probe — unauthenticated, no tenant data.
  app.get("/healthz", async () => ({ status: "ok", ts: new Date().toISOString() }));

  /** Bearer auth + hard gate HG3 (C6): no session from a REVOKED device binding. */
  async function requireAuth(req: AuthedRequest, reply: FastifyReply): Promise<void> {
    const header = req.headers.authorization;
    if (!header?.startsWith("Bearer ")) {
      problem(reply, 401, "Unauthorized", "Missing bearer token");
      return;
    }
    const principal = await verifyAccessToken(tokens, header.slice("Bearer ".length), clock());
    if (!principal) {
      problem(reply, 401, "Unauthorized", "Invalid or expired token");
      return;
    }
    const device = await repos.getDeviceById(principal.tenant_id, principal.device_id);
    if (!device || device.binding_state === "REVOKED") {
      problem(reply, 401, "Unauthorized", "Device binding revoked (HG3)");
      return;
    }
    req.principal = principal;
  }

  app.register(
    async (api) => {
      // ---- auth (C2 /auth/otp/request) -------------------------------------
      api.post("/auth/otp/request", { config: { rateLimit: { max: 5, timeWindow: "1 minute" } } }, async (req, reply) => {
        const body = (req.body ?? {}) as { phone?: unknown };
        if (typeof body.phone !== "string" || !PHONE_PATTERN.test(body.phone)) {
          return problem(reply, 400, "Bad Request", "phone must match ^[6-9][0-9]{9}$");
        }
        if (EKO) {
          const sent = await ekoSendOtp(EKO, body.phone);
          if (!sent.ok) {
            req.log.error({ reason: sent.reason }, "eko send-otp failed");
            return problem(reply, 502, "Bad Gateway", "Could not send the OTP right now — try again shortly");
          }
          return reply.code(204).send();
        }
        // No Eko config: dev stub / PILOT_OTP — gateway "sends" OTP_CODE; nothing to persist.
        return reply.code(204).send();
      });

      // ---- auth (C2 /auth/otp/verify) --------------------------------------
      api.post("/auth/otp/verify", { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } }, async (req, reply) => {
        const body = (req.body ?? {}) as {
          phone?: unknown;
          otp?: unknown;
          name?: unknown;
          device?: {
            hardware?: { manufacturer?: string; model?: string; os_version?: string };
            public_key?: unknown;
          } | null;
        };
        if (typeof body.phone !== "string" || typeof body.otp !== "string" || typeof body.device !== "object" || body.device === null) {
          return problem(reply, 400, "Bad Request", "phone, otp and device are required");
        }

        // Prove phone ownership first — independent of whether the number is
        // already a known user. A wrong/expired OTP never reveals account state.
        if (EKO) {
          const verified = await ekoVerifyOtp(EKO, body.phone, body.otp);
          if (!verified.ok) {
            req.log.error({ reason: verified.reason }, "eko verify-otp failed");
            return problem(reply, 401, "Unauthorized", "OTP verification failed");
          }
        } else if (body.otp !== OTP_CODE) {
          return problem(reply, 401, "Unauthorized", "OTP verification failed");
        }

        // Self-registration: a brand-new number that just proved ownership
        // becomes a DC automatically — the mobile app never mints any other
        // role. Circle Head / Corporate Admin accounts stay web-provisioned.
        let user = await repos.findUserByPhone(body.phone);
        if (!user) {
          const name = typeof body.name === "string" ? body.name.trim().slice(0, 120) : "";
          if (name.length < 2) {
            return problem(reply, 422, "Name Required", "New number — provide a name to register as a DC");
          }
          user = {
            id: randomUUID(),
            tenant_id: SELF_REGISTER_TENANT_ID,
            name,
            phone: body.phone,
            role: "DC",
            status: "ACTIVE",
          };
          await repos.insertUser(user);
        } else if (user.status !== "ACTIVE") {
          return problem(reply, 401, "Unauthorized", "Account inactive");
        }

        // Register device; M0 auto-binds (C2: PENDING→BOUND is admin policy).
        // One BOUND device per DC: prior BOUND devices become REPLACED.
        await repos.markUserDevicesReplaced(user.tenant_id, user.id);
        const device = {
          id: randomUUID(),
          tenant_id: user.tenant_id,
          user_id: user.id,
          hardware: body.device.hardware,
          // public_key recorded in M0, signature enforcement later (C3 §2)
          public_key: typeof body.device.public_key === "string" ? body.device.public_key : undefined,
          binding_state: "BOUND" as const,
        };
        await repos.insertDevice(device);

        const principal: Principal = {
          user_id: user.id,
          tenant_id: user.tenant_id,
          role: user.role,
          device_id: device.id,
        };
        const now = clock();
        const access_token = await signAccessToken(tokens, principal, now);
        const refresh_token = newRefreshToken();
        await repos.insertRefreshToken({
          token: refresh_token,
          tenant_id: user.tenant_id,
          user_id: user.id,
          device_id: device.id,
          expires_at: new Date(now.getTime() + 30 * 24 * 3600 * 1000).toISOString(),
        });

        return reply.code(200).send({ access_token, refresh_token, device_id: device.id, user });
      });

      // ---- auth (C2 /auth/token/refresh, v0.9.0) ---------------------------
      // Silent re-auth for the ~1h access token. The refresh token is
      // single-use (rotated on every call); a REVOKED device or non-ACTIVE
      // user cannot refresh (HG3).
      api.post("/auth/token/refresh", { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } }, async (req, reply) => {
        const body = (req.body ?? {}) as { refresh_token?: unknown };
        if (typeof body.refresh_token !== "string" || body.refresh_token.length === 0) {
          return problem(reply, 400, "Bad Request", "refresh_token is required");
        }
        const existing = await repos.getRefreshToken(body.refresh_token);
        if (!existing || new Date(existing.expires_at).getTime() < clock().getTime()) {
          return problem(reply, 401, "Unauthorized", "Refresh token invalid or expired");
        }
        const [device, user] = await Promise.all([
          repos.getDeviceById(existing.tenant_id, existing.device_id),
          repos.getUserById(existing.tenant_id, existing.user_id),
        ]);
        if (!device || device.binding_state === "REVOKED") {
          return problem(reply, 401, "Unauthorized", "Device binding revoked (HG3)");
        }
        if (!user || user.status !== "ACTIVE") {
          return problem(reply, 401, "Unauthorized", "User is not active");
        }
        const principal: Principal = {
          user_id: user.id,
          tenant_id: user.tenant_id,
          role: user.role,
          device_id: device.id,
        };
        const now = clock();
        const access_token = await signAccessToken(tokens, principal, now);
        await repos.deleteRefreshToken(existing.token); // single-use rotation
        const refresh_token = newRefreshToken();
        await repos.insertRefreshToken({
          token: refresh_token,
          tenant_id: user.tenant_id,
          user_id: user.id,
          device_id: device.id,
          expires_at: new Date(now.getTime() + 30 * 24 * 3600 * 1000).toISOString(),
        });
        return reply.code(200).send({ access_token, refresh_token });
      });

      // ---- master-data (C2 /master-data/locations, C3 §6 delta pull) --------
      api.get("/master-data/locations", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        const q = req.query as { updated_since?: string; limit?: string };
        const limit = Math.min(Math.max(Number.parseInt(q.limit ?? "500", 10) || 500, 1), 1000);
        const updatedSince = q.updated_since ?? null;
        if (updatedSince !== null && Number.isNaN(new Date(updatedSince).getTime())) {
          return problem(reply, 400, "Bad Request", "updated_since must be a date-time");
        }
        const scope = await resolveScope(repos, principal, clock());
        const page = await repos.listLocationsUpdatedSince(scope, updatedSince, limit);
        return reply.code(200).send({ items: page.items, next_cursor: page.next_cursor });
      });

      // ---- master-data (C2 /master-data/csp-assignments, design 0001 §5) -----
      api.get("/master-data/csp-assignments", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        const q = req.query as { as_of?: string };
        const asOf = q.as_of && DATE_PATTERN.test(q.as_of) ? q.as_of : istDateOf(clock());
        // DC → own; Circle Head → circle's; tenant-root → all (scope choke point).
        const scope = await resolveScope(repos, principal, clock());
        const items = await repos.listActiveCspAssignments(scope.tenant_id, scope.dc_user_ids, asOf);
        return reply.code(200).send({ items });
      });

      // ---- master-data (C2 /master-data/beat-plans) --------------------------
      api.get("/master-data/beat-plans", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        const q = req.query as { from_date?: string };
        if (!q.from_date || !DATE_PATTERN.test(q.from_date)) {
          return problem(reply, 400, "Bad Request", "from_date (YYYY-MM-DD) is required");
        }
        // C6: DC reads own plans; AM reads assigned DCs'; CORPORATE_ADMIN tenant-root.
        const scope = await resolveScope(repos, principal, clock());
        if (scope.dc_user_ids !== "ALL" && scope.dc_user_ids.size === 0) {
          return problem(reply, 403, "Forbidden", "No beat-plan scope for this principal");
        }
        const items = await repos.listBeatPlansFromDate(scope, q.from_date);
        return reply.code(200).send({ items });
      });

      // ---- sync (C2 /sync/batches, C3 §2–4) ----------------------------------
      api.post("/sync/batches", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        // C6 + spec §4: DCs submit evidence; Circle Heads submit their OWN
        // attendance (the engine restricts CH ops to attendance.* types).
        if (principal.role !== "DC" && principal.role !== "CIRCLE_HEAD") {
          return problem(reply, 403, "Forbidden", "Only DC and Circle Head devices submit evidence batches");
        }
        const batch = req.body as Partial<SyncBatch> | null;
        if (
          !batch ||
          typeof batch.batch_id !== "string" ||
          typeof batch.device_id !== "string" ||
          !Array.isArray(batch.ops) ||
          batch.ops.length < 1
        ) {
          return problem(reply, 400, "Bad Request", "batch_id, device_id and non-empty ops[] are required (C3 §2)");
        }
        const response = await applySyncBatch(repos, principal, batch as SyncBatch, clock);
        return reply.code(200).send(response);
      });

      // ---- circle workbench (C2 /circle/csp-assignments/transfer, design 0001 §6)
      api.post("/circle/csp-assignments/transfer", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        // C6: csp-assignment create/transfer is CIRCLE_HEAD (CORPORATE_ADMIN via "*").
        if (principal.role !== "CIRCLE_HEAD" && principal.role !== "CORPORATE_ADMIN") {
          return problem(reply, 403, "Forbidden", "Only a Circle Head (or admin) may assign/transfer CSPs");
        }
        const body = (req.body ?? {}) as { csp_location_id?: unknown; to_dc_user_id?: unknown; reason?: unknown };
        const reasons = ["INITIAL_ALLOCATION", "TRANSFER", "REBALANCE", "COVERAGE_GAP"];
        if (
          typeof body.csp_location_id !== "string" ||
          typeof body.to_dc_user_id !== "string" ||
          typeof body.reason !== "string" ||
          !reasons.includes(body.reason)
        ) {
          return problem(reply, 400, "Bad Request", "csp_location_id, to_dc_user_id and a valid reason are required");
        }

        const today = istDateOf(clock());
        const scope = await resolveScope(repos, principal, clock());
        // Target DC must be inside the head's circle (design 0001 §6 guardrail:
        // transfers can't orphan a CSP outside the circle).
        if (scope.dc_user_ids !== "ALL" && !scope.dc_user_ids.has(body.to_dc_user_id)) {
          return problem(reply, 422, "Unprocessable", "Target DC is not in your circle");
        }
        const [csp, circleId] = await Promise.all([
          repos.getLocationById(principal.tenant_id, body.csp_location_id),
          repos.getActiveDcCircleId(principal.tenant_id, body.to_dc_user_id, today),
        ]);
        if (!csp || csp.type !== "CSP") {
          return problem(reply, 422, "Unprocessable", "csp_location_id must reference an existing CSP");
        }
        if (!circleId) {
          return problem(reply, 422, "Unprocessable", "Target DC has no active circle membership");
        }

        // End-old + start-new; history is never edited (design 0001 §3). The
        // old assignment ends as of YESTERDAY (IST) so the transfer takes
        // effect immediately — valid_to is inclusive (C2 spec).
        const yesterday = istDateOf(new Date(clock().getTime() - 24 * 3600 * 1000));
        const ended_assignment_id = await repos.endActiveCspAssignment(principal.tenant_id, csp.id, yesterday);
        const assignment = {
          id: randomUUID(),
          tenant_id: principal.tenant_id,
          circle_id: circleId,
          csp_location_id: csp.id,
          dc_user_id: body.to_dc_user_id,
          assigned_by_user_id: principal.user_id,
          reason: body.reason as "INITIAL_ALLOCATION" | "TRANSFER" | "REBALANCE" | "COVERAGE_GAP",
          valid_from: today,
          valid_to: null,
          updated_at: clock().toISOString(),
        };
        await repos.insertCspAssignment(assignment);
        return reply.code(200).send({ assignment, ended_assignment_id });
      });

      // ---- dashboard (C2 /dashboard/attendance, design 0001 §7) --------------
      api.get("/dashboard/attendance", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        const q = req.query as { date?: string };
        if (!q.date || !DATE_PATTERN.test(q.date)) {
          return problem(reply, 400, "Bad Request", "date (YYYY-MM-DD, IST calendar date) is required");
        }
        // C6: HR_ADMIN is tenant-root for ATTENDANCE ONLY (its visit/location
        // scope stays empty — PII minimisation).
        const scope = await resolveScope(repos, principal, clock());
        const dcScope = principal.role === "HR_ADMIN" ? ("ALL" as const) : scope.dc_user_ids;
        const people = await repos.listDcUsers(scope.tenant_id, dcScope);
        // Spec §4: a Circle Head has the same attendance flow for their own
        // workday — surface their own row on their board.
        if (principal.role === "CIRCLE_HEAD") {
          const self = await repos.getUserById(scope.tenant_id, principal.user_id);
          if (self) people.unshift(self);
        }
        // Two batched reads for the whole board — not 2×N (industrialised for
        // the 1000-DC national roster).
        const personIds = people.map((p) => p.id);
        const [days, pointsByDc] = await Promise.all([
          repos.getAttendanceDaysForDcDates(scope.tenant_id, personIds, [q.date]),
          repos.listTrackPointsForDcsDate(scope.tenant_id, personIds, q.date),
        ]);
        const items = people.map((person) => {
          const d = deriveAttendance(days.get(`${person.id}|${q.date!}`) ?? null, q.date!, clock());
          return {
            dc_user_id: person.id,
            dc_name: person.name,
            status: d.status,
            started_at: d.started_at,
            ended_at: d.ended_at,
            auto_closed: d.auto_closed,
            hours_worked: d.hours_worked,
            km_today: kmForPoints(pointsByDc.get(person.id) ?? []), // track_straightline_v0 — PROVISIONAL (C7)
          };
        });
        return reply.code(200).send({ items });
      });

      // ---- circle workbench (C2 /circle/csp-assignments/import) --------------
      // Bulk Excel/CSV upload: the CLIENT parses the sheet; this endpoint takes
      // rows of { csp_code, dc_phone } and applies each through the same
      // audited end-old + start-new path as single transfers. Staged-import
      // doctrine (design 0001 §8): nothing is silently dropped — every row
      // comes back accepted or rejected-with-reason.
      api.post("/circle/csp-assignments/import", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        if (principal.role !== "CIRCLE_HEAD" && principal.role !== "CORPORATE_ADMIN") {
          return problem(reply, 403, "Forbidden", "Only a Circle Head (or admin) may bulk-assign CSPs");
        }
        const body = (req.body ?? {}) as { rows?: unknown };
        if (!Array.isArray(body.rows) || body.rows.length === 0 || body.rows.length > 500) {
          return problem(reply, 400, "Bad Request", "rows[] (1–500 of {csp_code, dc_phone}) is required");
        }

        const today = istDateOf(clock());
        const yesterday = istDateOf(new Date(clock().getTime() - 24 * 3600 * 1000));
        const scope = await resolveScope(repos, principal, clock());
        const activeByCsp = new Map(
          (await repos.listActiveCspAssignments(principal.tenant_id, "ALL", today)).map((a) => [a.csp_location_id, a]),
        );

        type RowResult = {
          row: number;
          csp_code: string;
          dc_phone: string;
          result: "assigned" | "transferred" | "unchanged" | "rejected";
          reason?: string;
          dc_name?: string;
        };
        const results: RowResult[] = [];

        for (const [i, raw] of body.rows.entries()) {
          const r = (raw ?? {}) as { csp_code?: unknown; dc_phone?: unknown };
          const cspCode = String(r.csp_code ?? "").trim();
          const dcPhone = String(r.dc_phone ?? "").trim();
          const reject = (reason: string): void => {
            results.push({ row: i + 1, csp_code: cspCode, dc_phone: dcPhone, result: "rejected", reason });
          };

          if (!cspCode || !dcPhone) {
            reject("csp_code and dc_phone are required");
            continue;
          }
          const csp = await repos.findLocationByCode(principal.tenant_id, cspCode);
          if (!csp || csp.type !== "CSP") {
            reject(`unknown CSP code "${cspCode}"`);
            continue;
          }
          const dc = await repos.findUserByPhone(dcPhone);
          if (!dc || dc.tenant_id !== principal.tenant_id || dc.role !== "DC" || dc.status !== "ACTIVE") {
            reject(`no active DC with phone ${dcPhone}`);
            continue;
          }
          if (scope.dc_user_ids !== "ALL" && !scope.dc_user_ids.has(dc.id)) {
            reject(`${dc.name} is not in your circle`);
            continue;
          }
          const circleId = await repos.getActiveDcCircleId(principal.tenant_id, dc.id, today);
          if (!circleId) {
            reject(`${dc.name} has no active circle membership`);
            continue;
          }

          const current = activeByCsp.get(csp.id);
          if (current?.dc_user_id === dc.id) {
            results.push({ row: i + 1, csp_code: cspCode, dc_phone: dcPhone, result: "unchanged", dc_name: dc.name });
            continue;
          }
          if (current) await repos.endActiveCspAssignment(principal.tenant_id, csp.id, yesterday);
          const assignment = {
            id: randomUUID(),
            tenant_id: principal.tenant_id,
            circle_id: circleId,
            csp_location_id: csp.id,
            dc_user_id: dc.id,
            assigned_by_user_id: principal.user_id,
            reason: (current ? "TRANSFER" : "INITIAL_ALLOCATION") as "TRANSFER" | "INITIAL_ALLOCATION",
            valid_from: today,
            valid_to: null,
            updated_at: clock().toISOString(),
          };
          await repos.insertCspAssignment(assignment);
          activeByCsp.set(csp.id, assignment); // later rows in the same sheet see this change
          results.push({
            row: i + 1,
            csp_code: cspCode,
            dc_phone: dcPhone,
            result: current ? "transferred" : "assigned",
            dc_name: dc.name,
          });
        }

        const summary = {
          total: results.length,
          assigned: results.filter((x) => x.result === "assigned").length,
          transferred: results.filter((x) => x.result === "transferred").length,
          unchanged: results.filter((x) => x.result === "unchanged").length,
          rejected: results.filter((x) => x.result === "rejected").length,
        };
        return reply.code(200).send({ summary, results });
      });

      // ---- circle workbench (C2 /circle/csp-details/import, spec §4 P1) ------
      // Bulk-update CSP master details (address / §3.1 profile fields) for CSPs
      // in the caller's own circle. `dry_run` returns the per-row diff without
      // writing — the web upload screen previews that before committing.
      api.post("/circle/csp-details/import", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        if (principal.role !== "CIRCLE_HEAD" && principal.role !== "CORPORATE_ADMIN") {
          return problem(reply, 403, "Forbidden", "Only a Circle Head (or admin) may bulk-update CSP details");
        }
        const body = (req.body ?? {}) as { rows?: unknown; dry_run?: unknown };
        if (!Array.isArray(body.rows) || body.rows.length === 0 || body.rows.length > 500) {
          return problem(reply, 400, "Bad Request", "rows[] (1–500) is required");
        }
        const dryRun = body.dry_run === true;
        const today = istDateOf(clock());
        const scope = await resolveScope(repos, principal, clock());
        const activeByCsp = new Map(
          (await repos.listActiveCspAssignments(principal.tenant_id, "ALL", today)).map((a) => [a.csp_location_id, a]),
        );

        type DetailRow = {
          row: number;
          csp_code: string;
          result: "updated" | "unchanged" | "rejected";
          reason?: string;
          changes?: CspFieldDiff;
        };
        const results: DetailRow[] = [];

        for (const [i, raw] of body.rows.entries()) {
          const r = (raw ?? {}) as Record<string, unknown>;
          const cspCode = String(r.csp_code ?? "").trim();
          const reject = (reason: string): void => {
            results.push({ row: i + 1, csp_code: cspCode, result: "rejected", reason });
          };
          if (!cspCode) { reject("csp_code is required"); continue; }
          const csp = await repos.findLocationByCode(principal.tenant_id, cspCode);
          if (!csp || csp.type !== "CSP") { reject(`unknown CSP code "${cspCode}"`); continue; }

          // Circle guardrail: the CSP's active-assignment DC must be in the head's circle.
          const assignment = activeByCsp.get(csp.id);
          if (scope.dc_user_ids !== "ALL") {
            if (!assignment || !scope.dc_user_ids.has(assignment.dc_user_id)) {
              reject("CSP is not in your circle");
              continue;
            }
          }

          const { csp_code: _drop, ...fields } = r;
          void _drop;
          const changes = diffCspFields(csp, fields);
          if (Object.keys(changes).length === 0) {
            results.push({ row: i + 1, csp_code: cspCode, result: "unchanged" });
            continue;
          }
          if (!dryRun) {
            await repos.updateLocationFields(principal.tenant_id, csp.id, patchFromDiff(changes, clock().toISOString()));
          }
          results.push({ row: i + 1, csp_code: cspCode, result: "updated", changes });
        }

        const summary = {
          total: results.length,
          updated: results.filter((x) => x.result === "updated").length,
          unchanged: results.filter((x) => x.result === "unchanged").length,
          rejected: results.filter((x) => x.result === "rejected").length,
          dry_run: dryRun,
        };
        return reply.code(200).send({ summary, results });
      });

      // ---- circle workbench (C2 /circle/home-locations/import, spec §3) ------
      // "Excel sheet for Lat Long": bulk-set DC / Circle-Head reference home
      // locations. Attendance is LOGGED against home, never gated (ADR-0004).
      api.post("/circle/home-locations/import", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        if (principal.role !== "CIRCLE_HEAD" && principal.role !== "CORPORATE_ADMIN") {
          return problem(reply, 403, "Forbidden", "Only a Circle Head (or admin) may set home locations");
        }
        const body = (req.body ?? {}) as { rows?: unknown };
        if (!Array.isArray(body.rows) || body.rows.length === 0 || body.rows.length > 500) {
          return problem(reply, 400, "Bad Request", "rows[] (1–500 of {phone, home_lat, home_lng}) is required");
        }
        const scope = await resolveScope(repos, principal, clock());

        type HomeRow = { row: number; phone: string; result: "updated" | "unchanged" | "rejected"; reason?: string };
        const results: HomeRow[] = [];

        for (const [i, raw] of body.rows.entries()) {
          const r = (raw ?? {}) as { phone?: unknown; home_lat?: unknown; home_lng?: unknown };
          const phone = String(r.phone ?? "").trim();
          const lat = Number(r.home_lat);
          const lng = Number(r.home_lng);
          const reject = (reason: string): void => {
            results.push({ row: i + 1, phone, result: "rejected", reason });
          };
          if (!PHONE_PATTERN.test(phone)) { reject("phone must be 10 digits"); continue; }
          if (!Number.isFinite(lat) || lat < -90 || lat > 90 || !Number.isFinite(lng) || lng < -180 || lng > 180) {
            reject("home_lat / home_lng out of range");
            continue;
          }
          const user = await repos.findUserByPhone(phone);
          if (!user || user.tenant_id !== principal.tenant_id || user.status !== "ACTIVE" ||
              (user.role !== "DC" && user.role !== "CIRCLE_HEAD")) {
            reject(`no active DC/Circle-Head with phone ${phone}`);
            continue;
          }
          // Scope: a Circle Head may set their own home + their circle DCs' homes.
          const inScope = scope.dc_user_ids === "ALL" || user.id === principal.user_id || scope.dc_user_ids.has(user.id);
          if (!inScope) { reject(`${user.name} is not in your circle`); continue; }

          if (user.home_lat === lat && user.home_lng === lng) {
            results.push({ row: i + 1, phone, result: "unchanged" });
            continue;
          }
          await repos.updateUserHomeLocation(principal.tenant_id, user.id, lat, lng);
          results.push({ row: i + 1, phone, result: "updated" });
        }

        const summary = {
          total: results.length,
          updated: results.filter((x) => x.result === "updated").length,
          unchanged: results.filter((x) => x.result === "unchanged").length,
          rejected: results.filter((x) => x.result === "rejected").length,
        };
        return reply.code(200).send({ summary, results });
      });

      // ---- DC CSP Details (C2 /dc/csp-details, spec §3) ----------------------
      api.get("/dc/csp-details", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        if (principal.role !== "DC") {
          return problem(reply, 403, "Forbidden", "CSP Details is the DC's own assigned list");
        }
        const today = istDateOf(clock());
        const [assignments, lastVisits] = await Promise.all([
          repos.listActiveCspAssignments(principal.tenant_id, new Set([principal.user_id]), today),
          repos.lastVisitDatesForDc(principal.tenant_id, principal.user_id),
        ]);

        // Load the assigned CSPs plus their ancestor chain (CSP→Branch→RBO→LHO)
        // in a bounded number of batched reads — never one query per row/level.
        const byId = new Map<string, LocationNode>();
        let frontier = assignments.map((a) => a.csp_location_id);
        for (let depth = 0; depth < 4 && frontier.length > 0; depth++) {
          const fetched = await repos.listLocationsByIds(principal.tenant_id, frontier);
          const next: string[] = [];
          for (const loc of fetched) {
            byId.set(loc.id, loc);
            if (loc.parent_id && !byId.has(loc.parent_id)) next.push(loc.parent_id);
          }
          frontier = next;
        }
        // §3.1 template fields Branch/RBO/LHO come from the hierarchy unless
        // explicitly overridden in the CSP's own csp_profile.
        const hierarchyProfile = (loc: LocationNode): Record<string, string> => {
          const out: Record<string, string> = {};
          let cur = loc.parent_id ? byId.get(loc.parent_id) : undefined;
          const seen = new Set<string>();
          while (cur && !seen.has(cur.id)) {
            seen.add(cur.id);
            if (cur.type === "BRANCH") { out.branch_code = cur.code; out.branch_name = cur.name; }
            else if (cur.type === "RBO") { out.rbo_name = cur.name; }
            else if (cur.type === "LHO") { out.lho_name = cur.name; }
            cur = cur.parent_id ? byId.get(cur.parent_id) : undefined;
          }
          return out;
        };

        const items = assignments
          .map((a) => byId.get(a.csp_location_id))
          .filter((loc): loc is LocationNode => loc != null)
          .map((loc) => ({
            csp_location_id: loc.id,
            code: loc.code,
            name: loc.name,
            address: loc.address ?? "",
            lat: loc.coordinates.lat,
            lng: loc.coordinates.lng,
            coordinate_confidence: loc.coordinate_confidence,
            last_visit_date: lastVisits.get(loc.id) ?? null,
            csp_profile: { ...hierarchyProfile(loc), ...(loc.csp_profile ?? {}) },
          }));
        return reply.code(200).send({ items });
      });

      // ---- DC change requests (C2 /dc/csp-change-requests, spec §3) ----------
      api.post("/dc/csp-change-requests", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        if (principal.role !== "DC") {
          return problem(reply, 403, "Forbidden", "Only DCs propose CSP edits");
        }
        const body = (req.body ?? {}) as { csp_location_id?: unknown; changes?: unknown };
        if (typeof body.csp_location_id !== "string" || typeof body.changes !== "object" || body.changes === null) {
          return problem(reply, 400, "Bad Request", "csp_location_id and changes{} are required");
        }
        const today = istDateOf(clock());
        const assigned = await repos.listActiveCspAssignments(principal.tenant_id, new Set([principal.user_id]), today);
        if (!assigned.some((a) => a.csp_location_id === body.csp_location_id)) {
          return problem(reply, 422, "Unprocessable", "You may only propose edits to CSPs assigned to you");
        }
        const loc = await repos.getLocationById(principal.tenant_id, body.csp_location_id);
        if (!loc) return problem(reply, 422, "Unprocessable", "Unknown CSP");

        const changes = diffCspFields(loc, body.changes as Record<string, unknown>);
        if (Object.keys(changes).length === 0) {
          return problem(reply, 422, "Unprocessable", "No whitelisted field actually changes value");
        }
        const request = {
          id: randomUUID(),
          tenant_id: principal.tenant_id,
          csp_location_id: loc.id,
          requested_by_user_id: principal.user_id,
          changes,
          status: "PENDING" as const,
          created_at: clock().toISOString(),
        };
        await repos.insertCspChangeRequest(request);
        return reply.code(200).send({ request });
      });

      // ---- Circle Head approval queue (C2 /circle/csp-change-requests) -------
      api.get("/circle/csp-change-requests", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        if (principal.role !== "CIRCLE_HEAD" && principal.role !== "CORPORATE_ADMIN") {
          return problem(reply, 403, "Forbidden", "Approval queue is for Circle Heads and Admin");
        }
        const q = req.query as { status?: string };
        const status =
          q.status === "PENDING" || q.status === "APPROVED" || q.status === "REJECTED" ? q.status : undefined;
        const scope = await resolveScope(repos, principal, clock());
        const requests = await repos.listCspChangeRequests(scope.tenant_id, scope.dc_user_ids, status);
        const [locs, requesters] = await Promise.all([
          repos.listLocationsByIds(scope.tenant_id, requests.map((r) => r.csp_location_id)),
          repos.listUsersByIds(scope.tenant_id, requests.map((r) => r.requested_by_user_id)),
        ]);
        const locById = new Map(locs.map((l) => [l.id, l]));
        const userById = new Map(requesters.map((u) => [u.id, u]));
        const items = requests.map((r) => {
          const loc = locById.get(r.csp_location_id);
          return {
            ...r,
            csp_code: loc?.code ?? "",
            csp_name: loc?.name ?? "",
            requested_by_name: userById.get(r.requested_by_user_id)?.name ?? "",
          };
        });
        return reply.code(200).send({ items });
      });

      // ---- decide (C2 /circle/csp-change-requests/decide) --------------------
      api.post("/circle/csp-change-requests/decide", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        if (principal.role !== "CIRCLE_HEAD" && principal.role !== "CORPORATE_ADMIN") {
          return problem(reply, 403, "Forbidden", "Only Circle Heads and Admin decide change requests");
        }
        const body = (req.body ?? {}) as { id?: unknown; decision?: unknown; rejection_reason?: unknown };
        if (typeof body.id !== "string" || (body.decision !== "APPROVED" && body.decision !== "REJECTED")) {
          return problem(reply, 400, "Bad Request", "id and decision (APPROVED|REJECTED) are required");
        }
        const request = await repos.getCspChangeRequest(principal.tenant_id, body.id);
        if (!request || request.status !== "PENDING") {
          return problem(reply, 422, "Unprocessable", "No pending request with that id");
        }
        // Scope guardrail: the requester must be one of the head's circle DCs.
        const scope = await resolveScope(repos, principal, clock());
        if (scope.dc_user_ids !== "ALL" && !scope.dc_user_ids.has(request.requested_by_user_id)) {
          return problem(reply, 422, "Unprocessable", "Request belongs to another circle");
        }

        if (body.decision === "APPROVED") {
          await repos.updateLocationFields(
            principal.tenant_id,
            request.csp_location_id,
            patchFromDiff(request.changes, clock().toISOString()),
          );
        }
        const decided = {
          ...request,
          status: body.decision as "APPROVED" | "REJECTED",
          rejection_reason:
            body.decision === "REJECTED" && typeof body.rejection_reason === "string" && body.rejection_reason.length > 0
              ? body.rejection_reason
              : undefined,
          decided_by_user_id: principal.user_id,
          decided_at: clock().toISOString(),
        };
        await repos.decideCspChangeRequest(principal.tenant_id, decided);
        return reply.code(200).send({ request: decided });
      });

      // ---- dashboard (C2 /dashboard/overview — the admin cockpit) ------------
      api.get("/dashboard/overview", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        // C6: tenant-root cockpit — CORPORATE_ADMIN and NATIONAL_HEAD only.
        if (principal.role !== "CORPORATE_ADMIN" && principal.role !== "NATIONAL_HEAD") {
          return problem(reply, 403, "Forbidden", "Overview is a tenant-root view");
        }
        const q = req.query as { date?: string };
        if (!q.date || !DATE_PATTERN.test(q.date)) {
          return problem(reply, 400, "Bad Request", "date (YYYY-MM-DD, IST calendar date) is required");
        }
        const scope = await resolveScope(repos, principal, clock());

        const [dcs, visits, banks, circles, memberships, assignments, locations] = await Promise.all([
          repos.listDcUsers(scope.tenant_id, "ALL"),
          repos.listVisitsByIstDate(scope, q.date),
          repos.listBanks(scope.tenant_id),
          repos.listCircles(scope.tenant_id),
          repos.listActiveCircleMemberships(scope.tenant_id, q.date),
          repos.listActiveCspAssignments(scope.tenant_id, "ALL", q.date),
          repos.listAllLocations(scope.tenant_id),
        ]);

        const dcIds = dcs.map((d) => d.id);
        // One batched read for the whole tenant's attendance + km — not 2×N.
        const [attendanceDays, trackByDc] = await Promise.all([
          repos.getAttendanceDaysForDcDates(scope.tenant_id, dcIds, [q.date]),
          repos.listTrackPointsForDcsDate(scope.tenant_id, dcIds, q.date),
        ]);
        const statusOf = (dcId: string): "NOT_STARTED" | "ON_DUTY" | "ENDED" => {
          const day = attendanceDays.get(`${dcId}|${q.date!}`);
          return day?.ended_at ? "ENDED" : day?.started_at ? "ON_DUTY" : "NOT_STARTED";
        };

        const csps = locations.filter((l) => l.type === "CSP");
        const assignedCspIds = new Set(assignments.map((a) => a.csp_location_id));
        const dcById = new Map(dcs.map((d) => [d.id, d]));
        const circleOfDc = new Map(
          memberships.filter((m) => m.role_in_circle === "DC").map((m) => [m.user_id, m.circle_id]),
        );
        const headOfCircle = new Map(
          memberships.filter((m) => m.role_in_circle === "CIRCLE_HEAD").map((m) => [m.circle_id, m.user_id]),
        );
        const usersById = dcById; // heads resolved separately below

        const headsById = new Map(
          (await repos.listUsersByIds(scope.tenant_id, [...headOfCircle.values()])).map((u) => [u.id, u]),
        );
        const circleRollups = circles.map((c) => {
            const circleDcs = [...circleOfDc.entries()].filter(([, cid]) => cid === c.id).map(([dcId]) => dcId);
            const headId = headOfCircle.get(c.id) ?? null;
            const head = headId ? headsById.get(headId) ?? null : null;
            return {
              circle_id: c.id,
              circle_name: c.name,
              circle_head: head?.name ?? null,
              dc_count: circleDcs.length,
              on_duty: circleDcs.filter((id) => statusOf(id) === "ON_DUTY").length,
              csp_count: assignments.filter((a) => a.circle_id === c.id).length,
              visits_today: visits.filter((v) => circleDcs.includes(v.dc_user_id)).length,
              flagged_today: visits.filter(
                (v) => circleDcs.includes(v.dc_user_id) && v.geofence_result === "OUTSIDE_FLAGGED",
              ).length,
            };
          });

        return reply.code(200).send({
          date: q.date,
          attendance: {
            total_dcs: dcs.length,
            on_duty: dcs.filter((d) => statusOf(d.id) === "ON_DUTY").length,
            ended: dcs.filter((d) => statusOf(d.id) === "ENDED").length,
            not_started: dcs.filter((d) => statusOf(d.id) === "NOT_STARTED").length,
          },
          visits: {
            total: visits.length,
            geo_verified: visits.filter((v) => v.geofence_result === "INSIDE").length,
            flagged: visits.filter((v) => v.geofence_result === "OUTSIDE_FLAGGED").length,
            late_sync: visits.filter((v) => v.sync_state === "LATE_SYNC").length,
            unplanned: visits.filter((v) => !v.planned).length,
          },
          csps: {
            total: csps.length,
            assigned: csps.filter((c) => assignedCspIds.has(c.id)).length,
            unassigned: csps.filter((c) => !assignedCspIds.has(c.id)).length,
            coordinates_unverified: csps.filter((c) => c.coordinate_confidence === "UNVERIFIED").length,
          },
          circles: circleRollups,
          banks: banks.map((b) => ({
            name: b.name,
            code: b.code,
            status: b.status,
            csp_count: csps.filter((c) => c.bank_id === b.id).length,
          })),
          assignments_by_dc: [...usersById.values()].map((dc) => ({
            dc_user_id: dc.id,
            dc_name: dc.name,
            csp_count: assignments.filter((a) => a.dc_user_id === dc.id).length,
            attendance: statusOf(dc.id),
            visits_today: visits.filter((v) => v.dc_user_id === dc.id).length,
            km_today: kmForPoints(trackByDc.get(dc.id) ?? []),
          })),
        });
      });

      // ---- dashboard (C2 /dashboard/scorecard, design 0002 dc_score_v1) ------
      api.get("/dashboard/scorecard", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        const q = req.query as { date?: string };
        if (!q.date || !DATE_PATTERN.test(q.date)) {
          return problem(reply, 400, "Bad Request", "date (YYYY-MM-DD, IST calendar date) is required");
        }
        const scope = await resolveScope(repos, principal, clock());
        const [dcs, visits] = await Promise.all([
          repos.listDcUsers(scope.tenant_id, scope.dc_user_ids),
          repos.listVisitsByIstDate(scope, q.date),
        ]);

        const minusDays = (istDate: string, days: number): string =>
          new Date(new Date(`${istDate}T00:00:00Z`).getTime() - days * 86_400_000).toISOString().slice(0, 10);

        // The 7-day streak window for the whole circle/tenant in ONE batched
        // read — was 7×N getAttendanceDay round-trips.
        const week = Array.from({ length: 7 }, (_, i) => minusDays(q.date!, i));
        const attendance = await repos.getAttendanceDaysForDcDates(
          scope.tenant_id,
          dcs.map((d) => d.id),
          week,
        );
        const items = dcs.map((dc) => {
          const dayFor = (date: string) => attendance.get(`${dc.id}|${date}`) ?? null;
          const daysWithStart = week.map((date) => dayFor(date)?.started_at != null);
          return computeScorecard({ id: dc.id, name: dc.name }, visits, dayFor(q.date!), daysWithStart);
        });
        return reply.code(200).send({ formula_version: "dc_score_v1", items });
      });

      // ---- dashboard (C2 /dashboard/visits) ----------------------------------
      api.get("/dashboard/visits", { preHandler: requireAuth }, async (req: AuthedRequest, reply) => {
        const principal = req.principal!;
        const q = req.query as { date?: string };
        if (!q.date || !DATE_PATTERN.test(q.date)) {
          return problem(reply, 400, "Bad Request", "date (YYYY-MM-DD, IST calendar date) is required");
        }
        // Scoping is server-side (C2) and applied in the repository query via
        // the single choke point — the handler does no filtering of its own.
        const scope = await resolveScope(repos, principal, clock());
        const visits = await repos.listVisitsByIstDate(scope, q.date);
        // photo_count and checked_out_at are derived at READ time (like
        // km_today) — visit.photo / visit.checkout ops arrive on their own
        // tiers and link by visit_id, never by order.
        const visitIds = visits.map((v) => v.id);
        const [photoCounts, checkoutTimes] = await Promise.all([
          repos.countPhotosForVisits(scope.tenant_id, visitIds),
          repos.checkoutTimesForVisits(scope.tenant_id, visitIds),
        ]);
        const items = visits.map((v) => {
          const checked_out_at = checkoutTimes.get(v.id) ?? null;
          const duration_minutes = checked_out_at
            ? Math.max(0, Math.round((new Date(checked_out_at).getTime() - new Date(v.checkin.occurred_at).getTime()) / 60000))
            : null;
          return { ...v, photo_count: photoCounts.get(v.id) ?? 0, checked_out_at, duration_minutes };
        });
        return reply.code(200).send({ items });
      });
    },
    { prefix: "/api/v1" },
  );

  return app;
}

export { istDateOf };
