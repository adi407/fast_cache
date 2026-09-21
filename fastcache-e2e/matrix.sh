#!/usr/bin/env bash
#
# Drives the end-to-end matrix: for each arm, payload and concurrency, start the service in its own JVM,
# populate it, drive load from a second JVM, and record the service's own latency, heap, GC and RSS.
#
#   ./fastcache-e2e/matrix.sh <output-dir> [phase]
#
# phases:  steady (default, 100% GET)  |  churn (80/20 and 90/10)  |  smallheap (Phase 14)
#
# The service is restarted for every cell. That costs wall-clock but it is the only way to get a heap and
# GC figure that belongs to one configuration rather than to the residue of the previous one.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${ROOT}/docs/validation/data/e2e}"
PHASE="${2:-steady}"
PORT="${E2E_PORT:-8090}"
FC_PORT="${FC_PORT:-6380}"
REDIS_PORT="${REDIS_PORT:-6399}"
REDIS_PID="${REDIS_PID:-0}"
mkdir -p "${OUT}"

# Refuse to run twice at once.
#
# Not defensive programming for its own sake: three matrix runs ended up interleaving into one CSV
# because killing the service JVMs did not kill the shells driving them, and the resulting file had cells
# from different runs in nonsensical order. A lock makes that failure loud instead of silent, and the
# silent version is the one that produces a published table nobody can reproduce.
LOCK="${OUT}/.matrix.lock"
if [ -e "${LOCK}" ]; then
  echo "another matrix run holds ${LOCK} (pid $(cat "${LOCK}" 2>/dev/null))." >&2
  echo "stop it first, or remove the lock if it is stale." >&2
  exit 3
fi
echo "$$" > "${LOCK}"
cleanup_lock() { rm -f "${LOCK}"; }

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) IS_WIN=1 ;;
  *)                    IS_WIN=0 ;;
esac

log() { printf '\n>>> %s\n' "$*"; }

# Stopping the service turned out to be the one genuinely non-portable part of this harness. Under Git
# Bash on Windows, $! is an MSYS pid that Windows process APIs do not recognise, so a Stop-Process call
# on it silently succeeds while the JVM keeps running. The first attempt at this matrix was discarded
# because of exactly that: a finished cell's service was still holding 1.4 GB and burning CPU while the
# next cell was being measured. The service is therefore asked to exit over HTTP, and the harness then
# verifies the port is actually free before starting the next cell.
stop_app() {
  local port="$1"
  curl -s --max-time 10 "http://127.0.0.1:${port}/admin/shutdown" >/dev/null 2>&1 || true
  for _ in $(seq 1 40); do
    if ! curl -s --max-time 2 "http://127.0.0.1:${port}/admin/pid" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "  WARNING: service on ${port} did not exit; results after this point are suspect" >&2
  return 1
}

kill_pid() {
  local pid="$1"
  [ -z "${pid}" ] && return 0
  if [ "${IS_WIN}" = "1" ]; then
    # Only used for the sidecar, which is launched as a direct `java` child and whose pid is therefore
    # usable. Tolerated as best-effort; the port check above is what the cells actually rely on.
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"ProcessId=${pid}\" | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" 2>/dev/null || true
  else
    kill "${pid}" 2>/dev/null || true
  fi
}

# --- FastCache sidecar ------------------------------------------------------------------------------
FC_SIDECAR_PID=""
start_sidecar() {
  local budget="$1" reject="$2"
  local jar="${ROOT}/fastcache-engine/target/fastcache-engine.jar"
  java -Xmx512m "-XX:MaxDirectMemorySize=$((budget * 2))m" -cp "${jar}" \
       io.fastcache.engine.net.SidecarMain \
       --port "${FC_PORT}" --offheap-max "${budget}m" --max-value-bytes 268435456 \
       --reject-ratio "${reject}" --metrics-port off --idle-timeout 0 \
       --heartbeat-timeout 0 --stale-grace 0 > "${OUT}/sidecar.log" 2>&1 &
  FC_SIDECAR_PID=$!
  sleep 4
  echo "sidecar pid ${FC_SIDECAR_PID} on ${FC_PORT} (budget ${budget}m, reject-ratio ${reject})"
}
stop_sidecar() { kill_pid "${FC_SIDECAR_PID}"; FC_SIDECAR_PID=""; }

