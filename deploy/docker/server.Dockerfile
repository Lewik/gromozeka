FROM eclipse-temurin:21-jre-noble

RUN groupadd --gid 10001 gromozeka \
    && useradd --uid 10001 --gid gromozeka --create-home gromozeka

WORKDIR /app

COPY --chown=gromozeka:gromozeka server/build/libs/gromozeka-server.jar /app/gromozeka-server.jar
COPY --chown=gromozeka:gromozeka presentation/build/dist/wasmJs/productionExecutable /app/web

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
