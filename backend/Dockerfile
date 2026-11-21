# Backend container: build the jar, run it on a JRE. The console is a separate container, so this
# image is API-only (BIZSIM_WEB_DIR is not set) and can be scaled or replaced on its own.
#
# The build context is the **repository root**, not backend/ (see docker-compose.dokploy.yml): the api
# image needs `web/` as well, because the shared-map template (D103) is rendered by the console's own
# component at build time. Every COPY below therefore carries the `backend/` or `web/` prefix — without
# it Docker fails at "COPY pom.xml: /pom.xml not found", which is exactly how the first attempt died.

# ---------------------------------------------------------------- 0. the console's shared-map template
FROM node:22-alpine AS map-template
WORKDIR /src
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
# D119: limits.json is the single authority for the class-size ceiling, and the map template's slot count
# comes from it. The resolver looks for it at ../backend/... relative to the console, i.e. /backend here.
COPY backend/src/main/resources/limits.json /backend/src/main/resources/limits.json
RUN node scripts/build-map-template.mjs

# ---------------------------------------------------------------- 1. build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY backend/pom.xml ./
# Warm the dependency cache before the sources are copied: a code change then rebuilds in seconds.
RUN mvn -B -q dependency:go-offline

COPY backend/src ./src
COPY --from=map-template /src/public/map-template.json ./src/main/resources/map-template.json
# The jar must contain it: without the template the API still serves everything else, but no session can
# share a map, and that failure belongs at build time rather than at a facilitator's click.
# `jar tf` rather than `unzip -l`: the JDK is guaranteed present in this image, unzip is not — and a
# build check that can itself be missing is worse than no check at all.
RUN mvn -B -DskipTests package \
    && JAR=$(ls target/bizsim-backend-*.jar) \
    && echo "built $JAR" \
    && (jar tf "$JAR" | grep -q "BOOT-INF/classes/map-template.json" \
        || (echo "map-template.json did not reach the jar" >&2; exit 1))

# ---------------------------------------------------------------- 2. run
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app bizsim

WORKDIR /app
COPY --from=build /src/target/bizsim-backend-*.jar /app/app.jar
# Also beside the jar: the app looks for the template on the classpath first and on disk second, so a
# restored/derived image that replaces the jar still has one.
COPY --from=build /src/src/main/resources/map-template.json /app/map-template.json

ENV BIZSIM_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

USER bizsim
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/api/ping || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
