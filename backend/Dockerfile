# Backend container: build the jar, run it on a JRE. The console is a separate container, so this
# image is API-only (BIZSIM_WEB_DIR is not set) and can be scaled or replaced on its own.

# ---------------------------------------------------------------- 1. build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml ./
# Warm the dependency cache before the sources are copied: a code change then rebuilds in seconds.
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------------------------------------------------------------- 2. run
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app bizsim

WORKDIR /app
COPY --from=build /src/target/bizsim-backend-*.jar /app/app.jar

ENV BIZSIM_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

USER bizsim
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/api/ping || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
