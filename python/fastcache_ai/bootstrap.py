"""Process lifecycle autopilot: find a running Java sidecar, or quietly become the one that starts it.

This module is the entire "zero infrastructure" promise. No Docker, no Redis, no ``docker-compose up``, no
port to pick, no service to remember to start. ``import fastcache_ai`` and the engine is there.

The sequence, in order of cost:

1. **Explicit address** — ``FASTCACHE_HOST``/``FASTCACHE_PORT`` win over everything. This is how you point
   a fleet of workers at one shared sidecar, or at a Spring Boot app running with
   ``fastcache.server.enabled=true``.
2. **Discovery file** — ``~/.fastcache/sidecar.json``, written atomically by a running engine. If it names
   a port that answers PING, attach to it. Five notebooks on one laptop share one JVM and one warm cache.
3. **Boot one** — locate the packaged JAR and a JVM, spawn it detached, and block on its stdout handshake
   until it reports its bound port.

**Cross-process safety.** Step 3 is guarded by an OS-level file lock, not a Python lock. Two interpreters
starting simultaneously is the normal case (a worker pool, a test suite with ``-n auto``), and without the
lock they would race to spawn a JVM each. The loser of the race re-probes inside the lock and attaches to
the winner's process.

**Lifetime.** By default the sidecar outlives the Python process that started it, so the next run gets a
warm cache. It is not a leak: the engine self-terminates after ``FASTCACHE_IDLE_TIMEOUT`` (default 30m) of
no traffic. Set ``FASTCACHE_EPHEMERAL=1`` to kill it at interpreter exit instead — the right setting for CI.

**Orphan isolation.** A clean exit runs atexit hooks; ``kill -9`` does not. The sidecar is therefore
launched with ``--parent-pid`` and a 40-second ``--heartbeat-timeout``, and the client beats every ten
seconds from a daemon thread. Hard-kill this interpreter and the JVM notices the dead PID within a
second and exits, releasing every byte it held — instead of sitting on multiple gigabytes until reboot.

Two consequences worth stating plainly. First, the sidecar no longer outlives its last client by 30
minutes: with the watchdog armed, an engine nobody is talking to is reclaimed within the 40-second
window, so a *warm cache between separate runs* is no longer the default. Set
``FASTCACHE_HEARTBEAT_TIMEOUT=0`` to restore it (that also suppresses the parent-PID watch, and the
30-minute idle timeout still bounds the process). Second, a dead parent only reaps the engine when no
other client is connected, so one worker of a pool restarting never takes the shared cache with it.
"""

from __future__ import annotations

import atexit
import json
import os
import queue
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Optional, Tuple

from .errors import SidecarStartupError, SidecarUnavailable
from .protocol import OP_PING, RESPONSE_HEADER, RESPONSE_HEADER_BYTES, RESPONSE_MAGIC, encode_request

_READY_PREFIX = "FASTCACHE_READY"
_ORPHANED_PREFIX = "FASTCACHE_ORPHANED"
_FAILED_PREFIX = "FASTCACHE_FAILED"
_STARTUP_TIMEOUT_SECONDS = float(os.environ.get("FASTCACHE_STARTUP_TIMEOUT", "30"))
_LOCK_TIMEOUT_SECONDS = 60.0

_spawn_lock = threading.Lock()
_owned_process: Optional[subprocess.Popen] = None
_resolved: Optional[Tuple[str, int]] = None
_console_port: Optional[int] = None


# ---------------------------------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------------------------------

def home_dir() -> Path:
    """State directory. Overridable so containers can point it at a writable volume."""
    return Path(os.environ.get("FASTCACHE_HOME", Path.home() / ".fastcache"))


def state_file() -> Path:
    return home_dir() / "sidecar.json"


def log_file() -> Path:
    return home_dir() / "sidecar.log"


def _lock_path() -> Path:
    return home_dir() / "sidecar.lock"


