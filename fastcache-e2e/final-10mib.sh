#!/usr/bin/env bash
#
# The final 10 MiB end-to-end validation. One experiment, four arms, three repetitions.
#
#   ./fastcache-e2e/final-10mib.sh <output-dir>
#
# This script deliberately does ONE cell family: 10 MiB payloads, 51 entries, 8 concurrent clients,
# 100% GET, steady state. It does not sweep payload sizes, concurrency levels or write ratios, and it
# must not be extended to. The question it exists to answer is whether FastCache's large-value transport
# advantage over native Redis survives an end-to-end JVM application, and every additional cell is a
# chance to contaminate the one that matters.
#
# Two previous attempts at this measurement were voided: one by two matrix processes interleaving on one
# host, one by a server still holding another payload tier's entries. The pre-flight below is written
# against those two failures specifically.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${ROOT}/docs/validation/data/final-10mib}"
PORT="${E2E_PORT:-8090}"
FC_PORT="${FC_PORT:-6380}"
REDIS_PORT="${REDIS_PORT:-6399}"

PAYLOAD="10m"                 # 10 MiB: E2eApplication.parseSize uses 1<<20
PAYLOAD_BYTES=10485760
ENTRIES=51                    # 51 x 10 MiB = 510 MiB working set, identical for every arm
CONCURRENCY=8
WRITE_RATIO=0.0               # 100% GET during the measured window
WARMUP_SECONDS="${WARMUP_SECONDS:-20}"
DURATION_SECONDS="${DURATION_SECONDS:-45}"
REPEATS="${REPEATS:-3}"
HEAP="${HEAP:-4g}"
ARMS=(caffeine fastcache-embedded fastcache-sidecar redis)

CSV="${OUT}/final-10mib.csv"
LOG="${OUT}/harness.log"
mkdir -p "${OUT}"

log()  { printf '\n>>> %s\n' "$*" | tee -a "${LOG}"; }
note() { printf '    %s\n' "$*" | tee -a "${LOG}"; }
die()  { printf '\nFATAL: %s\n' "$*" | tee -a "${LOG}" >&2; exit 1; }

FAILED_CELLS=0

# ---------------------------------------------------------------------------------------------------
# Pre-flight 1: process isolation.
#
# Exactly one application JVM, one generator and one cache server may be alive during a cell. Verified
# rather than assumed, because the previous round's contamination came from surviving JVMs that the
# harness believed it had killed.
# ---------------------------------------------------------------------------------------------------
java_pids() { pgrep -f 'io\.fastcache\.(e2e|engine\.net\.SidecarMain)' 2>/dev/null || true; }

# The sidecar is a long-lived server started once for the whole run, so it is expected to be alive
# between cells and is not a stray. Anything else carrying FastCache code is: an application JVM that
# outlived its cell, or a second sidecar, are exactly the two shapes that contaminated the last attempt.
require_no_stray_jvms() {
  local phase="$1" stray="" pid
  for pid in $(java_pids); do
    [ "${pid}" = "${FC_SIDECAR_PID:-}" ] && continue
    stray="${stray} ${pid}"
  done
  if [ -n "${stray}" ]; then
    note "unexpected FastCache JVMs alive:"
    ps -o pid,rss,etime,args -p ${stray} 2>/dev/null | tee -a "${LOG}" || true
    die "process isolation violated ${phase}"
  fi
  note "process isolation ok (${phase}): only the expected server processes alive"
}

# ---------------------------------------------------------------------------------------------------
# Pre-flight 2: port isolation.
#
# "The port is free" is not sufficient and was the exact hole last time: an OOM-thrashing JVM could not
# answer an HTTP probe inside its timeout, so the port read as free while the process still held it.
# A bind test asks the kernel, which cannot be too busy to answer.
# ---------------------------------------------------------------------------------------------------
require_port_free() {
  local port="$1" what="$2"
  if command -v ss >/dev/null 2>&1 && ss -ltn "sport = :${port}" 2>/dev/null | grep -q ":${port}"; then
    note "port ${port} held by:"
    ss -ltnp "sport = :${port}" 2>/dev/null | tee -a "${LOG}" || true
    die "port ${port} (${what}) not free"
  fi
  # Kernel-level confirmation: if anything holds the port, this bind fails.
  python3 - "${port}" <<'PY' || die "port bind test failed for ${what}"
import socket, sys
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("127.0.0.1", int(sys.argv[1])))
finally:
    s.close()
