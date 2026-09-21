#!/usr/bin/env python3
"""Summarise the final 10 MiB end-to-end validation.

Reports the two levels separately (cache transport, then end-to-end application), applies the
pre-registered 1.5x threshold to the end-to-end number, and reports run-to-run variance so an
unstable result is visible as instability rather than as a conclusion.

Reads only cells the harness marked valid. Invalid cells are listed, never averaged in.
"""
import csv
import os
import statistics
import sys

PAYLOAD_MIB = 10.0
THRESHOLD = 1.5
ARMS = ["caffeine", "fastcache-embedded", "fastcache-sidecar", "redis"]


def load(path):
    valid, invalid = {}, []
    if not os.path.exists(path):
        return valid, invalid, "no results file at %s" % path
    for row in csv.DictReader(open(path)):
        if row.get("cellValid", "").strip().lower() != "true":
            invalid.append((row.get("label", "?"), row.get("invalidReason", "?")))
            continue
        valid.setdefault(row["arm"], []).append(row)
    return valid, invalid, None


def nums(rows, column):
    out = []
    for r in rows:
        try:
            out.append(float(r[column]))
        except (KeyError, TypeError, ValueError):
            pass
    return out


def mean(rows, column):
    v = nums(rows, column)
    return statistics.fmean(v) if v else float("nan")


def spread(rows, column):
    """Run-to-run spread as a percentage of the mean. The inconclusive bar is 20%."""
    v = nums(rows, column)
    if len(v) < 2:
        return float("nan")
    m = statistics.fmean(v)
    return (max(v) - min(v)) / m * 100.0 if m else float("nan")


