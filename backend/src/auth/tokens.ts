/**
 * HS256 access JWTs (C2 /auth/otp/verify: "JWT, ~1h") + opaque refresh tokens.
 * Claims: sub (user id), tenant, role, device_id.
 */
import { randomBytes } from "node:crypto";
import { jwtVerify, SignJWT } from "jose";
import type { Principal, Role } from "../domain/types.js";

const encoder = new TextEncoder();

export interface TokenConfig {
  secret: string;
  accessTtlSeconds: number; // ~1h per C2
}

export const DEV_TOKEN_CONFIG: TokenConfig = {
  secret: process.env.JWT_SECRET ?? "eko-dc-visits-dev-secret-not-for-prod",
  accessTtlSeconds: 3600,
};

export async function signAccessToken(cfg: TokenConfig, p: Principal, now: Date): Promise<string> {
  return new SignJWT({ tenant: p.tenant_id, role: p.role, device_id: p.device_id })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(p.user_id)
    .setIssuedAt(Math.floor(now.getTime() / 1000))
    .setExpirationTime(Math.floor(now.getTime() / 1000) + cfg.accessTtlSeconds)
    .sign(encoder.encode(cfg.secret));
}

export async function verifyAccessToken(cfg: TokenConfig, token: string): Promise<Principal | null> {
  try {
    const { payload } = await jwtVerify(token, encoder.encode(cfg.secret), { algorithms: ["HS256"] });
    if (
      typeof payload.sub !== "string" ||
      typeof payload.tenant !== "string" ||
      typeof payload.role !== "string" ||
      typeof payload.device_id !== "string"
    ) {
      return null;
    }
    return {
      user_id: payload.sub,
      tenant_id: payload.tenant,
      role: payload.role as Role,
      device_id: payload.device_id,
    };
  } catch {
    return null;
  }
}

/** Opaque, stored server-side (rotation/expiry policy beyond issuance is M1). */
export function newRefreshToken(): string {
  return randomBytes(32).toString("hex");
}
