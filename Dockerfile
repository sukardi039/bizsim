# One image, one port: the backend serves the built console, so a deployment is a single container
# plus a database. Fractions of this file were verified individually on a machine without Docker
# (npm ci + npm run build, mvn package, java -jar), but the image itself has not been built here.

# ---------------------------------------------------------------- 1. console
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---------------------------------------------------------------- 2. backend
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml ./
# Warm the cache before the source is copied, so a code change does not re-download the world.
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q -DskipTests package

# ---------------------------------------------------------------- 3. runtime
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app bizsim

WORKDIR /app
COPY --from=build /src/target/bizsim-backend-*.jar /app/app.jar
COPY --from=web /web/dist /app/web

# The console is served from this directory; without it the image is API-only.
ENV BIZSIM_WEB_DIR=/app/web \
    BIZSIM_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

USER bizsim
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/api/ping || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