def fmt(x, places=1):
    return "-" if x != x else ("%%.%df" % places) % x


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "final-results"
    valid, invalid, err = load(os.path.join(out_dir, "final-10mib.csv"))

    lines = ["# Final 10 MiB end-to-end validation\n"]
    if err:
        lines.append("\n**NO RESULTS.** %s\n" % err)
        print("\n".join(lines))
        return 1

    lines.append("\nPayload 10 MiB (10485760 bytes) | 51 entries (510 MiB working set) "
                 "| 8 concurrent clients | 100% GET\n")
    lines.append("\nValid cells per arm: " +
                 ", ".join("%s=%d" % (a, len(valid.get(a, []))) for a in ARMS) + "\n")

    if invalid:
        lines.append("\n## Invalid cells (excluded, never averaged)\n")
        lines.append("| cell | reason |")
        lines.append("|---|---|")
        for label, reason in invalid:
            lines.append("| `%s` | %s |" % (label, reason))
        lines.append("")

    # ---- Level 1: cache transport -------------------------------------------------------------
    lines.append("\n## Level 1 - cache transport (lookup only)\n")
    lines.append("| Metric | " + " | ".join(ARMS) + " |")
    lines.append("|---|" + "---:|" * len(ARMS))
    for label, col, places in [("lookup p50 us", "lookupP50us", 1),
                               ("lookup p95 us", "lookupP95us", 1),
                               ("lookup p99 us", "lookupP99us", 1),
                               ("decode p50 us", "decodeP50us", 1)]:
        lines.append("| %s | " % label +
                     " | ".join(fmt(mean(valid.get(a, []), col), places) for a in ARMS) + " |")
    # Cache-level bandwidth: one payload moved per lookup.
    # Only for arms that actually move bytes. Caffeine and the embedded engine return a reference;
    # dividing a payload size by a pointer dereference is not a transfer rate, it is a misleading number.
    row = []
    for a in ARMS:
        p50 = mean(valid.get(a, []), "lookupP50us")
        transfers = a in ("fastcache-sidecar", "redis")
        row.append(fmt(PAYLOAD_MIB / (p50 / 1e6), 0)
                   if transfers and p50 == p50 and p50 > 0 else "n/a")
    lines.append("| transport MiB/s at p50 | " + " | ".join(row) + " |")

    # ---- Level 2: end-to-end ------------------------------------------------------------------
    lines.append("\n## Level 2 - end-to-end application (HTTP, client-observed)\n")
    lines.append("| Metric | " + " | ".join(ARMS) + " |")
    lines.append("|---|" + "---:|" * len(ARMS))
    for label, col, places in [("HTTP p50 us", "httpP50us", 1),
                               ("HTTP p95 us", "httpP95us", 1),
                               ("HTTP p99 us", "httpP99us", 1),
                               ("req/s", "reqPerSec", 1)]:
        lines.append("| %s | " % label +
                     " | ".join(fmt(mean(valid.get(a, []), col), places) for a in ARMS) + " |")
    row = []
    for a in ARMS:
        rps = mean(valid.get(a, []), "reqPerSec")
        row.append("-" if rps != rps else fmt(rps * PAYLOAD_MIB, 0))
    lines.append("| application MiB/s | " + " | ".join(row) + " |")

    # ---- Memory / GC --------------------------------------------------------------------------
    lines.append("\n## Memory and GC (application JVM)\n")
    lines.append("| Metric | " + " | ".join(ARMS) + " |")
    lines.append("|---|" + "---:|" * len(ARMS))
    for label, col, scale, places in [("heap used MiB", "heapUsed", 1 / 1048576.0, 0),
                                      ("heap committed MiB", "heapCommitted", 1 / 1048576.0, 0),
                                      ("allocated GiB", "allocatedBytes", 1 / 1073741824.0, 1),
                                      ("GC collections", "gcCollections", 1.0, 0),
                                      ("GC total pause ms", "gcTotalPauseMillis", 1.0, 0),
                                      ("GC p99 pause us", "gcPauseP99Micros", 1.0, 0)]:
        vals = []
        for a in ARMS:
            m = mean(valid.get(a, []), col)
            vals.append("-" if m != m else fmt(m * scale, places))
        lines.append("| %s | " % label + " | ".join(vals) + " |")

    # ---- Variance -----------------------------------------------------------------------------
    lines.append("\n## Run-to-run variance (max-min as % of mean; >20% is inconclusive)\n")
    lines.append("| Metric | " + " | ".join(ARMS) + " |")
    lines.append("|---|" + "---:|" * len(ARMS))
    unstable = []
    for label, col in [("req/s", "reqPerSec"), ("HTTP p50", "httpP50us"),
                       ("HTTP p99", "httpP99us"), ("lookup p50", "lookupP50us")]:
        vals = []
        for a in ARMS:
            s = spread(valid.get(a, []), col)
            vals.append("-" if s != s else fmt(s, 1) + "%")
            if s == s and s > 20.0 and col == "reqPerSec":
                unstable.append("%s %s=%.1f%%" % (a, label, s))
        lines.append("| %s | " % label + " | ".join(vals) + " |")

    # ---- Pre-registered decision --------------------------------------------------------------
    lines.append("\n## Pre-registered threshold\n")
    sidecar = mean(valid.get("fastcache-sidecar", []), "reqPerSec")
    redis = mean(valid.get("redis", []), "reqPerSec")
    lines.append("```")
    lines.append("fastcache-sidecar  %s req/s" % fmt(sidecar))
    lines.append("redis              %s req/s" % fmt(redis))
    if redis == redis and sidecar == sidecar and redis > 0:
        ratio = sidecar / redis
        lines.append("ratio              %.3fx   (threshold %.1fx)" % (ratio, THRESHOLD))
        lines.append("")
        if invalid or unstable:
            lines.append("VERDICT  INCONCLUSIVE - experimental defect, see above")
            if unstable:
                lines.append("         unstable: " + "; ".join(unstable))
        elif ratio >= THRESHOLD:
            lines.append("VERDICT  A - large-value thesis SURVIVES the end-to-end test")
        else:
            lines.append("VERDICT  C — end-to-end advantage below %.1fx; weak product" % THRESHOLD)
    else:
        lines.append("ratio              not computable — missing arm")
        lines.append("")
        lines.append("VERDICT  INCONCLUSIVE - an arm produced no valid cell")
    lines.append("```")

    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
