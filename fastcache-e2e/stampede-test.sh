#!/usr/bin/env bash
#
# Phase 13 - stampede: N concurrent callers, one missing key, one expensive loader.
#
#   ./fastcache-e2e/stampede-test.sh <output-dir> [concurrency]
#
# Each arm is measured twice: with whatever coalescing it provides unaided ("native"), and with the ten
# lines of application-level coalescing any team would write ("app"), applied identically to all four.
# Publishing only the first would overstate FastCache; publishing only the second would hide that two
# arms need no code at all.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${ROOT}/docs/validation/data/e2e}"
CONCURRENCY="${2:-100}"
PORT="${E2E_PORT:-8093}"
FC_PORT="${FC_PORT:-6383}"
REDIS_PORT="${REDIS_PORT:-6399}"
PAYLOAD="${PAYLOAD:-1m}"
mkdir -p "${OUT}"
CSV="${OUT}/stampede.csv"
echo "arm,mode,concurrency,loaderRuns,correct,errors,firstResponseMillis,totalMillis,p50Millis,p95Millis,p99Millis,maxMillis" > "${CSV}"

FC_PID=""
start_sidecar() {
  java -Xmx512m -XX:MaxDirectMemorySize=2g -cp "${ROOT}/fastcache-engine/target/fastcache-engine.jar" \
    io.fastcache.engine.net.SidecarMain --port "${FC_PORT}" --offheap-max 1024m \
    --max-value-bytes 268435456 --reject-ratio 1.0 --metrics-port off --idle-timeout 0 \
    --heartbeat-timeout 0 --stale-grace 0 > "${OUT}/stampede-sidecar.log" 2>&1 &
  FC_PID=$!
  sleep 4
}

stop_app() {
  curl -s --max-time 10 "http://127.0.0.1:${PORT}/admin/shutdown" >/dev/null 2>&1 || true
  for _ in $(seq 1 40); do
    curl -s --max-time 2 "http://127.0.0.1:${PORT}/admin/pid" >/dev/null 2>&1 || return 0
    sleep 1
  done
  return 1
}
cleanup() { stop_app || true; [ -n "${FC_PID}" ] && kill "${FC_PID}" 2>/dev/null || true; }
trap cleanup EXIT

start_sidecar
echo "sidecar on ${FC_PORT}"

for arm in caffeine fastcache-embedded fastcache-sidecar redis; do
  port="${FC_PORT}"
  [ "${arm}" = "redis" ] && port="${REDIS_PORT}"

  E2E_JVM_FLAGS="-Xmx4g -XX:MaxDirectMemorySize=2g" "${ROOT}/fastcache-e2e/run.sh" app \
      --arm "${arm}" --payload "${PAYLOAD}" --entries 8 --port "${PORT}" --budget 1g \
      --miss-cost 200 --rep object --cache-host 127.0.0.1 --cache-port "${port}" --cache-pid 0 \
      > "${OUT}/stampede-${arm}.applog" 2>&1 &

  ready=0
  for _ in $(seq 1 60); do
    curl -s --max-time 3 "http://127.0.0.1:${PORT}/admin/metrics" >/dev/null 2>&1 && { ready=1; break; }
    sleep 1
  done
  [ "${ready}" = "1" ] || { echo "  ${arm}: did not start" >&2; continue; }

  for mode in native app none; do
    for round in 1 2 3; do
      body=$(curl -s --max-time 300 \
             "http://127.0.0.1:${PORT}/stampede?concurrency=${CONCURRENCY}&mode=${mode}&round=${round}" \
             2>/dev/null || echo '{}')
      echo "  ${arm}/${mode} round ${round}: ${body}"
      printf '%s' "${body}" | python -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: sys.exit(0)
cols=['arm','mode','concurrency','loaderRuns','correct','errors','firstResponseMillis','totalMillis',
      'p50Millis','p95Millis','p99Millis','maxMillis']
print(','.join(str(d.get(c,'')) for c in cols))
" >> "${CSV}" 2>/dev/null || true
    done
  done

  stop_app || echo "  ${arm}: did not stop cleanly" >&2
  sleep 2
done

echo
echo "=== loader executions (1 means the stampede was collapsed) ==="
awk -F, 'NR>1{printf "  %-20s %-8s conc=%-5s loaderRuns=%-6s first=%-9s total=%-9s p99=%s\n",$1,$2,$3,$4,$7,$8,$11}' "${CSV}"
echo
echo "results -> ${CSV}"
