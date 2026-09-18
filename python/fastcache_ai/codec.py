"""High-performance serialization for the socket line.

The flags byte travels with every payload and is echoed back verbatim by the engine, so the cache itself
stays codec-agnostic — it moves opaque bytes and never parses a value. Layout::

    bits 0-3  format       0=raw 1=utf8 2=msgpack 3=pickle 4=numpy
    bits 4-7  compression  0=none 1=zstd 2=lz4 3=zlib

Selection policy, in order of preference:

* ``bytes``/``bytearray``/``memoryview`` → shipped as-is. No encoder can beat doing nothing.
* ``str`` → UTF-8. The dominant case for LLM context windows.
* ``numpy.ndarray`` → a small JSON header plus the raw buffer. This is the point of the whole module: an
  embedding matrix goes over the wire as its own memory, not as a pickle stream of Python objects.
* everything else → msgpack when installed (faster and smaller than pickle, and language-neutral), pickle
  otherwise.

Compression kicks in above ``min_compress_bytes`` and is *reverted* when it fails to shrink the payload —
spending CPU to make a 30 MB tensor 2% larger is a pure loss, and incompressible float data is common.

Security note: pickle decoding only ever runs against bytes this process put into its own loopback-bound
sidecar. Do not point a FastCache client at an untrusted host and decode pickled values from it.
"""

from __future__ import annotations

import json
import pickle
import threading
import zlib
from typing import Any, Optional, Tuple

from .errors import SerializationError

# --- Optional accelerators. Every one of these is a soft dependency: the client works without all of them.
try:  # pragma: no cover - import-time capability probe
    import numpy as _np
except ImportError:
    _np = None

try:  # pragma: no cover
    import msgpack as _msgpack
except ImportError:
    _msgpack = None

try:  # pragma: no cover
    import zstandard as _zstd
except ImportError:
    _zstd = None

try:  # pragma: no cover
    import lz4.frame as _lz4
except ImportError:
    _lz4 = None


FMT_RAW = 0
FMT_UTF8 = 1
FMT_MSGPACK = 2
FMT_PICKLE = 3
FMT_NUMPY = 4

COMP_NONE = 0
COMP_ZSTD = 1
COMP_LZ4 = 2
COMP_ZLIB = 3

_FORMAT_MASK = 0x0F
_COMPRESSION_SHIFT = 4

DEFAULT_MIN_COMPRESS_BYTES = 8 * 1024
_NUMPY_HEADER_LEN = 4  # big-endian u32 prefix on the JSON descriptor


def _best_compressor() -> int:
    """Picks the fastest available codec. zstd > lz4 > zlib, and zlib always exists in the stdlib."""
    if _zstd is not None:
        return COMP_ZSTD
    if _lz4 is not None:
        return COMP_LZ4
    return COMP_ZLIB