# --- one cell ---------------------------------------------------------------------------------------
APP_PID=""
run_cell() {
  local arm="$1" payload="$2" entries="$3" conc="$4" write_ratio="$5" heap="$6" csv="$7" label="$8"

  # A cell must never start against a port a previous service still holds; that is how the first attempt
  # at this matrix produced two JVMs competing for the same machine.
  if curl -s --max-time 2 "http://127.0.0.1:${PORT}/admin/pid" >/dev/null 2>&1; then
    echo "  ${label}: port ${PORT} still in use, forcing shutdown" >&2
    stop_app "${PORT}" || { echo "  ${label}: SKIPPED, port not free" >&2; return 0; }
  fi

  E2E_JVM_FLAGS="-Xmx${heap} -XX:MaxDirectMemorySize=2g -XX:StartFlightRecording=settings=profile,filename=${OUT}/${label}.jfr,dumponexit=true" \
    "${ROOT}/fastcache-e2e/run.sh" app \
      --arm "${arm}" --payload "${payload}" --entries "${entries}" --port "${PORT}" \
      --budget 2g --miss-cost 50 --rep "${REPRESENTATION:-object}" \
      --cache-host 127.0.0.1 \
      --cache-port "$([ "${arm}" = "redis" ] && echo "${REDIS_PORT}" || echo "${FC_PORT}")" \
      --cache-pid "$([ "${arm}" = "redis" ] && echo "${REDIS_PID}" || echo "${FC_SIDECAR_PID}")" \
      > "${OUT}/${label}.applog" 2>&1 &
  APP_PID=$!

  # Wait for readiness rather than sleeping a fixed amount: a 25 MB arm takes longer to come up and a
  # fixed sleep would either waste minutes or start measuring a half-started JVM.
  local ready=0
  for _ in $(seq 1 60); do
    if curl -s --max-time 3 "http://127.0.0.1:${PORT}/admin/metrics" >/dev/null 2>&1; then ready=1; break; fi
    sleep 1
  done
  if [ "${ready}" != "1" ]; then
    echo "  ${label}: app did not start - see ${OUT}/${label}.applog" >&2
    stop_app "${PORT}" || true
    APP_PID=""
    return 0
  fi

  "${ROOT}/fastcache-e2e/run.sh" load \
      --base "http://127.0.0.1:${PORT}" --entries "${entries}" --concurrency "${conc}" \
      --warmup-seconds "${WARMUP_SECONDS:-15}" --duration-seconds "${DURATION_SECONDS:-45}" \
      --write-ratio "${write_ratio}" --label "${label}" --csv "${csv}" 2>&1 | grep '^RESULT' || true

  # A cell whose reads did not match what was written is not a slow cell, it is a void one. Say so at
  # the time rather than leaving it to be noticed in the CSV later.
  if [ -f "${csv}" ]; then
    local bad; bad=$(awk -F, -v want="${label}" 'NR>1 && $1==want && $32>0 {print $32}' "${csv}" | tail -1)
    if [ -n "${bad:-}" ]; then
      echo "  ${label}: INVALID - ${bad} corrupt reads (stale or wrong-sized payloads)" >&2
    fi
  fi

  stop_app "${PORT}"
  APP_PID=""
  sleep 2
}

trap 'stop_app "${PORT}" || true; stop_sidecar; cleanup_lock' EXIT

# ----------------------------------------------------------------------------------------------------
ENTRIES_FOR() {  # keep ~512 MB of working set at every payload size
  case "$1" in
    1m)  echo 512 ;;
    10m) echo 51 ;;
    25m) echo 20 ;;
    *)   echo 32 ;;
  esac
}

case "${PHASE}" in
  steady)
    log "Phase 6 - steady state, 100% GET"
    start_sidecar 2048 "${REJECT_RATIO:-1.0}"
    for payload in 1m 10m 25m; do
      for arm in caffeine fastcache-embedded fastcache-sidecar redis; do
        run_cell "${arm}" "${payload}" "$(ENTRIES_FOR "${payload}")" 8 0.0 "${HEAP:-4g}" \
                 "${OUT}/steady.csv" "steady-${arm}-${payload}-c8"
      done
    done
    log "concurrency sweep at 10m"
    for conc in 1 32; do
      for arm in caffeine fastcache-embedded fastcache-sidecar redis; do
        run_cell "${arm}" 10m 51 "${conc}" 0.0 "${HEAP:-4g}" \
                 "${OUT}/steady.csv" "steady-${arm}-10m-c${conc}"
      done
    done
    stop_sidecar
    ;;

  churn)
    log "Phase 7 - churn"
    start_sidecar 2048 "${REJECT_RATIO:-1.0}"
    for ratio in 0.2 0.1; do
      for arm in caffeine fastcache-embedded fastcache-sidecar redis; do
        run_cell "${arm}" 10m 51 8 "${ratio}" "${HEAP:-4g}" \
                 "${OUT}/churn.csv" "churn-${arm}-10m-w${ratio}"
      done
    done
    stop_sidecar
    ;;

  smallheap)
    log "Phase 14 - constrained heap"
    start_sidecar 2048 "${REJECT_RATIO:-1.0}"
    for heap in 1g 512m; do
      for arm in caffeine fastcache-embedded fastcache-sidecar redis; do
        run_cell "${arm}" 25m 20 8 0.0 "${heap}" \
                 "${OUT}/smallheap.csv" "smallheap-${arm}-25m-${heap}"
      done
    done
    stop_sidecar
    ;;

  representation)
    # Phase 3 - the same cells under Representation A, so the object graph's cost can be separated
    # from the bytes' cost by subtraction rather than by argument.
    log "Phase 3 - representation A (bulk payload + minimal header)"
    start_sidecar 2048 "${REJECT_RATIO:-1.0}"
    REPRESENTATION=bytes
    export REPRESENTATION
    for payload in 1m 10m; do
      for arm in caffeine fastcache-sidecar redis; do
        run_cell "${arm}" "${payload}" "$(ENTRIES_FOR "${payload}")" 8 0.0 "${HEAP:-4g}" \
                 "${OUT}/representation.csv" "repA-${arm}-${payload}-c8"
      done
    done
    stop_sidecar
    ;;

  *) echo "unknown phase ${PHASE}" >&2; exit 2 ;;
esac

log "results -> ${OUT}"
ls -la "${OUT}"/*.csv 2>/dev/null || true
