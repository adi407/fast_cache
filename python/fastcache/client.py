"""Socket streaming driver: the Python side of the wire.

Three properties this client is built around:

* **Pooled connections.** Opening a TCP connection per operation would add a handshake to every cache hit,
  and the cache exists to remove latency. Sockets are pooled in a LIFO queue so the most recently used
  (and therefore warmest, largest-congestion-window) connection is reused first.
* **Exactly one retry.** A pooled socket can be closed underneath us — the sidecar idled out, a laptop
  slept, the JVM restarted. One transparent reconnect-and-retry covers all of those. A second retry would
  only turn a real outage into a slow one, so failures past that surface immediately.
* **Split sends for big payloads.** A 50 MB context window is *not* concatenated onto its header; header
  and body are sent separately so peak memory stays at one copy of the payload instead of two.
"""

from __future__ import annotations

import os
import queue
import socket
import struct
import threading
from typing import Any, Dict, NamedTuple, Optional

from . import bootstrap
from .codec import Codec
from .errors import FastCacheError, ProtocolError, SidecarUnavailable, WriteRejected
from .l1 import MISS, HotKeyCache
from .protocol import (
    OP_DELETE,
    OP_FLUSH,
    OP_GET,
    OP_HEARTBEAT,
    OP_PING,
    OP_PUT,
    OP_REFRESH_DONE,
    OP_REFRESH_LEASE,
    OP_STATS,
    REQUEST_HEADER,
    REQUEST_MAGIC,
    RESPONSE_HEADER,
    RESPONSE_HEADER_BYTES,
    RESPONSE_MAGIC,
    ST_MISS,
    ST_OK,
    ST_STALE,
    status_name,
)
from .ttl import parse_ttl

#: Payloads at or above this size are sent as a separate ``sendall`` instead of being concatenated.
_SPLIT_SEND_THRESHOLD = 64 * 1024

#: Seconds between liveness beats. A quarter of the engine's 40s orphan window, so three consecutive
#: beats can be lost to a GC pause or a busy event loop before the sidecar concludes we are dead.
_HEARTBEAT_INTERVAL_SECONDS = float(os.environ.get("FASTCACHE_HEARTBEAT_INTERVAL", "10"))

_MISSING = object()


class Fetched(NamedTuple):
    """Outcome of a read, including whether the value came from the stale-while-revalidate window.

    ``stale=True`` means: this value is usable, serve it now, and somebody should refresh the key. The
    decorator uses it to decide whether to attempt a single-flight refresh or simply return.
    """

    found: bool
    value: Any
    stale: bool
    from_l1: bool = False