PY
  note "port ${port} (${what}) free, confirmed by bind"
}

# Verify the process we started is the one holding the port, and is still alive.
require_port_owned_by() {
  local port="$1" pid="$2" what="$3"
  kill -0 "${pid}" 2>/dev/null || die "${what} pid ${pid} is not alive"
  local owner
  owner="$(ss -ltnp "sport = :${port}" 2>/dev/null | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true)"
  [ -n "${owner}" ] || die "nothing is listening on ${port} for ${what}"
  # The JVM launcher may exec into a child, so accept the process itself or a descendant of it.
  local walk="${owner}"
  for _ in 1 2 3 4; do
    [ "${walk}" = "${pid}" ] && { note "${what}: pid ${pid} owns port ${port}"; return 0; }
    walk="$(ps -o ppid= -p "${walk}" 2>/dev/null | tr -d ' ' || true)"
    [ -n "${walk}" ] && [ "${walk}" != "0" ] || break
  done
  die "${what}: port ${port} is owned by pid ${owner}, not by ${pid} or a descendant"
}

# ---------------------------------------------------------------------------------------------------
# Cache servers
# ---------------------------------------------------------------------------------------------------
FC_SIDECAR_PID=""
start_sidecar() {
  require_port_free "${FC_PORT}" "fastcache sidecar"
  java -Xmx512m -XX:MaxDirectMemorySize=4g \
       -cp "${ROOT}/fastcache-engine/target/fastcache-engine.jar" \
       io.fastcache.engine.net.SidecarMain \
       --port "${FC_PORT}" --offheap-max 2048m --max-value-bytes 268435456 \
       --reject-ratio "${REJECT_RATIO:-1.0}" --metrics-port off --idle-timeout 0 \
       --heartbeat-timeout 0 --stale-grace 0 > "${OUT}/sidecar.log" 2>&1 &
  FC_SIDECAR_PID=$!
  for _ in $(seq 1 40); do
    grep -q FASTCACHE_READY "${OUT}/sidecar.log" 2>/dev/null && break
    sleep 1
  done
  grep -q FASTCACHE_READY "${OUT}/sidecar.log" || die "sidecar did not report ready"
  require_port_owned_by "${FC_PORT}" "${FC_SIDECAR_PID}" "sidecar"
}
stop_sidecar() {
  [ -n "${FC_SIDECAR_PID}" ] || return 0
  kill "${FC_SIDECAR_PID}" 2>/dev/null || true
  wait "${FC_SIDECAR_PID}" 2>/dev/null || true
  FC_SIDECAR_PID=""
}

REDIS_PID=""
start_redis() {
  require_port_free "${REDIS_PORT}" "redis"
  redis-server "${ROOT}/fastcache-e2e/redis-final.conf" --port "${REDIS_PORT}" \
      > "${OUT}/redis.log" 2>&1 &
  REDIS_PID=$!
  for _ in $(seq 1 40); do
    redis-cli -p "${REDIS_PORT}" ping 2>/dev/null | grep -q PONG && break
    sleep 1
  done
  redis-cli -p "${REDIS_PORT}" ping 2>/dev/null | grep -q PONG || die "redis did not come up"
  require_port_owned_by "${REDIS_PORT}" "${REDIS_PID}" "redis"
  redis-cli -p "${REDIS_PORT}" info server | grep -E 'redis_version' | tee -a "${LOG}" || true
  # Asserted rather than assumed: if the config file did not load, Redis would silently run with
  # defaults and the comparison would be against a differently-configured server than the one the
  # microbenchmark used. `io_threads_active` is 0 at idle even when configured, so read the config.
  local threads maxmem policy
  threads="$(redis-cli -p "${REDIS_PORT}" config get io-threads | tail -1)"
  maxmem="$(redis-cli -p "${REDIS_PORT}" config get maxmemory | tail -1)"
  policy="$(redis-cli -p "${REDIS_PORT}" config get maxmemory-policy | tail -1)"
  note "redis config: io-threads=${threads} maxmemory=${maxmem} policy=${policy}"
  [ "${threads}" = "4" ] || die "redis io-threads=${threads}, expected 4 — config file did not load"
  [ "${policy}" = "allkeys-lru" ] || die "redis policy=${policy}, expected allkeys-lru"
}
stop_redis() {
  [ -n "${REDIS_PID}" ] || return 0
  redis-cli -p "${REDIS_PORT}" shutdown nosave 2>/dev/null || kill "${REDIS_PID}" 2>/dev/null || true
  wait "${REDIS_PID}" 2>/dev/null || true
  REDIS_PID=""
}

