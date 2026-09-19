#!/usr/bin/env python3
"""Aggregate a benchmark CSV into the tables used in JVM_MEMORY_GC_RESULTS.md.

Reports every run, not the best one, and prints the min-max spread beside each mean so that
run-to-run variability stays visible. Nothing here discards outliers.

    python fastcache-benchmarks/analyse.py docs/benchmarks/data/scenario-C-churn.csv \
        --columns gcPauseMillis gcCollections allocMB opsPerSec
"""
from __future__ import annotations

import argparse
import csv
import statistics
from collections import defaultdict


def label(payload_bytes: int) -> str:
    mib = 1 << 20
    return f"{payload_bytes // mib}MB" if payload_bytes >= mib else f"{payload_bytes // 1024}KB"


def numeric(rows, column):
    out = []
    for row in rows:
        value = row.get(column, "")
        if value in ("", "None", "-1"):
            continue
        try:
            out.append(float(value))
        except ValueError:
            pass
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_path")
    parser.add_argument("--columns", nargs="+", required=True)
    parser.add_argument("--group-by-payload", action="store_true", default=True)
    args = parser.parse_args()

    with open(args.csv_path, newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))

    grouped = defaultdict(list)
    for row in rows:
        grouped[(int(row["payloadBytes"]), row["arm"])].append(row)

    width = max(len(c) for c in args.columns) + 14
    header = f"{'payload':>8} {'arm':<20} " + " ".join(f"{c:>{width}}" for c in args.columns)
    print(header)
    print("-" * len(header))

    for (payload, arm), group in sorted(grouped.items()):
        cells = []
        for column in args.columns:
            values = numeric(group, column)
            if not values:
                cells.append(f"{'-':>{width}}")
                continue
            mean = statistics.fmean(values)
            if len(values) > 1:
                cells.append(f"{mean:>{width - 13}.1f} [{min(values):.0f}-{max(values):.0f}]")
            else:
                cells.append(f"{mean:>{width}.1f}")
        print(f"{label(payload):>8} {arm:<20} " + " ".join(cells))

    print(f"\n{len(rows)} rows, {len(set(r.get('run', '1') for r in rows))} run(s) per cell")


if __name__ == "__main__":
    main()
