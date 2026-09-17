"""The ``@fastcache`` decorator: the entire API surface an AI engineer has to learn.

.. code-block:: python

    from fastcache_ai import fastcache

    @fastcache(ttl="15m")
    def embed(prompt: str) -> np.ndarray:
        return model.encode(prompt)

That is the whole setup. No server to start, no connection string, no client to construct.

**Fail-open.** Every cache interaction is wrapped. If the sidecar is unreachable, the JVM is still booting,
or a value will not serialize, the wrapped function is simply called. A caching layer must never be able to
break the application it was added to speed up.

**Async.** Coroutine functions get a coroutine wrapper. The L1 lookup runs inline on the event loop (it is a
dict read), and only the socket round trip is pushed to a worker thread — so a hot key never leaves the
loop, and a cold one never blocks it.
"""

from __future__ import annotations

import asyncio
import atexit
import functools
import inspect
import logging
import os
import threading
import time
from hashlib import blake2b
from typing import Any, Callable, Optional

from .client import FastCacheClient, Fetched
from .errors import FastCacheError
from .l1 import MISS
from .singleflight import SingleFlight

log = logging.getLogger("fastcache")

_default_client: Optional[FastCacheClient] = None
_default_client_lock = threading.Lock()

#: Process-wide, so two decorated functions caching the same key share one flight.
_single_flight = SingleFlight()

#: How long a follower waits for the leader before computing the value itself. Matches the engine's
#: default stale-grace window, so the two layers give up at the same moment rather than one stranding
#: callers past the point the other has already moved on.
_REFRESH_WAIT_SECONDS = float(os.environ.get("FASTCACHE_REFRESH_WAIT", "2.0"))

#: Poll interval while waiting for another process to publish a value.
_REFRESH_POLL_SECONDS = 0.025

_MISSING = object()


def default_client() -> FastCacheClient:
    """Process-wide shared client, built on first use.

    Shared rather than per-decorator so that connection pool, L1 cache and frequency counters are shared
    too: two functions caching the same key must see one hot-key counter, not two half-counts that never
    cross the promotion threshold.
    """
    global _default_client
    if _default_client is None:
        with _default_client_lock:
            if _default_client is None:
                _default_client = FastCacheClient()
    return _default_client


def _flush_telemetry_at_exit() -> None:
    """Sends one last heartbeat so a short-lived script's L1 savings are not lost.

    Most programs never call ``close()``; they just end. Without this, any process that lives for less
    than one heartbeat interval contributes nothing to the console's L1 figures, and the savings number
    quietly under-reports by however much the local cache absorbed.

    Registered after the bootstrap hook, so it runs *before* it: atexit is LIFO, and flushing telemetry
    into a sidecar that has already been terminated would accomplish nothing.
    """
    client = _default_client
    if client is None:
        return
    try:
        client.heartbeat()
    except Exception:  # noqa: BLE001 - interpreter shutdown is not the place to raise
        pass


atexit.register(_flush_telemetry_at_exit)


def set_default_client(client: FastCacheClient) -> None:
    """Replaces the shared client — for tests, or to point at a remote sidecar."""
    global _default_client
    with _default_client_lock:
        _default_client = client


# ---------------------------------------------------------------------------------------------------
# Key derivation
# ---------------------------------------------------------------------------------------------------

def _stable_token(value: Any) -> bytes:
    """Renders a value into stable bytes for hashing.

    ``repr`` alone is not safe here. ``repr`` of a large numpy array is *truncated* with an ellipsis, so two
    different 4096-dimension embeddings can render identically — and the cache would serve one function's
    result for the other's arguments. Arrays are therefore hashed over their actual buffer.
    """
    if isinstance(value, (bytes, bytearray, memoryview)):
        return b"b:" + bytes(value)
    if isinstance(value, str):
        return b"s:" + value.encode("utf-8")
    if isinstance(value, bool) or value is None:
        return b"c:" + repr(value).encode("utf-8")
    if isinstance(value, (int, float, complex)):
        return b"n:" + repr(value).encode("utf-8")
    if isinstance(value, (list, tuple)):
        return b"l:" + b"|".join(_stable_token(item) for item in value)
    if isinstance(value, dict):
        # Sorted by key so two equal dicts built in different orders hash the same.
        return b"d:" + b"|".join(
            _stable_token(k) + b"=" + _stable_token(v) for k, v in sorted(value.items(), key=lambda kv: repr(kv[0]))
        )
    if isinstance(value, (set, frozenset)):
        return b"S:" + b"|".join(sorted(_stable_token(item) for item in value))

    array_interface = getattr(value, "__array_interface__", None)
    if array_interface is not None:  # numpy ndarray, without importing numpy
        try:
            digest = blake2b(memoryview(value).cast("B"), digest_size=16).hexdigest()
            return f"a:{value.dtype.str}:{tuple(value.shape)}:{digest}".encode("utf-8")
        except (TypeError, ValueError, BufferError):
            pass  # Non-contiguous or exotic array; fall through to repr.

    return b"o:" + repr(value).encode("utf-8")