# CPU seconds consumed by a cache process, read from /proc so it covers the whole measured window.
cpu_seconds() {
  local pid="$1"
  [ -n "${pid}" ] && [ -r "/proc/${pid}/stat" ] || { echo "-1"; return; }
  awk -v hz="$(getconf CLK_TCK)" '{printf "%.2f", ($14 + $15) / hz}' "/proc/${pid}/stat"
}
rss_kb() {
  local pid="$1"
  [ -n "${pid}" ] && [ -r "/proc/${pid}/status" ] || { echo "-1"; return; }
  awk '/^VmRSS:/{print $2}' "/proc/${pid}/status"
}

# ---------------------------------------------------------------------------------------------------
# Application lifecycle
# ---------------------------------------------------------------------------------------------------
APP_PID=""
stop_app() {
  [ -n "${APP_PID}" ] || return 0
  curl -s --max-time 10 "http://127.0.0.1:${PORT}/admin/shutdown" >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    kill -0 "${APP_PID}" 2>/dev/null || { APP_PID=""; return 0; }
    sleep 1
  done
  note "app ${APP_PID} ignored in-band shutdown; killing"
  kill -9 "${APP_PID}" 2>/dev/null || true
  wait "${APP_PID}" 2>/dev/null || true
  APP_PID=""
  # Whatever happened, the next cell may not start until the port is genuinely released.
  for _ in $(seq 1 30); do
    if ! (ss -ltn "sport = :${PORT}" 2>/dev/null | grep -q ":${PORT}"); then return 0; fi
    sleep 1
  done
  die "port ${PORT} still held after killing the application"
}

