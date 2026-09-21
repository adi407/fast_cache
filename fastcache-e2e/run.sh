#!/usr/bin/env bash
#
# Build and run the end-to-end experiment.
#
#   ./fastcache-e2e/run.sh build
#   ./fastcache-e2e/run.sh app  --arm caffeine --payload 10m --entries 51
#   ./fastcache-e2e/run.sh load --concurrency 8 --duration-seconds 60 --csv out.csv
#
# Assembles a classpath from the local Maven repository rather than building a fat jar, so the experiment
# needs a JDK and a populated ~/.m2 and nothing else. Where mvn is available (CI), `resolve` populates the
# repository first; where it is not, the jars are expected to be there already.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
M2="${HOME}/.m2/repository"
OUT="${ROOT}/fastcache-e2e/target/classes"

SPRING_BOOT="${SPRING_BOOT_VERSION:-3.3.4}"
SPRING="${SPRING_VERSION:-6.1.13}"
TOMCAT="${TOMCAT_VERSION:-10.1.30}"
JACKSON="${JACKSON_VERSION:-2.17.2}"
LOGBACK="${LOGBACK_VERSION:-1.5.8}"
SLF4J="${SLF4J_VERSION:-2.0.16}"
CAFFEINE="${CAFFEINE_VERSION:-3.2.2}"
# spring-web 6.1 references the micrometer observation API on its request path. Boot 3.3.4 pins 1.13.4;
# any 1.1x works here since only the stable observation types are touched.
MICROMETER="${MICROMETER_VERSION:-1.15.6}"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';'; win() { cygpath -w "$1"; } ;;
  *)                    SEP=':'; win() { printf '%s' "$1"; } ;;
esac

# Every jar the application needs. Listed explicitly rather than globbed so a missing dependency is a
# clear error at startup instead of a NoClassDefFoundError half way through a run.
JARS=(
  "${M2}/org/springframework/boot/spring-boot/${SPRING_BOOT}/spring-boot-${SPRING_BOOT}.jar"
  "${M2}/org/springframework/boot/spring-boot-autoconfigure/${SPRING_BOOT}/spring-boot-autoconfigure-${SPRING_BOOT}.jar"
  "${M2}/org/springframework/spring-core/${SPRING}/spring-core-${SPRING}.jar"
  "${M2}/org/springframework/spring-beans/${SPRING}/spring-beans-${SPRING}.jar"
  "${M2}/org/springframework/spring-context/${SPRING}/spring-context-${SPRING}.jar"
  "${M2}/org/springframework/spring-aop/${SPRING}/spring-aop-${SPRING}.jar"
  "${M2}/org/springframework/spring-expression/${SPRING}/spring-expression-${SPRING}.jar"
  "${M2}/org/springframework/spring-web/${SPRING}/spring-web-${SPRING}.jar"
  "${M2}/org/springframework/spring-webmvc/${SPRING}/spring-webmvc-${SPRING}.jar"
  "${M2}/org/springframework/spring-jcl/${SPRING}/spring-jcl-${SPRING}.jar"
  "${M2}/org/apache/tomcat/embed/tomcat-embed-core/${TOMCAT}/tomcat-embed-core-${TOMCAT}.jar"
  "${M2}/org/apache/tomcat/embed/tomcat-embed-el/${TOMCAT}/tomcat-embed-el-${TOMCAT}.jar"
  "${M2}/org/apache/tomcat/embed/tomcat-embed-websocket/${TOMCAT}/tomcat-embed-websocket-${TOMCAT}.jar"
  "${M2}/com/fasterxml/jackson/core/jackson-databind/${JACKSON}/jackson-databind-${JACKSON}.jar"
  "${M2}/com/fasterxml/jackson/core/jackson-core/${JACKSON}/jackson-core-${JACKSON}.jar"
  "${M2}/com/fasterxml/jackson/core/jackson-annotations/${JACKSON}/jackson-annotations-${JACKSON}.jar"
  "${M2}/ch/qos/logback/logback-classic/${LOGBACK}/logback-classic-${LOGBACK}.jar"
  "${M2}/ch/qos/logback/logback-core/${LOGBACK}/logback-core-${LOGBACK}.jar"
  "${M2}/org/slf4j/slf4j-api/${SLF4J}/slf4j-api-${SLF4J}.jar"
  "${M2}/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar"
  "${M2}/jakarta/annotation/jakarta.annotation-api/2.1.1/jakarta.annotation-api-2.1.1.jar"
  "${M2}/com/github/ben-manes/caffeine/caffeine/${CAFFEINE}/caffeine-${CAFFEINE}.jar"
  "${M2}/io/micrometer/micrometer-observation/${MICROMETER}/micrometer-observation-${MICROMETER}.jar"
  "${M2}/io/micrometer/micrometer-commons/${MICROMETER}/micrometer-commons-${MICROMETER}.jar"
)

resolve() {
  command -v mvn >/dev/null || { echo "mvn not available; expecting jars already in ${M2}" >&2; return 0; }
  mvn -B -q -f "${ROOT}/fastcache-e2e/pom.xml" dependency:go-offline || true
  mvn -B -q dependency:get -Dartifact="com.github.ben-manes.caffeine:caffeine:${CAFFEINE}" || true
}

classpath() {
  local cp=""
  for jar in "${JARS[@]}"; do
    [ -f "${jar}" ] || { echo "missing dependency: ${jar}" >&2; exit 1; }
    cp="${cp}$(win "${jar}")${SEP}"
  done
  # The engine only. The Spring starter is deliberately NOT on the classpath: its auto-configuration
  # and @Aspect classes would be discovered by Spring Boot and change what the application under
  # test actually is. This experiment measures the cache backends directly, not the annotation layer.
  printf '%s%s' "${cp}" "$(win "${ROOT}/fastcache-engine/target/classes")"
}

build() {
  mkdir -p "${OUT}"
  local cp; cp="$(classpath)"
  # Compiled together with the benchmark sources: the e2e app reuses WireClient, RespClient, Probe,
  # Stats and Payloads rather than duplicating them, so both sides of the comparison run through exactly
  # the same client code the previous rounds measured.
  local sources
  sources=$(find "${ROOT}/fastcache-benchmarks/src/main/java" "${ROOT}/fastcache-e2e/src/main/java" \
            -name '*.java' ! -name 'SpringCacheBench.java')
  # -parameters is required: without it Spring cannot resolve @PathVariable/@RequestParam names and
  # every request fails at runtime. Maven's Spring Boot plugin passes it by default, which is why
  # a hand-rolled javac build has to pass it explicitly.
  javac -nowarn -parameters -d "$(win "${OUT}")" -cp "${cp}" ${sources}
  echo "built -> ${OUT}"
}

case "${1:-build}" in
  resolve) resolve ;;
  build)   build ;;
  app)
    shift
    exec java ${E2E_JVM_FLAGS:--Xmx4g -XX:MaxDirectMemorySize=4g} \
      "-Xlog:gc*:file=$(win "${ROOT}/fastcache-e2e/target/gc-app.log")::filecount=0" \
      -cp "$(win "${OUT}")${SEP}$(classpath)" io.fastcache.e2e.E2eApplication "$@"
    ;;
  load)
    shift
    # The generator gets its own modest heap. It must never be the bottleneck, and its allocations must
    # never land in the service's numbers.
    exec java -Xmx1g -cp "$(win "${OUT}")${SEP}$(classpath)" io.fastcache.e2e.LoadClient "$@"
    ;;
  *) echo "usage: run.sh {resolve|build|app|load} [args]" >&2; exit 2 ;;
esac
