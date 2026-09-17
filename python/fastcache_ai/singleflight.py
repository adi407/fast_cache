"""Process-local single-flight, layered under the engine's host-wide refresh lease.

Two levels, because they solve different halves of the same problem:

* **Local (this module).** A thousand threads in one interpreter all missing the same key would otherwise
  each issue a ``REFRESH_LEASE`` round trip to the sidecar — a thousand network calls to discover that 999
  of them have nothing to do. The local gate collapses them to one before any socket is touched.
* **Host-wide (the engine's ``RefreshCoordinator``).** Eight gunicorn workers are eight interpreters; a
  local gate in each still lets eight backend calls through. Only the local winner asks the engine for the
  global lease, so the whole host makes exactly one.

Nothing here blocks forever. Every wait is bounded, and a follower whose leader never publishes falls back
to doing the work itself. A stampede defence that can deadlock a request path is worse than the stampede.
"""

from __future__ import annotations

import threading
import time
from typing import Dict, Tuple


class _Flight:
    """One in-progress computation. ``done`` is set by the leader when the value has been published."""

    __slots__ = ("done", "started_at", "waiters")

    def __init__(self) -> None:
        self.done = threading.Event()
        self.started_at = time.monotonic()
        self.waiters = 0


class SingleFlight:
    """Per-key leader election within one process."""

    def __init__(self) -> None:
        self._flights: Dict[str, _Flight] = {}
        self._lock = threading.Lock()
        self._leads = 0
        self._suppressed = 0
        self._timeouts = 0

    def begin(self, key: str) -> Tuple[bool, _Flight]:
        """Claims leadership of ``key``.

        :return: ``(is_leader, flight)``. The leader must call :meth:`complete` in a finally block.
        """
        with self._lock:
            flight = self._flights.get(key)
            if flight is None:
                flight = _Flight()
                self._flights[key] = flight
                self._leads += 1
                return True, flight
            flight.waiters += 1
            self._suppressed += 1
            return False, flight

    def complete(self, key: str, flight: _Flight) -> None:
        """Publishes completion and releases every waiter. Must run even on the exception path."""
        with self._lock:
            # Identity check: a slow leader may have had its entry replaced after a timeout, and removing
            # the newer flight would strand the threads waiting on it.
            if self._flights.get(key) is flight:
                del self._flights[key]
        flight.done.set()

    def wait(self, flight: _Flight, timeout: float) -> bool:
        """Parks until the leader publishes. Returns False on timeout."""
        completed = flight.done.wait(timeout)
        if not completed:
            self._timeouts += 1
        return completed

    def abandon(self, key: str, flight: _Flight) -> None:
        """Drops a flight a follower has given up on, so the next caller can lead instead of queueing."""
        with self._lock:
            if self._flights.get(key) is flight:
                del self._flights[key]
        flight.done.set()

    @property
    def in_flight(self) -> int:
        with self._lock:
            return len(self._flights)

    def stats(self) -> Dict[str, int]:
        with self._lock:
            return {
                "leads": self._leads,
                "suppressed": self._suppressed,
                "timeouts": self._timeouts,
                "in_flight": len(self._flights),
            }
