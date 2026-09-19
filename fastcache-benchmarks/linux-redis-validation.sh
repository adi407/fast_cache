#!/usr/bin/env bash
#
# Native-Linux Redis validation for FastCache.
#
# Runs the FastCache sidecar against a native Redis server on the same Linux host and records the full
# environment so another engineer can reproduce it. Designed to run unattended in CI or on any Linux box
# with a JDK, Maven and redis-server.
#
#   ./fastcache-benchmarks/linux-redis-validation.sh [output-dir]
#
# The previous comparison ran against Memurai on Windows (WinSock IOCP). This script exists to determine
# whether the measured FastCache advantage survives against Redis on the platform Redis is actually
# optimised for (epoll). It is written to produce a negative result as readily as a positive one.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${ROOT}/docs/validation/data}"
REDIS_PORT="${REDIS_PORT:-6399}"
REDIS_CONF="${OUT}/redis-linux.conf"
mkdir -p "${OUT}"

command -v redis-server >/dev/null || { echo "redis-server not found" >&2; exit 1; }
command -v java >/dev/null || { echo "java not found" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------------
# Environment record. A benchmark result without the machine that produced it is an anecdote.
# ---------------------------------------------------------------------------------------------------
ENVFILE="${OUT}/environment.txt"
{
  echo "timestamp            : $(date -Is)"
  echo "distro               : $(. /etc/os-release 2>/dev/null && echo "${PRETTY_NAME:-unknown}")"
  echo "kernel               : $(uname -srmo)"
  echo "cpu model            : $(awk -F': ' '/model name/{print $2; exit}' /proc/cpuinfo 2>/dev/null || echo unknown)"
  echo "cpu cores            : $(nproc)"
  echo "ram total            : $(awk '/MemTotal/{printf "%.1f GB", $2/1048576}' /proc/meminfo)"
  echo "ram available        : $(awk '/MemAvailable/{printf "%.1f GB", $2/1048576}' /proc/meminfo)"
  echo "redis-server         : $(redis-server --version)"
  echo "java                 : $(java -version 2>&1 | head -1)"
  echo "maven                : $(mvn -v 2>/dev/null | head -1 || echo 'not present')"
  echo "fastcache commit     : $(git -C "${ROOT}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "fastcache dirty      : $(git -C "${ROOT}" status --porcelain 2>/dev/null | wc -l) modified path(s)"
  echo "transparent hugepages: $(cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null || echo unknown)"
  echo "cpu governor         : $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)"
  echo "loopback mtu         : $(cat /sys/class/net/lo/mtu 2>/dev/null || echo unknown)"
  echo "net.core.rmem_max    : $(sysctl -n net.core.rmem_max 2>/dev/null || echo unknown)"
  echo "net.core.wmem_max    : $(sysctl -n net.core.wmem_max 2>/dev/null || echo unknown)"
} | tee "${ENVFILE}"
echo

# ---------------------------------------------------------------------------------------------------
# Redis configuration. Every setting is here to make the comparison FAIR, not to favour either side.
# ---------------------------------------------------------------------------------------------------
cat > "${REDIS_CONF}" <<CONF
bind 127.0.0.1
port ${REDIS_PORT}
protected-mode no

# FastCache is non-durable and writes nothing to disk. Leaving RDB snapshotting on would charge Redis
# for a feature FastCache does not offer and this benchmark does not use.
save ""
appendonly no

# The benchmark stores up to 50 MB per key. Redis's default already covers this; stated for the record.
proto-max-bulk-len 512mb

# Bounded LRU cache semantics, matching Caffeine's maximumWeight and FastCache's budget.
maxmemory 4gb
maxmemory-policy allkeys-lru

# Left at defaults deliberately: io-threads is varied by the second configuration below rather than
# being pre-tuned here.
io-threads 1
io-threads-do-reads no

loglevel warning
logfile ${OUT}/redis-linux.log
daemonize no
CONF

start_redis() {
  local threads="$1"
  sed -i "s/^io-threads .*/io-threads ${threads}/" "${REDIS_CONF}"
  if [ "${threads}" -gt 1 ]; then
    sed -i "s/^io-threads-do-reads .*/io-threads-do-reads yes/" "${REDIS_CONF}"
  else
    sed -i "s/^io-threads-do-reads .*/io-threads-do-reads no/" "${REDIS_CONF}"
  fi
  redis-server "${REDIS_CONF}" &
  REDIS_PID=$!
  for _ in $(seq 1 40); do
    if redis-cli -p "${REDIS_PORT}" PING 2>/dev/null | grep -q PONG; then break; fi
    sleep 0.25
  done
  redis-cli -p "${REDIS_PORT}" PING >/dev/null || { echo "redis failed to start" >&2; exit 1; }
  echo "redis up: pid ${REDIS_PID}, io-threads ${threads}, $(redis-cli -p "${REDIS_PORT}" INFO server | tr -d '\r' | grep redis_version)"
}

stop_redis() {
  redis-cli -p "${REDIS_PORT}" SHUTDOWN NOSAVE 2>/dev/null || true
  wait "${REDIS_PID}" 2>/dev/null || true
}
trap stop_redis EXIT

run() {  # run <csv-name> <extra args...>
  local name="$1"; shift
  echo
  echo "=== ${name} ==="
  BENCH_JVM_FLAGS="${BENCH_JVM_FLAGS:--Xmx4g -XX:MaxDirectMemorySize=6g}" \
    "${ROOT}/fastcache-benchmarks/run.sh" \
      --redis-port "${REDIS_PORT}" --redis-pid "${REDIS_PID}" \
      --csv "${OUT}/${name}.csv" "$@" 2>&1 | grep -v 'INFO:\|FastCacheLog\|^[A-Z][a-z][a-z] [0-9]'
}

# ---------------------------------------------------------------------------------------------------
# Primary configuration: Redis defaults (io-threads 1), which is how Redis ships and how most
# deployments run it.
# ---------------------------------------------------------------------------------------------------
start_redis 1

# Phase 4 - GET across payload sizes and concurrency levels.
for conc in 1 8 32; do
  run "linux-get-conc${conc}" --scenario B \
      --implementation caffeine,fastcache-sidecar,redis \
      --payload-size 256k,1m,10m,25m,50m --target 512m --budget 4g \
      --operations 600 --concurrency "${conc}" --repeat 3 --reject-ratio 1.0
done

# Phase 5 - SET, read-heavy and balanced mixes, with GET and SET timed separately.
for ratio in 0.0 0.5 0.9; do
  run "linux-mixed-r${ratio}" --scenario M \
      --implementation fastcache-sidecar,redis \
      --payload-size 1m,10m,25m --target 512m --budget 4g \
      --operations 600 --concurrency 8 --repeat 3 --read-ratio "${ratio}" --reject-ratio 1.0
done

stop_redis

# ---------------------------------------------------------------------------------------------------
# Secondary configuration: does the result depend on Redis's threading model?
# ---------------------------------------------------------------------------------------------------
start_redis 4
run "linux-get-iothreads4-conc8" --scenario B \
    --implementation fastcache-sidecar,redis \
    --payload-size 1m,10m,25m --target 512m --budget 4g \
    --operations 600 --concurrency 8 --repeat 3 --reject-ratio 1.0
stop_redis

echo
echo "=== results written to ${OUT} ==="
ls -la "${OUT}"/*.csv 2>/dev/null || true
