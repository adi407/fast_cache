#!/usr/bin/env bash
#
# Phase 16 - sustained application load, sampled over time.
#
#   ./fastcache-e2e/longrun.sh <output-dir> <arm> <minutes>
#
# The matrix cells are 45 seconds each, which is enough to compare arms and not enough to see drift. This
# runs one arm under continuous 80/20 read-write load and samples the service every 30 seconds, so that
# heap growth, RSS growth, GC degradation, rising rejection counts and latency drift would all be visible
# as trends rather than as a single end-of-run number.
#
# What it is looking for, stated before running it: RSS climbing while entry count does not (a native
# leak), GC pause per request rising over time (degradation), or write rejections appearing mid-run (the
# capacity model engaging).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${ROOT}/docs/validation/data/e2e}"
ARM="${2:-fastcache-sidecar}"
MINUTES="${3:-60}"
PORT="${E2E_PORT:-8092}"
FC_PORT="${FC_PORT:-6382}"
REDIS_PORT="${REDIS_PORT:-6399}"
PAYLOAD="${PAYLOAD:-10m}"
ENTRIES="${ENTRIES:-51}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-30}"
mkdir -p "${OUT}"
CSV="${OUT}/longrun-${ARM}.csv"

FC_PID=""
start_sidecar() {
  java -Xmx512m -XX:MaxDirectMemorySize=4g -cp "${ROOT}/fastcache-engine/target/fastcache-engine.jar" \
    io.fastcache.engine.net.SidecarMain --port "${FC_PORT}" --offheap-max 2048m \
    --max-value-bytes 268435456 --reject-ratio "${REJECT_RATIO:-1.0}" --metrics-port off \
    --idle-timeout 0 --heartbeat-timeout 0 --stale-grace 0 > "${OUT}/longrun-sidecar.log" 2>&1 &
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

cleanup() {
  stop_app || true
  [ -n "${FC_PID}" ] && kill "${FC_PID}" 2>/dev/null || true
}
trap cleanup EXIT

CACHE_PORT="${FC_PORT}"
if [ "${ARM}" = "fastcache-sidecar" ]; then
  start_sidecar
  echo "sidecar up on ${FC_PORT}"
elif [ "${ARM}" = "redis" ]; then
  CACHE_PORT="${REDIS_PORT}"
fi

E2E_JVM_FLAGS="-Xmx4g -XX:MaxDirectMemorySize=2g -Xlog:gc*:file=${OUT}/longrun-${ARM}-gc.log::filecount=0" \
  "${ROOT}/fastcache-e2e/run.sh" app --arm "${ARM}" --payload "${PAYLOAD}" --entries "${ENTRIES}" \
    --port "${PORT}" --budget 2g --miss-cost 50 --rep object \
    --cache-host 127.0.0.1 --cache-port "${CACHE_PORT}" --cache-pid "${FC_PID:-0}" \
    > "${OUT}/longrun-${ARM}.applog" 2>&1 &

for _ in $(seq 1 90); do
  curl -s --max-time 3 "http://127.0.0.1:${PORT}/admin/metrics" >/dev/null 2>&1 && break
  sleep 1
done
curl -s --max-time 900 "http://127.0.0.1:${PORT}/admin/warmup" >/dev/null 2>&1 || true
curl -s --max-time 30 "http://127.0.0.1:${PORT}/admin/mark" >/dev/null 2>&1 || true

echo "driving ${ARM} for ${MINUTES} minutes, sampling every ${SAMPLE_SECONDS}s -> ${CSV}"

"${ROOT}/fastcache-e2e/run.sh" load \
    --base "http://127.0.0.1:${PORT}" --entries "${ENTRIES}" --concurrency "${CONCURRENCY:-8}" \
    --warmup-seconds 0 --duration-seconds $((MINUTES * 60)) --write-ratio 0.2 \
    --label "longrun-${ARM}" --csv "${OUT}/longrun-final.csv" > "${OUT}/longrun-${ARM}.loadlog" 2>&1 &
LOAD_PID=$!

echo "elapsedMin,heapUsed,heapCommitted,oldGenUsed,rss,externalRss,entries,writeRejections,hits,misses,corrupt,gcCollections,gcTotalPauseMillis,gcPauseP99Micros,allocatedBytes" > "${CSV}"

START=$(date +%s)
DEADLINE=$((START + MINUTES * 60))
while [ "$(date +%s)" -lt "${DEADLINE}" ]; do
  sleep "${SAMPLE_SECONDS}"
  NOW=$(date +%s)
  # settle=false on purpose: forcing a collection every 30 s would itself change the GC behaviour this
  # run exists to observe.
  BODY=$(curl -s --max-time 30 "http://127.0.0.1:${PORT}/admin/metrics" 2>/dev/null || echo '{}')
  MIN=$(awk -v a="${NOW}" -v b="${START}" 'BEGIN{printf "%.1f",(a-b)/60}')
  printf '%s' "${BODY}" | python -c "
import json,sys
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except Exception:
    sys.exit(0)
cols = ['heapUsed','heapCommitted','oldGenUsed','rss','externalRss','entries','writeRejections',
        'hits','misses','corrupt','gcCollections','gcTotalPauseMillis','gcPauseP99Micros','allocatedBytes']
print('${MIN},' + ','.join(str(d.get(c,'')) for c in cols))
" >> "${CSV}" 2>/dev/null || true
  printf '  %5s min  heap=%sMB rss=%sMB entries=%s rejects=%s\n' "${MIN}" \
    "$(printf '%s' "${BODY}" | grep -oE '"heapUsed":[0-9]+' | cut -d: -f2 | awk '{printf "%d",$1/1048576}')" \
    "$(printf '%s' "${BODY}" | grep -oE '"rss":[0-9]+' | cut -d: -f2 | awk '{printf "%d",$1/1048576}')" \
    "$(printf '%s' "${BODY}" | grep -oE '"entries":[-0-9]+' | cut -d: -f2)" \
    "$(printf '%s' "${BODY}" | grep -oE '"writeRejections":[0-9]+' | cut -d: -f2)"
done

wait "${LOAD_PID}" 2>/dev/null || true
echo "long run complete -> ${CSV}"
