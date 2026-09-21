#!/usr/bin/env python3
"""Phase 8 - where does a cache hit's allocation actually go?

Reads the JFR recordings the matrix captures per cell and attributes allocation by class and call site,
so the claim "the per-hit array dominates" is evidenced rather than inferred from a heap total.

    python fastcache-e2e/analyse_alloc.py docs/validation/data/e2e

Uses `jfr print --json`, which ships with the JDK, so nothing needs installing. ObjectAllocationSample is
a *sampled* event: the weights are statistical estimates of allocation pressure, not exact byte counts.
They are used here for the shape of the distribution - which class, which call site - and the exact totals
come from ThreadMXBean.getTotalThreadAllocatedBytes in the CSV instead.
"""
from __future__ import annotations

import collections
import glob
import json
import os
import subprocess
import sys

MB = 1 << 20


def read_events(path, event):
    """Returns the parsed events of one type, or [] if jfr cannot read them."""
    try:
        out = subprocess.run(
            ["jfr", "print", "--json", "--events", event, path],
            capture_output=True, text=True, timeout=900)
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        print(f"  ! jfr unavailable or timed out for {os.path.basename(path)}: {exc}")
        return []
    if out.returncode != 0:
        return []
    try:
        return json.loads(out.stdout).get("recording", {}).get("events", [])
    except json.JSONDecodeError:
        return []


def top_frame(event):
    """The first application frame in the stack, so allocation lands on our code not on the JDK's."""
    stack = (event.get("values", {}) or {}).get("stackTrace") or {}
    for frame in stack.get("frames", []) or []:
        method = frame.get("method", {}) or {}
        cls = ((method.get("type", {}) or {}).get("name")) or ""
        name = method.get("name", "")
        if cls.startswith("io.fastcache"):
            return f"{cls}.{name}"
    for frame in stack.get("frames", []) or []:
        method = frame.get("method", {}) or {}
        cls = ((method.get("type", {}) or {}).get("name")) or ""
        if cls:
            return f"{cls}.{method.get('name', '')}"
    return "(no stack)"


def analyse(path):
    label = os.path.basename(path).replace(".jfr", "")
    events = read_events(path, "jdk.ObjectAllocationSample")
    if not events:
        print(f"\n{label}: no allocation samples")
        return

    by_class = collections.Counter()
    by_site = collections.Counter()
    total = 0
    for event in events:
        values = event.get("values", {}) or {}
        weight = values.get("weight") or 0
        obj = values.get("objectClass") or {}
        name = obj.get("name") if isinstance(obj, dict) else str(obj)
        by_class[name or "?"] += weight
        by_site[top_frame(event)] += weight
        total += weight

    print(f"\n{'=' * 100}\n{label}   estimated allocation pressure {total / MB:,.0f} MB "
          f"across {len(events):,} samples\n{'=' * 100}")
    print(f"  {'by class':<62} {'MB':>12} {'share':>8}")
    for name, weight in by_class.most_common(6):
        print(f"  {str(name)[:60]:<62} {weight / MB:>12,.0f} {weight / total * 100:>7.1f}%")
    print(f"\n  {'by call site (first io.fastcache frame)':<62} {'MB':>12} {'share':>8}")
    for name, weight in by_site.most_common(6):
        print(f"  {str(name)[:60]:<62} {weight / MB:>12,.0f} {weight / total * 100:>7.1f}%")

    # The question Phase 8 exists to answer: how much of the pressure is the payload array itself?
    array_bytes = sum(w for n, w in by_class.items() if n and n.startswith("byte["))
    print(f"\n  byte[] share of allocation pressure: {array_bytes / max(total, 1) * 100:.1f}%")


def main() -> None:
    root = sys.argv[1] if len(sys.argv) > 1 else "docs/validation/data/e2e"
    paths = sorted(glob.glob(os.path.join(root, "*.jfr")))
    if not paths:
        print(f"no .jfr recordings under {root}")
        return
    for path in paths:
        if os.path.getsize(path) == 0:
            print(f"\n{os.path.basename(path)}: empty recording (process did not dump)")
            continue
        analyse(path)


if __name__ == "__main__":
    main()
