import { createServer } from "node:http";
import { randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { ProfileStore } from "./profile-store.mjs";
import { normalizeRequestId } from "./protocol.mjs";
import { getSafetyTerms } from "./safety-terms.mjs";
import { CommentGatewayService } from "./service.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const MAX_BODY_BYTES = 32 * 1024;
export const SHUTDOWN_GRACE_MS = 10_000;

/** Raised only for client body problems (oversized or unparsable JSON). */
export class RequestBodyError extends Error {
  constructor(message, status, code) {
    super(message);
    this.name = "RequestBodyError";
    this.status = status;
    this.code = code;
  }
}

/**
 * Default logger: one JSON object per line on stderr. Callers must only pass
 * already-sanitized fields (see logInternalError).
 */
export const stderrJsonLogger = Object.freeze({
  error(entry) {
    process.stderr.write(`${JSON.stringify(entry)}\n`);
  },
});

function sendJson(response, status, body, extraHeaders = {}) {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    ...extraHeaders,
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "content-security-policy": "default-src 'none'",
    "x-content-type-options": "nosniff",
  });
  response.end(payload);
}

function sendResult(response, result, extraHeaders = {}) {
  sendJson(response, result.status, result.body, { ...(result.headers || {}), ...extraHeaders });
}

async function readJson(request) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      throw new RequestBodyError("请求体超过网关上限。", 413, "PAYLOAD_TOO_LARGE");
    }
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new RequestBodyError("请求无法处理。", 400, "INVALID_JSON");
  }
}

/** Only printable ASCII ids are echoed; anything else cannot be a header value. */
function headerSafeRequestId(body) {
  const requestId = body && typeof body === "object" ? normalizeRequestId(body.request_id) : "";
  return /^[\x20-\x7e]+$/.test(requestId) ? requestId : "";
}

/**
 * Socket peer address, or the first X-Forwarded-For hop when the operator has
 * declared a trusted reverse proxy (GATEWAY_TRUST_PROXY=1). The proxy must
 * overwrite, not append to, X-Forwarded-For or the first hop is client-chosen.
 */
export function clientAddress(request, trustProxy = false) {
  if (trustProxy) {
    const forwarded = request.headers?.["x-forwarded-for"];
    const firstHop = typeof forwarded === "string" ? forwarded.split(",")[0].trim() : "";
    if (firstHop) {
      return firstHop.slice(0, 64);
    }
  }
  return request.socket?.remoteAddress || "unknown";
}

function errorCode(reason) {
  const code = reason?.code;
  return (typeof code === "string" || typeof code === "number")
    && /^[A-Za-z0-9_.-]{1,64}$/.test(String(code)) ? String(code) : null;
}

/**
 * Never pass error messages, bodies, headers, tokens or provider data here:
 * messages can embed file contents or user text.
 */
function logInternalError(logger, { method, path, requestId, reason }) {
  try {
    logger.error({
      timestamp: new Date().toISOString(),
      method: typeof method === "string" ? method.slice(0, 16) : null,
      path: typeof path === "string" ? path.slice(0, 200) : null,
      request_id: requestId || null,
      error_class: reason instanceof Error ? reason.name : typeof reason,
      error_code: errorCode(reason),
    });
  } catch {
    // Logging must never turn a 500 into a crash.
  }
}

function applyPageHeaders(response, nonce) {
  response.setHeader("content-type", "text/html; charset=utf-8");
  response.setHeader("cache-control", "no-store");
  response.setHeader("content-security-policy", `default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; base-uri 'none'; form-action 'self'`);
  response.setHeader("referrer-policy", "no-referrer");
  response.setHeader("x-content-type-options", "nosniff");
  response.setHeader("x-frame-options", "DENY");
}

