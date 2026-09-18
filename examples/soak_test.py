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

Two things about reading that CSV, which ``--report`` handles and a spreadsheet will not. Memory here
sawtooths by hundreds of MB as large payloads land and expire together, so the average of a window
says more about which phase of the cycle it covered than about drift - the floor is the part a leak
raises. And the sampler only runs when the OS lets it: a suspended laptop leaves a gap with a drained
cache on the far side, which is why each row records the ``gap_s`` it actually covers.
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

#: A sample interval this far past nominal means the process was not running the whole time: a
#: suspended laptop, a stalled RSS probe, the sampler thread starved. The rows on either side of such
#: a gap describe different conditions and must not be pooled into one average.
GAP_FACTOR = 2.0
#: Samples to discard after a resume. The cache spent the gap idle - TTLs expired and the sweeper
#: drained it - so the first rows back describe a cache refilling, not one under steady load.
GAP_RECOVERY_SAMPLES = 2
#: RSS and the off-heap reserve sawtooth by hundreds of MB under this workload: 4 MB payloads land,
#: live out a 30s TTL, and are released together. The trough is the part that carries meaning, because
#: a leak cannot give memory back and so raises the floor. A percentile rather than the minimum, so
#: that one failed probe or one unusually deep trough cannot define it.
FLOOR_PERCENTILE = 10
#: Share of sample intervals that may contain shed writes before the memory checks stop meaning
#: anything. While MemoryGuard is rejecting, it is holding the reserve down at its gate floor - so the
#: floor being measured is the guard's number, not the cache's, and no leak could show through it.
GUARD_SHEDDING_LIMIT = 0.25

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


_last_probe_wall: float | None = None


def sidecar_rss_bytes() -> tuple[int, float]:
    """Resident set size of the engine process, and the wall-clock seconds since the previous probe.

    The interval ships with the number because it is what decides whether the number is comparable to
    the one before it. The sampler asks for a reading every ``SAMPLE_SECONDS``; what it gets is
    however long the OS let this process run. A laptop that suspends for an hour leaves two adjacent
    rows - one a loaded cache, the next a cache that has been idle long enough for every TTL to expire
    - and nothing in the CSV to say they are an hour apart. RSS appears to collapse, and any analysis
    reads that as a sample like any other.

    Measured on the wall clock deliberately: ``time.monotonic`` excludes suspended time on Linux,
    which is exactly the time this is trying to catch.

    Returns ``(0, gap)`` when the probe fails - 0 means "could not read", not "nothing resident".
    """
    global _last_probe_wall
    now = time.time()
    gap = 0.0 if _last_probe_wall is None else now - _last_probe_wall
    _last_probe_wall = now

    state = fc.bootstrap.read_state() or {}
    pid = state.get("pid")
    if not pid:
        return 0, gap
    try:
        if os.name == "nt":
            import subprocess
            out = subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 f"(Get-Process -Id {pid} -ErrorAction SilentlyContinue).WorkingSet64"],
                capture_output=True, text=True, timeout=20)
            return int((out.stdout or "0").strip() or 0), gap
        with open(f"/proc/{pid}/status") as handle:
            for line in handle:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) * 1024, gap
    except Exception:                                        # noqa: BLE001
        pass
    return 0, gap


