import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { join } from "node:path";
import test from "node:test";

import { DEFAULT_SAFETY_TERMS_FILE } from "../src/safety-terms.mjs";
import { createGatewayHandler, createGracefulShutdown } from "../src/server.mjs";
import {
  ADMIN_TOKEN,
  createService,
  DEVICE_TOKEN,
  httpRequest,
  PROVIDER_KEY,
  requestBody,
  startHttp,
  VIDEO_TEXT,
  withTempDir,
} from "./helpers.mjs";

const SAFETY_VERSION = JSON.parse(readFileSync(DEFAULT_SAFETY_TERMS_FILE, "utf8")).version;

async function withGateway(options, callback) {
  await withTempDir(async (folder) => {
    const logs = [];
    const service = createService(folder, options.service || {});
    const handler = createGatewayHandler(service, {
      logger: { error: (entry) => logs.push(entry) },
      trustProxy: options.trustProxy || false,
    });
    const gateway = await startHttp(handler);
    try {
      await callback({ port: gateway.port, logs, folder });
    } finally {
      await gateway.close();
    }
  });
}

test("GET /health reports protocol and safety terms version; unknown routes are 404", async () => {
  await withGateway({}, async ({ port, logs }) => {
    const health = await httpRequest(port, { path: "/health" });
    assert.equal(health.status, 200);
    assert.deepEqual(health.json, {
      status: "ok",
      protocol_version: "comment-gateway.v1",
      provider_configured: true,
      active_profile: "standard",
      safety_terms_version: SAFETY_VERSION,
    });
    assert.equal(health.headers["cache-control"], "no-store");

    const missing = await httpRequest(port, { path: "/nope" });
    assert.equal(missing.status, 404);
    assert.equal(missing.json.error.code, "NOT_FOUND");
    assert.equal(logs.length, 0);
  });
});

test("body errors: invalid JSON is 400, oversized body is 413", async () => {
  await withGateway({}, async ({ port, logs }) => {
    const invalid = await httpRequest(port, {
      method: "POST",
      path: "/v1/comment-candidates",
      headers: { authorization: `Bearer ${DEVICE_TOKEN}` },
      body: "{not json",
    });
    assert.equal(invalid.status, 400);
    assert.equal(invalid.json.error.code, "INVALID_JSON");
    assert.equal(invalid.headers["x-request-id"], undefined);

    const oversized = await httpRequest(port, {
      method: "POST",
      path: "/v1/comment-candidates",
      headers: { authorization: `Bearer ${DEVICE_TOKEN}` },
      body: JSON.stringify({ ...requestBody(), padding: "x".repeat(40 * 1024) }),
    });
    assert.equal(oversized.status, 413);
    assert.equal(oversized.json.error.code, "PAYLOAD_TOO_LARGE");

    const adminInvalid = await httpRequest(port, {
      method: "PUT",
      path: "/admin/v1/model-profiles",
      headers: { authorization: `Bearer ${ADMIN_TOKEN}` },
      body: "[",
    });
    assert.equal(adminInvalid.status, 400);
    assert.equal(adminInvalid.json.error.code, "INVALID_JSON");
    assert.equal(logs.length, 0);
  });
});

