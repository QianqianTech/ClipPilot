/**
 * StateStore contract. Every method is async so a shared backend (Redis,
 * Memcached, a database) can implement it without changing the service.
 *
 *   incrementWindow(key, windowMs, now) -> Promise<{ count, resetAt }>
 *     Fixed window counter. The first increment opens the window
 *     [now, now + windowMs); later increments inside it add 1 atomically.
 *     Once now >= resetAt the key starts over at count 1.
 *     Redis: INCR key; PEXPIRE key windowMs NX; PTTL key (in one MULTI/Lua).
 *
 *   getWindow(key, now) -> Promise<{ count, resetAt } | null>
 *     Read-only view of a live window, null when absent or expired.
 *
 *   getCached(key, now) -> Promise<value | null>
 *   setCached(key, value, ttlMs, now) -> Promise<void>
 *     Short-lived cache for JSON-serializable values (idempotent responses).
 *     Redis: GET key / SET key <json> PX ttlMs.
 *
 * `now` is passed in (instead of read from a clock) so tests and the service
 * share one time source; a remote backend may rely on its own TTLs instead.
 */

export const DEFAULT_MAX_WINDOW_ENTRIES = 10_000;
export const DEFAULT_MAX_CACHED_ENTRIES = 1_000;

function purgeExpired(map, now) {
  for (const [key, entry] of map) {
    if (entry.resetAt <= now) {
      map.delete(key);
    }
  }
}

function makeRoom(map, maximum, now) {
  if (map.size < maximum) {
    return;
  }
  purgeExpired(map, now);
  // Map iteration follows insertion order, so the first keys are the oldest.
  for (const key of map.keys()) {
    if (map.size < maximum) {
      break;
    }
    map.delete(key);
  }
}

/**
 * Process-local default. State is lost on restart and is NOT shared between
 * instances; it is only correct for a single gateway process.
 */
export class MemoryStateStore {
  constructor({
    maxWindowEntries = DEFAULT_MAX_WINDOW_ENTRIES,
    maxCachedEntries = DEFAULT_MAX_CACHED_ENTRIES,
  } = {}) {
    this.maxWindowEntries = maxWindowEntries;
    this.maxCachedEntries = maxCachedEntries;
    this.windows = new Map();
    this.cache = new Map();
  }

  async incrementWindow(key, windowMs, now) {
    const current = this.windows.get(key);
    if (current && current.resetAt > now) {
      current.count += 1;
      return { count: current.count, resetAt: current.resetAt };
    }
    this.windows.delete(key);
    makeRoom(this.windows, this.maxWindowEntries, now);
    const entry = { count: 1, resetAt: now + Math.max(1, windowMs) };
    this.windows.set(key, entry);
    return { count: entry.count, resetAt: entry.resetAt };
  }

  async getWindow(key, now) {
    const current = this.windows.get(key);
    if (!current) {
      return null;
    }
    if (current.resetAt <= now) {
      this.windows.delete(key);
      return null;
    }
    return { count: current.count, resetAt: current.resetAt };
  }

  async getCached(key, now) {
    const entry = this.cache.get(key);
    if (!entry) {
      return null;
    }
    if (entry.resetAt <= now) {
      this.cache.delete(key);
      return null;
    }
    return JSON.parse(entry.json);
  }

  async setCached(key, value, ttlMs, now) {
    // Serialize like a remote store would, so callers never share mutable state.
    const json = JSON.stringify(value);
    this.cache.delete(key);
    makeRoom(this.cache, this.maxCachedEntries, now);
    this.cache.set(key, { json, resetAt: now + Math.max(1, ttlMs) });
  }
}