class Codec:
    """Stateless encoder/decoder. Cheap to construct; one instance per client is plenty."""

    __slots__ = ("min_compress_bytes", "compression", "_local")

    def __init__(
        self,
        min_compress_bytes: int = DEFAULT_MIN_COMPRESS_BYTES,
        compression: Optional[int] = None,
    ) -> None:
        self.min_compress_bytes = min_compress_bytes
        self.compression = _best_compressor() if compression is None else compression
        # Reusing a zstd context avoids re-initialising the compression tables per call, which at 50 MB
        # payloads is a measurable fraction of encode time. But a ZstdCompressor is NOT thread-safe:
        # sharing one across threads and calling compress() concurrently is a hard interpreter crash
        # (SIGSEGV), not an exception. One client is shared process-wide and every web server is
        # multi-threaded, so the contexts are kept thread-local - the reuse benefit without the crash.
        self._local = threading.local()

    @property
    def _zstd_compressor(self):
        """This thread's compressor, created on first use."""
        if _zstd is None:
            return None
        compressor = getattr(self._local, "compressor", None)
        if compressor is None:
            compressor = _zstd.ZstdCompressor(level=3)
            self._local.compressor = compressor
        return compressor

    @property
    def _zstd_decompressor(self):
        """This thread's decompressor, created on first use."""
        if _zstd is None:
            return None
        decompressor = getattr(self._local, "decompressor", None)
        if decompressor is None:
            decompressor = _zstd.ZstdDecompressor()
            self._local.decompressor = decompressor
        return decompressor

    # -- encode -----------------------------------------------------------------------------------

    def encode(self, value: Any) -> Tuple[bytes, int, int]:
        """Serializes ``value``.

        :return: ``(payload, flags, source_characters)`` ready for the wire
        """
        try:
            payload, fmt = self._serialize(value)
        except SerializationError:
            raise
        except Exception as exc:  # noqa: BLE001 - any encoder failure becomes one error type
            raise SerializationError(f"could not encode {type(value).__name__}: {exc}") from exc

        characters = count_characters(value)
        payload, compression = self._compress(payload)
        return payload, (compression << _COMPRESSION_SHIFT) | fmt, characters

    def _serialize(self, value: Any) -> Tuple[bytes, int]:
        if isinstance(value, (bytes, bytearray)):
            return bytes(value), FMT_RAW
        if isinstance(value, memoryview):
            return value.tobytes(), FMT_RAW
        if isinstance(value, str):
            return value.encode("utf-8"), FMT_UTF8
        if _np is not None and isinstance(value, _np.ndarray):
            return self._serialize_ndarray(value), FMT_NUMPY
        if _msgpack is not None:
            try:
                return _msgpack.packb(value, use_bin_type=True), FMT_MSGPACK
            except (TypeError, ValueError):
                pass  # Not a msgpack-representable graph; fall through to pickle.
        return pickle.dumps(value, protocol=pickle.HIGHEST_PROTOCOL), FMT_PICKLE

    @staticmethod
    def _serialize_ndarray(array: "Any") -> bytes:
        """``[u32 header_len][json header][raw buffer]``.

        ``ascontiguousarray`` is a no-op for the common case and a single copy for a sliced or transposed
        view — either way the wire format stays a flat buffer the receiver can rebuild with ``frombuffer``
        and reshape, with no per-element work.
        """
        if array.dtype == object:
            raise SerializationError("object-dtype arrays are not supported; convert to a concrete dtype")
        contiguous = _np.ascontiguousarray(array)
        header = json.dumps(
            {"dtype": contiguous.dtype.str, "shape": list(contiguous.shape)}, separators=(",", ":")
        ).encode("utf-8")
        return len(header).to_bytes(_NUMPY_HEADER_LEN, "big") + header + contiguous.tobytes()

    def _compress(self, payload: bytes) -> Tuple[bytes, int]:
        if len(payload) < self.min_compress_bytes:
            return payload, COMP_NONE
        try:
            if self.compression == COMP_ZSTD and self._zstd_compressor is not None:
                compressed = self._zstd_compressor.compress(payload)
            elif self.compression == COMP_LZ4 and _lz4 is not None:
                compressed = _lz4.compress(payload)
            else:
                compressed = zlib.compress(payload, level=1)
        except Exception:  # noqa: BLE001 - compression is an optimisation, never a failure mode
            return payload, COMP_NONE

        # Incompressible data (float tensors, already-compressed blobs) makes this larger. Keep the
        # original rather than paying decompression cost on every read for a negative saving.
        if len(compressed) >= len(payload):
            return payload, COMP_NONE
        return compressed, self.compression

    # -- decode -----------------------------------------------------------------------------------

    def decode(self, payload: bytes, flags: int) -> Any:
        fmt = flags & _FORMAT_MASK
        compression = (flags >> _COMPRESSION_SHIFT) & _FORMAT_MASK
        try:
            raw = self._decompress(payload, compression)
            return self._deserialize(raw, fmt)
        except SerializationError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise SerializationError(f"could not decode payload (flags=0x{flags:02x}): {exc}") from exc

    def _decompress(self, payload: bytes, compression: int) -> bytes:
        if compression == COMP_NONE:
            return payload
        if compression == COMP_ZSTD:
            if self._zstd_decompressor is None:
                raise SerializationError("payload is zstd-compressed but 'zstandard' is not installed")
            return self._zstd_decompressor.decompress(payload)
        if compression == COMP_LZ4:
            if _lz4 is None:
                raise SerializationError("payload is lz4-compressed but 'lz4' is not installed")
            return _lz4.decompress(payload)
        if compression == COMP_ZLIB:
            return zlib.decompress(payload)
        raise SerializationError(f"unknown compression id {compression}")

    @staticmethod
    def _deserialize(raw: bytes, fmt: int) -> Any:
        if fmt == FMT_RAW:
            return raw
        if fmt == FMT_UTF8:
            return raw.decode("utf-8")
        if fmt == FMT_MSGPACK:
            if _msgpack is None:
                raise SerializationError("payload is msgpack-encoded but 'msgpack' is not installed")
            return _msgpack.unpackb(raw, raw=False)
        if fmt == FMT_PICKLE:
            return pickle.loads(raw)
        if fmt == FMT_NUMPY:
            if _np is None:
                raise SerializationError("payload is a numpy array but 'numpy' is not installed")
            header_len = int.from_bytes(raw[:_NUMPY_HEADER_LEN], "big")
            start = _NUMPY_HEADER_LEN + header_len
            header = json.loads(raw[_NUMPY_HEADER_LEN:start])
            array = _np.frombuffer(raw, dtype=_np.dtype(header["dtype"]), offset=start)
            return array.reshape(header["shape"])
        raise SerializationError(f"unknown format id {fmt}")


#: Node budget for character counting. A 10k-element structure is measured exactly; beyond that the count
#: is truncated, which understates savings — the safe direction for a number shown as money.
_CHARACTER_SCAN_BUDGET = 10_000


def count_characters(value: Any) -> int:
    """Counts the text characters in a value, for input-token cost estimation.

    Plain strings are the common case and are measured exactly. Containers are walked because an LLM
    response is very often a ``dict`` wrapping the text rather than a bare string, and reporting zero
    savings for those would make the console look broken on the most ordinary usage there is.

    Bytes are deliberately *not* counted: they are not text, and guessing an encoding to pretend otherwise
    would inflate a figure presented in dollars.
    """
    remaining = _CHARACTER_SCAN_BUDGET
    total = 0
    stack = [value]
    while stack and remaining > 0:
        item = stack.pop()
        remaining -= 1
        if isinstance(item, str):
            total += len(item)
        elif isinstance(item, dict):
            # Keys are usually short field names, but they are still characters that were generated.
            stack.extend(item.keys())
            stack.extend(item.values())
        elif isinstance(item, (list, tuple, set, frozenset)):
            stack.extend(item)
    return total


def describe_capabilities() -> dict:
    """What this interpreter can actually do. Surfaced by ``fastcache.info()`` for support triage."""
    return {
        "numpy": _np is not None,
        "msgpack": _msgpack is not None,
        "zstandard": _zstd is not None,
        "lz4": _lz4 is not None,
        "preferred_compression": {
            COMP_ZSTD: "zstd",
            COMP_LZ4: "lz4",
            COMP_ZLIB: "zlib",
        }[_best_compressor()],
    }