def build_key(func: Callable, args: tuple, kwargs: dict, namespace: Optional[str] = None) -> str:
    """Derives the cache key for one invocation. Exposed so callers can invalidate precisely."""
    prefix = namespace or f"{func.__module__}.{func.__qualname__}"
    digest = blake2b(digest_size=16)
    for arg in args:
        digest.update(_stable_token(arg))
        digest.update(b"\x00")
    for name in sorted(kwargs):
        digest.update(name.encode("utf-8"))
        digest.update(b"=")
        digest.update(_stable_token(kwargs[name]))
        digest.update(b"\x00")
    return f"{prefix}:{digest.hexdigest()}"


# ---------------------------------------------------------------------------------------------------
# Stampede defence
# ---------------------------------------------------------------------------------------------------

def single_flight_stats() -> dict:
    """Local single-flight counters: leads taken, duplicate computations suppressed, waits timed out."""
    return _single_flight.stats()


def _await_published(cache: FastCacheClient, cache_key: str, deadline: float) -> Fetched:
    """Polls for a value another *process* is computing.

    Polling rather than blocking is deliberate. A blocking wait would need the engine to hold the socket
    open for the duration of someone else's LLM call, tying up a connection and making a hung leader
    indistinguishable from a slow one. A 25 ms poll costs a handful of tiny frames over a two-second
    window and cannot wedge anything.
    """
    while time.monotonic() < deadline:
        time.sleep(_REFRESH_POLL_SECONDS)
        try:
            found = cache.fetch(cache_key)
        except FastCacheError:
            break
        if found.found and not found.stale:
            return found
    return Fetched(False, None, stale=False)


# ---------------------------------------------------------------------------------------------------
# Decorator
# ---------------------------------------------------------------------------------------------------

