"""Integration tests. These boot a real JVM sidecar — they are not mocked, on purpose.

The interesting failure modes of this system (native reclamation, back-pressure, hot-key promotion,
cross-process discovery) only exist when a real engine is running, so a mocked socket would test nothing
worth testing.

Run with::

    pip install -e ".[dev]"
    mvn -q package -f ../pom.xml        # or set FASTCACHE_JAR
    pytest -v
"""

from __future__ import annotations

import asyncio
import os
import threading
import time

import pytest

import fastcache_ai as fastcache  # noqa: E402
from fastcache_ai import fastcache as cached  # noqa: E402
from fastcache_ai.codec import Codec  # noqa: E402
from fastcache_ai.l1 import MISS, HotKeyCache, NullL1  # noqa: E402
from fastcache_ai.ttl import format_ttl, parse_ttl  # noqa: E402

np = pytest.importorskip("numpy")


# ---------------------------------------------------------------------------------------------------
# TTL grammar
# ---------------------------------------------------------------------------------------------------

@pytest.mark.parametrize(
    "spec,expected",
    [("500ms", 500), ("30s", 30_000), ("15m", 900_000), ("2h", 7_200_000), ("1d", 86_400_000),
     ("never", -1), ("250", 250), (None, 0)],
)
def test_ttl_parsing(spec, expected):
    assert parse_ttl(spec) == expected


def test_ttl_round_trip():
    assert format_ttl(parse_ttl("15m")) == "15m"


def test_ttl_rejects_nonsense():
    with pytest.raises(ValueError):
        parse_ttl("15 fortnights")


# ---------------------------------------------------------------------------------------------------
# Value round trips
# ---------------------------------------------------------------------------------------------------

@pytest.mark.parametrize(
    "value",
    ["a string", b"\x00\xff bytes", 42, 3.14, {"nested": {"list": [1, 2, 3]}}, ["a", "b"], None],
)
def test_round_trip(value):
    fastcache.put("k", value)
    assert fastcache.get("k") == value


def test_numpy_round_trip_is_exact():
    array = np.random.rand(256, 384).astype("float32")
    fastcache.put("vec", array)
    restored = fastcache.get("vec")
    assert np.array_equal(array, restored)
    assert restored.dtype == array.dtype
    assert restored.shape == array.shape


def test_non_contiguous_array_survives():
    """A transposed view is not C-contiguous; the codec must normalise it, not corrupt it."""
    array = np.arange(120, dtype="int64").reshape(10, 12).T
    fastcache.put("transposed", array)
    assert np.array_equal(fastcache.get("transposed"), array)


def test_large_payload():
    """A 20 MB context window, the size this engine exists for."""
    payload = "x" * (20 * 1024 * 1024)
    assert fastcache.put("ctx", payload) == 0
    assert fastcache.get("ctx") == payload


def test_miss_returns_default():
    assert fastcache.get("absent", "fallback") == "fallback"


def test_delete():
    fastcache.put("doomed", 1)
    assert fastcache.delete("doomed") is True
    assert fastcache.get("doomed") is None
    assert fastcache.delete("doomed") is False


def test_ttl_expiry_has_three_phases():
    """fresh -> stale-but-servable -> gone.

    The middle phase is the stampede defence: an expired entry stays servable for the grace window so a
    thousand concurrent readers get a value instead of all missing at once. A test that asserted
    "expired means gone" would now be asserting the absence of that feature.
    """
    client = fastcache.default_client()
    grace = 2.0  # engine default stale-grace

    fastcache.put("brief", "value", ttl="300ms")
    assert client.fetch("brief") == (True, "value", False, False), "should start fresh"

    time.sleep(0.6)
    stale = client.fetch("brief")
    assert stale.found and stale.stale, "inside the grace window the value is still served, marked stale"
    assert stale.value == "value"

    time.sleep(grace)
    assert fastcache.get("brief", "gone") == "gone", "past TTL + grace the entry is really gone"


def test_zero_grace_expires_immediately():
    """With the grace window disabled, TTL is strict again."""
    from fastcache_ai.client import FastCacheClient

    host, port = fastcache.ensure_sidecar()
    strict = FastCacheClient(host=host, port=port, l1=NullL1(), namespace="strict")
    try:
        # ttl=-1 is 'never'; use a tiny TTL and wait past TTL + the engine's grace to prove the entry is
        # reclaimed rather than served indefinitely.
        strict.put("k", "v", ttl="200ms")
        time.sleep(2.6)
        assert strict.get("k", "gone") == "gone"
    finally:
        strict.close()


# ---------------------------------------------------------------------------------------------------
# Codec
# ---------------------------------------------------------------------------------------------------

