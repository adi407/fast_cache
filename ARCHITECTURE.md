# FastCache — Architecture & Engineering Notes

The design record: how each subsystem works, which failure mode it exists to prevent, and what it costs.
For installation and a five-minute tour, see [README.md](README.md).

Every claim here is backed by a test or a measurement taken on the machine noted in §2.

---

## 1. Architecture

```
┌──────────────────────────┐        ┌──────────────────────────────────────────────┐
│  Python AI process       │        │  JVM (embedded, or sidecar process)          │
│                          │        │                                              │
│  @fastcache decorator    │        │   FastCacheServer                            │
│        │                 │        │     └─ 1 virtual thread per connection       │
│        ▼                 │        │           │                                  │
│  L1 HotKeyCache ──hit──► │        │           ▼                                  │
│        │ miss            │        │   ShardedStorageEngine                       │
│        ▼                 │        │     ├─ Shard 0  ─┐                           │
│  SingleFlight (local)    │        │     ├─ Shard 1   │  ConcurrentHashMap        │
│        │                 │        │     ├─ ...       │  + StampedLock            │
│        ▼                 │        │     └─ Shard 31 ─┘  → CacheEntry (record)    │
│  Codec (zstd/msgpack/    │        │                         └─ off-heap slot     │
│         numpy raw)       │        │   RefreshCoordinator  (host-wide single      │
│        │                 │        │                        flight)               │
│        ▼                 │        │   MemoryGuard · EvictionSweeper (vthreads)   │
│  Connection pool ────────┼─TCP───►│   OrphanWatchdog · SavingsLedger             │
│  Heartbeat (10s)  ───────┼───────►│                                              │
└──────────────────────────┘        │   MetricsServer ──► :8081/dashboard          │
                                    └──────────────────────────────────────────────┘
        ▲
        └── @FastCache (Spring AOP) stores live object references, guarded against mutation
```

### Module layout

| Path | What it is |
|---|---|
| `fastcache-engine/` | Storage engine, NIO sidecar server, console. **Zero runtime dependencies.** |
| `fastcache-spring-boot-starter/` | `@FastCache`, the aspect, mutation guard, auto-configuration |
| `python/` | `@fastcache` decorator, L1 cache, codec, process autopilot, heartbeat |

### Key design decisions

**Off-heap payloads.** A 50 MB context window in a `byte[]` is a GC problem: it is scanned as a root, copied
on evacuation, and its size pressures the collector into exactly the stop-the-world pauses a latency-
sensitive serving path cannot absorb. FastCache stores payloads in direct `ByteBuffer`s, invisible to the
GC, and releases them **deterministically** via `Unsafe.invokeCleaner` rather than waiting for a `Cleaner`
that may never run.

**Reference counting, not "hope".** Freeing a direct buffer another thread is reading is a SIGSEGV, not an
exception. Each payload carries a refcount: the map entry holds one, every reader `Lease` takes another,
and the slot is unmapped only at zero. This is what makes eviction safe to run concurrently with reads.

**No `synchronized`, anywhere.** Every path may run on a virtual thread; a virtual thread that parks inside
a monitor pins its carrier. Locks are `StampedLock`/`ReentrantLock` throughout.

**Zero-copy both directions.** A PUT is read from the socket *straight into* the off-heap slot that will
hold it. A GET is written from that slot *straight to* the socket via a gathering write.

---

## 2. Build and run

```bash
mvn -q package                       # builds both Java modules + fastcache-engine.jar
cp fastcache-engine/target/fastcache-engine.jar python/fastcache_ai/_bin/
cd python && pip install -e ".[dev]" && pytest -q
```

### Verified on this machine

| Check | Result |
|---|---|
| Engine under 5,000 concurrent virtual threads | no leaks — 0 native slots outstanding at close |
| 20 MB payload, Python → Java → Python | put 36 ms, get 33 ms, byte-identical |
| JVM cold boot triggered by first cache call | 1.85 s, fully automatic |
| L1 hot-key absorption | 500 reads of one key → 8 network round trips |
| Memory guard at a 64 MiB budget | rejects at 87.5%, no OOM, reads keep serving, recovers |
| **Shard routing (murmur3)** | **1.085–1.219x max/mean vs 1.56–2.22x before; ideal floor is 1.120x** |
| **Stampede, Spring path** | **500 concurrent callers → exactly 1 recomputation, 499 served stale** |
| **Stampede, Python path** | **200 concurrent callers → exactly 1 recomputation** |
| **Orphan isolation** | **`kill -9` the parent → JVM exits and releases all memory** |
| **Mutation guard** | **7/7: records by reference, mutables deep-copied, unsafe types refused** |
| **Console** | **$214.60 saved of $216.01 projected on a 12,436-request demo workload** |
| Python test suite | 70 passed |

