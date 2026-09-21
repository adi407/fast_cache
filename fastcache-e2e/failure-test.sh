#!/usr/bin/env bash
#
# Phase 17 - what happens to a running service when its cache dies.
#
#   ./fastcache-e2e/failure-test.sh <output-dir>
#
# Kills the cache server out from under a live application and records what the application does: whether
# requests fail, whether they fail loudly or silently, how long recovery takes, whether the cache
# repopulates on its own, and whether anyone has to intervene.
#
# Run for both FastCache and Redis. The question is not "does it survive" - neither is durable, so the
# cached data is gone either way - but what the *application* experiences, which is the part a team has to
# write code for.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${ROOT}/docs/validation/data/e2e}"
PORT="${E2E_PORT:-8091}"
FC_PORT="${FC_PORT:-6381}"
REDIS_PORT="${REDIS_PORT:-6399}"
PAYLOAD="${PAYLOAD:-1m}"
ENTRIES="${ENTRIES:-32}"
mkdir -p "${OUT}"
REPORT="${OUT}/failure-test.txt"
: > "${REPORT}"

say() { printf '%s\n' "$*" | tee -a "${REPORT}"; }

probe() {  # one request; prints HTTP status and elapsed ms, never fails the script
  local id="$1"
  local start end code
  start=$(date +%s%3N)
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 \
         "http://127.0.0.1:${PORT}/documents/${id}?seed=1" 2>/dev/null || echo "000")
  end=$(date +%s%3N)
  printf '%s %s' "${code}" "$((end - start))"
}

stop_app() {
  curl -s --max-time 10 "http://127.0.0.1:${PORT}/admin/shutdown" >/dev/null 2>&1 || true
  for _ in $(seq 1 40); do
    curl -s --max-time 2 "http://127.0.0.1:${PORT}/admin/pid" >/dev/null 2>&1 || return 0
    sleep 1
  done
  return 1
}

run_scenario() {
  local arm="$1" cache_port="$2" kill_cmd="$3" restart_cmd="$4"

  say ""
  say "=============================================================="
  say "ARM: ${arm}"
  say "=============================================================="

  "${ROOT}/fastcache-e2e/run.sh" app --arm "${arm}" --payload "${PAYLOAD}" --entries "${ENTRIES}" \
      --port "${PORT}" --budget 1g --miss-cost 50 --rep object \
      --cache-host 127.0.0.1 --cache-port "${cache_port}" --cache-pid 0 \
      > "${OUT}/failure-${arm}.applog" 2>&1 &
  for _ in $(seq 1 60); do
    curl -s --max-time 3 "http://127.0.0.1:${PORT}/admin/metrics" >/dev/null 2>&1 && break
    sleep 1
  done
  curl -s --max-time 300 "http://127.0.0.1:${PORT}/admin/warmup" >/dev/null 2>&1 || true

  say "before kill      : $(probe doc-1)   (http_status elapsed_ms)"

  say "killing the cache server..."
  eval "${kill_cmd}" || true
  sleep 2

  # The interesting window. A cache that fails open should keep serving by falling back to the loader;
  # a cache that fails closed will surface errors to the caller.
  local failures=0 successes=0
  for i in $(seq 1 10); do
    local result; result=$(probe "doc-${i}")
    local code=${result%% *}
    [ "${code}" = "200" ] && successes=$((successes + 1)) || failures=$((failures + 1))
    say "  during outage #${i} : ${result}"
  done
  say "during outage    : ${successes} served, ${failures} failed"

  if [ -n "${restart_cmd}" ]; then
    say "restarting the cache server..."
    eval "${restart_cmd}" || true
    sleep 5
    local recovered=0
    for i in $(seq 1 10); do
      local result; result=$(probe "doc-${i}")
      [ "${result%% *}" = "200" ] && recovered=$((recovered + 1))
      say "  after restart #${i} : ${result}"
    done
    say "after restart    : ${recovered}/10 served"
    say "cache entries    : $(curl -s --max-time 30 "http://127.0.0.1:${PORT}/admin/metrics" 2>/dev/null \
                              | grep -oE '\"entries\":[-0-9]+' || echo 'unavailable')"
  fi

  stop_app || say "WARNING: app did not stop cleanly"
}

# --- FastCache -------------------------------------------------------------------------------------
start_fc() {
  java -Xmx512m -XX:MaxDirectMemorySize=2g -cp "${ROOT}/fastcache-engine/target/fastcache-engine.jar" \
    io.fastcache.engine.net.SidecarMain --port "${FC_PORT}" --offheap-max 1024m \
    --max-value-bytes 268435456 --reject-ratio 1.0 --metrics-port off --idle-timeout 0 \
    --heartbeat-timeout 0 --stale-grace 0 >> "${OUT}/failure-sidecar.log" 2>&1 &
  FC_PID=$!
  sleep 4
}
start_fc
say "FastCache sidecar started (msys pid ${FC_PID}) on ${FC_PORT}"

# Killed by port rather than by pid: $! is an MSYS pid on Windows and Stop-Process does not recognise it.
KILL_FC='powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort '"${FC_PORT}"' -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty OwningProcess | ForEach-Object { Stop-Process -Id \$_ -Force }" 2>/dev/null'
run_scenario "fastcache-sidecar" "${FC_PORT}" "${KILL_FC}" "start_fc"

# --- Redis -----------------------------------------------------------------------------------------
KILL_REDIS='powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort '"${REDIS_PORT}"' -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty OwningProcess | ForEach-Object { Stop-Process -Id \$_ -Force }" 2>/dev/null'
RESTART_REDIS="cd '${ROOT}/.redis' && ./Memurai/memurai.exe redis-bench.conf >> '${OUT}/failure-redis.log' 2>&1 & sleep 4"
run_scenario "redis" "${REDIS_PORT}" "${KILL_REDIS}" "${RESTART_REDIS}"

say ""
say "report -> ${REPORT}"
