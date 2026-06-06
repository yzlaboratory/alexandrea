# syntax=docker/dockerfile:1
#
# The Spring Boot image CI ships and `docker compose up` runs locally (ADR 0023).
# Java 25 + Spring Boot 4 per ADR 0014; base tags are Renovate-managed.
#
# Three stages: build the boot jar, split it into layers, assemble a slim
# non-root runtime. The layer split (Spring Boot's `tools` jarmode) keeps the
# rarely-changing dependency layers cached across rebuilds — only the thin
# application layer churns when source changes.

# --- Build ------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY . .
# bootJar (not build/assemble) so only the executable jar lands in build/libs —
# no `-plain.jar` to disambiguate. The cache mount reuses the Gradle dist and
# the resolved dependency cache between builds.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean bootJar

# --- Extract layers ---------------------------------------------------------
FROM eclipse-temurin:25-jre AS extract
WORKDIR /app
COPY --from=build /build/build/libs/*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# --- Runtime ----------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl backs the container HEALTHCHECK below; the spring user owns the app and
# the /data mount so nothing runs or writes as root. /data is where the SQLite
# file lives (ALEXANDREA_DB_PATH), mounted as a volume by compose / the host.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system spring \
 && useradd --system --gid spring --home /app spring \
 && mkdir -p /data \
 && chown -R spring:spring /data

COPY --from=extract --chown=spring:spring /app/extracted/dependencies/ ./
COPY --from=extract --chown=spring:spring /app/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=spring:spring /app/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=spring:spring /app/extracted/application/ ./

USER spring
EXPOSE 8080

# Default DB location inside the image; compose mounts a volume here. Overridable.
ENV ALEXANDREA_DB_PATH=/data/alexandrea.db

# Readiness flips true only once Flyway and the context are up (probes enabled
# in application.yml). start-period covers JVM + Spring boot time.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# Exec form: java is PID 1 so it receives SIGTERM directly for graceful shutdown.
ENTRYPOINT ["java", "-jar", "application.jar"]
