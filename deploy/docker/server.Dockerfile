FROM eclipse-temurin:21-jre-noble AS web-assets

RUN apt-get update \
    && apt-get install --yes --no-install-recommends brotli gzip \
    && rm -rf /var/lib/apt/lists/*

COPY presentation/build/dist/wasmJs/productionExecutable /app/web

RUN find /app/web -type f \( \
        -name "*.wasm.br" -o \
        -name "*.wasm.gz" -o \
        -name "gromozeka.js.br" -o \
        -name "gromozeka.js.gz" \
    \) -delete \
    && find /app/web -type f \( -name "*.wasm" -o -name "gromozeka.js" \) \
    -exec brotli --force --quality=11 --no-copy-stat {} \; \
    -exec gzip --force --keep --best --no-name {} \;

FROM eclipse-temurin:21-jre-noble

RUN groupadd --gid 10001 gromozeka \
    && useradd --uid 10001 --gid gromozeka --create-home gromozeka

WORKDIR /app

COPY --chown=gromozeka:gromozeka server/build/libs/gromozeka-server.jar /app/gromozeka-server.jar
COPY --from=web-assets --chown=gromozeka:gromozeka /app/web /app/web

ENV GROMOZEKA_MODE=prod \
    GROMOZEKA_HOME=/var/lib/gromozeka \
    GROMOZEKA_REMOTE_HOST=0.0.0.0 \
    GROMOZEKA_REMOTE_PORT=8765 \
    GROMOZEKA_WEB_STATIC_DIR=/app/web

RUN mkdir -p /var/lib/gromozeka \
    && chown gromozeka:gromozeka /var/lib/gromozeka

USER gromozeka

EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8765"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/gromozeka-server.jar"]