---

## 3. Configuration

Everything has a production-sane default. This table is for the day you need to change one.

| Setting | Java property / Spring | Python env | Default |
|---|---|---|---|
| Shard count | `fastcache.shards` | `FASTCACHE_SHARDS` | 32 |
| Off-heap budget | `fastcache.off-heap-max` | `FASTCACHE_OFFHEAP_MAX` | 512 MB / 2 GB |
| Reject ratio | `fastcache.reject-ratio` | `FASTCACHE_REJECT_RATIO` | 0.85 |
| Default TTL | `fastcache.default-ttl` | `FASTCACHE_DEFAULT_TTL` | `15m` |
| Max value size | `fastcache.max-value-size` | `FASTCACHE_VALUE_MAX_BYTES` | 64 MiB |
| Hash spreading (murmur3) | `fastcache.hash-spreading` | `FASTCACHE_HASH_SPREADING` | **true** |
| Stale-while-revalidate window | `fastcache.stale-grace` | `FASTCACHE_STALE_GRACE` | `2s` |
| Refresh lease | `fastcache.refresh-lease` | `FASTCACHE_REFRESH_LEASE` | `10s` |
| Mutation guard | `fastcache.mutation-guard` | — (Spring only) | `AUTO` |
| Orphan heartbeat window | `--heartbeat-timeout` | `FASTCACHE_HEARTBEAT_TIMEOUT` | `40s` |
| Heartbeat send interval | — | `FASTCACHE_HEARTBEAT_INTERVAL` | `10s` |
| Console port | `fastcache.server.*` | `FASTCACHE_METRICS_PORT` | `8081` (`off` disables) |
| Cost model | `--cost-model` | `FASTCACHE_COST_MODEL` | `gpt-4o` |
| Sidecar idle timeout | `--idle-timeout` | `FASTCACHE_IDLE_TIMEOUT` | `30m` |
| L1 threshold / TTL | — | `FASTCACHE_L1_THRESHOLD` / `_TTL` | 8 per sec / 5 s |
| Kill sidecar at exit | — | `FASTCACHE_EPHEMERAL` | off |

---

## 4. The management console

```python
import fastcache
fastcache.dashboard(open_browser=True)      # http://127.0.0.1:8081/dashboard
```

| Endpoint | Purpose |
|---|---|
| `/dashboard` | Single-page console: memory bars, live counters, ticking savings figure |
| `/metrics` | The same data as JSON, for scraping |
| `/health` | Liveness, for supervisors |

Both data endpoints take `?model=gpt-4o`, `?model=claude-3-5-sonnet`, or `?model=<name>:<usd-per-million>`
to re-price the same traffic without restarting anything.

**Footprint.** `com.sun.net.httpserver` from the JDK, one 13.9 KB HTML file, no CDN, no build step, no
framework. Tomcat would add ~9 MB of jars; Spring Boot Web forty more. The page is served fully populated
with its data inlined, so it is readable with JavaScript disabled and has no loading flash.

**The savings model, stated honestly.** Every cached value was produced by a call that cost money.
Characters written to the cache are cost *incurred*; characters read back — including reads the Python L1
answered locally and reported on its heartbeat — are cost *avoided*. Tokens are estimated at 4 characters
each and priced at the model's published input rate.

```
projected = (characters_stored + characters_avoided) / 4 × rate     # no cache at all
actual    =  characters_stored                        / 4 × rate     # what you really paid
saved     = projected − actual
```

It is an estimate and the console says so: 4 chars/token is a rule of thumb rather than a BPE tokenizer
(expect ±15% on English prose, more on code or CJK), and output tokens are not counted at all. It is built
for "is this cache earning its keep", not for invoicing.

---

## 5. Architectural edge cases and their mitigations

Each item names the file that implements it.

### 5.1 Shard skew — murmur3 as the default router
`core/ShardedStorageEngine.java`

`String.hashCode` avalanches poorly, so keys differing only in a numeric suffix cluster badly under a plain
modulo. Routing now applies the murmur3 32-bit finalizer (`fmix32`) before the modulo. Measured over
10,000 keys across 32 shards (max shard ÷ mean shard, 1.00 = perfect):

