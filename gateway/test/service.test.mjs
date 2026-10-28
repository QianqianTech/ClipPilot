import assert from "node:assert/strict";
import test from "node:test";

import { defaultProfileDocument } from "../src/profile-store.mjs";
import { getSafetyTerms } from "../src/safety-terms.mjs";
import { AUTH_FAILURE_LIMITS } from "../src/service.mjs";
import { MemoryStateStore } from "../src/state-store.mjs";
import {
  ADMIN_TOKEN,
  createClock,
  createService,
  DEVICE_TOKEN,
  fakeFetch,
  MapTokenVerifier,
  providerReply,
  requestBody,
  withTempDir,
} from "./helpers.mjs";

const DEVICE_AUTH = `Bearer ${DEVICE_TOKEN}`;
const ADMIN_AUTH = `Bearer ${ADMIN_TOKEN}`;

function numberedReply(callIndex) {
  return providerReply([{
    text: `学习计划第${callIndex + 1}条建议很实用，拆成小目标更容易坚持。`,
    evidence_label: "学习计划",
  }]);
}

test("idempotency cache is isolated per subject", async () => {
  await withTempDir(async (folder) => {
    const provider = fakeFetch(numberedReply);
    const service = createService(folder, {
      fetchImpl: provider.fetchImpl,
      tokenVerifier: new MapTokenVerifier({
        "token-for-device-a-000000": "user:a",
        "token-for-device-b-000000": "user:b",
      }),
    });
    const first = await service.generate({
      authorization: "Bearer token-for-device-a-000000",
      body: requestBody("shared-request-id"),
    });
    const second = await service.generate({
      authorization: "Bearer token-for-device-b-000000",
      body: requestBody("shared-request-id"),
    });
    assert.equal(first.status, 200);
    assert.equal(second.status, 200);
    assert.equal(provider.calls.length, 2);
    assert.notEqual(second.body.candidates[0].text, first.body.candidates[0].text);

    const replay = await service.generate({
      authorization: "Bearer token-for-device-a-000000",
      body: requestBody("shared-request-id"),
    });
    assert.equal(provider.calls.length, 2);
    assert.deepEqual(replay.body, first.body);
  });
});

test("idempotency cache evicts the oldest entry when the store is full", async () => {
  await withTempDir(async (folder) => {
    const provider = fakeFetch(numberedReply);
    const service = createService(folder, {
      fetchImpl: provider.fetchImpl,
      stateStore: new MemoryStateStore({ maxCachedEntries: 2 }),
    });
    for (const id of ["request-1", "request-2", "request-3"]) {
      await service.generate({ authorization: DEVICE_AUTH, body: requestBody(id) });
    }
    assert.equal(provider.calls.length, 3);
    await service.generate({ authorization: DEVICE_AUTH, body: requestBody("request-3") });
    assert.equal(provider.calls.length, 3);
    await service.generate({ authorization: DEVICE_AUTH, body: requestBody("request-1") });
    assert.equal(provider.calls.length, 4);
  });
});

test("idempotent responses expire after 10 minutes", async () => {
  await withTempDir(async (folder) => {
    const clock = createClock();
    const provider = fakeFetch(numberedReply);
    const service = createService(folder, { fetchImpl: provider.fetchImpl, now: clock.now });
    await service.generate({ authorization: DEVICE_AUTH, body: requestBody("request-ttl") });
    clock.advance(10 * 60_000 - 1);
    await service.generate({ authorization: DEVICE_AUTH, body: requestBody("request-ttl") });
    assert.equal(provider.calls.length, 1);
    clock.advance(1);
    await service.generate({ authorization: DEVICE_AUTH, body: requestBody("request-ttl") });
    assert.equal(provider.calls.length, 2);
  });
});

test("per-minute and per-day rate limits trigger and roll over", async () => {
  await withTempDir(async (folder) => {
    const clock = createClock(Date.UTC(2026, 9, 8, 23, 58, 0));
    const service = createService(folder, {
      now: clock.now,
      env: { GATEWAY_REQUESTS_PER_MINUTE: "2", GATEWAY_REQUESTS_PER_DAY: "3" },
    });
    let sequence = 0;
    const send = () => service.generate({
      authorization: DEVICE_AUTH,
      body: requestBody(`rate-request-${sequence += 1}`),
    });

    assert.equal((await send()).status, 200);
    assert.equal((await send()).status, 200);
    const minuteLimited = await send();
    assert.equal(minuteLimited.status, 429);
    assert.equal(minuteLimited.body.error.code, "RATE_LIMITED");
    assert.equal(minuteLimited.headers["retry-after"], "60");

    clock.advance(60_000);
    assert.equal((await send()).status, 200);
    const dayLimited = await send();
    assert.equal(dayLimited.status, 429);
    assert.equal(dayLimited.body.error.code, "RATE_LIMITED");
    assert.equal(dayLimited.headers["retry-after"], "60");

    // 00:00 UTC the next day: both windows are fresh.
    clock.advance(60_000);
    assert.equal((await send()).status, 200);
    assert.equal((await send()).status, 200);
  });
});

