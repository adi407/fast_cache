"""Exception hierarchy for the FastCache Python client.

Design rule: none of these escape the ``@fastcache`` decorator. The decorator is fail-open, so a dead
sidecar degrades an application to "uncached", never to "broken". These exist for code using the client
API directly, where the caller does want to know.
"""

from __future__ import annotations


class FastCacheError(Exception):
    """Base class for every FastCache failure."""


class SidecarUnavailable(FastCacheError):
    """The Java engine could not be reached, started, or located."""


class SidecarStartupError(SidecarUnavailable):
    """The sidecar process was launched but never announced a bound port."""


class ProtocolError(FastCacheError):
    """The sidecar returned a frame this client cannot parse (version skew, or not a FastCache server)."""


class WriteRejected(FastCacheError):
    """The engine refused a write.

    Carries the wire status so callers can distinguish back-pressure (retry later, shed load) from a
    permanent problem (payload too large — retrying will never help).
    """

    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status

    @property
    def retryable(self) -> bool:
        from .protocol import ST_REJECTED_MEMORY

        return self.status == ST_REJECTED_MEMORY


class SerializationError(FastCacheError):
    """The value could not be encoded for transport, or the stored bytes could not be decoded."""