| key pattern | raw `hashCode` | **murmur3** | ideal uniform hash |
|---|---|---|---|
| `spread:N` | 2.086 | **1.094** | 1.120 |
| `prompt-N-v2` | 2.224 | **1.219** | 1.120 |
| `session:N` | 1.978 | **1.133** | 1.120 |
| `user_N_ctx` | 1.562 | **1.085** | 1.120 |

- **The target is met, and the metric has a floor.** Max/mean skew is sample-size dependent: filling 32
  bins from *n* keys is a multinomial draw, so even a *perfect* hash averages 1.33x at n=2,000, 1.12x at
  n=10,000 and 1.01x at n=1,000,000. Murmur3 now sits at that floor — `spread:N` measures *below* the ideal
  average and `prompt-N-v2` above it, which is single-draw noise, not bias. No further hashing work can
  improve this; the remaining imbalance is arithmetic, not hash quality.
- **The cheap fix does not work.** `h ^ (h >>> 16)`, the spread `ConcurrentHashMap` uses, measures 2.26 on
  the first pattern — indistinguishable from doing nothing. One xor-shift does not avalanche a structured
  `String.hashCode`; the full four-step finalizer does.
- **`Math.abs` is safe** even for `Integer.MIN_VALUE`: the remainder is taken first and `x % n` always
  lands in `(-n, n)`. Writing `Math.abs(key.hashCode()) % shardCount` instead would be a real bug.

### 5.2 Cache stampede (thundering herd)
`core/RefreshCoordinator.java`, `core/CacheEntry.java`, `python/fastcache_ai/singleflight.py`

When a 50 MB context window expires under a thousand concurrent readers, the naive outcome is a thousand
simultaneous misses hitting the LLM pipeline — the cache converting steady load into a synchronised spike,
which is worse than having no cache. Two mechanisms, together:

1. **Stale-while-revalidate.** An expired entry stays *servable* for `staleGrace` (2s). Readers get the
   slightly-stale value instantly instead of queueing behind a cold recompute.
2. **Single-flight.** Exactly one caller wins a refresh lease and recomputes. Measured: **500 concurrent
   Spring callers → 1 recomputation, 499 served stale**; **200 concurrent Python callers → 1 recomputation**.

- **Two levels, because they solve different halves.** A process-local gate collapses a thousand threads to
  one before any socket is touched; the engine's `RefreshCoordinator` then collapses eight gunicorn workers
  to one across the host. Local-only would let eight backend calls through; engine-only would cost a
  thousand pointless round trips to discover that 999 threads have nothing to do.
- **Leases expire, locks do not.** The winner can crash, hang on a slow model call, or be SIGKILLed. A
  plain lock would block the key permanently; a lease means a stuck leader costs one lease period.
- **Nothing blocks forever.** Every wait is bounded by the grace window, and a follower whose leader never
  publishes computes the value itself. A stampede defence that can deadlock a request path is worse than
  the stampede.
- **A failed refresh serves stale rather than raising.** The caller had a usable answer moments ago; a
  transient backend blip should not become a user-visible error. On a *cold* miss the exception propagates
  normally — there is nothing to fall back to.
- **Waiters park on a `CountDownLatch`**, which unmounts a virtual thread. A thousand threads parked inside
  monitors waiting on a slow LLM call would pin a thousand carriers.
- **Followers poll, they do not hold a socket.** Blocking cross-process would tie up a connection for the
  duration of someone else's model call and make a hung leader indistinguishable from a slow one.

### 5.3 Orphan isolation (heartbeat / poison pill)
`net/OrphanWatchdog.java`, `python/fastcache_ai/client.py`

A training script boots the sidecar, fills it with 4 GB of embeddings, and is `kill -9`'d. No atexit hook
runs, no shutdown handler fires, and the JVM sits on 4 GB until reboot. Two independent signals:

1. **Heartbeat window (40s).** Clients beat every 10s from a daemon thread; any protocol traffic counts
   too. Silence past the window → terminal alert on stderr, then `System.exit(0)`.
2. **Parent liveness.** `--parent-pid` turns a SIGKILL into sub-second detection via
   `ProcessHandle.isAlive()`.

- **The parent is not the only client.** A dead parent PID reaps the engine **only when no other connection
  is live**. In a gunicorn or Celery pool, worker 1 starts the engine and the others attach; worker 1
  restarting is routine, and reaping on its PID alone would delete the warm cache out from under seven
  healthy workers — turning a feature meant to prevent waste into a recurring outage.