test("rate limits are keyed by subject, not by the raw header", async () => {
  await withTempDir(async (folder) => {
    const service = createService(folder, {
      env: { GATEWAY_REQUESTS_PER_MINUTE: "1" },
      tokenVerifier: new MapTokenVerifier({
        "token-one-000000000000": "user:shared",
        "token-two-000000000000": "user:shared",
        "token-other-0000000000": "user:other",
      }),
    });
    assert.equal((await service.generate({ authorization: "Bearer token-one-000000000000", body: requestBody("r-000001") })).status, 200);
    assert.equal((await service.generate({ authorization: "Bearer token-two-000000000000", body: requestBody("r-000002") })).status, 429);
    assert.equal((await service.generate({ authorization: "Bearer token-other-0000000000", body: requestBody("r-000003") })).status, 200);
  });
});

test("expired identities from a custom verifier are rejected", async () => {
  await withTempDir(async (folder) => {
    const clock = createClock();
    const service = createService(folder, {
      now: clock.now,
      tokenVerifier: new MapTokenVerifier({
        "short-lived-token-0000": { subject: "user:a", expiresAt: clock.value + 1000 },
      }),
    });
    const auth = "Bearer short-lived-token-0000";
    assert.equal((await service.generate({ authorization: auth, body: requestBody("exp-0001") })).status, 200);
    clock.advance(1000);
    assert.equal((await service.generate({ authorization: auth, body: requestBody("exp-0002") })).status, 401);
  });
});

test("device auth failures are throttled per address until the window passes", async () => {
  await withTempDir(async (folder) => {
    const clock = createClock();
    const service = createService(folder, { now: clock.now });
    const { maxFailures, windowMs } = AUTH_FAILURE_LIMITS.device;
    assert.equal(maxFailures, 10);
    assert.equal(windowMs, 5 * 60_000);
    for (let attempt = 0; attempt < maxFailures; attempt += 1) {
      const result = await service.generate({
        authorization: "Bearer wrong-token-000000000000",
        body: requestBody(`bad-${attempt}-000`),
        remoteAddress: "198.51.100.7",
      });
      assert.equal(result.status, 401);
    }
    const throttled = await service.generate({
      authorization: DEVICE_AUTH,
      body: requestBody("good-request-1"),
      remoteAddress: "198.51.100.7",
    });
    assert.equal(throttled.status, 429);
    assert.equal(throttled.body.error.code, "AUTH_THROTTLED");
    assert.equal(throttled.headers["retry-after"], "300");

    const otherAddress = await service.generate({
      authorization: DEVICE_AUTH,
      body: requestBody("good-request-2"),
      remoteAddress: "198.51.100.8",
    });
    assert.equal(otherAddress.status, 200);

    clock.advance(windowMs);
    const afterWindow = await service.generate({
      authorization: DEVICE_AUTH,
      body: requestBody("good-request-3"),
      remoteAddress: "198.51.100.7",
    });
    assert.equal(afterWindow.status, 200);
  });
});

test("successful auth does not reset the failure counter", async () => {
  await withTempDir(async (folder) => {
    const service = createService(folder);
    const address = { remoteAddress: "198.51.100.9" };
    for (let attempt = 0; attempt < 4; attempt += 1) {
      assert.equal((await service.adminStatus("Bearer wrong-admin-token-0000", address)).status, 401);
    }
    assert.equal((await service.adminStatus(ADMIN_AUTH, address)).status, 200);
    assert.equal((await service.adminStatus("Bearer wrong-admin-token-0000", address)).status, 401);
    assert.equal((await service.adminStatus(ADMIN_AUTH, address)).status, 429);
  });
});

