# syntax=docker/dockerfile:1
# lateralus-v2 — JVM uberjar with workbench deps.
# Build:  docker compose build
# Run:    docker compose run --rm lateralus

FROM clojure:temurin-22-tools-deps AS build
WORKDIR /src
COPY deps.edn build.clj ./
# Prefetch deps before copying sources for better layer caching.
RUN clojure -P -M:workbench && clojure -P -T:build
COPY src ./src
COPY resources ./resources
# Fingerprint from start-workbench: busts this layer when src/resources change,
# even if BuildKit would otherwise reuse a stale uberjar.
ARG LATERALUS_SRC_REV=dev
RUN echo "lateralus src-rev ${LATERALUS_SRC_REV}" \
 && clojure -T:build uber \
 && JAR=$(ls target/net.clojars.kschltz/lateralus-v2-*.jar | head -1) \
 && cp "$JAR" /src/lateralus.jar

FROM eclipse-temurin:22-jre-jammy
WORKDIR /app
RUN useradd --create-home --uid 10001 lateralus \
 && mkdir -p /data/config \
 && chown -R lateralus:lateralus /data /app
COPY --from=build /src/lateralus.jar /app/lateralus.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
USER lateralus
ENV LATERALUS_JAR=/app/lateralus.jar \
    LATERALUS_CONFIG_HOME=/data/config \
    LATERALUS_WORKBENCH_HOST=0.0.0.0 \
    LATERALUS_BASE_URL=http://ollama:11434/v1 \
    LATERALUS_MODEL=llama3.2
EXPOSE 7860
VOLUME ["/data/config"]
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["-i"]