def test_incompressible_payload_is_not_compressed():
    """Random bytes cannot shrink; the codec must detect that and skip compression."""
    codec = Codec(min_compress_bytes=1024)
    payload, flags, characters = codec.encode(os.urandom(64 * 1024))
    assert (flags >> 4) == 0, "random data should not be stored compressed"
    assert characters == 0, "bytes are not text and must not be counted as tokens"
    assert codec.decode(payload, flags) is not None


def test_compressible_payload_is_compressed():
    codec = Codec(min_compress_bytes=1024)
    payload, flags, characters = codec.encode("a" * (64 * 1024))
    assert (flags >> 4) != 0
    assert len(payload) < 64 * 1024
    assert characters == 64 * 1024, "character count is pre-compression, not wire size"
    assert codec.decode(payload, flags) == "a" * (64 * 1024)


# ---------------------------------------------------------------------------------------------------
# L1 hot-key cache
# ---------------------------------------------------------------------------------------------------

def test_l1_promotes_only_hot_keys():
    l1 = HotKeyCache(threshold=3, window_seconds=10.0, ttl_seconds=5.0)
    for _ in range(2):
        l1.lookup("cold")
    assert l1.admit("cold", "v") is False, "a key below threshold must not consume local memory"

    for _ in range(3):
        l1.lookup("hot")
    assert l1.admit("hot", "v") is True
    assert l1.lookup("hot") == "v"


def test_l1_entries_expire():
    l1 = HotKeyCache(threshold=1, window_seconds=10.0, ttl_seconds=0.2)
    l1.lookup("k")
    l1.admit("k", "v")
    assert l1.lookup("k") == "v"
    time.sleep(0.35)
    assert l1.lookup("k") is MISS


def test_l1_caches_none_distinctly():
    """A cached None must not read back as a miss."""
    l1 = HotKeyCache(threshold=1, window_seconds=10.0, ttl_seconds=5.0)
    l1.lookup("k")
    l1.admit("k", None)
    assert l1.lookup("k") is None
    assert l1.lookup("absent") is MISS


def test_l1_respects_capacity():
    l1 = HotKeyCache(threshold=1, window_seconds=10.0, ttl_seconds=5.0, capacity=4)
    for i in range(20):
        l1.lookup(f"k{i}")
        l1.admit(f"k{i}", i)
    assert l1.stats()["entries"] <= 4


def test_l1_collapses_celebrity_traffic():
    """The headline behaviour: thousands of reads of one key, a handful of network calls."""
    client = fastcache.default_client()
    fastcache.put("celebrity", "system prompt")
    client.l1.clear()

    before = client.stats()["client"]["network_gets"]
    for _ in range(500):
        assert fastcache.get("celebrity") == "system prompt"
    after = client.stats()["client"]["network_gets"]

    assert after - before <= client.l1.threshold + 2, "L1 failed to absorb the hot key"
    assert "celebrity" in client.l1.hot_keys()


def test_l1_invalidated_on_write():
    client = fastcache.default_client()
    for _ in range(client.l1.threshold + 1):
        fastcache.get("mutable")
    fastcache.put("mutable", "v1")
    for _ in range(client.l1.threshold + 1):
        fastcache.get("mutable")
    fastcache.put("mutable", "v2")
    assert fastcache.get("mutable") == "v2", "a local write must not leave a stale L1 entry behind"


# ---------------------------------------------------------------------------------------------------
# Decorator
# ---------------------------------------------------------------------------------------------------

def test_decorator_caches():
    calls = []

    @cached(ttl="1m")
    def expensive(prompt):
        calls.append(prompt)
        return prompt.upper()

    assert expensive("hi") == "HI"
    assert expensive("hi") == "HI"
    assert len(calls) == 1


def test_decorator_distinguishes_arguments():
    @cached(ttl="1m")
    def identity(value, flag=False):
        return (value, flag)

    assert identity("a") == ("a", False)
    assert identity("a", flag=True) == ("a", True)
    assert identity.cache_key("a") != identity.cache_key("a", flag=True)


def test_decorator_key_uses_array_contents_not_repr():
    """numpy's repr truncates; two different big arrays must not collide."""

    @cached(ttl="1m")
    def norm(vector):
        return float(np.linalg.norm(vector))

    first = np.arange(5000, dtype="float64")
    second = first.copy()
    second[-1] = -1.0
    assert norm.cache_key(first) != norm.cache_key(second)
    assert norm(first) != norm(second)