test("admin auth: 401, throttling after 5 failures / 15 min, 503 when unconfigured", async () => {
  await withTempDir(async (folder) => {
    const clock = createClock();
    const service = createService(folder, { now: clock.now });
    const { maxFailures, windowMs } = AUTH_FAILURE_LIMITS.admin;
    assert.equal(maxFailures, 5);
    assert.equal(windowMs, 15 * 60_000);
    const address = { remoteAddress: "203.0.113.4" };

    const missing = await service.adminProfiles(undefined, address);
    assert.equal(missing.status, 401);
    assert.equal(missing.body.error.code, "UNAUTHORIZED");
    for (let attempt = 1; attempt < maxFailures; attempt += 1) {
      assert.equal((await service.updateAdminProfiles("Bearer nope", defaultProfileDocument(), address)).status, 401);
    }
    const throttled = await service.adminStatus(ADMIN_AUTH, address);
    assert.equal(throttled.status, 429);
    assert.equal(throttled.body.error.code, "AUTH_THROTTLED");
    // Device route has its own budget for the same address.
    assert.equal((await service.generate({ authorization: DEVICE_AUTH, body: requestBody("dev-0001"), ...address })).status, 200);

    clock.advance(windowMs);
    assert.equal((await service.adminStatus(ADMIN_AUTH, address)).status, 200);

    const unconfigured = createService(folder, { env: { GATEWAY_ADMIN_TOKEN: "" } });
    for (let attempt = 0; attempt < maxFailures + 2; attempt += 1) {
      const result = await unconfigured.adminStatus(ADMIN_AUTH, address);
      assert.equal(result.status, 503);
      assert.equal(result.body.error.code, "ADMIN_AUTH_NOT_CONFIGURED");
    }
  });
});

test("admin status exposes server-side default profiles and safety terms version", async () => {
  await withTempDir(async (folder) => {
    const service = createService(folder);
    const status = await service.adminStatus(ADMIN_AUTH);
    assert.equal(status.status, 200);
    assert.deepEqual(status.body.default_profiles, defaultProfileDocument().profiles);
    assert.equal(status.body.safety_terms_version, getSafetyTerms().version);
    assert.doesNotMatch(JSON.stringify(status.body), /provider-secret|session-token/);
  });
});

test("provider failures map to 504 / 429 / 502", async () => {
  await withTempDir(async (folder) => {
    const throwing = createService(folder, {
      fetchImpl: async () => { throw new Error("network down"); },
    });
    const thrown = await throwing.generate({ authorization: DEVICE_AUTH, body: requestBody("prov-0001") });
    assert.equal(thrown.status, 504);
    assert.equal(thrown.body.error.code, "PROVIDER_UNAVAILABLE");

    const hanging = createService(folder, {
      providerTimeoutMs: 20,
      fetchImpl: (url, options) => new Promise((resolveFetch, rejectFetch) => {
        options.signal.addEventListener("abort", () => rejectFetch(new Error("aborted")));
      }),
    });
    const timedOut = await hanging.generate({ authorization: DEVICE_AUTH, body: requestBody("prov-0002") });
    assert.equal(timedOut.status, 504);
    assert.equal(timedOut.body.error.code, "PROVIDER_UNAVAILABLE");

    const limited = createService(folder, { fetchImpl: fakeFetch(() => providerReply([], 429)).fetchImpl });
    const limitedResult = await limited.generate({ authorization: DEVICE_AUTH, body: requestBody("prov-0003") });
    assert.equal(limitedResult.status, 429);
    assert.equal(limitedResult.body.error.code, "PROVIDER_REJECTED");

    const failing = createService(folder, { fetchImpl: fakeFetch(() => providerReply([], 500)).fetchImpl });
    assert.equal((await failing.generate({ authorization: DEVICE_AUTH, body: requestBody("prov-0004") })).status, 502);
  });
});

test("review context is answered with context_review labels; unsafe candidates are dropped", async () => {
  await withTempDir(async (folder) => {
    const provider = fakeFetch(() => providerReply([
      { text: "这场动作戏的剪辑节奏很好，镜头调度也很稳。", evidence_label: "剪辑" },
      { text: "想要完整片源可以加微信，我发给你。", evidence_label: "片源" },
      { text: "这种反诈骗的提醒也该多拍一些。", evidence_label: "提醒" },
    ]));
    const service = createService(folder, { fetchImpl: provider.fetchImpl });
    const result = await service.generate({
      authorization: DEVICE_AUTH,
      body: requestBody("review-0001", {
        video_context: { source: "accessibility_text", text: "这部电影的动作戏有点暴力，但剪辑节奏很好" },
      }),
    });
    assert.equal(result.status, 200);
    assert.equal(result.body.context_review, true);
    assert.deepEqual(result.body.candidates.map((candidate) => candidate.evidence_label), ["剪辑", "提醒"]);
    for (const candidate of result.body.candidates) {
      assert.deepEqual(candidate.safety_labels, ["context_review"]);
    }

    const blocked = await service.generate({
      authorization: DEVICE_AUTH,
      body: requestBody("review-0002", {
        video_context: { source: "accessibility_text", text: "主播在直播间里推荐赌博网站给观众" },
      }),
    });
    assert.equal(blocked.status, 422);
    assert.equal(blocked.body.error.code, "UNSAFE_CONTEXT");
    assert.equal(provider.calls.length, 1);
  });
});
