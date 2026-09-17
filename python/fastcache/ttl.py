"""TTL grammar shared with the Java engine: ``500ms``, ``30s``, ``15m``, ``2h``, ``7d``, ``never``.

A bare number means *milliseconds*, matching ``io.fastcache.engine.util.TimeSpec``. Keeping one grammar
across both languages means ``ttl="15m"`` is copy-pasteable between a Spring annotation and a Python
decorator, which is most of what "one cache, two runtimes" has to mean in practice.
"""

from __future__ import annotations

import re
from typing import Union

from .protocol import TTL_NEVER

_PATTERN = re.compile(r"^(?P<magnitude>\d+(?:\.\d+)?)\s*(?P<unit>ms|s|sec|secs|m|min|mins|h|hr|hrs|d|day|days)?$")

_MULTIPLIERS = {
    None: 1,
    "ms": 1,
    "s": 1_000,
    "sec": 1_000,
    "secs": 1_000,
    "m": 60_000,
    "min": 60_000,
    "mins": 60_000,
    "h": 3_600_000,
    "hr": 3_600_000,
    "hrs": 3_600_000,
    "d": 86_400_000,
    "day": 86_400_000,
    "days": 86_400_000,
}


def parse_ttl(spec: Union[str, int, float, None]) -> int:
    """Converts a TTL spec into integer milliseconds.

    Returns ``TTL_NEVER`` (-1) for eternal entries and ``0`` for "use the server default".
    """
    if spec is None:
        return 0
    if isinstance(spec, (int, float)):
        return TTL_NEVER if spec <= 0 else int(spec)

    text = spec.strip().lower()
    if not text:
        return 0
    if text in {"never", "inf", "infinite"}:
        return TTL_NEVER

    match = _PATTERN.match(text)
    if not match:
        raise ValueError(f"unparseable ttl: {spec!r} (expected e.g. '15m', '30s', '500ms', 'never')")

    millis = float(match.group("magnitude")) * _MULTIPLIERS[match.group("unit")]
    return TTL_NEVER if millis <= 0 else int(millis)


def format_ttl(millis: int) -> str:
    """Inverse of :func:`parse_ttl`, for logs and ``repr``."""
    if millis == TTL_NEVER:
        return "never"
    for unit_millis, suffix in ((86_400_000, "d"), (3_600_000, "h"), (60_000, "m"), (1_000, "s")):
        if millis % unit_millis == 0:
            return f"{millis // unit_millis}{suffix}"
    return f"{millis}ms"
