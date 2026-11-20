# Build stage: same Gradle and JDK as local development.
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.lockfile ./
COPY src ./src
RUN gradle installDist --no-daemon -q

# Runtime stage: JRE only, non-root user, data in /data.
FROM eclipse-temurin:21.0.12_8-jre-noble
RUN useradd --system --uid 10001 relay && mkdir /data && chown relay /data
COPY --from=build /src/build/install/sync-spike /app
USER relay
ENV RELAY_PORT=8080 RELAY_DB_PATH=/data/relay.db RELAY_TIMER_INTERVAL_SECONDS=30
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD ["sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/${RELAY_PORT} && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
ENTRYPOINT ["/app/bin/sync-spike"]