def fastcache(
    ttl: Any = "15m",
    key: Optional[Callable[..., str]] = None,
    namespace: Optional[str] = None,
    client: Optional[FastCacheClient] = None,
    cache_none: bool = False,
    enabled: bool = True,
):
    """Caches a function's return value in FastCache.

    :param ttl: ``"15m"``, ``"30s"``, ``"never"``, or milliseconds
    :param key: optional ``f(*args, **kwargs) -> str`` replacing the derived key
    :param namespace: key prefix; defaults to the fully-qualified function name
    :param client: explicit client; defaults to the process-wide shared one
    :param cache_none: whether a ``None`` return is cached (negative caching)
    :param enabled: set False to disable without removing the decorator
    """

    def decorate(func: Callable) -> Callable:
        if not enabled:
            return func

        def resolve_client() -> FastCacheClient:
            return client if client is not None else default_client()

        def make_key(args: tuple, kwargs: dict) -> str:
            if key is not None:
                return key(*args, **kwargs)
            return build_key(func, args, kwargs, namespace)

        def store(cache: FastCacheClient, cache_key: str, result: Any) -> None:
            if result is None and not cache_none:
                return
            try:
                cache.put(cache_key, result, ttl=ttl)
            except FastCacheError as exc:
                log.debug("fastcache: store failed for %s (%s)", cache_key, exc)

        if inspect.iscoroutinefunction(func):

            @functools.wraps(func)
            async def async_wrapper(*args, **kwargs):
                try:
                    cache = resolve_client()
                    cache_key = make_key(args, kwargs)
                except FastCacheError as exc:
                    log.debug("fastcache: bypassing cache (%s)", exc)
                    return await func(*args, **kwargs)

                # Hot path stays on the event loop: an L1 hit is a dict lookup, not I/O.
                local = cache.l1.lookup(cache_key)
                if local is not MISS:
                    return local

                try:
                    cached = await asyncio.to_thread(cache.fetch, cache_key)
                except FastCacheError as exc:
                    log.debug("fastcache: lookup failed for %s (%s)", cache_key, exc)
                    cached = Fetched(False, None, stale=False)

                if cached.found and not cached.stale:
                    return cached.value

                # Coroutines cannot share the threading-based single-flight without blocking the loop, so
                # the async path coordinates through the engine's host-wide lease only. That still holds a
                # multi-process deployment to one backend call; it permits at most one duplicate per
                # process, which is the right trade against blocking an event loop.
                granted = False
                try:
                    granted = await asyncio.to_thread(cache.try_refresh_lease, cache_key)
                except FastCacheError:
                    granted = True

                if not granted and cached.found:
                    return cached.value
                if not granted:
                    published = await asyncio.to_thread(
                        _await_published, cache, cache_key, time.monotonic() + _REFRESH_WAIT_SECONDS)
                    if published.found:
                        return published.value

                try:
                    result = await func(*args, **kwargs)
                except Exception:
                    if cached.found:
                        log.debug("fastcache: async refresh of %s failed; serving stale", cache_key)
                        return cached.value
                    raise
                finally:
                    if granted:
                        try:
                            await asyncio.to_thread(cache.complete_refresh, cache_key)
                        except FastCacheError:
                            pass

                try:
                    await asyncio.to_thread(store, cache, cache_key, result)
                except Exception as exc:  # noqa: BLE001 - a store must never fail the call
                    log.debug("fastcache: async store failed for %s (%s)", cache_key, exc)
                return result

            wrapper: Callable = async_wrapper
        else:

            @functools.wraps(func)
            def sync_wrapper(*args, **kwargs):
                try:
                    cache = resolve_client()
                    cache_key = make_key(args, kwargs)
                    cached = cache.fetch(cache_key)
                except FastCacheError as exc:
                    log.debug("fastcache: bypassing cache (%s)", exc)
                    return func(*args, **kwargs)

                if cached.found and not cached.stale:
                    return cached.value

                return _refresh(cache, cache_key, cached, func, args, kwargs)

            def _refresh(cache, cache_key, cached, fn, args, kwargs):
                """One caller recomputes; everyone else serves stale or waits. See module docstring."""
                is_leader, flight = _single_flight.begin(cache_key)

                if not is_leader:
                    # Another thread in this process is already on it.
                    if cached.found:
                        return cached.value  # Stale beats blocking, every time.
                    if _single_flight.wait(flight, _REFRESH_WAIT_SECONDS):
                        try:
                            published = cache.fetch(cache_key)
                            if published.found:
                                return published.value
                        except FastCacheError:
                            pass
                    # Leader vanished or overran. Compute rather than fail; do not store, because the
                    # leader may publish at any moment and a straggler should not overwrite it.
                    return fn(*args, **kwargs)

                try:
                    # Local leader. Now ask whether this *host* wants us to do the work, so eight worker
                    # processes make one backend call between them instead of eight.
                    if not cache.try_refresh_lease(cache_key):
                        if cached.found:
                            return cached.value
                        published = _await_published(
                            cache, cache_key, time.monotonic() + _REFRESH_WAIT_SECONDS)
                        if published.found:
                            return published.value
                        return fn(*args, **kwargs)

                    try:
                        result = fn(*args, **kwargs)
                    except Exception:
                        if cached.found:
                            # A refresh failure with a usable value in hand should not become an error for
                            # the caller; that is what the grace window bought us.
                            log.debug("fastcache: refresh of %s failed; serving stale", cache_key)
                            return cached.value
                        raise
                    finally:
                        cache.complete_refresh(cache_key)

                    store(cache, cache_key, result)
                    return result
                finally:
                    _single_flight.complete(cache_key, flight)

            wrapper = sync_wrapper

        def cache_key_for(*args, **kwargs) -> str:
            """The key this call would use. Useful for manual invalidation and debugging."""
            return make_key(args, kwargs)

        def invalidate(*args, **kwargs) -> bool:
            """Evicts the entry for one specific argument set."""
            try:
                return resolve_client().delete(make_key(args, kwargs))
            except FastCacheError:
                return False

        wrapper.cache_key = cache_key_for       # type: ignore[attr-defined]
        wrapper.invalidate = invalidate         # type: ignore[attr-defined]
        wrapper.__wrapped__ = func              # type: ignore[attr-defined]
        return wrapper

    return decorate



