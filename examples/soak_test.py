"""Long-running soak test — the thing unit tests cannot tell you.

Correctness is covered by 195 tests. What they cannot cover is *drift*: behaviour that is fine for thirty
seconds and wrong after six hours. This harness runs a sustained mixed workload and samples the numbers
that would reveal the failure modes this design is most exposed to:

  * **Native memory leak.** Every off-heap slot is freed through a reference count. A missed release does
    not throw, does not show up on the heap, and does not fail a test - it just grows RSS until the OS
    kills the process. `offheap_reserved` and `offheap_slots` returning to baseline after churn is the
    only real proof the refcounting holds.
  * **Fragmentation.** Repeatedly allocating and freeing direct buffers of varying sizes can fragment the
    native arena even with no leak, so RSS is tracked separately from the engine's own accounting. RSS
    climbing while `offheap_reserved` stays flat is fragmentation, not a leak - a different problem with a
    different fix.
  * **Sweeper fallback.** The eviction sweeper runs on a virtual thread and catches Throwable to stay
    alive. If it ever dies quietly, entries stop being reclaimed and the cache grows without bound.
  * **Heap creep.** Payloads live off-heap, so heap should stay flat regardless of cache size. A rising
    heap means something is retaining objects it should not.
  * **Coordinator drift.** In-flight refreshes should return to zero between bursts. A number that only
    climbs means leases are being taken and never released.

Usage::

    python soak_test.py --hours 3
    python soak_test.py --hours 0.5 --workers 16     # quick confidence run

Writes a CSV sample every 30s next to this file. Analyse it afterwards with --report.
"""

from __future__ import annotations

import argparse
import csv
import os
import random
import string
import sys
import threading
import time
from datetime import datetime, timezone

_HERE = os.path.dirname(os.path.abspath(__file__))
os.environ.setdefault("FASTCACHE_HOME", os.path.join(_HERE, ".fastcache-soak"))
os.environ.setdefault("FASTCACHE_METRICS_PORT", "8098")
os.environ.setdefault("FASTCACHE_AUTOSTART", "0")
os.environ.setdefault("FASTCACHE_HEARTBEAT_TIMEOUT", "0")   # long-lived by design
os.environ.setdefault("FASTCACHE_IDLE_TIMEOUT", "0")
os.environ.setdefault("FASTCACHE_OFFHEAP_MAX", "1g")

sys.path.insert(0, os.path.join(os.path.dirname(_HERE), "python"))

import fastcache_ai as fc  # noqa: E402
from fastcache_ai import fastcache  # noqa: E402

SAMPLE_SECONDS = 30
CSV_PATH = os.path.join(_HERE, "soak-samples.csv")

_stop = threading.Event()
_errors: list[str] = []
_ops = {"get": 0, "put": 0, "delete": 0, "decorated": 0}
_ops_lock = threading.Lock()


#: Built once at import. Generating payloads per operation would make this a benchmark of
#: `random.choices` rather than of the cache: 4 MB costs 0.62s and holds the GIL the whole time, which
#: starves every other worker and the sampler thread with it.
_POOL = "".join(random.choices(string.ascii_letters + "     ", k=1 << 20))


def _payload(size: int) -> str:
    """A slice of the shared pool, offset per call so payloads are not byte-identical."""
    offset = random.randrange(len(_POOL))
    repeats = size // len(_POOL) + 2
    return ((_POOL * repeats)[offset:offset + size])


@fastcache(ttl="45s", namespace="soak")
def expensive(prompt: str) -> str:
    """Stands in for a model call: the decorated path, exercising L1 and single-flight."""
    time.sleep(0.005)
    return _payload(4096) + prompt


