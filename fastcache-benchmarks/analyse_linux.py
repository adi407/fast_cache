#!/usr/bin/env python3
"""Aggregate the native-Linux validation CSVs into the tables used in the validation report.

    python fastcache-benchmarks/analyse_linux.py docs/validation/data

Reports every run's spread alongside the mean, and computes the FastCache/Redis ratio per cell so the
direction is never inferred from eyeballing two columns.
"""
from __future__ import annotations

import csv
import glob
import os
import statistics
import sys
from collections import defaultdict

MB = 1 << 20


def label(size: int) -> str:
    return f"{size // MB}MB" if size >= MB else f"{size // 1024}KB"


def mean(rows, column):
    values = [float(r[column]) for r in rows if r.get(column) not in ('', None, '-1')]
    return statistics.fmean(values) if values else None


def spread(rows, column):
    values = [float(r[column]) for r in rows if r.get(column) not in ('', None, '-1')]
    return f"[{min(values):.0f}-{max(values):.0f}]" if values else "-"


def load(path):
    grouped = defaultdict(lambda: defaultdict(list))
    with open(path, newline='', encoding='utf-8') as handle:
        for row in csv.DictReader(handle):
            grouped[int(row['payloadBytes'])][row['arm']].append(row)
    return grouped


def get_table(path):
    grouped = load(path)
    print(f"\n{'=' * 104}\n{os.path.basename(path)}\n{'=' * 104}")
    header = (f"{'payload':>8} {'arm':<20} {'ops/s':>9} {'p50 us':>12} {'p95 us':>12} "
              f"{'p99 us':>12} {'MB/s':>8} {'corrupt':>8}")
    print(header)
    print('-' * len(header))
    for size in sorted(grouped):
        for arm in ('caffeine', 'fastcache-sidecar', 'redis'):
            rows = grouped[size].get(arm)
            if not rows:
                continue
            p50 = mean(rows, 'clientP50us')
            mbps = (size / MB) / (p50 / 1e6) if p50 else 0
            print(f"{label(size):>8} {arm:<20} {mean(rows, 'throughputOpsPerSec') or 0:>9.0f} "
                  f"{p50 or 0:>12.0f} {mean(rows, 'clientP95us') or 0:>12.0f} "
                  f"{mean(rows, 'clientP99us') or 0:>12.0f} {mbps:>8.0f} "
                  f"{mean(rows, 'corrupt') or 0:>8.0f}")
        fc, rd = grouped[size].get('fastcache-sidecar'), grouped[size].get('redis')
        if fc and rd:
            r50 = mean(fc, 'clientP50us') / mean(rd, 'clientP50us')
            r99 = mean(fc, 'clientP99us') / mean(rd, 'clientP99us')
            rops = mean(fc, 'throughputOpsPerSec') / max(mean(rd, 'throughputOpsPerSec'), 1e-9)
            verdict = 'FastCache' if r50 < 0.95 else ('Redis' if r50 > 1.05 else 'tie')
            print(f"{'':>8} {'>> fc/redis ratio':<20} {rops:>9.2f} {r50:>12.2f} "
                  f"{'':>12} {r99:>12.2f} {'':>8}   {verdict}")
            print(f"{'':>8} {'>> spread p50':<20} fc={spread(fc, 'clientP50us')}  "
                  f"redis={spread(rd, 'clientP50us')}")
        print('-' * len(header))


def mixed_table(path):
    grouped = load(path)
    ratio = os.path.basename(path).split('-r')[-1].replace('.csv', '')
    print(f"\n{'=' * 104}\n{os.path.basename(path)}   (read share {ratio})\n{'=' * 104}")
    header = (f"{'payload':>8} {'arm':<20} {'GET p50':>10} {'GET p99':>10} {'GET MB/s':>9} | "
              f"{'SET p50':>10} {'SET p99':>10} {'SET MB/s':>9} {'corrupt':>8}")
    print(header)
    print('-' * len(header))
    for size in sorted(grouped):
        for arm in ('fastcache-sidecar', 'redis'):
            rows = grouped[size].get(arm)
            if not rows:
                continue
            print(f"{label(size):>8} {arm:<20} {mean(rows, 'getP50us') or 0:>10.0f} "
                  f"{mean(rows, 'getP99us') or 0:>10.0f} {mean(rows, 'getMBps') or 0:>9.0f} | "
                  f"{mean(rows, 'setP50us') or 0:>10.0f} {mean(rows, 'setP99us') or 0:>10.0f} "
                  f"{mean(rows, 'setMBps') or 0:>9.0f} {mean(rows, 'corrupt') or 0:>8.0f}")
        fc, rd = grouped[size].get('fastcache-sidecar'), grouped[size].get('redis')
        if fc and rd:
            def safe(a, b, col):
                x, y = mean(a, col), mean(b, col)
                return x / y if x and y else float('nan')
            print(f"{'':>8} {'>> fc/redis ratio':<20} {safe(fc, rd, 'getP50us'):>10.2f} "
                  f"{safe(fc, rd, 'getP99us'):>10.2f} {'':>9} | "
                  f"{safe(fc, rd, 'setP50us'):>10.2f} {safe(fc, rd, 'setP99us'):>10.2f}")
        print('-' * len(header))


def main() -> None:
    root = sys.argv[1] if len(sys.argv) > 1 else 'docs/validation/data'
    env = os.path.join(root, 'environment.txt')
    if os.path.exists(env):
        print(open(env, encoding='utf-8').read())
    for path in sorted(glob.glob(os.path.join(root, 'linux-get-*.csv'))):
        get_table(path)
    for path in sorted(glob.glob(os.path.join(root, 'linux-mixed-*.csv'))):
        mixed_table(path)


if __name__ == '__main__':
    main()