export function createGatewayHandler(service, { adminPagePath, logger = stderrJsonLogger, trustProxy = false } = {}) {
  const pagePath = adminPagePath || resolve(HERE, "../public/admin.html");
  return async (request, response) => {
    let path = null;
    // The body request_id is authoritative; any X-Request-Id request header is ignored.
    let requestId = "";
    const echoHeaders = () => (requestId ? { "x-request-id": requestId } : {});
    try {
      const url = new URL(request.url || "/", "http://gateway.invalid");
      path = url.pathname;
      const remoteAddress = clientAddress(request, trustProxy);
      const authorization = request.headers.authorization;
      if (request.method === "GET" && url.pathname === "/health") {
        sendResult(response, await service.health());
        return;
      }
      if (request.method === "GET" && (url.pathname === "/admin" || url.pathname === "/admin/")) {
        const nonce = randomBytes(16).toString("base64");
        const page = (await readFile(pagePath, "utf8")).replaceAll("%NONCE%", nonce);
        applyPageHeaders(response, nonce);
        response.end(page);
        return;
      }
      if (request.method === "GET" && url.pathname === "/admin/v1/status") {
        sendResult(response, await service.adminStatus(authorization, { remoteAddress }));
        return;
      }
      if (request.method === "GET" && url.pathname === "/admin/v1/model-profiles") {
        sendResult(response, await service.adminProfiles(authorization, { remoteAddress }));
        return;
      }
      if (request.method === "PUT" && url.pathname === "/admin/v1/model-profiles") {
        const document = await readJson(request);
        sendResult(response, await service.updateAdminProfiles(authorization, document, { remoteAddress }));
        return;
      }
      if (request.method === "POST" && url.pathname === "/v1/comment-candidates") {
        const body = await readJson(request);
        requestId = headerSafeRequestId(body);
        const result = await service.generate({
          authorization,
          profileId: request.headers["x-model-profile"],
          body,
          remoteAddress,
        });
        sendResult(response, result, echoHeaders());
        return;
      }
      sendJson(response, 404, { error: { code: "NOT_FOUND", message: "接口不存在。" } });
    } catch (reason) {
      if (response.headersSent) {
        response.destroy?.();
        return;
      }
      if (reason instanceof RequestBodyError) {
        sendJson(response, reason.status, { error: { code: reason.code, message: reason.message } },
          echoHeaders());
        return;
      }
      logInternalError(logger, { method: request.method, path, requestId, reason });
      sendJson(response, 500, { error: { code: "INTERNAL_ERROR", message: "网关内部错误。" } },
        echoHeaders());
    }
  };
}

/**
 * Returns shutdown(signal): stop accepting connections, let in-flight requests
 * finish (responses switch to `connection: close`), and exit(0) once the server
 * has closed, or exit(1) after timeoutMs.
 */
export function createGracefulShutdown(server, {
  timeoutMs = SHUTDOWN_GRACE_MS,
  exit = (code) => process.exit(code),
  log = (line) => process.stdout.write(`${line}\n`),
} = {}) {
  const active = new Set();
  let shuttingDown = false;
  let exited = false;
  const exitOnce = (code) => {
    if (!exited) {
      exited = true;
      exit(code);
    }
  };
  const closeIdle = () => server.closeIdleConnections?.();
  server.prependListener("request", (request, response) => {
    active.add(response);
    if (shuttingDown) {
      response.setHeader("connection", "close");
    }
    response.on("close", () => {
      active.delete(response);
      if (shuttingDown) {
        setImmediate(closeIdle);
      }
    });
  });
  return function shutdown(signal) {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;
    log(`CommentModelGateway received ${signal}; draining ${active.size} request(s)`);
    for (const response of active) {
      if (!response.headersSent) {
        response.setHeader("connection", "close");
      }
    }
    const timer = setTimeout(() => {
      server.closeAllConnections?.();
      exitOnce(1);
    }, timeoutMs);
    timer.unref?.();
    server.close(() => {
      clearTimeout(timer);
      exitOnce(0);
    });
    closeIdle();
  };
}

export function startGateway({ env = process.env } = {}) {
  const port = Number(env.GATEWAY_PORT || 8787);
  const host = env.GATEWAY_BIND_HOST || "127.0.0.1";
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("GATEWAY_PORT 无效。");
  }
  // Fail fast on a missing or malformed shared safety vocabulary.
  getSafetyTerms();
  const profilePath = env.GATEWAY_PROFILE_FILE || resolve(HERE, "../config/model-profiles.json");
  const service = new CommentGatewayService({ env, store: new ProfileStore(profilePath) });
  const server = createServer();
  const shutdown = createGracefulShutdown(server);
  server.on("request", createGatewayHandler(service, { trustProxy: env.GATEWAY_TRUST_PROXY === "1" }));
  for (const signal of ["SIGTERM", "SIGINT"]) {
    process.once(signal, () => shutdown(signal));
  }
  server.listen(port, host, () => {
    // This intentionally emits no model credential, client token, video text, or comment text.
    process.stdout.write(`CommentModelGateway listening on ${host}:${port}\n`);
  });
  return server;
}

const executedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : "";
if (import.meta.url === executedPath) {
  startGateway();
}
