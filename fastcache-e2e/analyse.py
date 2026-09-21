#!/usr/bin/env python3
"""Aggregate the end-to-end matrix CSVs into the tables used in the results report.

    python fastcache-e2e/analyse.py docs/validation/data/e2e/steady.csv

Reports the request distribution, the per-phase breakdown, and the memory/GC figures side by side,
because the whole point of this experiment is that those three can disagree.
"""
from __future__ import annotations

import argparse
import csv
import statistics
import sys
from collections import defaultdict

MB = 1 << 20
ARMS = ("caffeine", "fastcache-embedded", "fastcache-sidecar", "redis")


def label(size: int) -> str:
    size = int(size)
    return f"{size // MB}MB" if size >= MB else f"{size // 1024}KB"


def num(rows, column):
    values = []
    for row in rows:
        raw = row.get(column, "")
        if raw in ("", None, "null"):
            continue
        try:
            values.append(float(raw))
        except ValueError:
            pass
    return statistics.fmean(values) if values else None


def load(path):
    grouped = defaultdict(lambda: defaultdict(list))
    with open(path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            key = (int(float(row["payloadBytes"])), int(float(row["concurrency"])))
            grouped[key][row["arm"]].append(row)
    return grouped


def fmt(value, digits=0, scale=1.0):
    return "-" if value is None else f"{value / scale:,.{digits}f}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_path")
    args = parser.parse_args()
    grouped = load(args.csv_path)

    print("=" * 128)
    print("REQUEST LATENCY  (full HTTP request, service-side phase breakdown)")
    print("=" * 128)
    header = (f"{'payload':>8} {'conc':>5} {'arm':<20} {'req/s':>8} {'p50 us':>10} {'p95 us':>10} "
              f"{'p99 us':>10} | {'lookup p50':>11} {'decode p50':>11} {'hit%':>6} {'corrupt':>8}")
    print(header)
    print("-" * len(header))
    for (size, conc) in sorted(grouped):
        for arm in ARMS:
            rows = grouped[(size, conc)].get(arm)
            if not rows:
                continue
            hit = num(rows, "hitRatio")
            print(f"{label(size):>8} {conc:>5} {arm:<20} {fmt(num(rows,'reqPerSec')):>8} "
                  f"{fmt(num(rows,'reqP50us')):>10} {fmt(num(rows,'reqP95us')):>10} "
                  f"{fmt(num(rows,'reqP99us')):>10} | {fmt(num(rows,'lookupP50us')):>11} "
                  f"{fmt(num(rows,'decodeP50us')):>11} "
                  f"{(hit*100 if hit is not None else 0):>5.1f}% {fmt(num(rows,'corrupt')):>8}")
        print("-" * len(header))

    print()
    print("=" * 128)
    print("SERVICE JVM MEMORY AND GC   (the application process only; cache server RSS reported separately)")
    print("=" * 128)
    header2 = (f"{'payload':>8} {'conc':>5} {'arm':<20} {'heap MB':>9} {'oldGen MB':>10} {'RSS MB':>8} "
               f"{'srvRSS MB':>10} | {'GC n':>6} {'GC ms':>9} {'p99 us':>9} {'alloc MB':>10} {'alloc MB/s':>11}")
    print(header2)
    print("-" * len(header2))
    for (size, conc) in sorted(grouped):
        for arm in ARMS:
            rows = grouped[(size, conc)].get(arm)
            if not rows:
                continue
            alloc = num(rows, "allocatedBytes")
            duration = num(rows, "durationSeconds") or 1
            print(f"{label(size):>8} {conc:>5} {arm:<20} "
                  f"{fmt(num(rows,'heapUsed'),0,MB):>9} {fmt(num(rows,'oldGenUsed'),0,MB):>10} "
                  f"{fmt(num(rows,'rss'),0,MB):>8} {fmt(num(rows,'externalRss'),0,MB):>10} | "
                  f"{fmt(num(rows,'gcCollections')):>6} {fmt(num(rows,'gcTotalPauseMillis'),1):>9} "
                  f"{fmt(num(rows,'gcPauseP99Micros')):>9} {fmt(alloc,0,MB):>10} "
                  f"{fmt((alloc/duration) if alloc else None,0,MB):>11}")
        print("-" * len(header2))

    print()
    print("=" * 128)
    print("HEAD-TO-HEAD RATIOS   (<1.00 favours the first named arm)")
    print("=" * 128)
    pairs = [("fastcache-sidecar", "redis"), ("fastcache-sidecar", "caffeine"),
             ("fastcache-embedded", "caffeine")]
    for left, right in pairs:
        print(f"\n{left}  /  {right}")
        print(f"{'payload':>8} {'conc':>5} {'req p50':>9} {'req p99':>9} {'req/s':>9} "
              f"{'heap':>9} {'GC pause':>10} {'alloc':>9}")
        for (size, conc) in sorted(grouped):
            a, b = grouped[(size, conc)].get(left), grouped[(size, conc)].get(right)
            if not a or not b:
                continue

            def ratio(column):
                x, y = num(a, column), num(b, column)
                return f"{x / y:.2f}" if x is not None and y not in (None, 0) else "-"

            print(f"{label(size):>8} {conc:>5} {ratio('reqP50us'):>9} {ratio('reqP99us'):>9} "
                  f"{ratio('reqPerSec'):>9} {ratio('heapUsed'):>9} "
                  f"{ratio('gcTotalPauseMillis'):>10} {ratio('allocatedBytes'):>9}")


if __name__ == "__main__":
    main()