test("corrupt profile file yields 500 INTERNAL_ERROR and exactly one sanitized log line", async () => {
  await withGateway({}, async ({ port, logs, folder }) => {
    await writeFile(join(folder, "model-profiles.json"), "{ corrupted profile file");

    const health = await httpRequest(port, { path: "/health?token=should-not-be-logged" });
    assert.equal(health.status, 500);
    assert.deepEqual(health.json, { error: { code: "INTERNAL_ERROR", message: "网关内部错误。" } });
    assert.equal(logs.length, 1);
    assert.deepEqual(Object.keys(logs[0]).sort(),
      ["error_class", "error_code", "method", "path", "request_id", "timestamp"]);
    assert.equal(logs[0].method, "GET");
    assert.equal(logs[0].path, "/health");
    assert.equal(logs[0].request_id, null);
    assert.equal(logs[0].error_class, "SyntaxError");
    assert.ok(!Number.isNaN(Date.parse(logs[0].timestamp)));

    logs.length = 0;
    const generate = await httpRequest(port, {
      method: "POST",
      path: "/v1/comment-candidates",
      headers: { authorization: `Bearer ${DEVICE_TOKEN}` },
      body: requestBody("corrupt-request-1"),
    });
    assert.equal(generate.status, 500);
    assert.equal(generate.json.error.code, "INTERNAL_ERROR");
    assert.equal(generate.headers["x-request-id"], "corrupt-request-1");
    assert.equal(logs.length, 1);
    assert.equal(logs[0].request_id, "corrupt-request-1");
    assert.equal(logs[0].path, "/v1/comment-candidates");
    const line = JSON.stringify(logs[0]);
    for (const secret of [DEVICE_TOKEN, ADMIN_TOKEN, PROVIDER_KEY, VIDEO_TEXT, "学习计划", "corrupted", "Bearer"]) {
      assert.ok(!line.includes(secret), `log line must not contain ${secret}`);
    }
  });
});

test("x-request-id echoes the normalized body request_id and ignores the request header", async () => {
  await withGateway({}, async ({ port }) => {
    const ok = await httpRequest(port, {
      method: "POST",
      path: "/v1/comment-candidates",
      headers: { authorization: `Bearer ${DEVICE_TOKEN}`, "x-request-id": "spoofed-header-id" },
      body: requestBody("  body-request-7  "),
    });
    assert.equal(ok.status, 200);
    assert.equal(ok.json.request_id, "body-request-7");
    assert.equal(ok.headers["x-request-id"], "body-request-7");

    const unauthorized = await httpRequest(port, {
      method: "POST",
      path: "/v1/comment-candidates",
      headers: { authorization: "Bearer wrong-token-000000000000", "x-request-id": "spoofed-header-id" },
      body: requestBody("body-request-8"),
    });
    assert.equal(unauthorized.status, 401);
    assert.equal(unauthorized.headers["x-request-id"], "body-request-8");

    const invalidId = await httpRequest(port, {
      method: "POST",
      path: "/v1/comment-candidates",
      headers: { authorization: `Bearer ${DEVICE_TOKEN}`, "x-request-id": "spoofed-header-id" },
      body: requestBody("abc"),
    });
    assert.equal(invalidId.status, 400);
    assert.equal(invalidId.json.error.code, "INVALID_REQUEST_ID");
    assert.equal(invalidId.headers["x-request-id"], undefined);
  });
});

test("admin endpoints: 401 without token, 503 when no admin token is configured", async () => {
  await withGateway({}, async ({ port }) => {
    const denied = await httpRequest(port, { path: "/admin/v1/status" });
    assert.equal(denied.status, 401);
    assert.equal(denied.json.error.code, "UNAUTHORIZED");
    const allowed = await httpRequest(port, {
      path: "/admin/v1/status",
      headers: { authorization: `Bearer ${ADMIN_TOKEN}` },
    });
    assert.equal(allowed.status, 200);
    assert.ok(allowed.json.default_profiles.standard.model);
    assert.equal(allowed.json.safety_terms_version, SAFETY_VERSION);
  });
  await withGateway({ service: { env: { GATEWAY_ADMIN_TOKEN: "" } } }, async ({ port }) => {
    const unconfigured = await httpRequest(port, {
      path: "/admin/v1/model-profiles",
      headers: { authorization: `Bearer ${ADMIN_TOKEN}` },
    });
    assert.equal(unconfigured.status, 503);
    assert.equal(unconfigured.json.error.code, "ADMIN_AUTH_NOT_CONFIGURED");
  });
});

