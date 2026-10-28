import { defaultProfileDocument } from "./profile-store.mjs";
import { parseProviderResponse, ProtocolError, normalizeGenerationRequest, buildProviderRequest } from "./protocol.mjs";
import { getSafetyTerms } from "./safety-terms.mjs";
import { MemoryStateStore } from "./state-store.mjs";
import { EnvTokenVerifier } from "./token-verifier.mjs";

const PROTOCOL_VERSION = "comment-gateway.v1";
const MINUTE_MS = 60_000;
const DAY_MS = 24 * 60 * MINUTE_MS;
export const IDEMPOTENCY_TTL_MS = 10 * MINUTE_MS;
export const DEFAULT_PROVIDER_TIMEOUT_MS = 12_000;

/**
 * Failed-authentication budget per remote address. A successful login does NOT
 * reset the counter: otherwise anyone holding one valid credential could
 * interleave successes to keep guessing others. Counters simply expire with
 * their window.
 */
export const AUTH_FAILURE_LIMITS = Object.freeze({
  device: Object.freeze({ maxFailures: 10, windowMs: 5 * MINUTE_MS }),
  admin: Object.freeze({ maxFailures: 5, windowMs: 15 * MINUTE_MS }),
});

function boundedInteger(value, fallback, minimum, maximum) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= minimum && parsed <= maximum ? parsed : fallback;
}

function normalizedProviderBaseUrl(value) {
  if (typeof value !== "string" || value.trim().length === 0) {
    return "";
  }
  try {
    const parsed = new URL(value.trim());
    if (parsed.protocol !== "https:" || parsed.username || parsed.password
        || parsed.search || parsed.hash) {
      return "";
    }
    parsed.pathname = parsed.pathname.replace(/\/+$/, "") || "/v1";
    return parsed.toString().replace(/\/$/, "");
  } catch {
    return "";
  }
}

function response(status, body, headers) {
  return headers ? { status, body, headers } : { status, body };
}

function error(status, code, message, headers) {
  return response(status, { error: { code, message } }, headers);
}

function retryAfter(resetAt, now) {
  return { "retry-after": String(Math.max(1, Math.ceil((resetAt - now) / 1000))) };
}

function verifierConfigured(verifier) {
  return typeof verifier.isConfigured !== "function" || verifier.isConfigured() === true;
}

function validIdentity(identity, now) {
  return Boolean(identity)
    && typeof identity.subject === "string"
    && identity.subject.length > 0
    && identity.subject.length <= 256
    && (identity.expiresAt === null || identity.expiresAt === undefined
      || (Number.isFinite(identity.expiresAt) && identity.expiresAt > now));
}

/** Keeps state-store keys unambiguous even if a subject contains ':'. */
function keyPart(value) {
  return encodeURIComponent(String(value));
}

function addressKey(remoteAddress) {
  return typeof remoteAddress === "string" && remoteAddress.length > 0
    ? keyPart(remoteAddress.slice(0, 64)) : "unknown";
}

export class CommentGatewayService {
  constructor({
    env = process.env,
    store,
    fetchImpl = globalThis.fetch,
    now = () => Date.now(),
    tokenVerifier,
    stateStore,
    providerTimeoutMs = DEFAULT_PROVIDER_TIMEOUT_MS,
  } = {}) {
    if (!store) {
      throw new Error("ProfileStore is required.");
    }
    this.store = store;
    this.fetchImpl = fetchImpl;
    this.now = now;
    this.tokenVerifier = tokenVerifier || EnvTokenVerifier.fromEnv(env);
    this.adminVerifier = new EnvTokenVerifier({ token: env.GATEWAY_ADMIN_TOKEN });
    this.stateStore = stateStore || new MemoryStateStore();
    this.providerTimeoutMs = providerTimeoutMs;
    this.providerBaseUrl = normalizedProviderBaseUrl(env.MODEL_BASE_URL);
    this.providerApiKey = String(env.MODEL_API_KEY || "");
    this.maxRequestsPerMinute = boundedInteger(env.GATEWAY_REQUESTS_PER_MINUTE, 20, 1, 120);
    this.maxRequestsPerDay = boundedInteger(env.GATEWAY_REQUESTS_PER_DAY, 500, 1, 100_000);
  }

