import assert from "node:assert/strict";
import test from "node:test";

import { MemoryStateStore } from "../src/state-store.mjs";
import { EnvTokenVerifier } from "../src/token-verifier.mjs";

test("memory state store: fixed windows count, expire and roll over", async () => {
  const store = new MemoryStateStore();
  assert.deepEqual(await store.incrementWindow("k", 1000, 0), { count: 1, resetAt: 1000 });
  assert.deepEqual(await store.incrementWindow("k", 1000, 500), { count: 2, resetAt: 1000 });
  assert.deepEqual(await store.getWindow("k", 999), { count: 2, resetAt: 1000 });
  assert.equal(await store.getWindow("k", 1000), null);
  assert.deepEqual(await store.incrementWindow("k", 1000, 1200), { count: 1, resetAt: 2200 });
});

test("memory state store: window entries are bounded", async () => {
  const store = new MemoryStateStore({ maxWindowEntries: 3 });
  for (let index = 0; index < 10; index += 1) {
    await store.incrementWindow(`k${index}`, 60_000, 0);
  }
  assert.equal(store.windows.size, 3);
  assert.equal(await store.getWindow("k0", 1), null);
  assert.equal((await store.getWindow("k9", 1)).count, 1);
});

test("memory state store: cache honours TTL, copies values and caps at 1000 entries", async () => {
  const store = new MemoryStateStore();
  const value = { status: 200, body: { nested: ["a"] } };
  await store.setCached("first", value, 10_000, 0);
  value.body.nested.push("mutated");
  assert.deepEqual(await store.getCached("first", 9_999), { status: 200, body: { nested: ["a"] } });
  assert.equal(await store.getCached("first", 10_000), null);

  for (let index = 0; index <= 1000; index += 1) {
    await store.setCached(`id-${index}`, { index }, 600_000, 1);
  }
  assert.equal(store.cache.size, 1000);
  assert.equal(await store.getCached("id-0", 2), null);
  assert.deepEqual(await store.getCached("id-1", 2), { index: 1 });
  assert.deepEqual(await store.getCached("id-1000", 2), { index: 1000 });
});

test("env token verifier: constant-time match, min length, hashed subject", async () => {
  const verifier = new EnvTokenVerifier({ token: "device-session-token-0123456789" });
  assert.equal(verifier.isConfigured(), true);
  const identity = await verifier.verify("Bearer device-session-token-0123456789");
  assert.match(identity.subject, /^env:[0-9a-f]{12}$/);
  assert.equal(identity.expiresAt, null);
  assert.doesNotMatch(identity.subject, /device-session/);
  assert.equal(await verifier.verify("Bearer device-session-token-012345678"), null);
  assert.equal(await verifier.verify("device-session-token-0123456789"), null);
  assert.equal(await verifier.verify(undefined), null);

  const short = new EnvTokenVerifier({ token: "too-short" });
  assert.equal(short.isConfigured(), false);
  assert.equal(await short.verify("Bearer too-short"), null);
});
