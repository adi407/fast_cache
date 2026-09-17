"""Wire protocol constants and framing. Must stay byte-identical to ``io.fastcache.engine.net.Protocol``.

    REQUEST  (22-byte header, big-endian)
      u16 magic=0xFC01 | u8 opcode | u8 flags | u16 keyLen | i64 ttlMillis | i32 valueLen
      | i32 sourceChars | key | value

    RESPONSE (8-byte header, big-endian)
      u16 magic=0xFC02 | u8 status | u8 flags | i32 valueLen | value

Fixed-width headers mean the reader always issues exactly two recvs and never scans for a delimiter —
which matters when the payload is a 50 MB context window.

``sourceChars`` carries the *pre-compression* character count so the savings console can price a value by
its original text length. A zstd-compressed 40 KB prompt is a few hundred bytes on the wire; costing it by
wire size would under-report token savings by two orders of magnitude.
"""

from __future__ import annotations

import struct

REQUEST_MAGIC = 0xFC01
RESPONSE_MAGIC = 0xFC02

REQUEST_HEADER = struct.Struct(">HBBHqii")
RESPONSE_HEADER = struct.Struct(">HBBi")

REQUEST_HEADER_BYTES = REQUEST_HEADER.size   # 22
RESPONSE_HEADER_BYTES = RESPONSE_HEADER.size  # 8

MAX_KEY_BYTES = 65_535

# --- Opcodes -----------------------------------------------------------------------------------------
OP_GET = 1
OP_PUT = 2
OP_DELETE = 3
OP_PING = 4
OP_STATS = 5
OP_FLUSH = 6
OP_CLOSE = 7

#: Liveness beat carrying this client's L1 telemetry. Feeds the orphan watchdog and the savings console.
OP_HEARTBEAT = 8

#: Cross-process single-flight: granted to exactly one caller host-wide.
OP_REFRESH_LEASE = 9
OP_REFRESH_DONE = 10

# --- Response statuses -------------------------------------------------------------------------------
ST_OK = 0
ST_MISS = 1
ST_REJECTED_MEMORY = 2
ST_REJECTED_TOO_LARGE = 3
ST_REJECTED_SHUTDOWN = 4
ST_REJECTED_ALLOCATION = 5
ST_BAD_REQUEST = 6
ST_SERVER_ERROR = 7

#: A usable value served from the stale-while-revalidate window; also a signal to refresh the key.
ST_STALE = 8

# TTL sentinels, shared with the Java side.
TTL_NEVER = -1
TTL_SERVER_DEFAULT = 0

STATUS_NAMES = {
    ST_OK: "OK",
    ST_MISS: "MISS",
    ST_REJECTED_MEMORY: "REJECTED_MEMORY_PRESSURE",
    ST_REJECTED_TOO_LARGE: "REJECTED_TOO_LARGE",
    ST_REJECTED_SHUTDOWN: "REJECTED_SHUTDOWN",
    ST_REJECTED_ALLOCATION: "REJECTED_ALLOCATION_FAILED",
    ST_BAD_REQUEST: "BAD_REQUEST",
    ST_SERVER_ERROR: "SERVER_ERROR",
    ST_STALE: "STALE",
}


def status_name(status: int) -> str:
    return STATUS_NAMES.get(status, f"UNKNOWN({status})")


def encode_request(
    opcode: int,
    key: bytes = b"",
    value: bytes = b"",
    flags: int = 0,
    ttl_millis: int = TTL_SERVER_DEFAULT,
    source_chars: int = 0,
) -> bytes:
    """Builds a complete request frame.

    Header and body are concatenated into one ``bytes`` so the socket sees a single ``sendall``. Two
    separate sends would let Nagle interleave them badly on the small-request path.
    """
    if len(key) > MAX_KEY_BYTES:
        raise ValueError(f"key exceeds {MAX_KEY_BYTES} bytes: {len(key)}")
    header = REQUEST_HEADER.pack(
        REQUEST_MAGIC, opcode, flags & 0xFF, len(key), ttl_millis, len(value), max(0, source_chars)
    )
    return header + key + value