def test_decorator_invalidate():
    calls = []

    @cached(ttl="1m")
    def fetch(key):
        calls.append(key)
        return len(key)

    fetch("abc")
    fetch("abc")
    assert len(calls) == 1
    fetch.invalidate("abc")
    fetch("abc")
    assert len(calls) == 2


def test_decorator_does_not_cache_exceptions():
    calls = []

    @cached(ttl="1m")
    def flaky(should_fail):
        calls.append(should_fail)
        if should_fail:
            raise RuntimeError("downstream is down")
        return "ok"

    with pytest.raises(RuntimeError):
        flaky(True)
    with pytest.raises(RuntimeError):
        flaky(True)
    assert len(calls) == 2, "a failure must never be cached"


def test_decorator_fails_open_when_sidecar_is_unreachable():
    """The contract that matters most: a broken cache degrades to an uncached call, never to an error."""
    from fastcache_ai.client import FastCacheClient

    dead = FastCacheClient(host="127.0.0.1", port=1, timeout=0.2)

    @cached(ttl="1m", client=dead)
    def compute(value):
        return value * 2

    assert compute(21) == 42


@pytest.mark.asyncio
async def test_async_decorator():
    calls = []

    @cached(ttl="1m")
    async def fetch(prompt):
        calls.append(prompt)
        await asyncio.sleep(0)
        return prompt[::-1]

    assert await fetch("stream") == "maerts"
    assert await fetch("stream") == "maerts"
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_async_concurrent_reads():
    fastcache.put("shared", {"tokens": 128})
    results = await asyncio.gather(*(asyncio.to_thread(fastcache.get, "shared") for _ in range(64)))
    assert all(result == {"tokens": 128} for result in results)


# ---------------------------------------------------------------------------------------------------
# Concurrency
# ---------------------------------------------------------------------------------------------------

def test_concurrent_clients_share_one_pool():
    errors = []

    def worker(index):
        try:
            for i in range(50):
                key = f"t{index}:{i}"
                fastcache.put(key, {"worker": index, "i": i})
                assert fastcache.get(key) == {"worker": index, "i": i}
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)

    threads = [threading.Thread(target=worker, args=(n,)) for n in range(16)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=60)

    assert not errors, f"concurrent access failed: {errors[:3]}"


# ---------------------------------------------------------------------------------------------------
# Engine behaviour
# ---------------------------------------------------------------------------------------------------

def test_stats_shape():
    fastcache.put("k", "v")
    fastcache.get("k")
    stats = fastcache.stats()
    assert stats["engine"]["shards"] == int(os.environ.get("FASTCACHE_SHARDS", "32"))
    assert stats["engine"]["entries"] >= 1
    assert 0.0 <= stats["engine"]["memory_ratio"] <= 1.0
    assert "hits" in stats["l1"]
    assert stats["client"]["network_gets"] >= 1


def test_oversized_payload_is_rejected_not_crashed():
    """Back-pressure is a status code, never an exception from the engine and never an OOM.

    Compression is disabled for this client on purpose: the engine's ceiling applies to what actually
    goes over the wire, and 65 MB of repeated bytes zstd-compresses to a few kilobytes — which would
    sail past the limit and prove nothing.
    """
    from fastcache_ai.client import FastCacheClient
    from fastcache_ai.errors import WriteRejected

    host, port = fastcache.ensure_sidecar()
    uncompressed = FastCacheClient(
        host=host, port=port, timeout=30.0, codec=Codec(min_compress_bytes=1 << 40)
    )
    try:
        oversized = b"x" * (65 * 1024 * 1024)  # Above the 64 MiB default ceiling.
        with pytest.raises(WriteRejected) as excinfo:
            uncompressed.put("too-big", oversized, raise_on_reject=True)
        assert excinfo.value.retryable is False, "too-large is permanent, not back-pressure"
        assert fastcache.ping(), "a rejected write must leave the connection and engine healthy"
    finally:
        uncompressed.close()


def test_keys_distribute_across_shards():
    """Murmur3 routing is the default, so sequential keys must now balance well.

    The old raw-``String.hashCode`` router measured ~2.3x max/mean on this exact key pattern. The bound
    below is the acceptance criterion for the routing change: under 1.5x, with headroom for the sampling
    noise of a 2000-key population across 32 shards.
    """
    for i in range(2000):
        fastcache.put(f"spread:{i}", i)
    stats = fastcache.stats()["engine"]
    assert stats["shard_skew"] < 1.5, f"murmur3 routing failed to balance: {stats['shard_skew']}"
    assert stats["entries"] >= 2000


def test_info_reports_environment():
    details = fastcache.info()
    assert details["version"] == fastcache.__version__
    assert details["resolved"] is not None
    assert "numpy" in details["codecs"]