# ---------------------------------------------------------------------------------------------------
# Cross-platform advisory file lock
# ---------------------------------------------------------------------------------------------------

class _FileLock:
    """Minimal advisory lock over a real file, so it works across processes (``threading.Lock`` does not).

    ``fcntl.flock`` on POSIX, ``msvcrt.locking`` on Windows. Both are polled non-blocking with a deadline
    rather than blocking outright: a process killed while holding the lock must not wedge every future
    import, and a bounded wait degrades to "start my own sidecar" instead of hanging an interpreter.
    """

    def __init__(self, path: Path, timeout: float = _LOCK_TIMEOUT_SECONDS) -> None:
        self.path = path
        self.timeout = timeout
        self._handle = None

    def __enter__(self) -> "_FileLock":
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._handle = open(self.path, "a+b")  # noqa: SIM115 - lifetime is managed by __exit__
        deadline = time.monotonic() + self.timeout
        while True:
            try:
                self._acquire()
                return self
            except OSError:
                if time.monotonic() >= deadline:
                    # Proceed unlocked. Worst case two sidecars start; the discovery file settles on one
                    # and the loser idles out. That is strictly better than blocking forever.
                    return self
                time.sleep(0.05)

    def _acquire(self) -> None:
        if os.name == "nt":
            import msvcrt

            msvcrt.locking(self._handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl

            fcntl.flock(self._handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)

    def __exit__(self, *exc_info) -> None:
        if self._handle is None:
            return
        try:
            if os.name == "nt":
                import msvcrt

                self._handle.seek(0)
                msvcrt.locking(self._handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(self._handle.fileno(), fcntl.LOCK_UN)
        except OSError:
            pass  # Lock was never taken (timeout path) or already released by process teardown.
        finally:
            self._handle.close()
            self._handle = None


# ---------------------------------------------------------------------------------------------------
# Discovery
# ---------------------------------------------------------------------------------------------------

def probe(host: str, port: int, timeout: float = 0.5) -> bool:
    """Liveness check: connect and exchange a real PING frame.

    A bare TCP connect is not enough — on Windows another process may hold the port, and on any OS a
    half-dead JVM still accepts connections. Round-tripping a protocol frame proves it is *our* engine.
    """
    try:
        with socket.create_connection((host, port), timeout=timeout) as sock:
            sock.settimeout(timeout)
            sock.sendall(encode_request(OP_PING))
            header = _recv_exactly(sock, RESPONSE_HEADER_BYTES, timeout)
            if header is None:
                return False
            magic, _status, _flags, length = RESPONSE_HEADER.unpack(header)
            if magic != RESPONSE_MAGIC:
                return False
            if length:
                _recv_exactly(sock, length, timeout)
            return True
    except (OSError, struct.error):
        return False


def _recv_exactly(sock: socket.socket, count: int, timeout: float) -> Optional[bytes]:
    sock.settimeout(timeout)
    chunks = bytearray()
    while len(chunks) < count:
        chunk = sock.recv(count - len(chunks))
        if not chunk:
            return None
        chunks.extend(chunk)
    return bytes(chunks)


def read_state() -> Optional[dict]:
    """Reads the discovery file. Returns None for missing, unreadable or corrupt files."""
    try:
        with open(state_file(), "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def discover() -> Optional[Tuple[str, int]]:
    """Finds a live sidecar without starting one. Returns ``(host, port)`` or None."""
    host = os.environ.get("FASTCACHE_HOST")
    port = os.environ.get("FASTCACHE_PORT")
    if host and port:
        # Explicit configuration is honoured without probing: if the operator says the sidecar is there,
        # failing loudly on first use is more useful than silently starting a competing one.
        return host, int(port)

    state = read_state()
    if state:
        candidate_host = state.get("host", "127.0.0.1")
        candidate_port = state.get("port")
        if candidate_port and probe(candidate_host, int(candidate_port)):
            return candidate_host, int(candidate_port)
    return None


# ---------------------------------------------------------------------------------------------------
# Locating the runtime
# ---------------------------------------------------------------------------------------------------

def find_jar() -> Path:
    """Locates the sidecar JAR: explicit override, then the packaged wheel copy, then a dev build."""
    override = os.environ.get("FASTCACHE_JAR")
    if override:
        path = Path(override)
        if not path.is_file():
            raise SidecarUnavailable(f"FASTCACHE_JAR points at a missing file: {path}")
        return path

    try:
        from importlib import resources

        packaged = resources.files("fastcache_ai").joinpath("_bin/fastcache-engine.jar")
        if packaged.is_file():
            return Path(str(packaged))
    except (ImportError, ModuleNotFoundError, AttributeError, TypeError):
        pass  # Not installed as a wheel; fall through to the development layout.

    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "fastcache-engine" / "target" / "fastcache-engine.jar"
        if candidate.is_file():
            return candidate

    raise SidecarUnavailable(
        "FastCache engine JAR not found. Install the wheel with the bundled engine "
        "(pip install fastcache-ai), build it with 'mvn -q package', or set FASTCACHE_JAR."
    )


def find_java() -> str:
    """Locates a JVM: explicit override, JAVA_HOME, PATH, then an optional packaged JRE (``jdk4py``)."""
    override = os.environ.get("FASTCACHE_JAVA")
    if override:
        return override

    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)

    on_path = shutil.which("java")
    if on_path:
        return on_path

    try:  # pragma: no cover - optional dependency
        from jdk4py import JAVA

        return str(JAVA)
    except ImportError:
        pass

    raise SidecarUnavailable(
        "No Java runtime found. Install a JDK 21+ (or 'pip install jdk4py' for a bundled one), "
        "or set FASTCACHE_JAVA to a java executable."
    )


def _verify_java_version(java: str) -> None:
    """Fails with an actionable message rather than a stack trace from an unsupported class file version."""
    try:
        result = subprocess.run(
            [java, "-version"], capture_output=True, text=True, timeout=15, check=False
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise SidecarUnavailable(f"could not execute {java}: {exc}") from exc

    banner = (result.stderr or result.stdout or "").strip()
    for token in banner.replace('"', " ").split():
        parts = token.split(".")
        if parts and parts[0].isdigit():
            major = int(parts[0]) if int(parts[0]) != 1 else int(parts[1] if len(parts) > 1 else 0)
            if major < 21:
                raise SidecarUnavailable(
                    f"FastCache requires Java 21+ (virtual threads); {java} reports {major}. "
                    f"Set FASTCACHE_JAVA to a newer JDK."
                )
            return
    # Unrecognised banner: let the JVM speak for itself rather than refusing to start on a parse failure.


# ---------------------------------------------------------------------------------------------------
# Spawning
# ---------------------------------------------------------------------------------------------------

def _build_command(java: str, jar: Path, port: int) -> list:
    off_heap = os.environ.get("FASTCACHE_OFFHEAP_MAX", "2g")
    flags = [
        java,
        # The heap stays tiny on purpose: payloads live off-heap, so a large heap would only give the GC
        # more to scan for no benefit.
        f"-Xmx{os.environ.get('FASTCACHE_HEAP_MAX', '256m')}",
        f"-XX:MaxDirectMemorySize={off_heap}",
        # Fail fast instead of thrashing. A sidecar in an OOM death spiral is worse than one that restarts.
        "-XX:+ExitOnOutOfMemoryError",
        "-Dfile.encoding=UTF-8",
        "-Djava.awt.headless=true",
        "-jar",
        str(jar),
        "--host", os.environ.get("FASTCACHE_BIND_HOST", "127.0.0.1"),
        "--port", str(port),
        "--offheap-max", off_heap,
        "--shards", os.environ.get("FASTCACHE_SHARDS", "32"),
        "--default-ttl", os.environ.get("FASTCACHE_DEFAULT_TTL", "15m"),
        "--idle-timeout", os.environ.get("FASTCACHE_IDLE_TIMEOUT", "30m"),
        "--hash-spreading", os.environ.get("FASTCACHE_HASH_SPREADING", "true"),
        "--stale-grace", os.environ.get("FASTCACHE_STALE_GRACE", "2s"),
        # Orphan isolation, armed only because *we* are the parent and we do send heartbeats.
        # The PID makes a `kill -9` of this interpreter detectable in under a second; the heartbeat
        # window is the general backstop for a parent that is alive but permanently wedged.
        "--heartbeat-timeout", _heartbeat_timeout(),
        # Paired with the heartbeat window on purpose: FASTCACHE_HEARTBEAT_TIMEOUT=0 is the single,
        # coherent opt-out from orphan reaping. Passing a parent PID while the window is disabled would
        # reap the sidecar the moment this interpreter exits, silently defeating the opt-out.
        "--parent-pid", "0" if _orphan_reaping_disabled() else str(os.getpid()),
        "--metrics-port", os.environ.get("FASTCACHE_METRICS_PORT", "8081"),
        "--cost-model", os.environ.get("FASTCACHE_COST_MODEL", "gpt-4o"),
        "--state-file", str(state_file()),
    ]
    extra = os.environ.get("FASTCACHE_JAVA_OPTS")
    if extra:
        flags[1:1] = extra.split()
    return flags


def _heartbeat_timeout() -> str:
    """Orphan-watchdog window. ``0`` (or ``never``) disables orphan reaping entirely."""
    return os.environ.get("FASTCACHE_HEARTBEAT_TIMEOUT", "40s").strip()


def _orphan_reaping_disabled() -> bool:
    return _heartbeat_timeout().lower() in {"0", "0s", "off", "never", "no", "false", ""}


def _platform_spawn_kwargs() -> dict:
    """Detaches the child so closing a terminal or notebook kernel does not take the cache with it."""
    if os.name == "nt":
        creation_flags = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP
        return {"creationflags": creation_flags}
    return {"start_new_session": True}


def _await_handshake(process: subprocess.Popen, timeout: float) -> int:
    """Blocks until the JVM prints its bound port.

    Read on a daemon thread: ``readline`` on a pipe cannot be given a timeout, and a JVM that hangs during
    startup must not hang the import that triggered it.
    """
    result: "queue.Queue" = queue.Queue(maxsize=1)

    def reader() -> None:
        try:
            for raw in iter(process.stdout.readline, b""):
                line = raw.decode("utf-8", errors="replace").strip()
                if line.startswith(_READY_PREFIX):
                    result.put(("ready", line))
                    return
                if line.startswith(_FAILED_PREFIX):
                    result.put(("failed", line))
                    return
        except (OSError, ValueError) as exc:
            result.put(("error", str(exc)))

    threading.Thread(target=reader, name="fastcache-handshake", daemon=True).start()

    try:
        kind, payload = result.get(timeout=timeout)
    except queue.Empty:
        process.kill()
        raise SidecarStartupError(
            f"sidecar did not announce a port within {timeout:.0f}s; see {log_file()}"
        ) from None

    if kind != "ready":
        process.kill()
        raise SidecarStartupError(f"sidecar failed to start: {payload}")

    global _console_port
    fields = dict(
        part.split("=", 1) for part in payload.split() if "=" in part
    )
    try:
        console = int(fields.get("console", "-1"))
        _console_port = console if console > 0 else None
    except ValueError:
        _console_port = None
    port = int(fields.get("port", "0"))
    if port <= 0:
        raise SidecarStartupError(f"sidecar announced an invalid port: {payload}")

    _drain_output_in_background(process)
    return port


def _drain_output_in_background(process: subprocess.Popen) -> None:
    """Keeps reading stdout into the log file.

    Not optional: an unread OS pipe fills at ~64 KB and then *blocks the JVM writing to it*. A sidecar
    frozen mid-request because nobody read its logs is a genuinely baffling production incident.
    """

    def drain() -> None:
        try:
            with open(log_file(), "ab", buffering=0) as sink:
                for line in iter(process.stdout.readline, b""):
                    sink.write(line)
        except OSError:
            pass

    threading.Thread(target=drain, name="fastcache-log-drain", daemon=True).start()


def _spawn_sidecar(port: int = 0) -> Tuple[str, int]:
    global _owned_process

    java = find_java()
    jar = find_jar()
    _verify_java_version(java)
    home_dir().mkdir(parents=True, exist_ok=True)

    command = _build_command(java, jar, port)
    process = subprocess.Popen(  # noqa: S603 - argv is fully constructed here, never shell-interpolated
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        **_platform_spawn_kwargs(),
    )

    try:
        bound_port = _await_handshake(process, _STARTUP_TIMEOUT_SECONDS)
    except SidecarStartupError:
        process.kill()
        raise

    _owned_process = process
    host = os.environ.get("FASTCACHE_BIND_HOST", "127.0.0.1")
    return host, bound_port


def ensure_sidecar() -> Tuple[str, int]:
    """Returns a live ``(host, port)``, starting the engine if required. Idempotent and race-safe."""
    global _resolved

    if _resolved is not None:
        host, port = _resolved
        if probe(host, port):
            return _resolved
        _resolved = None  # The sidecar we were using went away; fall through and re-resolve.

    with _spawn_lock:  # Guards this interpreter.
        if _resolved is not None:
            return _resolved

        found = discover()
        if found is not None:
            _resolved = found
            return found

        with _FileLock(_lock_path()):  # Guards every interpreter on this machine.
            # Re-check inside the lock: another process may have started one while we waited.
            found = discover()
            if found is not None:
                _resolved = found
                return found

            requested_port = int(os.environ.get("FASTCACHE_PORT", "0"))
            _resolved = _spawn_sidecar(requested_port)
            return _resolved


def console_url() -> Optional[str]:
    """URL of the sidecar's management console, or None when it is disabled or unknown.

    Read from the handshake when we started the sidecar ourselves, and from the discovery file when we
    attached to someone else's — so the URL is available either way.
    """
    if _console_port:
        host = os.environ.get("FASTCACHE_BIND_HOST", "127.0.0.1")
        return f"http://{host}:{_console_port}/dashboard"
    state = read_state() or {}
    port = state.get("console_port")
    if isinstance(port, int) and port > 0:
        return f"http://{state.get('host', '127.0.0.1')}:{port}/dashboard"
    return None


def owned_process() -> Optional[subprocess.Popen]:
    """The sidecar this interpreter started, if any. None when we attached to someone else's."""
    return _owned_process


def shutdown_sidecar(timeout: float = 5.0) -> None:
    """Terminates the sidecar *this* process started. Never kills one we merely attached to."""
    global _owned_process, _resolved

    process = _owned_process
    if process is None or process.poll() is not None:
        _owned_process = None
        return

    try:
        process.terminate()
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()  # The shutdown hook had its chance; the OS reclaims the native memory either way.
    except OSError:
        pass
    finally:
        _owned_process = None
        _resolved = None


def _atexit_hook() -> None:
    if os.environ.get("FASTCACHE_EPHEMERAL", "").lower() in {"1", "true", "yes"}:
        shutdown_sidecar()


atexit.register(_atexit_hook)


def info() -> dict:
    """Diagnostics for ``fastcache.info()``: what was found, what is running, where the logs are."""
    state = read_state() or {}
    return {
        "resolved": _resolved,
        "console_url": console_url(),
        "owned_pid": _owned_process.pid if _owned_process else None,
        "state_file": str(state_file()),
        "log_file": str(log_file()),
        "sidecar_state": state,
        "python": sys.version.split()[0],
        "ephemeral": os.environ.get("FASTCACHE_EPHEMERAL", "0"),
    }