# ---------------------------------------------------------------------------------------------------
# One cell
# ---------------------------------------------------------------------------------------------------
run_cell() {
  local arm="$1" rep="$2"
  local label="final-${arm}-10mib-c${CONCURRENCY}-r${rep}"

  log "cell ${label}"
  require_port_free "${PORT}" "application"

  local cache_pid=0 cache_port="${FC_PORT}"
  case "${arm}" in
    redis)             cache_pid="${REDIS_PID}";       cache_port="${REDIS_PORT}" ;;
    fastcache-sidecar) cache_pid="${FC_SIDECAR_PID}";  cache_port="${FC_PORT}" ;;
  esac

  # The cross-process servers outlive the application, so they are flushed here as well as by the
  # application's own startup clear. Belt and braces, and cheap: this is the failure that voided the
  # previous 10 MiB result.
  case "${arm}" in
    redis)             redis-cli -p "${REDIS_PORT}" flushall >/dev/null ;;
    fastcache-sidecar) : ;;   # the application clears it before binding its port
  esac

  E2E_JVM_FLAGS="-Xmx${HEAP} -XX:MaxDirectMemorySize=2g" \
    "${ROOT}/fastcache-e2e/run.sh" app \
      --arm "${arm}" --payload "${PAYLOAD}" --entries "${ENTRIES}" --port "${PORT}" \
      --budget 2g --miss-cost 50 --rep object \
      --cache-host 127.0.0.1 --cache-port "${cache_port}" --cache-pid "${cache_pid}" \
      > "${OUT}/${label}.applog" 2>&1 &
  APP_PID=$!

  local ready=0
  for _ in $(seq 1 90); do
    if curl -s --max-time 3 "http://127.0.0.1:${PORT}/admin/metrics" >/dev/null 2>&1; then ready=1; break; fi
    kill -0 "${APP_PID}" 2>/dev/null || break
    sleep 1
  done
  [ "${ready}" = "1" ] || { note "application did not start; see ${label}.applog"; stop_app
                            FAILED_CELLS=$((FAILED_CELLS + 1)); return 0; }
  require_port_owned_by "${PORT}" "${APP_PID}" "application (${arm})"

  local cpu_before rss_before
  cpu_before="$(cpu_seconds "${cache_pid}")"

  set +e
  "${ROOT}/fastcache-e2e/run.sh" load \
      --base "http://127.0.0.1:${PORT}" --entries "${ENTRIES}" --concurrency "${CONCURRENCY}" \
      --warmup-seconds "${WARMUP_SECONDS}" --duration-seconds "${DURATION_SECONDS}" \
      --write-ratio "${WRITE_RATIO}" --label "${label}" --csv "${CSV}" 2>&1 \
      | tee -a "${LOG}" | grep -E '^(RESULT|INTEGRITY|CELL-INVALID)'
  local status=${PIPESTATUS[0]}
  set -e

  local cpu_after rss_after
  cpu_after="$(cpu_seconds "${cache_pid}")"
  rss_after="$(rss_kb "${cache_pid}")"
  printf '%s,%s,%s,%s,%s\n' "${label}" "${arm}" "${cpu_before}" "${cpu_after}" "${rss_after}" \
      >> "${OUT}/cache-process.csv"

  if [ "${status}" != "0" ]; then
    note "CELL FAILED (exit ${status}) — not counted as a result"
    FAILED_CELLS=$((FAILED_CELLS + 1))
  fi

  stop_app
  sleep 3
  require_no_stray_jvms "after ${label}"
}

# ---------------------------------------------------------------------------------------------------
trap 'stop_app || true; stop_sidecar; stop_redis' EXIT

log "final 10 MiB end-to-end validation"
note "payload      ${PAYLOAD_BYTES} bytes (10 MiB)"
note "entries      ${ENTRIES}  (working set $((PAYLOAD_BYTES * ENTRIES / 1024 / 1024)) MiB, identical for every arm)"
note "concurrency  ${CONCURRENCY}"
note "workload     100% GET, write-ratio ${WRITE_RATIO}"
note "repeats      ${REPEATS} per arm, warmed up independently"
note "app heap     ${HEAP}"

echo "label,arm,cacheCpuBeforeSec,cacheCpuAfterSec,cacheRssKb" > "${OUT}/cache-process.csv"

require_no_stray_jvms "at start"
require_port_free "${PORT}" "application"

start_redis
start_sidecar
require_port_owned_by "${REDIS_PORT}" "${REDIS_PID}" "redis"
require_port_owned_by "${FC_PORT}" "${FC_SIDECAR_PID}" "sidecar"

# Arm order is rotated per repetition so no arm sits systematically in the position that benefits from
# a warm page cache or a settled host.
for rep in $(seq 1 "${REPEATS}"); do
  log "repetition ${rep} of ${REPEATS}"
  offset=$(( (rep - 1) % ${#ARMS[@]} ))
  for i in "${!ARMS[@]}"; do
    run_cell "${ARMS[$(( (i + offset) % ${#ARMS[@]} ))]}" "${rep}"
  done
done

stop_sidecar
stop_redis

log "done — ${FAILED_CELLS} failed cell(s)"
note "results -> ${CSV}"
[ -f "${CSV}" ] && column -s, -t < "${CSV}" | cut -c1-200 | tee -a "${LOG}" || true

# A failed cell is an experimental defect, not a result. Surfaced as a non-zero exit so the run cannot
# be quietly read as complete.
exit $(( FAILED_CELLS > 0 ? 6 : 0 ))
