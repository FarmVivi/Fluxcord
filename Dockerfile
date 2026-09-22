################ Build ################
# Build stage for the full multi-module project
FROM maven:3.9.16-eclipse-temurin-25-alpine AS build

WORKDIR /workspace

# Copy everything (keeps it simple for multi-module; rely on Docker cache)
COPY . .

# Build all modules (skip tests for faster image builds)
RUN mvn -T1C -DskipTests package \
    && mkdir -p /workspace/.artifacts /workspace/.bundles/plugins /workspace/.bundles/examples \
    && cp -f /workspace/fluxcord-core/target/*-shaded.jar /workspace/.artifacts/fluxcord.jar \
    && (cp -f /workspace/plugins/*/target/*.jar /workspace/.bundles/plugins/ 2>/dev/null || true) \
    && (cp -f /workspace/examples/plugins/*/target/*.jar /workspace/.bundles/examples/ 2>/dev/null || true)

################ Production ################
# JDAVE (DAVE native lib) ships prebuilt natives for glibc only, and they need GLIBC >= 2.38.
# Ubuntu 24.04 "noble" (glibc 2.39 + recent libstdc++) runs JDAVE's bundled libdave.so as-is,
# so no native compilation is needed here. Do NOT use an Alpine/musl base: JDAVE has no musl build.
FROM eclipse-temurin:26.0.2_10-jre-noble AS production

ENV APP_DIR=/app \
    BUNDLES_DIR=/opt/fluxcord/bundles \
    JAVA_OPTS=""

WORKDIR /app

# Install curl for healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

## Core shaded jar
COPY --from=build /workspace/.artifacts/fluxcord.jar /app/fluxcord.jar

# Bundle available plugins (internal plugins)
RUN mkdir -p /opt/fluxcord/bundles/plugins /opt/fluxcord/bundles/examples

# Copy bundled plugin jars (empty dir is fine)
COPY --from=build /workspace/.bundles/plugins/ /opt/fluxcord/bundles/plugins/
COPY --from=build /workspace/.bundles/examples/ /opt/fluxcord/bundles/examples/

# Entrypoint script to optionally install/update plugins before launch
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

VOLUME ["/app/data", "/app/plugins"]

ENTRYPOINT ["/entrypoint.sh"]

# Docker healthcheck (ready file created by core after full startup)
# Docker healthcheck: HTTP ready endpoint exposed by the core
ENV HEALTH_PORT=8081
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD curl -fsS http://127.0.0.1:${HEALTH_PORT}/readyz || exit 1