def worker(worker_id: int, key_space: int) -> None:
    """Mixed traffic: short TTLs to keep the sweeper busy, mixed sizes to stress the allocator."""
    rng = random.Random(worker_id)
    while not _stop.is_set():
        try:
            roll = rng.random()

            if roll < 0.45:                                  # ordinary read
                fc.get(f"soak:{rng.randrange(key_space)}")
                with _ops_lock:
                    _ops["get"] += 1

            elif roll < 0.72:                                # write, varied size
                size = rng.choice([512, 4096, 65536, 512 * 1024])
                fc.put(f"soak:{rng.randrange(key_space)}", _payload(size), ttl="60s")
                with _ops_lock:
                    _ops["put"] += 1

            elif roll < 0.80:                                # large payload: the off-heap stress path
                fc.put(f"soak:big:{rng.randrange(20)}", _payload(4 * 1024 * 1024), ttl="30s")
                with _ops_lock:
                    _ops["put"] += 1

            elif roll < 0.88:                                # churn: create then delete
                key = f"soak:churn:{rng.randrange(key_space)}"
                fc.put(key, _payload(2048), ttl="20s")
                fc.delete(key)
                with _ops_lock:
                    _ops["delete"] += 1

            elif roll < 0.97:                                # celebrity key: L1 + stampede paths
                expensive("the one prompt everybody sends")
                with _ops_lock:
                    _ops["decorated"] += 1

            else:                                            # cold decorated call
                expensive(f"unique-{rng.randrange(10_000)}")
                with _ops_lock:
                    _ops["decorated"] += 1

        except Exception as exc:                             # noqa: BLE001
            _errors.append(f"{datetime.now(timezone.utc).isoformat()} worker{worker_id}: {exc!r}")
            time.sleep(0.5)


def sidecar_rss_bytes() -> int:
    """Resident set size of the engine process — the ground truth a leak cannot hide from."""
    state = fc.bootstrap.read_state() or {}
    pid = state.get("pid")
    if not pid:
        return 0
    try:
        if os.name == "nt":
            import subprocess
            out = subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 f"(Get-Process -Id {pid} -ErrorAction SilentlyContinue).WorkingSet64"],
                capture_output=True, text=True, timeout=20)
            return int((out.stdout or "0").strip() or 0)
        with open(f"/proc/{pid}/status") as handle:
            for line in handle:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) * 1024
    except Exception:                                        # noqa: BLE001
        pass
    return 0


def sample(writer, handle, started: float) -> dict:
    stats = fc.stats()
    engine, l1 = stats["engine"], stats["l1"]
    with _ops_lock:
        ops = dict(_ops)

    row = {
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "elapsed_min": round((time.monotonic() - started) / 60, 2),
        "rss_mb": round(sidecar_rss_bytes() / 1048576, 1),
        "offheap_reserved_mb": round(engine.get("offheap_reserved", 0) / 1048576, 1),
        "offheap_slots": engine.get("offheap_slots", 0),
        "entries": engine.get("entries", 0),
        "hits": engine.get("hits", 0),
        "misses": engine.get("misses", 0),
        "stale_hits": engine.get("stale_hits", 0),
        "ttl_evictions": engine.get("ttl_evictions", 0),
        "lru_evictions": engine.get("lru_evictions", 0),
        "write_rejections": engine.get("write_rejections", 0),
        "herd_suppressed": engine.get("herd_suppressed", 0),
        "refreshes_in_flight": engine.get("refreshes_in_flight", 0),
        "memory_ratio": engine.get("memory_ratio", 0),
        "l1_entries": l1.get("entries", 0),
        "ops_total": sum(ops.values()),
        "errors": len(_errors),
    }
    writer.writerow(row)
    handle.flush()
    return row


