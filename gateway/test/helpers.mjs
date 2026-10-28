import { mkdtemp, rm } from "node:fs/promises";
import { createServer, request as httpRequestRaw } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { ProfileStore } from "../src/profile-store.mjs";
import { CommentGatewayService } from "../src/service.mjs";

export const DEVICE_TOKEN = "device-session-token-0123456789";
export const ADMIN_TOKEN = "admin-session-token-0123456789";
export const PROVIDER_KEY = "provider-secret-does-not-leave-server";
export const VIDEO_TEXT = "今天分享如何把学习计划拆成每周都能完成的小目标。";
export const BASE_ENV = Object.freeze({
  MODEL_GATEWAY_CLIENT_TOKEN: DEVICE_TOKEN,
  GATEWAY_ADMIN_TOKEN: ADMIN_TOKEN,
  MODEL_BASE_URL: "https://model.example/v1",
  MODEL_API_KEY: PROVIDER_KEY,
});

export function requestBody(requestId = "request-123", overrides = {}) {
  return {
    protocol_version: "comment-gateway.v1",
    request_id: requestId,
    client_mode: "manual_comment_assist",
    video_context: {
      source: "accessibility_text",
      language: "zh-CN",
      text: VIDEO_TEXT,
    },
    strategy: {
      keywords: ["学习计划", "效率", "学习计划"],
      direction: "ADD_VALUE",
      max_length: 80,
    },
    candidate_count: 3,
    ...overrides,
  };
}

export function providerReply(candidates, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify({
      choices: [{ message: { content: JSON.stringify({ candidates }) } }],
    }),
  };
}

/** Fake provider fetch that records calls; `reply(callIndex)` builds each response. */
export function fakeFetch(reply = () => providerReply([{
  text: "学习计划拆得很细，跟着每周小目标做会更容易坚持。",
  evidence_label: "学习计划",
}])) {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    return reply(calls.length - 1, options);
  };
  return { calls, fetchImpl };
}

/** Test verifier mapping bearer tokens to subjects, to exercise the TokenVerifier interface. */
export class MapTokenVerifier {
  constructor(entries) {
    this.entries = new Map(Object.entries(entries));
  }

  async verify(header) {
    const match = /^Bearer\s+(.+)$/i.exec(header || "");
    const entry = match ? this.entries.get(match[1]) : undefined;
    if (!entry) {
      return null;
    }
    return typeof entry === "string" ? { subject: entry, expiresAt: null } : entry;
  }
}

export function createClock(start = Date.UTC(2026, 9, 8, 10, 0, 0)) {
  const clock = {
    value: start,
    now: () => clock.value,
    advance(ms) { clock.value += ms; },
  };
  return clock;
}

export async function withTempDir(callback) {
  const folder = await mkdtemp(join(tmpdir(), "comment-gateway-test-"));
  try {
    return await callback(folder);
  } finally {
    await rm(folder, { recursive: true, force: true });
  }
}

export function createService(folder, options = {}) {
  const { env = {}, ...rest } = options;
  return new CommentGatewayService({
    store: new ProfileStore(join(folder, "model-profiles.json")),
    env: { ...BASE_ENV, ...env },
    fetchImpl: fakeFetch().fetchImpl,
    ...rest,
  });
}

/** Runs a request handler on 127.0.0.1:0 with real node:http sockets. */
export async function startHttp(handler) {
  const server = createServer(handler);
  await new Promise((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
  return {
    server,
    port: server.address().port,
    close: () => new Promise((resolveClose) => {
      server.closeAllConnections?.();
      server.close(() => resolveClose());
    }),
  };
}

export function httpRequest(port, { method = "GET", path = "/", headers = {}, body } = {}) {
  return new Promise((resolveRequest, rejectRequest) => {
    const payload = body === undefined ? null
      : Buffer.from(typeof body === "string" ? body : JSON.stringify(body));
    const outgoing = httpRequestRaw({
      host: "127.0.0.1",
      port,
      method,
      path,
      agent: false,
      headers: payload ? { "content-type": "application/json", "content-length": payload.length, ...headers } : headers,
    }, (incoming) => {
      const chunks = [];
      incoming.on("data", (chunk) => chunks.push(chunk));
      incoming.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        let json;
        try {
          json = JSON.parse(text);
        } catch {
          json = null;
        }
        resolveRequest({ status: incoming.statusCode, headers: incoming.headers, text, json });
      });
    });
    outgoing.on("error", rejectRequest);
    outgoing.end(payload || undefined);
  });
}