  providerConfigured() {
    return Boolean(this.providerBaseUrl && this.providerApiKey.length >= 8);
  }

  async health() {
    const profiles = await this.store.read();
    return response(200, {
      status: "ok",
      protocol_version: PROTOCOL_VERSION,
      provider_configured: this.providerConfigured(),
      active_profile: profiles.active_profile,
      safety_terms_version: getSafetyTerms().version,
    });
  }

  async generate({ authorization, profileId, body, remoteAddress }) {
    const auth = await this.authenticate({
      kind: "device",
      verifier: this.tokenVerifier,
      authorization,
      remoteAddress,
      notConfigured: error(503, "CLIENT_AUTH_NOT_CONFIGURED", "网关尚未配置设备鉴权。"),
      unauthorized: error(401, "UNAUTHORIZED", "设备令牌无效或已过期。"),
    });
    if (auth.failure) {
      return auth.failure;
    }
    let request;
    try {
      request = normalizeGenerationRequest(body);
    } catch (reason) {
      if (reason instanceof ProtocolError) {
        return error(reason.status, reason.code, reason.message);
      }
      return error(400, "INVALID_REQUEST", "请求无法处理。");
    }
    const cached = await this.getIdempotentResponse(auth.subject, request.requestId);
    if (cached) {
      return cached;
    }
    const limitError = await this.consumeRateLimit(auth.subject);
    if (limitError) {
      return limitError;
    }
    if (!this.providerConfigured()) {
      return error(503, "MODEL_NOT_CONFIGURED", "网关尚未完成后台模型配置。");
    }
    const profileDocument = await this.store.read();
    const selectedProfile = typeof profileId === "string" && profileDocument.profiles[profileId]
      ? profileId : profileDocument.active_profile;
    const profile = profileDocument.profiles[selectedProfile];
    const providerRequest = buildProviderRequest(request, profile);
    let providerResponse;
    try {
      providerResponse = await this.callProvider(providerRequest);
    } catch {
      return error(504, "PROVIDER_UNAVAILABLE", "模型服务暂时不可用或超时。");
    }
    if (!providerResponse.ok) {
      return error(providerResponse.status === 429 ? 429 : 502,
        "PROVIDER_REJECTED", "模型服务未接受本次请求。");
    }
    let generated;
    try {
      generated = parseProviderResponse(await providerResponse.text(), request);
    } catch (reason) {
      if (reason instanceof ProtocolError) {
        return error(reason.status, reason.code, reason.message);
      }
      return error(502, "INVALID_PROVIDER_RESPONSE", "模型返回无法安全处理。");
    }
    const result = response(200, {
      ...generated,
      provider_profile: selectedProfile,
    });
    await this.rememberIdempotentResponse(auth.subject, request.requestId, result);
    return result;
  }

  async adminStatus(authorization, { remoteAddress } = {}) {
    const authorizationError = await this.requireAdmin(authorization, remoteAddress);
    if (authorizationError) {
      return authorizationError;
    }
    const profiles = await this.store.read();
    let providerBaseHost;
    try {
      providerBaseHost = this.providerBaseUrl ? new URL(this.providerBaseUrl).host : "";
    } catch {
      providerBaseHost = "";
    }
    return response(200, {
      provider_configured: this.providerConfigured(),
      provider_base_host: providerBaseHost,
      active_profile: profiles.active_profile,
      default_profiles: defaultProfileDocument().profiles,
      request_limit_per_minute: this.maxRequestsPerMinute,
      request_limit_per_day: this.maxRequestsPerDay,
      safety_terms_version: getSafetyTerms().version,
      credential_storage: "server_environment_only",
    });
  }

  async adminProfiles(authorization, { remoteAddress } = {}) {
    const authorizationError = await this.requireAdmin(authorization, remoteAddress);
    return authorizationError || response(200, await this.store.read());
  }

