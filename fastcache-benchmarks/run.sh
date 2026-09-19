#!/usr/bin/env bash
#
# Compile-and-run wrapper for the JVM memory/GC benchmark.
#
# Maven is this project's build system, but this script exists so the benchmark runs on a machine that has
# a JDK and nothing else -- including the machine these results were produced on, which had no mvn binary.
# It resolves third-party jars from the local Maven repository cache.
#
# Usage:
#   ./run.sh --scenario C --implementation all --payload-size 10m --duration 300
#   BENCH_MAIN=io.fastcache.bench.LeakProbe   ./run.sh
#   BENCH_MAIN=io.fastcache.bench.GuardProbe  ./run.sh
#   BENCH_MAIN=io.fastcache.bench.SpringCacheBench ./run.sh --loader-millis 50
#
# Environment:
#   BENCH_MAIN        main class (default io.fastcache.bench.Bench)
#   BENCH_JVM_FLAGS   JVM flags for the benchmark process
#   CAFFEINE_VERSION  default 3.2.2
#   SPRING_VERSION    default 6.1.13 (matches spring-boot 3.3.4 in the parent pom)
#   SLF4J_VERSION     default 2.0.13 - the starter logs through slf4j
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
M2="${HOME}/.m2/repository"
CAFFEINE_VERSION="${CAFFEINE_VERSION:-3.2.2}"
SPRING_VERSION="${SPRING_VERSION:-6.1.13}"
SLF4J_VERSION="${SLF4J_VERSION:-2.0.13}"
BENCH_MAIN="${BENCH_MAIN:-io.fastcache.bench.Bench}"

ENGINE_CLASSES="${ROOT}/fastcache-engine/target/classes"
STARTER_CLASSES="${ROOT}/fastcache-spring-boot-starter/target/classes"
OUT="${ROOT}/fastcache-benchmarks/target/classes"

# Jars the benchmark compiles against. Spring is only needed by SpringCacheBench; a missing Spring jar is
# reported rather than silently dropped, because a benchmark that quietly compiles fewer files than it
# should is worse than one that fails.
JARS=(
  "${M2}/com/github/ben-manes/caffeine/caffeine/${CAFFEINE_VERSION}/caffeine-${CAFFEINE_VERSION}.jar"
  "${M2}/org/springframework/spring-context/${SPRING_VERSION}/spring-context-${SPRING_VERSION}.jar"
  "${M2}/org/springframework/spring-context-support/${SPRING_VERSION}/spring-context-support-${SPRING_VERSION}.jar"
  "${M2}/org/springframework/spring-core/${SPRING_VERSION}/spring-core-${SPRING_VERSION}.jar"
  "${M2}/org/springframework/spring-beans/${SPRING_VERSION}/spring-beans-${SPRING_VERSION}.jar"
  "${M2}/org/springframework/spring-aop/${SPRING_VERSION}/spring-aop-${SPRING_VERSION}.jar"
  "${M2}/org/springframework/spring-expression/${SPRING_VERSION}/spring-expression-${SPRING_VERSION}.jar"
  "${M2}/org/springframework/spring-jcl/${SPRING_VERSION}/spring-jcl-${SPRING_VERSION}.jar"
  "${M2}/org/slf4j/slf4j-api/${SLF4J_VERSION}/slf4j-api-${SLF4J_VERSION}.jar"
)

AVAILABLE=()
for jar in "${JARS[@]}"; do
  if [ -f "${jar}" ]; then
    AVAILABLE+=("${jar}")
  else
    case "${jar}" in
      *caffeine*) echo "missing required dependency: ${jar}" >&2; exit 1 ;;
      *) echo "note: optional dependency absent: $(basename "${jar}")" >&2 ;;
    esac
  fi
done
JARS=("${AVAILABLE[@]}")
[ -d "${ENGINE_CLASSES}" ] || { echo "build the engine first (mvn -q package)" >&2; exit 1; }
# The starter is optional: only SpringCacheBench needs it.
[ -d "${STARTER_CLASSES}" ] || echo "note: starter not built - SpringCacheBench skipped" >&2

mkdir -p "${OUT}"
JAR_PATH="${ROOT}/fastcache-engine/target/fastcache-engine.jar"
GC_LOG="${ROOT}/fastcache-benchmarks/target/gc.log"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    # javac and java are native Windows binaries: every path handed to them must be Windows-shaped.
    SEP=';'
    win() { cygpath -w "$1"; }
    ;;
  *)
    SEP=':'
    win() { printf '%s' "$1"; }
    ;;
esac

CP=""
for jar in "${JARS[@]}"; do
  CP="${CP}$(win "${jar}")${SEP}"
done
CP="${CP}$(win "${ENGINE_CLASSES}")${SEP}$(win "${STARTER_CLASSES}")"

# SpringCacheBench is the only source needing the starter CacheManager. On a checkout without
# that class, compiling it would fail the whole benchmark over one optional comparison, so it
# is excluded and the omission announced rather than hidden.
SOURCES=$(find "${ROOT}/fastcache-benchmarks/src/main/java" -name '*.java')
if [ ! -f "${STARTER_CLASSES}/io/fastcache/spring/FastCacheManager.class" ]; then
  echo "note: FastCacheManager absent - excluding SpringCacheBench from the build" >&2
  SOURCES=$(printf '%s\n' "${SOURCES}" | grep -v 'SpringCacheBench.java')
fi

javac -nowarn -d "$(win "${OUT}")" -cp "${CP}" ${SOURCES}

# Default flags describe a service-sized heap. Override BENCH_JVM_FLAGS to test another shape; whatever is
# used is echoed back by the benchmark itself, so results always carry the flags that produced them.
exec java ${BENCH_JVM_FLAGS:--Xmx4g -XX:MaxDirectMemorySize=4g} \
  "-Xlog:gc:file=$(win "${GC_LOG}"):time,uptime:filecount=0" \
  -cp "$(win "${OUT}")${SEP}${CP}" \
  "${BENCH_MAIN}" ${BENCH_MAIN_ARGS:-} "$@" \
  $([ "${BENCH_MAIN}" = "io.fastcache.bench.Bench" ] && echo "--jar $(win "${JAR_PATH}")" || true)