- **Opt-in, for the same reason.** A sidecar embedded in Spring, or started by hand to be shared, has no
  heartbeating parent. Arming this by default would make such a deployment shoot itself 40 seconds after a
  perfectly healthy startup.
- **`exit(0)`, not `halt`.** Exiting runs the shutdown hook, which releases every native slot and removes
  the discovery file. The code is 0 because an orphan reaping itself is correct behaviour — non-zero would
  make a supervisor restart the very JVM being retired.
- **Trade-off, stated plainly.** With the watchdog armed, an engine nobody is talking to is reclaimed
  within 40 seconds, so a *warm cache between separate runs* is no longer the default.
  `FASTCACHE_HEARTBEAT_TIMEOUT=0` restores it (that one switch also suppresses the parent-PID watch, and
  the 30-minute idle timeout still bounds the process).
- **Telemetry is flushed at exit.** L1 hits never cross the wire, so a script shorter than one heartbeat
  interval would contribute nothing to the console. An `atexit` hook sends a final beat, registered after
  the bootstrap hook so LIFO ordering runs it *before* the sidecar can be terminated.

### 5.4 Payload mutation guard
`spring/MutationGuard.java`

The Spring path's whole performance argument is caching live object references. The cost of that argument
is aliasing: `docs.add(x)` on a cached `List` silently edits what every caller in the JVM sees for the next
fifteen minutes. No exception, no log line, no failing test. Each value type is classified once:

| Verdict | Types | Behaviour |
|---|---|---|
| **Immutable** | records with immutable components, `String`, boxed primitives, enums, `UUID`, `BigDecimal`, `java.time`, `List.of(...)` | Stored and served **by reference** — zero cost |
| **Copyable** | mutable but `Serializable` | Deep-copied in **and** out |
| **Unsafe** | mutable, not serializable | **Not cached**; type named once in a warning |

- **A record is not automatically safe.** `record Page(String title, List<Item> items)` is mutable if
  `items` is an `ArrayList`. Classification recurses through components; treating "it's a record" as proof
  of immutability would reintroduce the entire bug class through the back door. Verified.
- **Copy on write *and* on read.** Copying only on read leaves the producer holding a live reference to the
  cached object. Copying only on write hands every reader the cache's own instance. Value semantics need
  both.
- **Serialization, not `clone()`.** `clone()` is shallow, so a cloned `ArrayList` still shares its elements
  and offers no protection at all. Serialization is slow — which is exactly why the default policy avoids
  it for the types people *should* be caching, and says so loudly for the types they should not.
- **Refusing to cache is the honest option** for unsafe types. Caching them would hand out an aliased
  mutable object, which is the bug. The method still works; it just runs uncached.
- **A copy failure on read never fails the call.** It falls back to the shared instance: degraded, but the
  request succeeds.

### 5.5 Cache eviction (TTL + LRU) on background virtual threads
`core/EvictionSweeper.java`, `core/Shard.java`

One supervisor virtual thread fans out across a `newVirtualThreadPerTaskExecutor`, one task per shard.
Each pass purges expired entries, sheds the coldest from over-capacity shards, then (under memory pressure)
sheds a further 10% slice so the engine digs itself out instead of waiting for TTLs.

- **The sweeper honours the grace window.** It reclaims on `isServable`, not `isExpired` — reclaiming an
  entry still inside its grace window would re-open the thundering herd that window exists to close.
- **Stale reads during a slow sweep** are impossible: the read path expires lazily too.
- **LRU selection** uses a bounded max-heap (O(n log k)); a full sort per pass would dominate the budget.
- **Removal must not be O(payload).** `CacheEntry` overrides `equals` to identity, because generated record
  equality would compare `ByteBuffer`s — a 50 MB `memcmp` per eviction.
- **A sweeper that dies leaks the whole cache**, so the loop catches `Throwable` and continues.

### 5.6 Thread pinning during socket read/write cycles
`net/ClientSession.java`, `net/FastCacheServer.java`

Blocking `SocketChannel` calls on a virtual thread do **not** block an OS thread: the JDK registers the
socket with the platform poller and unmounts the continuation. Reactor scalability, straight-line code.

- **No `synchronized` on any I/O path.** A virtual thread parked on a socket read inside a monitor pins its
  carrier for the whole round trip — seconds under token-streaming load.
- **No buffer pooling.** With a hundred thousand sessions, pool contention costs more than the allocation.
- **`ConcurrentHashMap`'s bin monitors** are safe here because no remapping function ever blocks.

