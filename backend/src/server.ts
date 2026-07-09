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

/** Route inventory consumed by contracts:check (paths relative to servers[0].url = /api/v1). */
export const IMPLEMENTED_ROUTES: ReadonlyArray<{ method: string; path: string }> = [
  { method: "post", path: "/auth/otp/request" },
  { method: "post", path: "/auth/otp/verify" },
  { method: "get", path: "/master-data/locations" },
  { method: "get", path: "/master-data/beat-plans" },
  { method: "post", path: "/sync/batches" },
  { method: "get", path: "/dashboard/visits" },
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
