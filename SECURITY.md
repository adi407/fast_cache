# Security Policy

## Reporting a vulnerability

Please report privately, not in a public issue:

**[github.com/adi407/fast_cache/security/advisories/new](https://github.com/adi407/fast_cache/security/advisories/new)**

Include a reproduction if you can — for this project, a dozen lines that demonstrate the problem is worth
more than a long description, because most of the interesting surface is concurrency and native memory
where the failure is easy to describe and hard to trigger.

This is a pre-release project maintained by one person. Expect a first response within about a week, and
please don't read silence as dismissal — ping the advisory thread if you hear nothing.

## Supported versions

| Version | Supported |
|---|---|
| 1.1.x | yes |
| < 1.1 | no |

Only the latest minor line receives fixes. There is no LTS branch.

---

## Threat model

FastCache is designed for **one trusted process tree on one host**: a Python application or JVM service
caching its own data in a sidecar it started itself. Everything below follows from that assumption, and if
your deployment breaks it, the assumption — not the code — is what needs revisiting.

- The wire protocol binds to `127.0.0.1` by default.
- The management console binds to `127.0.0.1` by default and is read-only (every handler rejects non-GET
  with 405).
- The discovery file `~/.fastcache/sidecar.json` is written via `Files.createTempFile`, which is
  owner-only on POSIX, then atomically moved into place.
- Cached values are whatever your own process put there.

---

## In scope

Genuine vulnerabilities, and the ones most worth looking for:

- **Memory safety in the off-heap path.** A use-after-free on a direct `ByteBuffer` reachable through
  ordinary API use would be the most serious class of bug in this codebase — it is a SIGSEGV or worse, not
  an exception. Every read is supposed to be gated behind the payload reference count; a way around that
  gate is a real finding.
- **Remote code execution on the engine side** from cached data. The engine treats payloads as opaque
  bytes and never parses them; anything that makes it interpret them is a bug.
- **Path traversal or arbitrary file write** via the discovery file, the log file, or JAR resolution.
- **Unbounded resource consumption from a loopback client** that the memory guard and frame limits are
  supposed to prevent — for example a frame that makes the engine allocate without admission control.
- **A way for one client to read another client's data that bypasses the key space.** Note that
  namespaces are an organisational convenience, not a security boundary; that is documented, not a bug.
- **Vulnerabilities in dependencies.** The engine has zero runtime dependencies by design; the Spring
  starter depends on Spring, and the Python client's accelerators (`zstandard`, `msgpack`, `numpy`) are
  all optional.

---

## Not vulnerabilities

These are documented design decisions. Reports about them will be closed with a link to this section —
not to be dismissive, but because they are trade-offs made deliberately and stated in the README.

**The protocol and console are unauthenticated.** There is no password, token or TLS. They bind to
loopback, hold one process's own data, and are meant to be reachable only by the application that started
them. Adding authentication would be a different product with a different threat model; see
[CONTRIBUTING.md](CONTRIBUTING.md) before proposing it.

**Binding to a public interface exposes everything.** `--host 0.0.0.0` or `fastcache.server.host` will do
exactly what you asked and let anyone who can route to the port read and write the entire cache. The
defaults are loopback; overriding them is a deployment decision, and the README says not to.

**The Python client unpickles cached values.** When no faster codec is available, the client falls back to
`pickle`, and `pickle.loads` on hostile bytes is arbitrary code execution. This is safe under the intended
model — the only writer is the same process — and unsafe the moment you point a client at a sidecar you do
not control. Install `msgpack` if you want a codec that is not Turing-complete for the objects it can
represent, though the fallback still exists for types msgpack cannot encode.

**The engine uses `sun.misc.Unsafe.invokeCleaner`.** This is how off-heap slots are released
deterministically rather than whenever the GC happens to run. It is reflective access to an unsupported
API, not a vulnerability by itself. A demonstrated use-after-free *through* it is very much in scope — see
above.

**The savings figure is an estimate.** It uses characters ÷ 4 as an input-token proxy and ignores output
tokens. Inaccuracy is a documentation matter, not a security one.

---

## Known limitation: shared multi-user hosts

Worth stating plainly rather than leaving to be discovered.

**On a host with multiple untrusted local users, any of them can reach the sidecar.** Loopback TCP has no
per-user restriction, so another local account can scan `127.0.0.1`, find the port, and then read, write
or flush the entire cache. The discovery file's permissions do not prevent this — they only make the port
slightly less convenient to find.

If that matters for your deployment, today's options are:

- Run the sidecar inside a container or user namespace, so its loopback is not shared.
- Set `FASTCACHE_METRICS_PORT=off` to remove the console, which at least stops the memory profile and
  aggregate cache behaviour from being readable.
- Don't cache anything sensitive on a shared host.

A shared-secret handshake — a token written into the owner-only discovery file and required on connect —
would close this properly, and is a change we would welcome. It is not implemented today, and this
document would be dishonest if it implied otherwise.

---

## Credential handling in this repository

The release pipeline signs artifacts with GPG and publishes to Maven Central and PyPI:

- The GPG private key and passphrase, and the Sonatype token pair, live only as **GitHub Actions secrets**.
- PyPI uses **Trusted Publishing** (OIDC), so no PyPI API token exists in the repository at all.
- Both publish jobs run in protected environments (`maven-central`, `pypi`).

If you believe a credential has been exposed — including as a *secret name*, which GitHub returns through
its API and does not protect — please report it through the advisory link above so it can be rotated.
