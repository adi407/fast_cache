# FastCache V1 — scope

Rationale, evidence and design detail: [`v1-architecture.md`](v1-architecture.md). This file is the list.

**V1 exists to expose exactly one validated capability:**

> JVM application → local FastCache sidecar → multi-megabyte values at high read bandwidth.

Measured: **2.981×** native Redis 7.0.15 at 10 MiB and 8 concurrent readers, end-to-end through a Spring
Boot service. 12/12 valid cells, zero corrupt reads, <7% run-to-run variance.

If a line item does not serve that sentence, it is not in V1.

---

## Build

### 1. MemoryGuard redesign — **blocker**

Every performance number on record was measured with admission control disabled. Shipped, the guard
honours **5.1%–6.3%** of a configured budget on an ordinary host, and one pressure pass can empty a
large-value cache.

- Budget is the only authority that refuses a write. Evict to fit; refuse only if eviction cannot free enough.
- Host pressure sheds and becomes an observable state. It never refuses.
- Read `available`, not `free`. Delete the `max(64 MiB, 5% of budget)` gate floor.
- Eviction targets **bytes**, globally, LRU across all shards. Delete `Math.max(1, size × 0.10)`.
- High/low watermarks (0.90 / 0.75 of budget) and a write reserve.

**Acceptance — V1 does not ship if these fail:**
1. A configured budget is honoured to within 10% on a host above the physical threshold.
2. A cache with fewer entries than shards loses ≤20% to one pressure pass.
3. A full cache evicts rather than refuses.

### 2. Java sidecar client

```java
byte[]  get(String key);            // null on miss
boolean exists(String key);
void    put(String key, byte[] value, Duration ttl);
void    evict(String key);
void    clear();
Stats   stats();
```

- Six methods. All map to opcodes that already exist.
- `byte[]` only — it is exactly what was measured.
- Connection pool, sockets 1:1 with in-flight operations (the protocol has no pipelining).
- **Size-aware deadlines.** A fixed timeout would abort legitimate 10 MiB reads.
- Full response-status mapping, circuit breaker, lazy reconnect with jittered backoff.
- Fail-open by default: read → miss, write → dropped and counted.
- **Never return a partial value.** A short read is a discarded connection and a miss.

### 3. Capacity model and metrics

Bytes are the primitive; entries are a diagnostic. `maxEntriesPerShard` demoted to a runaway guard at a
default that cannot bind.

Metrics: `bytesRead` / `bytesWritten` / `readBandwidth` (the primary ones — this product is measured in
bytes per second, not operations per second), hit / miss, p50 / p95 / p99, `rejectedWrites` by status,
evictions in bytes and count, **`shedding` state and duration**, resident / budget / available bytes,
`sidecarRss`, active connections and pool wait, `circuitOpen` / `reconnects`, `sidecarUptime`.

### 4. Protocol and server hardening

Oversized and malformed frame handling, connection limits, **refuse a non-loopback bind**, `0600` on the
discovery file and lock.

### 5. Java bootstrap

Ports the contract Python already uses. Three steps: explicit address → discovery file (`PING` before
trusting it) → spawn under an OS file lock and block on the `FASTCACHE_READY` handshake.

**`attach` is the default; `attach-or-spawn` is one property.** A Spring application is already a JVM and
should not supervise a second one in production. Under Docker and Kubernetes the sidecar is a container in
the same pod and `attach` to loopback is the entire integration.

### 6. Spring integration

- `FastCacheRemoteManager`, selected by `fastcache.mode = sidecar`.
- **Mutually exclusive with the embedded manager, and loud about it.**
- `@Cacheable`, `@CachePut`, `@CacheEvict` (key and `allEntries`).
- `@Cacheable(sync = true)` → **per-JVM coalescing only**, via a `ConcurrentHashMap` of in-flight loads.
- User supplies the codec. **V1 ships no default** — the cost of a real serializer at 10 MiB is unmeasured,
  and a default would be picked blind.

### 7. Packaging

One Maven dependency carrying a bundled engine JAR, plus a published Docker image of the same JAR.
Nothing else.

### 8. Documentation

Including, not optionally: the trust boundary (**any local process can read the whole cache**), the
payload-size guidance below, and the zero-copy layer table.

---

## Do not build

### Deferred — sequenced after V1

`get(key, ByteBuffer)` and a direct-buffer pool · streaming read to a channel · cross-process single-flight
(`OP_REFRESH_LEASE`) · Unix domain socket transport · automatic Caffeine L1 tiering · WebFlux/Netty
response path · metadata-only `exists` · async client API

### Not planned

Replication · persistence · failover · clustering · multi-host transport · Redis wire compatibility ·
bulk/`MGET` · range reads · shared-memory client transport · authentication and ACLs · GraalVM native
sidecar · pub/sub, streams, transactions, scripting · an "AI cache" product surface

### Marked NOT JUSTIFIED BY CURRENT EVIDENCE

- **Async API** — no async path was built or measured.
- **Automatic small-value bypass / L1 tiering** — no two-tier Java measurement exists.
- **Bulk operations** — no pipelining in the protocol, and at 10 MiB one value already saturates the link.
- **Range reads** — contradict whole-consumption, which defines the target workload.
- **Native executable** — `attach` mode already removes the start-up cost it would address.

---

## Payload-size policy

| Size | V1 | Evidence |
|---|---|---|
| < 1 MiB | Accept, **warn once per cache** | Redis faster; FastCache p99 1.94×–2.27× worse at 256 KiB |
| 1 MiB | Accept, no advantage claimed | Redis faster single-client; p99 1.09× worse at 8 clients |
| 5 MiB | Accept, **no claim either way** | Never measured |
| **10 MiB** | **The supported configuration** | **End-to-end validated, 2.981×** |
| 25 / 50 MiB | Accept; claims must say "microbenchmark" | Microbenchmark only |
| 100 MiB | **Reject by default** | Exceeds the 64 MiB default; never measured |

A single value may not exceed **25% of the budget** — admitting one would force evicting most of the cache.

---

## Effort

| Item | Days |
|---|---:|
| 1. MemoryGuard redesign | 8–12 |
| 2. Java sidecar client | 6–10 |
| 3. Capacity model + metrics | 3–5 |
| 4. Failure policy, breaker, reconnect | 4–6 |
| 5. Protocol/server hardening | 4–6 |
| 6. Java bootstrap | 5–8 |
| 7. Spring integration | 6–9 |
| 8. Packaging | 3–5 |
| 9. Documentation | 4–6 |
| 10. Real-workload validation | 5–8 |
| **Total** | **43–65** |

Roughly **9–13 weeks for one engineer**; **6–8 weeks for two**, since items 1 and 2 are independent and
both on the critical path.

---

## Before any of this starts

Two things are unchanged by the successful validation and should decide whether V1 is funded at all:

1. **The market is narrow.** One workload shape in eleven examined needs this bandwidth. A 2.981×
   advantage strengthens the case within that shape; it does not widen it.
2. **Two high-severity risks are unmeasured**, and both are one experiment each:
   - the advantage under **multi-process** fan-out (all concurrency measured so far was 8 threads in one
     JVM sharing a connection pool), and
   - what a **real serializer** costs at 10 MiB against a 39.6 ms saving.

Neither is scheduled. Neither should run before the funding decision.
