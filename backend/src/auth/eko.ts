/**
 * Eko Mobile/OTP Verification gateway client — ekoicici product
 * (api.eko.in/ekoicici/v3, ICICI-partnered; distinct from the generic
 * ekoapi/staging product the public docs describe, which this account isn't
 * provisioned on). Real SMS OTP for pilot login — replaces the PILOT_OTP dev
 * stub (server.ts) whenever EKO_DEVELOPER_KEY / EKO_ACCESS_KEY /
 * EKO_INITIATOR_ID / EKO_USER_CODE are all set. This is a LIVE, BILLED
 * account — every Send OTP call sends a real SMS at real cost.
 *
 * Auth scheme (per-request, not a bearer token): `developer_key` is a static
 * header; `secret-key` is base64(HMAC-SHA256(key = base64-encoded access_key
 * STRING itself, msg = timestamp)) — the base64 encoding of access_key is
 * the literal HMAC key, not decoded back to bytes. (Confirmed live: decoding
 * it back — the natural-looking reading of Eko's own public snippet — gets a
 * 401 every time; using the base64 string as-is is what the account actually
 * accepts.) Sent alongside `secret-key-timestamp`. The access_key itself
 * never goes over the wire, only its base64 form.
 *
 * `initiator_id` and `user_code` are FIXED account credentials (never a
 * phone number). `source` is a fixed literal. `csp_id`/`mobile` are the
 * caller's own number, set per request.
 */
import { createHmac, randomUUID } from "node:crypto";

export interface EkoConfig {
  baseUrl: string;
  developerKey: string;
  accessKey: string;
  initiatorId: string;
  userCode: string;
}

/** null when Eko isn't configured — callers fall back to the dev/PILOT_OTP stub. */
export function loadEkoConfig(env: NodeJS.ProcessEnv = process.env): EkoConfig | null {
  const developerKey = env.EKO_DEVELOPER_KEY;
  const accessKey = env.EKO_ACCESS_KEY;
  const initiatorId = env.EKO_INITIATOR_ID;
  const userCode = env.EKO_USER_CODE;
  if (!developerKey || !accessKey || !initiatorId || !userCode) return null;
  return {
    baseUrl: env.EKO_BASE_URL ?? "https://api.eko.in/ekoicici/v3",
    developerKey,
    accessKey,
    initiatorId,
    userCode,
  };
}

function authHeaders(accessKey: string): Record<string, string> {
  const encodedKey = Buffer.from(accessKey).toString("base64");
  const timestamp = Date.now();
  // The base64 STRING is the HMAC key as-is — do not decode it back to bytes.
  const hmac = createHmac("sha256", encodedKey);
  hmac.update(String(timestamp));
  return { "secret-key": hmac.digest("base64"), "secret-key-timestamp": String(timestamp) };
}

/** Max 20 chars per the Eko docs; the dashless half of a uuid is 20 exactly. */
function clientRefId(): string {
  return randomUUID().replace(/-/g, "").slice(0, 20);
}

/**
 * Eko correlates the pending OTP to the `client_ref_id` from Send, not just
 * the mobile number — Verify with a fresh, unrelated ref comes back "OTP not
 * found" even with the correct code (confirmed live). Send and Verify are two
 * separate HTTP calls into this backend with nothing else connecting them, so
 * the ref from Send is remembered here, per mobile, for Verify to reuse.
 * In-process only — fine for this single-container deployment; would need a
 * shared store (Postgres/Redis) behind multiple backend instances.
 */
const PENDING_REF_TTL_MS = 10 * 60 * 1000; // generous vs. Eko's own OTP expiry
const pendingRefs = new Map<string, { ref: string; expiresAt: number }>();

function rememberRef(mobile: string, ref: string): void {
  pendingRefs.set(mobile, { ref, expiresAt: Date.now() + PENDING_REF_TTL_MS });
}

function refFor(mobile: string): string {
  const entry = pendingRefs.get(mobile);
  if (entry && entry.expiresAt > Date.now()) return entry.ref;
  return clientRefId(); // no pending Send for this number — fresh ref, Eko will report the real reason
}

type EkoOutcome = { ok: true } | { ok: false; reason: string };

async function call(
  cfg: EkoConfig,
  method: "POST" | "PUT",
  path: string,
  body: Record<string, unknown>,
): Promise<{ status: number; json: Record<string, unknown> } | { networkError: string }> {
  try {
    const res = await fetch(`${cfg.baseUrl}${path}`, {
      method,
      headers: {
        developer_key: cfg.developerKey,
        "content-type": "application/json",
        ...authHeaders(cfg.accessKey),
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(8_000),
    });
    const json = (await res.json().catch(() => ({}))) as Record<string, unknown>;
    return { status: res.status, json };
  } catch (err) {
    return { networkError: err instanceof Error ? err.message : "network error" };
  }
}

function outcomeOf(r: { status: number; json: Record<string, unknown> } | { networkError: string }): EkoOutcome {
  if ("networkError" in r) return { ok: false, reason: r.networkError };
  if (r.json.status === 0) return { ok: true };
  const message = typeof r.json.message === "string" ? r.json.message : `eko http ${r.status}`;
  return { ok: false, reason: message };
}

export async function ekoSendOtp(cfg: EkoConfig, mobile: string): Promise<EkoOutcome> {
  const ref = clientRefId();
  const body = {
    initiator_id: cfg.initiatorId,
    user_code: cfg.userCode,
    source: "API",
    client_ref_id: ref,
    csp_id: mobile,
    mobile,
  };
  const outcome = outcomeOf(await call(cfg, "POST", "/tools/kyc/mobile/otp", body));
  if (outcome.ok) rememberRef(mobile, ref);
  return outcome;
}

export async function ekoVerifyOtp(cfg: EkoConfig, mobile: string, otp: string): Promise<EkoOutcome> {
  const body = {
    initiator_id: cfg.initiatorId,
    user_code: cfg.userCode,
    source: "API",
    client_ref_id: refFor(mobile),
    mobile,
    otp,
  };
  const r = await call(cfg, "PUT", "/tools/kyc/mobile/otp/verify", body);
  const outcome = outcomeOf(r);
  // Belt-and-braces: only trust success if Eko actually handed back a verification token.
  if (outcome.ok && !("networkError" in r) && typeof r.json.data !== "object") {
    return { ok: false, reason: "eko verify-otp: missing data.otp_verification_token" };
  }
  return outcome;
}