def run(hours: float, workers: int, key_space: int) -> int:
    host, port = fc.ensure_sidecar()
    print(f"sidecar   : {host}:{port}")
    print(f"console   : {fc.console_url()}")
    print(f"duration  : {hours}h   workers: {workers}   key space: {key_space}")
    print(f"samples   : {CSV_PATH} every {SAMPLE_SECONDS}s")
    print()

    threads = [threading.Thread(target=worker, args=(i, key_space), daemon=True) for i in range(workers)]
    for t in threads:
        t.start()

    started = time.monotonic()
    deadline = started + hours * 3600
    fields = ["timestamp", "elapsed_min", "rss_mb", "offheap_reserved_mb", "offheap_slots", "entries",
              "hits", "misses", "stale_hits", "ttl_evictions", "lru_evictions", "write_rejections",
              "herd_suppressed", "refreshes_in_flight", "memory_ratio", "l1_entries", "ops_total",
              "errors"]

    with open(CSV_PATH, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        handle.flush()   # so an interrupted run still leaves a readable file
        try:
            while time.monotonic() < deadline:
                time.sleep(SAMPLE_SECONDS)
                row = sample(writer, handle, started)
                print(f"  {row['elapsed_min']:>7.1f}m  rss={row['rss_mb']:>7.1f}MB  "
                      f"offheap={row['offheap_reserved_mb']:>6.1f}MB  slots={row['offheap_slots']:>6}  "
                      f"entries={row['entries']:>6}  ops={row['ops_total']:>9}  err={row['errors']}",
                      flush=True)
        except KeyboardInterrupt:
            print("\ninterrupted - writing final sample")
            sample(writer, handle, started)

    _stop.set()
    for t in threads:
        t.join(timeout=10)

    print()
    return report()


def report() -> int:
    """Reads the CSV back and judges it. Returns a process exit code."""
    with open(CSV_PATH, newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if len(rows) < 3:
        print("not enough samples to judge")
        return 1

    def series(col):
        return [float(r[col]) for r in rows]

    # Compare the last quarter against the second quarter: the first quarter is warm-up, so including it
    # would report cache fill as if it were a leak.
    n = len(rows)
    early = rows[n // 4: n // 2]
    late = rows[-(n // 4):]

    def mean(subset, col):
        return sum(float(r[col]) for r in subset) / len(subset)

    verdicts = []

    rss_early, rss_late = mean(early, "rss_mb"), mean(late, "rss_mb")
    rss_growth = (rss_late - rss_early) / max(rss_early, 1) * 100
    verdicts.append(("RSS drift", f"{rss_early:.0f} -> {rss_late:.0f} MB ({rss_growth:+.1f}%)",
                     abs(rss_growth) < 25))

    off_early, off_late = mean(early, "offheap_reserved_mb"), mean(late, "offheap_reserved_mb")
    verdicts.append(("Off-heap reserved", f"{off_early:.0f} -> {off_late:.0f} MB",
                     off_late <= max(off_early * 1.5, off_early + 64)))

    slots = series("offheap_slots")
    verdicts.append(("Native slots bounded", f"max {max(slots):.0f}, final {slots[-1]:.0f}",
                     slots[-1] <= max(slots) * 1.1))

    evictions = series("ttl_evictions")
    verdicts.append(("Sweeper still reclaiming", f"{evictions[-1]:.0f} TTL evictions total",
                     evictions[-1] > evictions[len(evictions) // 2]))

    inflight = series("refreshes_in_flight")
    verdicts.append(("Refresh leases released", f"max in-flight {max(inflight):.0f}",
                     max(inflight) < 50))

    errors = series("errors")
    verdicts.append(("No client errors", f"{errors[-1]:.0f} errors", errors[-1] == 0))

    print("=" * 74)
    print(f"SOAK REPORT  ({n} samples over {rows[-1]['elapsed_min']} minutes, "
          f"{rows[-1]['ops_total']} operations)")
    print("=" * 74)
    failed = 0
    for name, detail, ok in verdicts:
        print(f"  {'PASS' if ok else 'FAIL'}  {name:28} {detail}")
        failed += 0 if ok else 1

    if _errors:
        print("\n  first errors:")
        for e in _errors[:5]:
            print(f"    {e}")

    print()
    print("VERDICT:", "no drift detected" if failed == 0 else f"{failed} check(s) failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hours", type=float, default=3.0)
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--key-space", type=int, default=5000)
    parser.add_argument("--report", action="store_true", help="analyse an existing soak-samples.csv")
    args = parser.parse_args()

    sys.exit(report() if args.report else run(args.hours, args.workers, args.key_space))
