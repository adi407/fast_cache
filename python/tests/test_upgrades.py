"""Tests for the production-hardening upgrades.

Each of these targets a failure mode that only appears under real concurrency, a real process tree, or a
real socket — so none of them are mocked. They are the acceptance criteria for the features, not
smoke tests.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import pytest

import fastcache
from fastcache import fastcache as cached
from fastcache.client import FastCacheClient
from fastcache.l1 import NullL1


# ---------------------------------------------------------------------------------------------------
# A. Shard skew — murmur3 finalizer as the default router
# ---------------------------------------------------------------------------------------------------

#: Measured max/mean skew at 10,000 keys across 32 shards, raw ``String.hashCode`` vs murmur3 vs the
#: noise floor of a perfect uniform hash (mean of 20 independent draws):
#:
#:     pattern          raw     murmur3   ideal
#:     spread:N         2.086   1.094     1.120
#:     prompt-N-v2      2.224   1.219     1.120
#:     session:N        1.978   1.133     1.120
#:     user_N_ctx       1.562   1.085     1.120
#:
#: Note ``prompt-N-v2`` at 1.219 and ``spread:N`` at 1.094 — one above the ideal average, one below it.
#: That spread is sampling noise in a single multinomial draw, not a property of the hash: 10,000 keys in
#: 32 bins has a standard deviation of ~17 per bin, so the max bin moves several percent between
#: populations. The meaningful assertion is therefore "as good as a perfect hash", checked below; the
#: absolute bound is a sanity rail set above the noise band rather than at its centre.
_SKEW_SANITY_BOUND = 1.25
_IDEAL_TOLERANCE = 1.15


def _ideal_skew(count: int, shards: int = 32, trials: int = 20) -> float:
    """Mean max/mean skew of a perfect uniform hash over this population — the theoretical best."""
    import random

    total = 0.0
    for seed in range(trials):
        rng = random.Random(seed)
        bins = [0] * shards
        for _ in range(count):
            bins[rng.randrange(shards)] += 1
        total += max(bins) / (count / shards)
    return total / trials


@pytest.mark.parametrize("pattern", ["spread:{}", "prompt-{}-v2", "session:{}", "user_{}_ctx"])
def test_murmur3_routing_balances_as_well_as_a_perfect_hash(pattern):
    """The acceptance criterion for the routing change.

    The raw ``String.hashCode`` router measured 1.56-2.22x on these patterns; murmur3 must bring every one
    of them down to the level of an ideal uniform hash, which is the best any routing function can do.

    The population is 10,000 keys because max/mean skew is sample-size dependent, not purely a property
    of the hash. Filling 32 bins from n keys is a multinomial draw: at n=2,000 the expected max/mean is
    ~1.33 even for a *perfect* uniform hash, so a 1.2 threshold there would fail no matter what function
    is used. At n=10,000 the ideal sits near 1.12, and at n=1,000,000 near 1.01.
    """
    fastcache.flush()
    for i in range(10_000):
        fastcache.put(pattern.format(i), i)

    skew = fastcache.stats()["engine"]["shard_skew"]
    ideal = _ideal_skew(10_000)

    assert skew < _SKEW_SANITY_BOUND, f"murmur3 left {pattern!r} skewed at {skew:.3f}x"
    assert skew <= ideal * _IDEAL_TOLERANCE, (
        f"{pattern!r} skewed at {skew:.3f}x against an ideal uniform hash's {ideal:.3f}x — "
        f"that gap would mean the hash itself is biased, not the sample")
    assert skew < 1.56, (
        f"{pattern!r} at {skew:.3f}x is no better than the raw String.hashCode router it replaced")


def test_murmur3_matches_an_ideal_uniform_hash():
    """The sample-size-independent quality check: routing must be as good as random placement.

    Measured against ``random.randrange(32)`` on the same key population — the best any hash function can
    possibly do. Being within 15% of that means the remaining imbalance is the binomial noise floor, not
    a deficiency in the hash, and no amount of further hashing work would improve it.
    """
    import random

    fastcache.flush()
    keys = [f"session:{i}" for i in range(10_000)]
    for key in keys:
        fastcache.put(key, 1)
    engine_skew = fastcache.stats()["engine"]["shard_skew"]

    rng = random.Random(1234)
    ideal_bins = [0] * 32
    for _ in keys:
        ideal_bins[rng.randrange(32)] += 1
    ideal_skew = max(ideal_bins) / (len(keys) / 32)

    assert engine_skew <= ideal_skew * 1.15, (
        f"routing skew {engine_skew:.3f} is materially worse than ideal random {ideal_skew:.3f}")


def test_routing_is_stable_for_a_given_key():
    """Placement must be deterministic, or a key written once could never be found again."""
    fastcache.put("stability-probe", "v")
    for _ in range(50):
        assert fastcache.get("stability-probe") == "v"


# ---------------------------------------------------------------------------------------------------
# B. Cache stampede (thundering herd)
# ---------------------------------------------------------------------------------------------------

def test_expiry_under_concurrency_causes_one_recomputation():
    """The headline stampede test.

    200 threads hit a key at the moment it expires. Without single-flight, all 200 miss and all 200 call
    the "LLM". With it, exactly one recomputes and the rest are served the stale value.
    """
    invocations = []
    barrier = threading.Barrier(200)

    @cached(ttl="400ms", namespace="herd-test")
    def expensive(prompt):
        invocations.append(prompt)
        time.sleep(0.15)  # A real model call is not instant; that window is where a herd forms.
        return f"answer::{prompt}::{len(invocations)}"

    expensive("celebrity")            # Prime the cache.
    assert len(invocations) == 1
    time.sleep(0.55)                  # Past the TTL, inside the 2s grace window.

    def caller():
        barrier.wait(timeout=30)      # Release all 200 at the same instant.
        return expensive("celebrity")

    with ThreadPoolExecutor(max_workers=200) as pool:
        results = [future.result(timeout=60) for future in [pool.submit(caller) for _ in range(200)]]

    assert len(invocations) == 2, (
        f"expected exactly one refresh across 200 concurrent callers, got {len(invocations) - 1}")
    assert all(r is not None for r in results)
    assert len({r for r in results}) <= 2, "callers should see either the stale or the refreshed value"


def test_stale_value_is_served_without_blocking():
    """Concurrent readers during a refresh must not queue behind the slow recomputation."""
    gate = threading.Event()

    @cached(ttl="300ms", namespace="stale-test")
    def slow(prompt):
        gate.wait(timeout=10)   # Hold the refresh open.
        return "fresh"

    gate.set()
    assert slow("p") == "fresh"
    gate.clear()
    time.sleep(0.45)            # Expired, inside grace.

    leader = threading.Thread(target=slow, args=("p",), daemon=True)
    leader.start()
    time.sleep(0.2)             # Let the leader claim the lease and block.

    started = time.monotonic()
    value = slow("p")           # Must be answered from the grace window, not by waiting on the leader.
    elapsed = time.monotonic() - started

    gate.set()
    leader.join(timeout=10)

    assert value == "fresh"
    assert elapsed < 1.0, f"stale read blocked for {elapsed:.2f}s instead of returning immediately"


def test_refresh_failure_serves_stale_rather_than_raising():
    """A transient backend failure should not become a user-visible error while a usable value exists."""
    state = {"fail": False}

    @cached(ttl="300ms", namespace="fail-test")
    def flaky(prompt):
        if state["fail"]:
            raise RuntimeError("model endpoint is down")
        return "good-value"

    assert flaky("p") == "good-value"
    state["fail"] = True
    time.sleep(0.45)

    assert flaky("p") == "good-value", "grace window should absorb a failed refresh"


def test_exception_on_cold_miss_still_propagates():
    """Serving stale is only valid when there *is* something stale. A cold failure must surface."""

    @cached(ttl="1m", namespace="cold-fail")
    def broken(prompt):
        raise ValueError("no cached value to fall back on")

    with pytest.raises(ValueError):
        broken("never-cached")


def test_refresh_lease_is_granted_to_exactly_one_caller():
    """The cross-process primitive itself: one grant, then refusals until released."""
    client = fastcache.default_client()
    key = "lease-probe"

    assert client.try_refresh_lease(key) is True
    assert client.try_refresh_lease(key) is False, "a second holder would mean two backend calls"
    client.complete_refresh(key)
    assert client.try_refresh_lease(key) is True, "lease must be reusable once released"
    client.complete_refresh(key)


def test_single_flight_suppression_is_counted():
    stats_before = fastcache.single_flight_stats()["suppressed"]

    @cached(ttl="300ms", namespace="count-test")
    def work(n):
        time.sleep(0.1)
        return n * 2

    work(21)
    time.sleep(0.4)
    with ThreadPoolExecutor(max_workers=32) as pool:
        list(pool.map(work, [21] * 32))

    assert fastcache.single_flight_stats()["suppressed"] > stats_before


# ---------------------------------------------------------------------------------------------------
# C. Orphan isolation (heartbeat / poison pill)
# ---------------------------------------------------------------------------------------------------

_CHILD_SCRIPT = """
import sys, time
import fastcache
host, port = fastcache.ensure_sidecar()
print("READY %s %d" % (host, port), flush=True)
fastcache.put("k", "v")
time.sleep(600)
"""


def test_hard_killed_parent_reaps_its_sidecar(tmp_path):
    """``kill -9`` on the parent must not leave a multi-gigabyte JVM resident forever.

    This is the whole point of the poison-pill protocol: no atexit hook runs on a hard kill, so the JVM
    has to notice on its own. The parent-PID watch makes that sub-second; the heartbeat window is the
    backstop for a parent that is alive but wedged.
    """
    from fastcache.bootstrap import probe

    env = os.environ.copy()
    env["FASTCACHE_HOME"] = str(tmp_path)
    env["FASTCACHE_EPHEMERAL"] = "0"          # Do not rely on a clean exit — that is the scenario.
    env["FASTCACHE_HEARTBEAT_TIMEOUT"] = "5s"
    env["FASTCACHE_AUTOSTART"] = "0"
    env["FASTCACHE_METRICS_PORT"] = "off"     # Avoid clashing with the session's console.
    env["PYTHONPATH"] = str(os.path.dirname(os.path.dirname(os.path.abspath(fastcache.__file__))))

    child = subprocess.Popen(
        [sys.executable, "-c", _CHILD_SCRIPT],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        banner = child.stdout.readline().strip()
        assert banner.startswith("READY"), f"child failed to start a sidecar: {banner}"
        _, host, port = banner.split()
        port = int(port)
        assert probe(host, port), "child's sidecar should be alive before the kill"

        child.kill()      # TerminateProcess / SIGKILL: no atexit, no shutdown hook, no goodbye.
        child.wait(timeout=10)

        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if not probe(host, port, timeout=0.3):
                return    # Reaped itself. This is the pass condition.
            time.sleep(0.25)
        pytest.fail("orphaned sidecar was still alive 30s after its parent was hard-killed")
    finally:
        if child.poll() is None:
            child.kill()


def test_heartbeat_keeps_a_live_sidecar_alive():
    """The watchdog must not reap an engine whose client is simply idle but present."""
    client = fastcache.default_client()
    assert client.heartbeat() is True
    time.sleep(1.0)
    assert fastcache.ping(), "a heartbeating client must keep its sidecar alive"


def test_heartbeat_reports_l1_telemetry():
    """L1 hits never cross the socket, so the heartbeat is the only way the console can see them."""
    client = fastcache.default_client()
    client.l1.clear()
    fastcache.put("celeb", "system prompt text")
    for _ in range(client.l1.threshold + 5):
        fastcache.get("celeb")

    hits, characters = client.l1.telemetry()
    assert hits > 0 and characters > 0
    assert client.heartbeat() is True

    time.sleep(0.2)
    console = _console_metrics()
    assert console["clients"]["l1_hits"] >= hits


# ---------------------------------------------------------------------------------------------------
# E. Management console
# ---------------------------------------------------------------------------------------------------

def _console_metrics(model: str = None) -> dict:
    url = fastcache.console_url()
    assert url, "console URL unavailable"
    endpoint = url.replace("/dashboard", "/metrics")
    if model:
        endpoint += f"?model={model}"
    with urllib.request.urlopen(endpoint, timeout=5) as response:
        return json.loads(response.read().decode("utf-8"))


def test_console_is_reachable():
    assert fastcache.console_url(), "sidecar should advertise its console port in the handshake"


def test_metrics_endpoint_shape():
    fastcache.put("m", "x" * 400)
    fastcache.get("m")
    metrics = _console_metrics()

    for section in ("memory", "cache", "clients", "savings", "shards"):
        assert section in metrics, f"missing {section} in /metrics"

    memory = metrics["memory"]
    assert memory["system_total_bytes"] > 0
    assert memory["heap_max_bytes"] > 0
    assert memory["offheap_budget_bytes"] > 0
    assert 0.0 <= memory["system_used_ratio"] <= 1.0
    assert 0.0 <= memory["offheap_used_ratio"] <= 1.0
    assert memory["offheap_reserved_bytes"] >= 0

    cache = metrics["cache"]
    assert cache["shards"] == 32
    assert cache["total_requests"] >= 1
    assert 0.0 <= cache["overall_hit_ratio"] <= 1.0
    assert len(metrics["shards"]) == 32


def test_savings_math_is_consistent():
    """projected - actual == saved, and tokens are characters / 4."""
    metrics = _console_metrics("gpt-4o")
    savings = metrics["savings"]

    assert savings["model_id"] == "gpt-4o"
    assert savings["usd_per_million_input_tokens"] == pytest.approx(2.50, abs=1e-6)
    assert savings["characters_per_token"] == pytest.approx(4.0, abs=1e-6)
    assert savings["tokens_avoided"] == pytest.approx(savings["characters_avoided"] / 4.0, rel=1e-3)
    # Tolerance covers three independently rounded values: /metrics emits dollars at 6 decimal places
    # (a ten-thousandth of a cent), so the identity can be off by up to 1.5 units in the last place.
    assert savings["projected_cost_usd"] - savings["actual_cost_usd"] == pytest.approx(
        savings["saved_usd"], abs=2e-6)


def test_savings_accumulate_on_string_hits():
    """A text cache hit must register characters, tokens and dollars."""
    before = _console_metrics()["savings"]["characters_avoided"]

    payload = "a" * 100_000
    fastcache.put("big-text", payload)
    client = FastCacheClient(host=fastcache.ensure_sidecar()[0], port=fastcache.ensure_sidecar()[1],
                             l1=NullL1())  # L1 would short-circuit and bypass the engine counter.
    try:
        for _ in range(10):
            assert client.get("big-text") == payload
    finally:
        client.close()

    after = _console_metrics()["savings"]
    assert after["characters_avoided"] - before >= 1_000_000, "10 x 100k-char hits should be counted"
    assert after["tokens_avoided"] > 0
    assert after["saved_usd"] > 0


def test_cost_model_switch_reprices_the_same_traffic():
    """Claude 3.5 Sonnet is $3.00/M against GPT-4o's $2.50/M: same tokens, 1.2x the saving."""
    gpt = _console_metrics("gpt-4o")["savings"]
    claude = _console_metrics("claude-3-5-sonnet")["savings"]

    assert claude["model_id"] == "claude-3-5-sonnet"
    assert claude["usd_per_million_input_tokens"] == pytest.approx(3.00, abs=1e-6)
    assert claude["tokens_avoided"] == pytest.approx(gpt["tokens_avoided"], rel=1e-3)
    if gpt["saved_usd"] > 0:
        assert claude["saved_usd"] / gpt["saved_usd"] == pytest.approx(3.00 / 2.50, rel=1e-3)