def sample(writer, handle, started: float) -> dict:
    stats = fc.stats()
    engine, l1 = stats["engine"], stats["l1"]
    with _ops_lock:
        ops = dict(_ops)
    rss_bytes, gap_s = sidecar_rss_bytes()

    row = {
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "elapsed_min": round((time.monotonic() - started) / 60, 2),
        "gap_s": round(gap_s, 1),
        "rss_mb": round(rss_bytes / 1048576, 1),
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
    fields = ["timestamp", "elapsed_min", "gap_s", "rss_mb", "offheap_reserved_mb", "offheap_slots",
              "entries",
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
                # Say so at the time, not only in the CSV: a gap here means the rows around it are
                # not describing the same run conditions.
                gap = ("" if row["gap_s"] <= SAMPLE_SECONDS * GAP_FACTOR
                       else f"   GAP {row['gap_s'] / 60:.1f}m")
                print(f"  {row['elapsed_min']:>7.1f}m  rss={row['rss_mb']:>7.1f}MB  "
                      f"offheap={row['offheap_reserved_mb']:>6.1f}MB  slots={row['offheap_slots']:>6}  "
                      f"entries={row['entries']:>6}  ops={row['ops_total']:>9}  err={row['errors']}"
                      f"{gap}",
                      flush=True)
        except KeyboardInterrupt:
            print("\ninterrupted - writing final sample")
            sample(writer, handle, started)

    _stop.set()
    for t in threads:
        t.join(timeout=10)

    print()
    return report()


def _sample_gaps(rows) -> list[float]:
    """Wall-clock seconds between each row and the one before it.

    Prefers the recorded ``gap_s``; CSVs written before that column existed are handled by
    differencing the timestamps, which is the same quantity at second resolution.
    """
    if rows and rows[0].get("gap_s") not in (None, ""):
        return [float(r["gap_s"]) for r in rows]
    stamps = [datetime.fromisoformat(r["timestamp"]) for r in rows]
    return [0.0] + [(b - a).total_seconds() for a, b in zip(stamps, stamps[1:])]


def _floor(values: list[float]) -> float | None:
    """The trough a sawtooth keeps returning to, as a low percentile of the window."""
    ordered = sorted(values)
    if not ordered:
        return None
    return ordered[int(round(FLOOR_PERCENTILE / 100 * (len(ordered) - 1)))]


def report() -> int:
    """Reads the CSV back and judges it. Returns a process exit code.

    Three things this must not do, because a wrong verdict is worse than no verdict:

    * **Average a sawtooth.** RSS here swings between roughly 270 MB and 750 MB on a cycle of a few
      minutes, because large payloads land together and are released together when their TTL expires.
      The mean of a window is then mostly a statement about which phase of that cycle the window
      happened to cover, and two windows sampled at different phases differ by more than any leak
      would. What a leak actually does is raise the *trough*: memory that is never given back cannot
      come out of the floor. So every memory check here compares floors.
    * **Average across a gap.** If sampling stopped - a suspended laptop, most often - the cache sat
      idle, every TTL expired and the sweeper drained it. The rows on the far side of that gap are a
      drained cache, not a leaking one, and pooling them with loaded rows moves the result in whatever
      direction the gap happened to fall. Gaps are detected, reported, and excluded.
    * **Certify a run that never got to grow.** When MemoryGuard is shedding writes it is holding the
      reserve at its gate floor, so the floor is the guard's number and a leak cannot show through it.
      A PASS read off that is as worthless as the false FAIL, so the memory checks abstain instead.

    Exit codes: 0 no drift, 1 a check failed, 2 inconclusive - the run could not answer the
    question, whether for want of continuous samples or because the guard held the cache down.
    """
    with open(CSV_PATH, newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if len(rows) < 3:
        print("not enough samples to judge")
        return 2

    gaps = _sample_gaps(rows)
    breaks = [i for i, g in enumerate(gaps) if i and g > SAMPLE_SECONDS * GAP_FACTOR]

    # Drop the row that closes each gap together with the samples spent refilling behind it. What is
    # left is the cache under sustained load, which is the only state these checks are about.
    dropped = {j for i in breaks for j in range(i, min(len(rows), i + 1 + GAP_RECOVERY_SAMPLES))}
    steady = [r for i, r in enumerate(rows) if i not in dropped]

    n = len(steady)
    if n < 8:
        print(f"{len(rows)} samples, only {n} of them continuous - "
              f"not enough unbroken data to judge")
        return 2

    def col(subset, name, drop_zero=False) -> list[float]:
        values = [float(r[name]) for r in subset]
        return [v for v in values if v] if drop_zero else values

    # Still skipping the first quarter: that is cache fill, and counting it as drift would report a
    # cache doing its job as a leak.
    early, late = steady[n // 4: n // 2], steady[-(n // 4):]

    # Writes shed under memory pressure are not a cache defect - MemoryGuard refusing writes is the
    # guard doing its documented job. But they decide whether the memory checks below mean anything.
    # The guard engages its machine-pressure gate once the engine's own reservation crosses
    # ``physicalGateFloorBytes`` (max of 64 MiB and 5% of the budget), so a shedding run sits pinned
    # at that floor. Reading a leak off a reserve the guard is holding down is not possible: the
    # number is the gate floor, not the working set.
    shed = col(steady, "write_rejections")
    intervals = [b - a for a, b in zip(shed, shed[1:])]
    shedding = sum(1 for d in intervals if d > 0) / max(len(intervals), 1)
    guard_quiet = shedding <= GUARD_SHEDDING_LIMIT

    verdicts: list[tuple[str, str, bool | None]] = []

    def memory_verdict(name, detail, ok) -> tuple[str, str, bool | None]:
        """A memory check is only evidence when the guard left the cache alone to grow."""
        if guard_quiet:
            return name, detail, ok
        return name, f"{detail}  [guard shedding - not judged]", None

    # A zero here is a probe that failed, not an empty process, so it must not be allowed to define
    # the floor.
    rss_early, rss_late = _floor(col(early, "rss_mb", True)), _floor(col(late, "rss_mb", True))
    if rss_early is None or rss_late is None:
        verdicts.append(("RSS floor", "no usable RSS readings", None))
    else:
        growth = (rss_late - rss_early) / max(rss_early, 1) * 100
        # One-sided on purpose: a floor that falls is the sweeper working, not drift.
        verdicts.append(memory_verdict(
            "RSS floor", f"{rss_early:.0f} -> {rss_late:.0f} MB ({growth:+.1f}%)", growth < 25))

    off_early = _floor(col(early, "offheap_reserved_mb"))
    off_late = _floor(col(late, "offheap_reserved_mb"))
    verdicts.append(memory_verdict(
        "Off-heap reserved floor", f"{off_early:.0f} -> {off_late:.0f} MB",
        off_late <= max(off_early * 1.5, off_early + 64)))

    # The old form of this check - final <= max * 1.1 - could never fail, since the final value is by
    # definition no greater than the maximum. A leaked slot is one that never comes back, so the floor
    # is what shows it.
    slot_early, slot_late = _floor(col(early, "offheap_slots")), _floor(col(late, "offheap_slots"))
    verdicts.append(memory_verdict(
        "Native slot floor", f"{slot_early:.0f} -> {slot_late:.0f} slots",
        slot_late <= max(slot_early * 1.5, slot_early + 200)))

    # Reported whatever the outcome: a run that spent its time shedding did not exercise the thing the
    # soak exists to test, and that is worth saying out loud rather than leaving as a silent PASS.
    verdicts.append(("Write admission",
                     f"{shed[-1] - shed[0]:.0f} writes shed, in {shedding * 100:.0f}% of intervals",
                     True if guard_quiet else None))

    # Cumulative counters, so the sawtooth does not apply - but they are still read off the continuous
    # rows, so that TTLs expiring during an idle gap are not mistaken for the sweeper keeping up.
    evictions = col(steady, "ttl_evictions")
    verdicts.append(("Sweeper still reclaiming", f"{evictions[-1]:.0f} TTL evictions total",
                     evictions[-1] > evictions[len(evictions) // 2]))

    inflight = col(steady, "refreshes_in_flight")
    verdicts.append(("Refresh leases released", f"max in-flight {max(inflight):.0f}",
                     max(inflight) < 50))

    errors = col(rows, "errors")
    verdicts.append(("No client errors", f"{errors[-1]:.0f} errors", errors[-1] == 0))

    print("=" * 74)
    print(f"SOAK REPORT  ({len(rows)} samples over {rows[-1]['elapsed_min']} minutes, "
          f"{rows[-1]['ops_total']} operations)")
    if breaks:
        unobserved = sum(gaps[i] - SAMPLE_SECONDS for i in breaks)
        print(f"  {len(breaks)} sampling gap(s): {unobserved / 60:.0f} minutes unobserved, "
              f"longest {max(gaps[i] for i in breaks) / 60:.0f}m "
              f"({len(rows) - n} sample(s) excluded)")
    print(f"  comparing the floor of {len(early)} early against {len(late)} late continuous samples")
    print("=" * 74)

    failed = unknown = 0
    for name, detail, ok in verdicts:
        print(f"  {'PASS' if ok else 'FAIL' if ok is False else '????'}  {name:28} {detail}")
        failed += ok is False
        unknown += ok is None

    if _errors:
        print()
        print("  first errors:")
        for e in _errors[:5]:
            print(f"    {e}")

    print()
    if failed:
        print("VERDICT:", f"{failed} check(s) failed")
        return 1
    if unknown:
        print("VERDICT:", f"inconclusive - {unknown} check(s) could not be judged from this run")
        return 2
    print("VERDICT: no drift detected")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hours", type=float, default=3.0)
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--key-space", type=int, default=5000)
    parser.add_argument("--report", action="store_true", help="analyse an existing soak-samples.csv")
    args = parser.parse_args()

    sys.exit(report() if args.report else run(args.hours, args.workers, args.key_space))