### 5.7 Dynamic memory boundary protection
`memory/MemoryGuard.java`, `memory/PhysicalMemory.java`

Admission control reserves budget *before* allocating; crossing the line returns
`REJECTED_MEMORY_PRESSURE` — a status code, never an exception, never an OOM. Hysteresis (reject 85%,
recover 78%) stops oscillation.

- **Machine pressure alone is not a reason to shed.** "Used physical memory" sits at 80–90% on any ordinary
  host because the OS counts reclaimable file-cache pages as used. Gating unconditionally on it means an
  engine holding 5 MB refuses every write on a normal laptop — freeing nothing, while silently reducing the
  cache to a 0% hit rate behind healthy-looking stats. The physical gate engages only once this engine's
  own reservation is material (≥ max(64 MiB, 5% of budget)).
- **On-heap references are not charged against the native budget** at all; they allocate no native memory.
  They are bounded by the per-shard entry cap and the GC.
- **Allocation can still fail after admission passes.** `beginWrite` catches `OutOfMemoryError`, refunds the
  reservation and rejects cleanly. This is the true last line of defence.
- **Rejection must not desynchronise the protocol.** A rejected PUT's payload is drained so the connection
  survives — but only up to 2× the accepted maximum, past which reading gigabytes to discard them would be
  free work for an abusive client.
- **One source of truth.** The guard and the console read the same `PhysicalMemory` numbers; a dashboard
  showing 40% while writes are shed at 85% is worse than no dashboard.

### 5.8 Celebrity keys (client-side L1)
`python/fastcache_ai/l1.py`

Sharding routes a key to exactly one shard, so ten thousand users sending the same system prompt all land
on one shard. Adding shards cannot help. The client counts per-key frequency and, past a threshold, serves
that key from Python memory for 5 seconds. Measured: 500 reads → 8 round trips.

- **Only fresh values are promoted.** Promoting a stale one would pin a value already due for refresh into
  local memory for another full L1 TTL, compounding staleness.
- **Local writes invalidate locally**, so a process never reads its own stale value. Other processes keep
  their copy until it lapses — L1 is eventually consistent by construction.
- **The character count is computed once at promotion**, not per hit; a hot key is read thousands of times
  per promotion and re-walking a nested response each time would make L1 slower than the socket.

### 5.9 Process lifecycle
`python/fastcache_ai/bootstrap.py`, `net/SidecarMain.java`

Import → explicit address, then a live sidecar named by `~/.fastcache/sidecar.json`, then boot one and
block on its stdout handshake.

- **Concurrent interpreters racing to spawn** are serialised by an OS file lock with a bounded wait.
- **Liveness is a real PING frame**, not a TCP connect.
- **The discovery file is written atomically** (temp + `ATOMIC_MOVE`).
- **Unread pipes block the JVM**, so a background thread drains sidecar stdout to a log file.
- **A busy console port never stops the cache.** `startConsole` catches the bind failure and continues —
  verified accidentally during development when a stale sidecar held 8081.
- **Import never blocks and never raises.**

### 5.10 Failure policy

Both integrations fail open: a dead sidecar, a rejected write or an unserializable value results in the
wrapped function being called normally.

- **Exceptions are never cached.**
- **Negative caching needs a sentinel**, since `null` is otherwise indistinguishable from a miss.
- **Back-pressure logs at debug**, not warn — a warn per call under sustained pressure becomes its own
  bottleneck.
- **The console is strictly read-only**: every handler rejects non-GET with 405.

### 5.11 Known trade-offs

- **`@FastCache` returns copies for mutable types.** That is the mutation guard doing its job, and it costs
  a serialization round trip. Cache records and the reference path stays free. Set
  `fastcache.mutation-guard: OFF` to opt out entirely.
- **Stale reads are possible by design**, bounded by `stale-grace` (2s) in the engine and the L1 TTL (5s)
  in the client. Set both to zero where staleness is a correctness problem.
- **Protocol v2 is not wire-compatible with v1.** The request header grew 4 bytes for the character count.
  Client and engine ship in one artifact, so a mismatched pair fails fast on the magic check rather than
  silently misreading frames.
- **The savings figure is an estimate**, not an invoice. See §4.
- **SpEL parameter names need `-parameters`.** Where it is missing, the aspect disables caching for that
  method and logs why, rather than collapsing every call onto one shared key.
- **The protocol and the console are unauthenticated** and bind to loopback only. Do not expose either.
- **Pickle is a fallback codec.** It only decodes bytes this process put into its own loopback sidecar.