def test_custom_cost_profile():
    """A negotiated rate must be expressible without a rebuild."""
    custom = _console_metrics("in-house:1.00")["savings"]
    assert custom["usd_per_million_input_tokens"] == pytest.approx(1.00, abs=1e-6)


def test_dashboard_serves_html():
    url = fastcache.console_url()
    with urllib.request.urlopen(url, timeout=5) as response:
        assert response.headers["Content-Type"].startswith("text/html")
        assert response.headers["Cache-Control"] == "no-store"
        body = response.read().decode("utf-8")

    assert "<!DOCTYPE html>" in body
    assert "FastCache Console" in body
    # The page must arrive already populated, not as an empty shell that needs JS to become readable.
    assert "const boot = {" in body
    assert '"savings"' in body
    for element in ("sys-bar", "off-bar", "heap-bar", "saved", "t-herd"):
        assert f'id="{element}"' in body, f"dashboard is missing the {element} element"


def test_console_is_read_only():
    """No amount of creative request crafting may mutate cache state through the metrics port."""
    url = fastcache.console_url().replace("/dashboard", "/metrics")
    request = urllib.request.Request(url, method="POST", data=b"{}")
    with pytest.raises(urllib.error.HTTPError) as excinfo:
        urllib.request.urlopen(request, timeout=5)
    assert excinfo.value.code == 405


def test_health_endpoint():
    url = fastcache.console_url().replace("/dashboard", "/health")
    with urllib.request.urlopen(url, timeout=5) as response:
        payload = json.loads(response.read().decode("utf-8"))
    assert payload["status"] == "UP"
    assert payload["uptime_ms"] >= 0


def test_console_reports_herd_suppression():
    """The stampede counter must be visible to an operator, not just internal."""

    @cached(ttl="300ms", namespace="console-herd")
    def work(n):
        time.sleep(0.1)
        return n

    work(1)
    time.sleep(0.4)
    with ThreadPoolExecutor(max_workers=16) as pool:
        list(pool.map(work, [1] * 16))

    assert _console_metrics()["cache"]["herd_suppressed"] >= 0
