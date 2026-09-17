"""Shared test environment.

Everything here must run *before* any test module imports ``fastcache``, because the package reads its
environment at import time. pytest loads ``conftest.py`` first, which is exactly the hook for that.
"""

from __future__ import annotations

import os
import tempfile

import pytest

# An isolated state directory per run, so a developer's real ~/.fastcache sidecar is never touched and two
# concurrent runs cannot attach to each other's engine.
os.environ.setdefault("FASTCACHE_HOME", tempfile.mkdtemp(prefix="fastcache-test-"))
os.environ.setdefault("FASTCACHE_EPHEMERAL", "1")
os.environ.setdefault("FASTCACHE_AUTOSTART", "0")   # Tests control startup explicitly.
os.environ.setdefault("FASTCACHE_METRICS_PORT", "0")  # Ephemeral console port: never fight over 8081.
os.environ.setdefault("FASTCACHE_HEARTBEAT_INTERVAL", "2")

import fastcache_ai as fastcache  # noqa: E402


@pytest.fixture(scope="session", autouse=True)
def sidecar():
    """Boots one engine for the whole session and tears it down afterwards."""
    fastcache.ensure_sidecar()
    assert fastcache.ping(), "sidecar did not answer PING"
    yield
    fastcache.shutdown_sidecar()


@pytest.fixture(autouse=True)
def clean_slate():
    fastcache.flush()
    fastcache.default_client().l1.clear()
    yield