  async updateAdminProfiles(authorization, document, { remoteAddress } = {}) {
    const authorizationError = await this.requireAdmin(authorization, remoteAddress);
    if (authorizationError) {
      return authorizationError;
    }
    try {
      return response(200, await this.store.write(document));
    } catch (reason) {
      return error(400, "INVALID_PROFILE_CONFIG", reason.message || "模型档位配置无效。");
    }
  }

  async requireAdmin(authorization, remoteAddress) {
    const auth = await this.authenticate({
      kind: "admin",
      verifier: this.adminVerifier,
      authorization,
      remoteAddress,
      notConfigured: error(503, "ADMIN_AUTH_NOT_CONFIGURED", "后台管理令牌尚未配置。"),
      unauthorized: error(401, "UNAUTHORIZED", "后台管理令牌无效。"),
    });
    return auth.failure || null;
  }

  /**
   * Shared auth path: 503 when unconfigured, 429 AUTH_THROTTLED once the
   * address has used up its failure budget (even if this token is correct),
   * otherwise verify and count the failure.
   */
  async authenticate({ kind, verifier, authorization, remoteAddress, notConfigured, unauthorized }) {
    if (!verifierConfigured(verifier)) {
      return { failure: notConfigured };
    }
    const limits = AUTH_FAILURE_LIMITS[kind];
    const throttleKey = `authfail:${kind}:${addressKey(remoteAddress)}`;
    const now = this.now();
    const failures = await this.stateStore.getWindow(throttleKey, now);
    if (failures && failures.count >= limits.maxFailures) {
      return {
        failure: error(429, "AUTH_THROTTLED", "鉴权失败次数过多，请稍后再试。",
          retryAfter(failures.resetAt, now)),
      };
    }
    const identity = await verifier.verify(authorization);
    if (!validIdentity(identity, now)) {
      await this.stateStore.incrementWindow(throttleKey, limits.windowMs, now);
      return { failure: unauthorized };
    }
    return { subject: identity.subject };
  }

  async callProvider(providerRequest) {
    if (typeof this.fetchImpl !== "function") {
      throw new Error("fetch is unavailable");
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.providerTimeoutMs);
    try {
      return await this.fetchImpl(`${this.providerBaseUrl}/chat/completions`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "authorization": `Bearer ${this.providerApiKey}`,
        },
        body: JSON.stringify(providerRequest),
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timeout);
    }
  }

  /**
   * Per-subject calendar-minute and UTC-day quotas. The minute counter is
   * checked first so a burst rejected per minute does not burn the daily quota.
   */
  async consumeRateLimit(subject) {
    const now = this.now();
    const id = keyPart(subject);
    const minuteStart = Math.floor(now / MINUTE_MS) * MINUTE_MS;
    const minute = await this.stateStore.incrementWindow(
      `rate:minute:${id}:${minuteStart}`, minuteStart + MINUTE_MS - now, now);
    if (minute.count > this.maxRequestsPerMinute) {
      return error(429, "RATE_LIMITED", "网关请求已达到配置限额。", retryAfter(minute.resetAt, now));
    }
    const dayStart = Math.floor(now / DAY_MS) * DAY_MS;
    const day = await this.stateStore.incrementWindow(
      `rate:day:${id}:${dayStart}`, dayStart + DAY_MS - now, now);
    if (day.count > this.maxRequestsPerDay) {
      return error(429, "RATE_LIMITED", "网关请求已达到配置限额。", retryAfter(day.resetAt, now));
    }
    return null;
  }

  idempotencyKey(subject, requestId) {
    return `idem:${keyPart(subject)}:${requestId}`;
  }

  async getIdempotentResponse(subject, requestId) {
    return this.stateStore.getCached(this.idempotencyKey(subject, requestId), this.now());
  }

  async rememberIdempotentResponse(subject, requestId, result) {
    await this.stateStore.setCached(
      this.idempotencyKey(subject, requestId), result, IDEMPOTENCY_TTL_MS, this.now());
  }
}