test("admin auth throttling uses the socket address unless a trusted proxy is declared", async () => {
  const wrong = { authorization: "Bearer wrong-admin-token-000000" };
  await withGateway({}, async ({ port }) => {
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const result = await httpRequest(port, {
        path: "/admin/v1/status",
        headers: { ...wrong, "x-forwarded-for": `192.0.2.${attempt}` },
      });
      assert.equal(result.status, 401);
    }
    const throttled = await httpRequest(port, {
      path: "/admin/v1/status",
      headers: { authorization: `Bearer ${ADMIN_TOKEN}`, "x-forwarded-for": "192.0.2.99" },
    });
    assert.equal(throttled.status, 429);
    assert.equal(throttled.json.error.code, "AUTH_THROTTLED");
    assert.ok(Number(throttled.headers["retry-after"]) > 0);
  });
  await withGateway({ trustProxy: true }, async ({ port }) => {
    for (let attempt = 0; attempt < 5; attempt += 1) {
      await httpRequest(port, {
        path: "/admin/v1/status",
        headers: { ...wrong, "x-forwarded-for": "192.0.2.10, 10.0.0.1" },
      });
    }
    const throttled = await httpRequest(port, {
      path: "/admin/v1/status",
      headers: { authorization: `Bearer ${ADMIN_TOKEN}`, "x-forwarded-for": "192.0.2.10" },
    });
    assert.equal(throttled.status, 429);
    const otherClient = await httpRequest(port, {
      path: "/admin/v1/status",
      headers: { authorization: `Bearer ${ADMIN_TOKEN}`, "x-forwarded-for": "192.0.2.11, 10.0.0.1" },
    });
    assert.equal(otherClient.status, 200);
  });
});

test("admin page keeps the CSP nonce and no longer hard-codes default models", async () => {
  await withGateway({}, async ({ port }) => {
    const page = await httpRequest(port, { path: "/admin" });
    const nonce = /nonce-([^']+)/.exec(page.headers["content-security-policy"])[1];
    assert.equal(page.status, 200);
    assert.ok(page.text.includes(`<script nonce="${nonce}">`));
    assert.ok(page.text.includes("default_profiles"));
    assert.doesNotMatch(page.text, /gpt-4o-mini/);
  });
});

test("graceful shutdown stops accepting, drains in-flight requests, then exits 0", async () => {
  let release;
  const gate = new Promise((resolveGate) => { release = resolveGate; });
  const server = createServer();
  const exits = [];
  let exited;
  const exitPromise = new Promise((resolveExit) => { exited = resolveExit; });
  const shutdown = createGracefulShutdown(server, {
    timeoutMs: 5_000,
    log: () => {},
    exit: (code) => { exits.push(code); exited(code); },
  });
  server.on("request", async (request, response) => {
    await gate;
    response.end("done");
  });
  await new Promise((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
  const { port } = server.address();

  const inFlight = httpRequest(port, { path: "/slow" });
  await new Promise((resolveDelay) => setTimeout(resolveDelay, 50));
  shutdown("SIGTERM");
  await assert.rejects(httpRequest(port, { path: "/late" }));
  assert.deepEqual(exits, []);

  release();
  const finished = await inFlight;
  assert.equal(finished.status, 200);
  assert.equal(finished.text, "done");
  assert.equal(finished.headers.connection, "close");
  assert.equal(await exitPromise, 0);
});

test("graceful shutdown gives up after the timeout and exits 1", async () => {
  const server = createServer(() => { /* never responds */ });
  let exited;
  const exitPromise = new Promise((resolveExit) => { exited = resolveExit; });
  const shutdown = createGracefulShutdown(server, { timeoutMs: 50, log: () => {}, exit: (code) => exited(code) });
  await new Promise((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
  const pending = httpRequest(server.address().port, { path: "/hang" }).catch(() => "aborted");
  await new Promise((resolveDelay) => setTimeout(resolveDelay, 30));
  shutdown("SIGINT");
  assert.equal(await exitPromise, 1);
  assert.equal(await pending, "aborted");
});
