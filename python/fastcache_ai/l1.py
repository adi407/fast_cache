"""Client-side L1 cache: the hot-key hotfix.

The problem it solves is structural, not incidental. FastCache routes a key to exactly one shard. That is
what makes it fast — and it means every request for one key lands on one shard. When ten thousand users
chat with the same bot, they send the *same* system prompt, which hashes to the *same* shard, and that
single shard's map, its lock and the one socket-reading virtual thread serving it become the bottleneck
while the other 31 shards idle. Adding shards does not help: a celebrity key is a single point by
definition.

So the fix belongs in the client. Track per-key request frequency in a short window; once a key crosses the
threshold, serve it from Python memory for a few seconds. A key getting 5,000 requests/second collapses to
one network round trip every 5 seconds — a ~25,000x reduction in shard pressure — while cold keys go
straight to the sidecar with no added latency.

Cost of the trade: a promoted key can serve a value up to ``ttl_seconds`` stale. That window is the whole
tuning knob. Five seconds is right for prompt and embedding caches; set it to zero for anything where
staleness is a correctness problem.

Implementation notes:

* ``OrderedDict`` gives O(1) LRU with ``move_to_end``/``popitem``, and is C-implemented.
* One ``threading.Lock`` guards everything. Under the GIL, finer-grained locking buys nothing here and
  every operation below is a handful of dict lookups.
* The frequency counter is a fixed-window counter, not a sliding window. A ring buffer of timestamps per
  key would be more precise and would allocate on every access; for "is this key hot", a counter that
  resets each window is accurate enough and allocation-free.
"""

from __future__ import annotations

import threading
import time
from collections import OrderedDict
from typing import Any, Dict, Optional, Tuple


class _Missing:
    """Sentinel distinguishing "not in L1" from "cached value that happens to be None"."""

    __slots__ = ()

    def __repr__(self) -> str:  # pragma: no cover - debugging affordance
        return "<L1.MISS>"


MISS = _Missing()


def _characters_of(value: Any) -> int:
    """Text size of a promoted value, imported lazily to keep this module dependency-free at import."""
    try:
        from .codec import count_characters

        return count_characters(value)
    except Exception:  # noqa: BLE001 - telemetry must never break a cache path
        return 0


