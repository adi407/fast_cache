<div align="center">

# FastCache

**A cache for AI workloads that needs no infrastructure.**
One annotation in Java, one decorator in Python. No Redis, no Docker, no connection string.

[![CI](https://github.com/adi407/fast_cache/actions/workflows/ci.yml/badge.svg)](https://github.com/adi407/fast_cache/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Python](https://img.shields.io/badge/Python-3.10%2B-blue.svg)](https://www.python.org/)
[![Tests](https://img.shields.io/badge/tests-195-brightgreen.svg)](#building-from-source)
[![Status](https://img.shields.io/badge/status-pre--release-yellow.svg)](#project-status)

</div>

---

## The problem

Caching an LLM response should be a one-line change. Instead it means standing up Redis, picking a
serialization format, and discovering that a 50 MB context window in a JVM heap causes GC pauses that cost
more than the cache saves.

FastCache is an in-memory, vertically sharded, **off-heap** key-value store with two front doors: a Spring
Boot annotation for JVM services, and a Python decorator that boots and manages its own engine process.
There is nothing to install, nothing to configure, and nothing to run.

```python
from fastcache_ai import fastcache

@fastcache(ttl="15m")
def embed(prompt: str) -> np.ndarray:
    return model.encode(prompt)
```

```java
@FastCache(ttl = "15m")
public EmbeddingMatrix embed(String prompt) { ... }
```

Both snippets are complete programs' worth of setup. The Python one starts a JVM sidecar on first use,
discovers it on subsequent runs, and reaps it if your process dies.

---

## Quickstart

### Python

```bash
pip install fastcache-ai          # not yet published — see Project status
```

```python
import fastcache_ai as fc
from fastcache_ai import fastcache

@fastcache(ttl="15m")
def summarize(document: str) -> str:
    return llm.complete(f"Summarize: {document}")

summarize(doc)          # miss — calls the model
summarize(doc)          # hit  — microseconds

fc.dashboard(open_browser=True)   # live memory, hit rate and cost console
```

> The import is `fastcache_ai`, not `fastcache`. The bare name on PyPI belongs to an
> [unrelated C `lru_cache`](https://github.com/pbrady/fastcache); sharing it would make the two packages
> overwrite each other on disk.

### Java / Spring Boot

```xml
<dependency>
  <groupId>io.github.adi407</groupId>
  <artifactId>fastcache-spring-boot-starter</artifactId>
  <version>1.1.0</version>
</dependency>
```

```java
@Service
public class SearchService {

    @FastCache(ttl = "15m", key = "#tenant + ':' + #query")
    public List<Document> search(String tenant, String query) { ... }
}
```

No `@EnableCaching`, no `CacheManager` bean, no XML. The starter registers the engine, the aspect and the
shard layout on classpath presence alone.

---

## What makes it different

|  | FastCache | Redis / Memcached | Caffeine / `ConcurrentMapCache` |
|---|---|---|---|
| Infrastructure | **None** | Server + client + ops | None |
| Large payloads (20–50 MB) | **Off-heap, no GC impact** | Network round trip | Heap pressure, GC pauses |
| Shared across processes | **Yes — local sidecar** | Yes | No |
| Python + JVM from one cache | **Yes** | Yes | No |
| Stampede protection | **Built in** | DIY | `get(k, fn)` only |
| Cost visibility | **Built-in console** | No | No |
| Durability / replication | **No** | Yes | No |

FastCache is deliberately **single-node and non-durable**. If you need replication, failover or
persistence, you need Redis — this is a different tool, not a replacement for that one.

---

## The console

Every sidecar serves a read-only management console on `:8081`, built on the JDK's own
`com.sun.net.httpserver` — one 14 KB HTML file, no framework, no CDN, no build step.

```
┌─ Estimated savings ──────────────┐  ┌─ Memory ─────────────────────────┐
│  $214.57                         │  │  System RAM   ████████░░  84.3%  │
│  99.3% of the projected GPT-4o   │  │  Off-heap     ░░░░░░░░░░   0.04% │
│  bill avoided                    │  │  JVM heap     █░░░░░░░░░   6.2%  │
│                                  │  │                                  │
│  Without FastCache    $215.98    │  │  Payloads live off-heap, so heap │
│  Actually incurred      $1.41    │  │  stays flat as the cache grows.  │
│  Input tokens avoided  85.8M     │  └──────────────────────────────────┘
└──────────────────────────────────┘
```

`/metrics` serves the same data as JSON. Both accept `?model=gpt-4o`, `?model=claude-3-5-sonnet`, or
`?model=<name>:<usd-per-million>` to re-price the same traffic without a restart.

---

## Measured behaviour

**Read the methodology before quoting these numbers.** They come from
[`examples/demo_workload.py`](examples/demo_workload.py), committed so you can reproduce or refute them:

```bash
cd examples && python demo_workload.py
```

That workload is **synthetic and deliberately cache-friendly**: 40 distinct questions plus one "celebrity"
prompt repeated 80× per round, with ~54 KB responses. The 99.3% hit rate follows directly from that
concentration. Traffic with no repeats would show a hit rate near zero and the cache would be pure
overhead — that is the honest other end of the range.

| Measurement | Result |
|---|---|
| Demo workload, 6,300 requests | 41 backend calls, 99.3% hit rate |
| Estimated saving on that workload | **$214.57 of $215.98 projected** (GPT-4o input rates) |
| 20 MB payload round trip, Python → Java → Python | put 36 ms, get 33 ms, byte-identical |
| Cold JVM boot on first cache call | 1.85 s, fully automatic |
| Hot-key absorption | 500 reads of one key → 8 network round trips |
| Stampede, Spring path | 500 concurrent callers → **1** recomputation |
| Stampede, Python path | 200 concurrent callers → **1** recomputation |
| Shard balance (murmur3 routing) | 1.09–1.22× max/mean; a perfect hash floors at 1.12× |
| Memory guard at a 64 MiB budget | sheds writes at 87.5%, no OOM, reads keep serving |
| Orphan isolation | `kill -9` the parent → JVM exits, releases all memory |
| Engine under 5,000 concurrent virtual threads | zero native slots leaked at close |
| Test suite | 125 Java + 70 Python = **195 tests** |

**How the dollar figure is computed.** Characters served from cache ÷ 4 (an input-token estimate), priced
at the model's published input rate. Three assumptions all push it *upward* versus a real bill: every hit
is counted as a full-price call avoided; 4 chars/token is a rule of thumb, not a tokenizer (±15% on English
prose); and output tokens — usually the expensive half — are not counted at all. It answers "is this cache
earning its keep", not "what do I owe".

---

## How it works

```
Python process                          JVM sidecar (or embedded in your Spring app)
──────────────                          ────────────────────────────────────────────
@fastcache                              FastCacheServer — 1 virtual thread per connection
   │                                       │
   ├─ L1 hot-key cache ──hit──┐            ├─ 32 shards, murmur3-routed
   ├─ single-flight gate      │            ├─ off-heap DirectByteBuffer payloads
   ├─ zstd / msgpack / numpy  │            ├─ RefreshCoordinator (host-wide single-flight)
   └─ pooled TCP ─────────────┴──────────► ├─ MemoryGuard — sheds writes before OOM
      heartbeat every 10s                  ├─ OrphanWatchdog — no zombie JVMs
                                           └─ MetricsServer → :8081/dashboard
```

Five decisions that shape everything else:

- **Payloads live off-heap.** A 50 MB context window in a `byte[]` gets scanned as a GC root and copied on
  every evacuation. In a `DirectByteBuffer` it is invisible to the collector. Slots are freed
  *deterministically* rather than whenever a `Cleaner` happens to run.
- **Reference counting, not hope.** Freeing a direct buffer another thread is reading is a SIGSEGV, not an
  exception. Every payload is refcounted, so eviction can run concurrently with reads.
- **No `synchronized` anywhere.** Every path may run on a virtual thread, and a thread that parks inside a
  monitor pins its carrier. Locks are `StampedLock`/`ReentrantLock` throughout.
- **Zero-copy both directions.** A PUT is read from the socket straight into the off-heap slot; a GET is
  written from that slot straight to the socket.
- **Fail open, always.** A dead sidecar, a rejected write or an unserializable value means your function
  runs normally. A cache that can break the app it was added to speed up is a liability.

Full design record, including every edge case and its mitigation:
**[ARCHITECTURE.md](ARCHITECTURE.md)**.

---

## Configuration

Zero config is a supported configuration. These exist for when you need them:

| Setting | Java / Spring | Python env | Default |
|---|---|---|---|
| Shards | `fastcache.shards` | `FASTCACHE_SHARDS` | 32 |
| Off-heap budget | `fastcache.off-heap-max` | `FASTCACHE_OFFHEAP_MAX` | 512 MB / 2 GB |
| Default TTL | `fastcache.default-ttl` | `FASTCACHE_DEFAULT_TTL` | `15m` |
| Serve-stale window | `fastcache.stale-grace` | `FASTCACHE_STALE_GRACE` | `2s` |
| Mutation guard | `fastcache.mutation-guard` | — | `AUTO` |
| Console port | — | `FASTCACHE_METRICS_PORT` | `8081` (`off` disables) |
| Cost model | `--cost-model` | `FASTCACHE_COST_MODEL` | `gpt-4o` |
| Orphan window | `--heartbeat-timeout` | `FASTCACHE_HEARTBEAT_TIMEOUT` | `40s` (`0` disables) |

[Full table →](ARCHITECTURE.md#3-configuration)

---

## Building from source

```bash
git clone https://github.com/adi407/fast_cache.git && cd fast_cache

mvn -B verify                                    # builds both modules, runs 125 Java tests
cp fastcache-engine/target/fastcache-engine.jar python/fastcache_ai/_bin/
cd python && pip install -e ".[dev]" && pytest -q # 70 integration tests against a real sidecar
```

Requires JDK 21+ (virtual threads) and Python 3.10+.

---

## Project status

**Pre-release. Not yet published to Maven Central or PyPI** — the coordinates above are reserved, not live.
Build from source for now.

Honest caveats, because you will hit them otherwise:

- **Verified on Linux, macOS and Windows** by CI on every push: 125 Java tests on JDK 21 (plus a JDK 25
  forward-compatibility canary), 70 Python integration tests across 3.10&ndash;3.13, and a wheel build
  that is installed into a clean virtualenv and round-tripped. The POSIX paths (`fcntl` locking,
  process-group signalling, SIGKILL orphan reaping) are covered there &mdash; development happened on
  Windows, and the Linux/macOS runs are what makes those paths verified rather than merely written.
- **Single-node, non-durable, unauthenticated.** The protocol and the console bind to loopback and have no
  auth. Do not expose either.
- **The savings figure is an estimate**, with the assumptions listed above.
- **Not yet run under sustained production load.** Correctness is tested on every push; multi-day soak
  behaviour, memory fragmentation over weeks and real traffic shapes are not.

If you find a bug, an issue with a reproduction is worth more than a star.

---

## Contributing

The design notes in [ARCHITECTURE.md](ARCHITECTURE.md) explain *why* each subsystem is the way it is —
worth a read before proposing a change, since most of the non-obvious choices are non-obvious for a reason
that is written down there.

Particularly welcome: Linux/macOS validation, a CI workflow, adversarial tests against the concurrency
paths, and real-workload numbers that contradict the demo.

## License

[Apache 2.0](LICENSE)
