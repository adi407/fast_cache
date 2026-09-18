# Contributing to FastCache

Thanks for looking. This document is short on process and long on context, because the hard part of
changing this codebase is not the mechanics — it's knowing which of the non-obvious decisions are load
bearing.

**Read [ARCHITECTURE.md](ARCHITECTURE.md) before proposing a change.** Most of the surprising choices are
surprising for a reason that is written down there. If something looks wrong, the odds are roughly even
that it's a bug or that §5 explains why it has to be that way — and either outcome is a useful thing to
find out before you write the patch.

---

## What is most welcome

Ranked by how much it would actually help:

1. **Real-workload numbers, especially ones that contradict the README.** Every measurement in this repo
   comes from a workload we designed. A report showing FastCache is pure overhead on your traffic is more
   valuable than another passing test. Use the [workload report](.github/ISSUE_TEMPLATE/workload_report.yml)
   template.
2. **Soak testing.** Correctness is well covered; multi-day behaviour is not. Native memory fragmentation
   over weeks, the eviction sweeper under sustained churn, the orphan watchdog across laptop sleep — all
   unknown.
3. **Adversarial concurrency tests.** The reference-counting, single-flight and eviction paths are the
   ones where a bug is silent and expensive. A test that breaks one of them is a gift.
4. **Bug reports with a reproduction.** Worth far more than a star.
5. **Platform validation.** CI covers Linux, macOS and Windows, but only on GitHub's runners. Containers,
   ARM, musl and constrained memory limits are unexplored.

## What to discuss before building

Not "don't" — just open an issue first, so you don't spend a weekend on something that gets declined on
grounds you had no way to know about:

- **Replication, persistence or clustering.** FastCache is deliberately single-node and non-durable. If
  you need those, Redis already exists and is better at them. This is a different tool.
- **Adding a runtime dependency to `fastcache-engine`.** It ships inside a Python wheel, so every
  transitive megabyte is one a data scientist has to download. The zero-dependency rule is why the
  console is hand-rolled on `com.sun.net.httpserver` rather than Spring Web.
- **Authentication or remote binding.** The protocol is unauthenticated by design and binds to loopback.
  Making it network-exposed is a different product with a different threat model.

---

## Building

Requires **JDK 21+** (virtual threads) and **Python 3.10+**.

```bash
git clone https://github.com/adi407/fast_cache.git && cd fast_cache

mvn -B verify                                     # both Java modules, 125 tests
cp fastcache-engine/target/fastcache-engine.jar python/fastcache_ai/_bin/
cd python && pip install -e ".[dev]" && pytest -q  # 70 integration tests
```

The Python tests boot a **real JVM sidecar** — they are not mocked. The interesting failure modes (native
reclamation, back-pressure, hot-key promotion, cross-process discovery, orphan reaping) only exist when a
real engine is running, so a mocked socket would test nothing worth testing. Expect the suite to take
~30 seconds and to start and stop several JVMs.

If a Python test fails with "could not reach sidecar", the real error is almost always in the engine log:

```bash
cat ~/.fastcache/sidecar.log
```

---

## House rules

These are the ones a reviewer will actually push back on.

### Never use `synchronized`

Every path may execute on a virtual thread, and a virtual thread that parks inside a monitor pins its
carrier. Use `StampedLock` or `ReentrantLock`. This is not a style preference — a pinned carrier during a
multi-second LLM call is the stall the engine exists to avoid.

### Fail open, always

No cache failure may fail a business call. A dead sidecar, a rejected write, an unserializable value — all
of them mean "call the wrapped function normally". A caching layer that can take down the application it
was added to speed up is a liability. The only exception that propagates is the one thrown by the
intercepted method itself.

### Back-pressure is a status code, never an exception

`WriteStatus` exists so callers can degrade gracefully. Adding a path that throws on a full cache breaks
every caller that currently just carries on.

### Never free an off-heap slot without the reference count

Freeing a direct `ByteBuffer` another thread is reading is a SIGSEGV, not an exception — it takes the JVM
down with no stack trace. Every read goes through a `Lease`; every release goes through the refcount.

### Comments explain *why*, not *what*

The codebase is heavily commented, but almost none of it restates the code. A comment earns its place by
recording a decision, a measurement, or a failure mode that isn't visible from the syntax. If your comment
would still be true after the code was rewritten differently, it probably isn't pulling its weight.

### Tests assert behaviour, not implementation

Prefer a test that would survive a rewrite. The routing tests compare against an ideal uniform hash rather
than a hard-coded skew number precisely because the constant would be testing the binomial distribution,
not the hash.

---

## Changing the wire protocol

`io.fastcache.engine.net.Protocol` and `python/fastcache_ai/protocol.py` must stay byte-identical. The
client and engine ship in one artifact, so a mismatched pair fails fast on the magic check rather than
silently misreading frames — but only if both sides are changed together.

The same applies to the TTL grammar: `TimeSpec.java` and `ttl.py` must agree, because `ttl="15m"` is
copy-pasteable between a Spring annotation and a Python decorator and it would be a nasty surprise if the
two runtimes disagreed about what it meant.

---

## Pull requests

- Branch from `main`, keep the change focused.
- CI must be green: 14 jobs across Linux, macOS and Windows, Python 3.10–3.13, JDK 21 plus a JDK 25 canary.
- Add a test that fails without your change. For a bug fix, the test should fail on `main`.
- If you changed behaviour that ARCHITECTURE.md describes, update that too. Stale design notes are worse
  than none.
- Explain the *why* in the PR description. "Fixes #12" tells a future reader nothing about the trade-off
  you made.

Don't worry about squashing or commit message format — that gets handled on merge.

---

## Releasing

Maintainers only. Bump the version in **both** `pom.xml` and `python/pyproject.toml` — the release
workflow hard-fails if they disagree — then:

```bash
git tag vX.Y.Z && git push origin vX.Y.Z
```

That runs the full suite, publishes the wheel to PyPI via Trusted Publishing, and uploads signed Maven
artifacts as a **validated draft** for manual approval at
[central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments).

Two things worth internalising: a PyPI filename can never be reused, and a Maven Central version can never
be deleted or overwritten. Run the dry run first —
`gh workflow run release.yml -f dry_run=true` — which builds and signs everything while being incapable of
reaching either registry.

---

## Security

Please don't open a public issue for a vulnerability — use
[GitHub's private advisory form](https://github.com/adi407/fast_cache/security/advisories/new).

Two things that are documented behaviour rather than vulnerabilities: the wire protocol and the management
console are **unauthenticated and bind to loopback only**, and the Python client will **unpickle** values
it finds in the cache. Both are safe under the intended deployment — a local sidecar holding your own
process's data — and both are unsafe if you point a client at a host you don't control. Reports that
FastCache is insecure when deliberately exposed will be closed with a pointer to this paragraph.

## Licence

By contributing you agree your work is licensed under [Apache 2.0](LICENSE), same as the project.