class HotKeyCache:
    """Frequency-gated, TTL-bounded local cache in front of the sidecar socket.

    :param threshold: requests within one window before a key is considered hot
    :param window_seconds: width of the frequency-counting window
    :param ttl_seconds: how long a promoted value is served locally (0 disables L1 entirely)
    :param capacity: maximum promoted keys held in Python memory
    """

    __slots__ = (
        "threshold", "window_seconds", "ttl_seconds", "capacity",
        "_values", "_counters", "_lock", "_hits", "_misses", "_promotions", "_expirations",
        "_characters_served",
    )

    def __init__(
        self,
        threshold: int = 8,
        window_seconds: float = 1.0,
        ttl_seconds: float = 5.0,
        capacity: int = 512,
    ) -> None:
        if threshold < 1:
            raise ValueError("threshold must be >= 1")
        self.threshold = threshold
        self.window_seconds = window_seconds
        self.ttl_seconds = ttl_seconds
        self.capacity = capacity

        # key -> (value, expires_at, source_characters). The character count is computed once at
        # promotion rather than on every hit: a hot key is read thousands of times per promotion, and
        # re-walking a nested response object on each of those would make L1 slower than the socket.
        self._values: "OrderedDict[str, Tuple[Any, float, int]]" = OrderedDict()
        self._counters: "OrderedDict[str, list]" = OrderedDict()  # key -> [count, window_start]
        self._lock = threading.Lock()

        self._hits = 0
        self._misses = 0
        self._promotions = 0
        self._expirations = 0
        self._characters_served = 0

    @property
    def enabled(self) -> bool:
        return self.ttl_seconds > 0

    def lookup(self, key: str) -> Any:
        """Records one access and returns the locally cached value, or :data:`MISS`.

        Every request goes through here, hit or miss — that is what makes the frequency count reflect real
        demand rather than only the misses.
        """
        if not self.enabled:
            return MISS

        now = time.monotonic()
        with self._lock:
            self._count_access(key, now)

            entry = self._values.get(key)
            if entry is None:
                self._misses += 1
                return MISS

            value, expires_at, characters = entry
            if now >= expires_at:
                del self._values[key]
                self._expirations += 1
                self._misses += 1
                return MISS

            self._values.move_to_end(key)
            self._hits += 1
            # Reported to the engine on the next heartbeat so the console can price L1 short-circuits.
            # These never touch the socket, so the engine cannot observe them any other way.
            self._characters_served += characters
            return value

    def admit(self, key: str, value: Any) -> bool:
        """Offers a freshly-fetched value for promotion.

        :return: True if the key was hot enough to be stored locally
        """
        if not self.enabled:
            return False

        now = time.monotonic()
        with self._lock:
            counter = self._counters.get(key)
            if counter is None or counter[0] < self.threshold:
                return False  # Not a celebrity key; leave it to the sidecar.

            self._values[key] = (value, now + self.ttl_seconds, _characters_of(value))
            self._values.move_to_end(key)
            self._promotions += 1

            while len(self._values) > self.capacity:
                self._values.popitem(last=False)  # Evict the coldest promoted key.
            return True

    def invalidate(self, key: str) -> None:
        """Drops a key locally. Called on writes and deletes so this process never reads its own stale value.

        Note the honest limit: this is *local*. Other processes keep serving their own promoted copy until
        its TTL lapses. L1 is eventually consistent by construction — a bounded staleness window is the
        price of removing the network from the hot path.
        """
        with self._lock:
            self._values.pop(key, None)

    def telemetry(self) -> Tuple[int, int]:
        """``(hits, characters_served)`` for the heartbeat frame. Cheap enough to call every few seconds."""
        with self._lock:
            return self._hits, self._characters_served

    def clear(self) -> None:
        with self._lock:
            self._values.clear()
            self._counters.clear()

    def stats(self) -> Dict[str, Any]:
        with self._lock:
            total = self._hits + self._misses
            return {
                "enabled": self.enabled,
                "entries": len(self._values),
                "tracked_keys": len(self._counters),
                "hits": self._hits,
                "misses": self._misses,
                "hit_ratio": (self._hits / total) if total else 0.0,
                "promotions": self._promotions,
                "expirations": self._expirations,
                "characters_served": self._characters_served,
                "threshold": self.threshold,
                "window_seconds": self.window_seconds,
                "ttl_seconds": self.ttl_seconds,
            }

    def hot_keys(self) -> Dict[str, int]:
        """Current in-window access counts. The diagnostic for "which prompt is melting a shard"."""
        with self._lock:
            return {key: counter[0] for key, counter in self._counters.items() if counter[0] >= self.threshold}

    # -- internals ----------------------------------------------------------------------------------

    def _count_access(self, key: str, now: float) -> None:
        """Fixed-window counter. Caller must hold the lock."""
        counter = self._counters.get(key)
        if counter is None:
            self._counters[key] = [1, now]
        elif now - counter[1] >= self.window_seconds:
            counter[0] = 1       # Window rolled over.
            counter[1] = now
        else:
            counter[0] += 1
        self._counters.move_to_end(key)

        # Bound the tracker independently of the value cache: an application with millions of distinct
        # cold keys must not grow this dict without limit. Tracking 8x capacity keeps enough history to
        # spot a rising key before it is promoted.
        limit = max(self.capacity * 8, 1024)
        while len(self._counters) > limit:
            self._counters.popitem(last=False)


class NullL1(HotKeyCache):
    """Disabled L1, for call sites where staleness is unacceptable. Same interface, no storage."""

    def __init__(self) -> None:
        super().__init__(threshold=1, window_seconds=1.0, ttl_seconds=0.0, capacity=0)

    def lookup(self, key: str) -> Any:
        return MISS

    def admit(self, key: str, value: Any) -> bool:
        return False
