/**
 * HTTP layer — implements exactly the paths in contracts/c2-api/openapi.yaml
 * (v0.1.0, M0 scope). scripts/contracts-check.ts diffs IMPLEMENTED_ROUTES
 * against the spec in both directions.
 */
import { randomUUID } from "node:crypto";
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import type { Principal, SyncBatch } from "./domain/types.js";
import type { Repos } from "./repos/types.js";
import { resolveScope } from "./scope.js";
import { applySyncBatch, type Clock } from "./sync/engine.js";
import { DEV_TOKEN_CONFIG, newRefreshToken, signAccessToken, verifyAccessToken, type TokenConfig } from "./auth/tokens.js";
import { istDateOf } from "./geo.js";
import { computeScorecard } from "./scorecard.js";

/** Route inventory consumed by contracts:check (paths relative to servers[0].url = /api/v1). */
export const IMPLEMENTED_ROUTES: ReadonlyArray<{ method: string; path: string }> = [
  { method: "post", path: "/auth/otp/request" },
  { method: "post", path: "/auth/otp/verify" },
  { method: "get", path: "/master-data/locations" },
  { method: "get", path: "/master-data/csp-assignments" },
  { method: "get", path: "/master-data/beat-plans" },
  { method: "post", path: "/sync/batches" },
  { method: "post", path: "/circle/csp-assignments/transfer" },
  { method: "get", path: "/dashboard/visits" },
  { method: "get", path: "/dashboard/attendance" },
  { method: "get", path: "/dashboard/scorecard" },
];

export const DEV_OTP = "000000"; // C2: M0 stub gateway always sends '000000' in dev
const PHONE_PATTERN = /^[6-9][0-9]{9}$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

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

  const app = Fastify({ logger: process.env.LOG_LEVEL ? { level: process.env.LOG_LEVEL } : false });

  /** Bearer auth + hard gate HG3 (C6): no session from a REVOKED device binding. */
  async function requireAuth(req: AuthedRequest, reply: FastifyReply): Promise<void> {
    const header = req.headers.authorization;
    if (!header?.startsWith("Bearer ")) {
      problem(reply, 401, "Unauthorized", "Missing bearer token");
      return;
    }
    const principal = await verifyAccessToken(tokens, header.slice("Bearer ".length));
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
      api.post("/auth/otp/request", async (req, reply) => {
        const body = (req.body ?? {}) as { phone?: unknown };
        if (typeof body.phone !== "string" || !PHONE_PATTERN.test(body.phone)) {
          return problem(reply, 400, "Bad Request", "phone must match ^[6-9][0-9]{9}$");
        }
        // M0 dev stub: OTP gateway always "sends" 000000; nothing to persist.
        return reply.code(204).send();
      });

      // ---- auth (C2 /auth/otp/verify) --------------------------------------
      api.post("/auth/otp/verify", async (req, reply) => {
        const body = (req.body ?? {}) as {
          phone?: unknown;
          otp?: unknown;
          device?: {
            hardware?: { manufacturer?: string; model?: string; os_version?: string };
            public_key?: unknown;
          } | null;
        };
        if (typeof body.phone !== "string" || typeof body.otp !== "string" || typeof body.device !== "object" || body.device === null) {
          return problem(reply, 400, "Bad Request", "phone, otp and device are required");
        }
        const user = await repos.findUserByPhone(body.phone);
        if (!user || user.status !== "ACTIVE" || body.otp !== DEV_OTP) {
          return problem(reply, 401, "Unauthorized", "OTP verification failed");
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
        // C6: sync-batch create is a DC capability.
        if (principal.role !== "DC") {
          return problem(reply, 403, "Forbidden", "Only DC devices submit evidence batches");
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
        const dcs = await repos.listDcUsers(scope.tenant_id, dcScope);
        const items = await Promise.all(
          dcs.map(async (dc) => {
            const day = await repos.getAttendanceDay(scope.tenant_id, dc.id, q.date!);
            const status = day?.ended_at ? "ENDED" : day?.started_at ? "ON_DUTY" : "NOT_STARTED";
            return {
              dc_user_id: dc.id,
              dc_name: dc.name,
              status,
              started_at: day?.started_at ?? null,
              ended_at: day?.ended_at ?? null,
            };
          }),
        );
        return reply.code(200).send({ items });
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

        const items = await Promise.all(
          dcs.map(async (dc) => {
            const week = await Promise.all(
              Array.from({ length: 7 }, (_, i) => repos.getAttendanceDay(scope.tenant_id, dc.id, minusDays(q.date!, i))),
            );
            const daysWithStart = week.map((d) => d?.started_at != null);
            return computeScorecard({ id: dc.id, name: dc.name }, visits, week[0] ?? null, daysWithStart);
          }),
        );
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
        const items = await repos.listVisitsByIstDate(scope, q.date);
        return reply.code(200).send({ items });
      });
    },
    { prefix: "/api/v1" },
  );

  return app;
}

export { istDateOf };
