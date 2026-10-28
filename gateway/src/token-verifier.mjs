import { createHash, timingSafeEqual } from "node:crypto";

export const MIN_TOKEN_LENGTH = 16;

/**
 * TokenVerifier contract (duck-typed, no base class required):
 *
 *   verify(authorizationHeader: string | undefined)
 *     -> Promise<{ subject: string, expiresAt: number | null } | null>
 *   isConfigured?() -> boolean   // optional; false makes the gateway answer 503
 *
 * `subject` is a stable, non-secret identifier of the device/user (it keys rate
 * limits and the idempotency cache, so it must never be the raw token).
 * `expiresAt` is an epoch-millisecond expiry or null; the service rejects an
 * identity whose expiresAt is not in the future. Return null for any invalid
 * header. Throwing means "verifier unavailable" and surfaces as a 500, not as
 * an authentication failure.
 */

function sha256(value) {
  return createHash("sha256").update(value).digest();
}

export function bearerToken(header) {
  if (typeof header !== "string") {
    return null;
  }
  const match = /^Bearer\s+(.+)$/i.exec(header);
  return match ? match[1] : null;
}

/**
 * Default verifier: a single shared token from the server environment
 * (MODEL_GATEWAY_CLIENT_TOKEN). Only the token digest is kept in memory and
 * digests are compared in constant time, so neither content nor length leaks.
 */
export class EnvTokenVerifier {
  constructor({ token = "", minLength = MIN_TOKEN_LENGTH } = {}) {
    const value = String(token || "");
    this.configured = value.length >= minLength;
    this.tokenDigest = this.configured ? sha256(value) : null;
    this.subject = this.configured ? `env:${this.tokenDigest.toString("hex").slice(0, 12)}` : "";
  }

  static fromEnv(env = process.env) {
    return new EnvTokenVerifier({ token: env.MODEL_GATEWAY_CLIENT_TOKEN });
  }

  isConfigured() {
    return this.configured;
  }

  async verify(authorizationHeader) {
    const supplied = bearerToken(authorizationHeader);
    if (!this.configured || supplied === null) {
      return null;
    }
    return timingSafeEqual(sha256(supplied), this.tokenDigest)
      ? { subject: this.subject, expiresAt: null }
      : null;
  }
}
