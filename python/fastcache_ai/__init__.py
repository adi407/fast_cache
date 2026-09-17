"""FastCache — a zero-infrastructure cache for AI workloads.

Installed as ``fastcache-ai``, imported as ``fastcache_ai``. The import name carries the suffix
because the bare ``fastcache`` name on PyPI already belongs to an unrelated C implementation of
``functools.lru_cache``; sharing it would make the two packages overwrite each other on disk.

.. code-block:: python

    from fastcache_ai import fastcache

    @fastcache(ttl="15m")
    def embed(prompt: str):
        return model.encode(prompt)

Importing this package starts a background thread that locates or boots the Java engine. The import itself
never blocks and never raises: if no JVM can be found, the decorator degrades to calling your function, and
the reason is available from :func:`info`.

Set ``FASTCACHE_AUTOSTART=0`` to skip the background warm-up; the sidecar will then be started lazily on
first cache use instead.
"""

from __future__ import annotations

import logging
import os
import threading
from typing import Any, Dict, Optional

from .bootstrap import console_url, ensure_sidecar, shutdown_sidecar
from .client import FastCacheClient
from .codec import Codec, describe_capabilities
from .decorator import build_key, default_client, fastcache, set_default_client, single_flight_stats
from .errors import (
    FastCacheError,
    ProtocolError,
    SerializationError,
    SidecarStartupError,
    SidecarUnavailable,
    WriteRejected,
)
from .l1 import HotKeyCache, NullL1
from .ttl import format_ttl, parse_ttl

__version__ = "1.1.0"

__all__ = [
    "fastcache",
    "FastCacheClient",
    "HotKeyCache",
    "NullL1",
    "Codec",
    "FastCacheError",
    "SidecarUnavailable",
    "SidecarStartupError",
    "ProtocolError",
    "WriteRejected",
    "SerializationError",
    "get",
    "put",
    "set",
    "delete",
    "stats",
    "flush",
    "ping",
    "info",
    "configure",
    "ensure_sidecar",
    "shutdown_sidecar",
    "console_url",
    "dashboard",
    "single_flight_stats",
    "default_client",
    "set_default_client",
    "build_key",
    "parse_ttl",
    "format_ttl",
    "__version__",
]

log = logging.getLogger("fastcache")

_startup_error: Optional[BaseException] = None
_startup_thread: Optional[threading.Thread] = None


# ---------------------------------------------------------------------------------------------------
# Module-level convenience API
# ---------------------------------------------------------------------------------------------------

def get(key: str, default: Any = None) -> Any:
    """Reads a value from the shared client."""
    return default_client().get(key, default)


def put(key: str, value: Any, ttl: Any = None) -> int:
    """Writes a value through the shared client. Returns the wire status (0 = stored)."""
    return default_client().put(key, value, ttl=ttl)


#: Redis-style alias for :func:`put`.
set = put  # noqa: A001 - shadowing the builtin is intentional and scoped to this module's namespace


def delete(key: str) -> bool:
    return default_client().delete(key)


def stats() -> Dict[str, Any]:
    """Engine, L1 and client telemetry."""
    return default_client().stats()


def flush() -> int:
    """Drops every cached entry. Mostly useful between test cases."""
    return default_client().flush()


def ping() -> bool:
    return default_client().ping()


def configure(**kwargs: Any) -> FastCacheClient:
    """Replaces the shared client with one built from ``kwargs``.

    .. code-block:: python

        fastcache.configure(host="10.0.0.5", port=7431, timeout=2.0)
        fastcache.configure(l1=fastcache.NullL1())   # disable local caching entirely
    """
    client = FastCacheClient(**kwargs)
    set_default_client(client)
    return client


def dashboard(open_browser: bool = False) -> Optional[str]:
    """Returns the management console URL, optionally opening it.

    .. code-block:: python

        fastcache.dashboard(open_browser=True)   # live savings, memory and hit-rate console

    Returns None when the console is disabled (``FASTCACHE_METRICS_PORT=off``) or its port was already
    taken — the cache itself is unaffected either way.
    """
    ensure_sidecar()
    url = console_url()
    if url and open_browser:
        import webbrowser

        webbrowser.open(url)
    return url


def info() -> Dict[str, Any]:
    """Everything needed to diagnose a FastCache problem in one call."""
    from . import bootstrap

    details = bootstrap.info()
    details["version"] = __version__
    details["codecs"] = describe_capabilities()
    details["autostart_error"] = repr(_startup_error) if _startup_error else None
    details["single_flight"] = single_flight_stats()
    return details


# ---------------------------------------------------------------------------------------------------
# Import-time autopilot
# ---------------------------------------------------------------------------------------------------

def _autostart() -> None:
    """Warms the sidecar in the background.

    Running on a daemon thread is the point: booting a JVM takes a few hundred milliseconds, and an
    ``import`` that blocks that long is one people work around. By the time the first cache call arrives,
    the engine is usually already up; if it is not, the call resolves it synchronously.

    Failures are recorded, never raised. A missing JDK on a developer laptop must not break
    ``import fastcache_ai`` at the top of a training script.
    """
    global _startup_error
    try:
        ensure_sidecar()
    except BaseException as exc:  # noqa: BLE001 - an import must not be able to fail here
        _startup_error = exc
        log.debug("fastcache: sidecar autostart deferred (%s)", exc)


if os.environ.get("FASTCACHE_AUTOSTART", "1").lower() not in {"0", "false", "no"}:
    _startup_thread = threading.Thread(target=_autostart, name="fastcache-autostart", daemon=True)
    _startup_thread.start()
