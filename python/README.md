# fastcache-ai

**A cache for AI workloads that needs no infrastructure.** No Redis, no Docker, no connection string.

```bash
pip install fastcache-ai
```

```python
from fastcache_ai import fastcache

@fastcache(ttl="15m")
def embed(prompt: str):
    return model.encode(prompt)
```

That is the entire setup. On first use the library finds or boots a Java engine process, reuses it across
runs, and reaps it if your process dies. A JDK 21+ runtime is required; `pip install "fastcache-ai[jre]"`
bundles one if you do not have it.

> **The import name is `fastcache_ai`, not `fastcache`.** The bare name on PyPI belongs to an unrelated
> C implementation of `functools.lru_cache`; sharing it would make the two packages overwrite each other.

## Why it exists

Caching an LLM response should be a one-line change. Instead it usually means standing up Redis, choosing a
serialization format, and then discovering that a 50 MB context window on the JVM heap causes GC pauses
costing more than the cache saves. FastCache stores payloads **off-heap**, invisible to the garbage
collector, and hands you two front doors onto the same engine: this decorator, and a Spring Boot
annotation for JVM services.

## What you get

- **Off-heap storage** sized for 20–50 MB context windows and vector matrices
- **Hot-key L1 cache** — a celebrity prompt hit thousands of times per second collapses to one network
  round trip every few seconds
- **Stampede protection** — when a hot key expires under concurrency, exactly one caller recomputes and
  everyone else is served the still-valid previous value
- **A management console** on `http://127.0.0.1:8081/dashboard` with live memory, hit rate and an
  estimated-savings figure
- **Fail-open** — if the engine is unreachable, your function simply runs

```python
import fastcache_ai as fc

fc.dashboard(open_browser=True)   # live console
fc.stats()                        # engine, L1 and client telemetry
fc.info()                         # everything needed to diagnose a problem
```

## Configuration

Zero configuration is a supported configuration. Common overrides:

| Variable | Purpose | Default |
|---|---|---|
| `FASTCACHE_OFFHEAP_MAX` | Native memory budget | `2g` |
| `FASTCACHE_DEFAULT_TTL` | TTL when the decorator omits one | `15m` |
| `FASTCACHE_METRICS_PORT` | Console port (`off` disables) | `8081` |
| `FASTCACHE_L1_TTL` | Local hot-key cache window, seconds | `5.0` |
| `FASTCACHE_HEARTBEAT_TIMEOUT` | Orphan-reaping window (`0` disables) | `40s` |

## Status

Pre-release. Single-node, non-durable and unauthenticated — it binds to loopback and is not a replacement
for Redis where you need replication, failover or persistence.

Full documentation, design notes and source:
**[github.com/adi407/fast_cache](https://github.com/adi407/fast_cache)**

Apache 2.0 licensed.
