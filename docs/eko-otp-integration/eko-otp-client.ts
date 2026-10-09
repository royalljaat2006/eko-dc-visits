/**
 * Standalone Eko Mobile/OTP Verification client — ekoicici product
 * (api.eko.in/ekoicici/v3). Framework-agnostic: no dependency on any
 * particular HTTP server. Drop this file into any Node/TypeScript backend.
 *
 * Requires Node 18+ (global fetch, node:crypto).
 *
 * ---------------------------------------------------------------------------
 * READ THIS BEFORE INTEGRATING — three non-obvious things that cost real
 * debugging time (and real billed SMS) to work out:
 *
 * 1. AUTH SIGNING: `secret-key` is HMAC-SHA256 of the current timestamp,
 *    keyed by the BASE64-ENCODED ACCESS-KEY STRING ITSELF — do NOT decode
 *    that base64 string back to raw bytes before using it as the HMAC key.
 *    The natural-looking reading of Eko's own public docs snippet (decode
 *    it back) is wrong for this account and returns a bare 401 with no
 *    useful error body. See computeSecretKey() below.
 *
 * 2. `initiator_id` is a FIXED account credential (a Merchant id from your
 *    Eko onboarding), never a phone number, never a value copied from a
 *    docs example. Sending any 10-digit mobile there fails with
 *    "Invalid Sender/Initiator" / "Merchant does not exist in system."
 *    `user_code` is a second, separate fixed credential, also required.
 *
 * 3. `client_ref_id` MUST be the same value across the Send and the
 *    matching Verify call for one login attempt. Eko correlates the
 *    pending OTP by this ref, not just the phone number — a fresh random
 *    ref on Verify (even with the exact right OTP code) fails with
 *    "OTP not found. Please make the OTP request again." Your backend must
 *    remember the ref from Send and reuse it on Verify for that phone.
 *
 * 4. The OTP is SINGLE-USE. If your flow calls Verify more than once for
 *    the same code (e.g. a first Verify succeeds but your own app-side
 *    logic then rejects the request for an unrelated reason — like "we
 *    also need a display name for this new user" — and the client retries
 *    Verify with the same OTP), the second call to Eko will fail with
 *    "OTP not found" even though the code is objectively correct, because
 *    Eko already consumed it on the first call. If your flow can call
 *    Verify twice for one code, cache the fact that this phone+otp pair
 *    already succeeded and skip re-asking Eko on the retry.
 * ---------------------------------------------------------------------------
 */
import { createHmac, randomUUID } from "node:crypto";

export interface EkoConfig {
  baseUrl: string; // e.g. "https://api.eko.in/ekoicici/v3" for ekoicici, or your product's base URL
  developerKey: string;
  accessKey: string;
  initiatorId: string;
  userCode: string;
}

export function loadEkoConfigFromEnv(env: NodeJS.ProcessEnv = process.env): EkoConfig | null {
  const developerKey = env.EKO_DEVELOPER_KEY;
  const accessKey = env.EKO_ACCESS_KEY;
  const initiatorId = env.EKO_INITIATOR_ID;
  const userCode = env.EKO_USER_CODE;
  if (!developerKey || !accessKey || !initiatorId || !userCode) return null;
  return { baseUrl: env.EKO_BASE_URL ?? "https://api.eko.in/ekoicici/v3", developerKey, accessKey, initiatorId, userCode };
}

function authHeaders(accessKey: string): Record<string, string> {
  const encodedKey = Buffer.from(accessKey).toString("base64");
  const timestamp = Date.now();
  // Gotcha #1: sign with the base64 STRING as-is. Do not Buffer.from(encodedKey, "base64").
  const hmac = createHmac("sha256", encodedKey);
  hmac.update(String(timestamp));
  return { "secret-key": hmac.digest("base64"), "secret-key-timestamp": String(timestamp) };
}

/** Max 20 chars per Eko's docs. */
export function newClientRefId(): string {
  return randomUUID().replace(/-/g, "").slice(0, 20);
}

export type EkoOutcome = { ok: true } | { ok: false; reason: string };

async function call(
  cfg: EkoConfig,
  method: "POST" | "PUT",
  path: string,
  body: Record<string, unknown>,
): Promise<{ status: number; json: Record<string, unknown> } | { networkError: string }> {
  try {
    const res = await fetch(`${cfg.baseUrl}${path}`, {
      method,
      headers: { developer_key: cfg.developerKey, "content-type": "application/json", ...authHeaders(cfg.accessKey) },
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

/**
 * Send OTP. Returns the clientRefId used — the CALLER is responsible for
 * remembering it (e.g. keyed by phone number) and passing the SAME value
 * into verifyOtp() for the matching Verify call. See gotcha #3 above.
 */
export async function sendOtp(cfg: EkoConfig, mobile: string): Promise<EkoOutcome & { clientRefId?: string }> {
  const clientRefId = newClientRefId();
  const body = { initiator_id: cfg.initiatorId, user_code: cfg.userCode, source: "API", client_ref_id: clientRefId, csp_id: mobile, mobile };
  const outcome = outcomeOf(await call(cfg, "POST", "/tools/kyc/mobile/otp", body));
  return outcome.ok ? { ...outcome, clientRefId } : outcome;
}

/**
 * Verify OTP. `clientRefId` MUST be the one returned by the matching
 * sendOtp() call for this phone number (gotcha #3) — not a fresh one.
 * Remember the OTP is single-use (gotcha #4): don't call this twice for
 * the same phone+otp pair once it has already returned ok:true.
 */
export async function verifyOtp(cfg: EkoConfig, mobile: string, otp: string, clientRefId: string): Promise<EkoOutcome> {
  const body = { initiator_id: cfg.initiatorId, user_code: cfg.userCode, source: "API", client_ref_id: clientRefId, mobile, otp };
  const r = await call(cfg, "PUT", "/tools/kyc/mobile/otp/verify", body);
  const outcome = outcomeOf(r);
  // Belt-and-braces: only trust success if Eko actually handed back a verification token.
  if (outcome.ok && !("networkError" in r) && typeof r.json.data !== "object") {
    return { ok: false, reason: "eko verify-otp: missing data.otp_verification_token" };
  }
  return outcome;
}