class FastCacheClient:
    """Thread-safe client for one sidecar.

    :param host: sidecar host; ``None`` resolves via the bootstrap autopilot
    :param port: sidecar port; ``None`` resolves via the bootstrap autopilot
    :param pool_size: maximum pooled connections
    :param timeout: per-operation socket timeout in seconds
    :param l1: hot-key cache, or ``None`` to build the default one
    """

    def __init__(
        self,
        host: Optional[str] = None,
        port: Optional[int] = None,
        pool_size: int = 32,
        timeout: float = 5.0,
        codec: Optional[Codec] = None,
        l1: Optional[HotKeyCache] = None,
        namespace: str = "",
    ) -> None:
        self._host = host
        self._port = port
        self._pool_size = pool_size
        self._timeout = timeout
        self._codec = codec or Codec()
        self._namespace = namespace
        self.l1 = l1 if l1 is not None else HotKeyCache(
            threshold=int(os.environ.get("FASTCACHE_L1_THRESHOLD", "8")),
            window_seconds=float(os.environ.get("FASTCACHE_L1_WINDOW", "1.0")),
            ttl_seconds=float(os.environ.get("FASTCACHE_L1_TTL", "5.0")),
            capacity=int(os.environ.get("FASTCACHE_L1_CAPACITY", "512")),
        )

        self._pool: "queue.LifoQueue[socket.socket]" = queue.LifoQueue(maxsize=pool_size)
        self._lock = threading.Lock()
        self._closed = False
        self._network_gets = 0
        self._network_puts = 0
        self._stale_serves = 0

        self._heartbeat_thread: Optional[threading.Thread] = None
        self._heartbeat_stop = threading.Event()
        self._heartbeat_interval = _HEARTBEAT_INTERVAL_SECONDS

    # -- connection management ----------------------------------------------------------------------

    def _address(self) -> tuple:
        if self._host is None or self._port is None:
            # Resolution is deferred to first use, not done at import: an import must never block on a
            # JVM start, and a process that imports fastcache but never calls it should pay nothing.
            self._host, self._port = bootstrap.ensure_sidecar()
        self._ensure_heartbeat()
        return self._host, self._port

    def _connect(self) -> socket.socket:
        host, port = self._address()
        try:
            sock = socket.create_connection((host, port), timeout=self._timeout)
        except OSError as exc:
            raise SidecarUnavailable(f"cannot reach FastCache sidecar at {host}:{port}: {exc}") from exc
        # Latency over throughput: these are small request frames and Nagle would add up to 40ms.
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.settimeout(self._timeout)
        return sock

    def _acquire(self) -> socket.socket:
        if self._closed:
            raise SidecarUnavailable("client is closed")
        try:
            return self._pool.get_nowait()
        except queue.Empty:
            return self._connect()

    def _release(self, sock: socket.socket) -> None:
        if self._closed:
            _close_quietly(sock)
            return
        try:
            self._pool.put_nowait(sock)
        except queue.Full:
            _close_quietly(sock)  # Pool is saturated; this connection is surplus.

    # -- request/response ---------------------------------------------------------------------------

    def _execute(self, opcode: int, key: bytes = b"", value: bytes = b"", flags: int = 0,
                 ttl_millis: int = 0, source_chars: int = 0) -> tuple:
        """Runs one operation, transparently reconnecting once on a stale pooled socket."""
        last_error: Optional[Exception] = None
        for attempt in range(2):
            sock = self._acquire()
            try:
                self._send(sock, opcode, key, value, flags, ttl_millis, source_chars)
                response = self._receive(sock)
                self._release(sock)
                return response
            except (OSError, ProtocolError, struct.error) as exc:
                _close_quietly(sock)
                last_error = exc
                if attempt == 0:
                    continue  # Almost certainly a recycled dead socket; try once with a fresh one.
                break
        raise SidecarUnavailable(f"FastCache operation failed: {last_error}") from last_error

    def _send(self, sock: socket.socket, opcode: int, key: bytes, value: bytes,
              flags: int, ttl_millis: int, source_chars: int = 0) -> None:
        header = REQUEST_HEADER.pack(REQUEST_MAGIC, opcode, flags & 0xFF, len(key), ttl_millis,
                                     len(value), max(0, source_chars))
        if len(value) >= _SPLIT_SEND_THRESHOLD:
            # Two syscalls, one copy of the payload. Concatenating instead would briefly hold two copies
            # of a 50 MB tensor in Python memory, which is exactly the allocation this design avoids.
            sock.sendall(header + key)
            sock.sendall(value)
        else:
            sock.sendall(header + key + value)

    def _receive(self, sock: socket.socket) -> tuple:
        header = _recv_exactly(sock, RESPONSE_HEADER_BYTES)
        magic, status, flags, length = RESPONSE_HEADER.unpack(header)
        if magic != RESPONSE_MAGIC:
            raise ProtocolError(f"bad response magic 0x{magic:04x}; is something else on this port?")
        payload = _recv_exactly(sock, length) if length else b""
        return status, flags, payload

    # -- operations ---------------------------------------------------------------------------------

    def _qualify(self, key: str) -> str:
        return f"{self._namespace}:{key}" if self._namespace else key

    def get(self, key: str, default: Any = None) -> Any:
        """Reads a value, checking the L1 hot-key cache before touching the network."""
        result = self.fetch(key)
        return result.value if result.found else default

    def fetch(self, key: str) -> Fetched:
        """Reads a value and reports how it was obtained.

        Unlike :meth:`get`, this distinguishes a fresh hit from one served out of the grace window, which
        is what lets a caller decide whether to run a single-flight refresh.
        """
        qualified = self._qualify(key)

        local = self.l1.lookup(qualified)
        if local is not MISS:
            return Fetched(True, local, stale=False, from_l1=True)

        self._network_gets += 1
        status, flags, payload = self._execute(OP_GET, qualified.encode("utf-8"))
        if status == ST_MISS:
            return Fetched(False, None, stale=False)
        if status not in (ST_OK, ST_STALE):
            raise ProtocolError(f"GET {key} returned {status_name(status)}")

        value = self._codec.decode(payload, flags)
        stale = status == ST_STALE
        if stale:
            self._stale_serves += 1
        else:
            # Only fresh values are promoted into L1. Promoting a stale one would pin a value that is
            # already due for refresh into local memory for another full L1 TTL, compounding staleness.
            self.l1.admit(qualified, value)
        return Fetched(True, value, stale=stale)

    def put(self, key: str, value: Any, ttl: Any = None, raise_on_reject: bool = False) -> int:
        """Writes a value.

        :param ttl: any TTL spec (``"15m"``, ``"never"``, milliseconds, or ``None`` for the server default)
        :param raise_on_reject: raise :class:`WriteRejected` instead of returning the status code
        :return: the wire status (``0`` = stored)
        """
        qualified = self._qualify(key)
        payload, flags, characters = self._codec.encode(value)
        self._network_puts += 1
        status, _flags, _body = self._execute(
            OP_PUT, qualified.encode("utf-8"), payload, flags, parse_ttl(ttl), characters
        )
        # Invalidate locally even on rejection: the L1 copy is now definitely not what the caller believes
        # is cached, and serving it would compound the divergence.
        self.l1.invalidate(qualified)
        if status != ST_OK and raise_on_reject:
            raise WriteRejected(status, f"PUT {key} rejected: {status_name(status)}")
        return status

    #: Alias for people with Redis muscle memory.
    set = put

    def delete(self, key: str) -> bool:
        qualified = self._qualify(key)
        status, _flags, _payload = self._execute(OP_DELETE, qualified.encode("utf-8"))
        self.l1.invalidate(qualified)
        return status == ST_OK

    def try_refresh_lease(self, key: str) -> bool:
        """Asks the engine for permission to be the one that recomputes ``key``.

        Granted to exactly one caller across every process on the host, which is what makes the stampede
        defence work for a multi-worker deployment (gunicorn, a Celery pool) and not merely within one
        interpreter. Failure to reach the sidecar returns True: if the coordinator is unreachable, doing
        the work is far better than nobody doing it.
        """
        try:
            status, _flags, _payload = self._execute(OP_REFRESH_LEASE, self._qualify(key).encode("utf-8"))
            return status == ST_OK
        except FastCacheError:
            return True

    def complete_refresh(self, key: str) -> None:
        """Releases a refresh lease. Safe to call even if the lease was never granted."""
        try:
            self._execute(OP_REFRESH_DONE, self._qualify(key).encode("utf-8"))
        except FastCacheError:
            pass  # The lease expires on its own; a lost release costs one lease period, not correctness.

    def heartbeat(self) -> bool:
        """Sends one liveness beat plus this client's L1 telemetry.

        Two jobs in one frame: it keeps the engine's orphan watchdog satisfied, and it is the only way the
        console can learn about reads that were short-circuited in Python and never crossed the socket.
        """
        try:
            hits, characters = self.l1.telemetry()
            report = f"l1_hits={hits} l1_chars={characters}".encode("utf-8")
            status, _flags, _payload = self._execute(OP_HEARTBEAT, b"", report)
            return status == ST_OK
        except FastCacheError:
            return False

    def _ensure_heartbeat(self) -> None:
        """Starts the beat thread once, lazily, after an address is known."""
        if self._heartbeat_interval <= 0 or self._closed:
            return
        with self._lock:
            if self._heartbeat_thread is not None and self._heartbeat_thread.is_alive():
                return
            self._heartbeat_stop.clear()
            # Daemon: this thread must never be the reason an interpreter refuses to exit. If the process
            # dies, the beats stop, and the sidecar reaps itself — which is precisely the intended chain.
            self._heartbeat_thread = threading.Thread(
                target=self._heartbeat_loop, name="fastcache-heartbeat", daemon=True
            )
            self._heartbeat_thread.start()

    def _heartbeat_loop(self) -> None:
        while not self._heartbeat_stop.wait(self._heartbeat_interval):
            if self._closed:
                return
            try:
                self.heartbeat()
            except Exception:  # noqa: BLE001 - a beat failure is never worth killing the thread over
                pass

    def ping(self) -> bool:
        try:
            status, _flags, _payload = self._execute(OP_PING)
            return status == ST_OK
        except SidecarUnavailable:
            return False

    def stats(self) -> Dict[str, Any]:
        """Engine telemetry, with this client's L1 counters merged in."""
        _status, _flags, payload = self._execute(OP_STATS)
        engine: Dict[str, Any] = {}
        for token in payload.decode("utf-8").split():
            name, _, raw = token.partition("=")
            engine[name] = _coerce(raw)
        return {
            "engine": engine,
            "l1": self.l1.stats(),
            "client": {
                "network_gets": self._network_gets,
                "network_puts": self._network_puts,
                "stale_serves": self._stale_serves,
                "pooled_connections": self._pool.qsize(),
                "address": f"{self._host}:{self._port}",
            },
        }

    def flush(self) -> int:
        """Drops every entry in the engine and in this client's L1."""
        _status, _flags, payload = self._execute(OP_FLUSH)
        self.l1.clear()
        return int(payload.decode("utf-8") or 0)

    def close(self) -> None:
        """Closes pooled connections. Does not stop the sidecar — other processes may be using it."""
        self._heartbeat_stop.set()
        # Flush L1 telemetry before the socket goes away. L1 hits never cross the wire, so the heartbeat
        # is the engine's only view of them; a script that runs for less than one beat interval would
        # otherwise have its entire local-cache contribution silently missing from the savings figure.
        if self._host is not None and self._port is not None and not self._closed:
            try:
                self.heartbeat()
            except Exception:  # noqa: BLE001 - a telemetry flush must never fail a close
                pass
        with self._lock:
            self._closed = True
        while True:
            try:
                _close_quietly(self._pool.get_nowait())
            except queue.Empty:
                return

    def __enter__(self) -> "FastCacheClient":
        return self

    def __exit__(self, *exc_info) -> None:
        self.close()


# ---------------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------------

def _recv_exactly(sock: socket.socket, count: int) -> bytes:
    """Reads exactly ``count`` bytes.

    ``recv_into`` over a preallocated ``memoryview`` avoids the quadratic concatenation a naive
    ``buffer += chunk`` loop produces, which on a 50 MB read is the difference between milliseconds and
    seconds.
    """
    buffer = bytearray(count)
    view = memoryview(buffer)
    read = 0
    while read < count:
        chunk = sock.recv_into(view[read:], count - read)
        if not chunk:
            raise ConnectionError(f"connection closed with {count - read} bytes outstanding")
        read += chunk
    return bytes(buffer)


def _close_quietly(sock: socket.socket) -> None:
    try:
        sock.close()
    except OSError:
        pass


def _coerce(raw: str) -> Any:
    """Parses a STATS token value into int/float/bool, leaving anything else as a string."""
    if raw in {"true", "false"}:
        return raw == "true"
    try:
        return int(raw)
    except ValueError:
        try:
            return float(raw)
        except ValueError:
            return raw
