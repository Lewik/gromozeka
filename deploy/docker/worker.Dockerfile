FROM node:26.5.1-bookworm-slim@sha256:9e6f9357d371591e32ab6f2d8a26d63bdd0d17c29eee3f4f3e7e454d9634bf73 AS browser-mcp

WORKDIR /browser-mcp

COPY browser-mcp/package.json browser-mcp/package-lock.json ./
COPY browser-mcp/scripts ./scripts
COPY browser-mcp/LICENSE browser-mcp/NOTICE browser-mcp/UPSTREAM.md browser-mcp/THIRD_PARTY_NOTICES.txt ./

RUN npm ci --omit=dev --no-audit --no-fund \
    && npm run verify

FROM eclipse-temurin:24-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7

RUN groupadd --gid 10001 gromozeka \
    && useradd --uid 10001 --gid gromozeka --create-home gromozeka

WORKDIR /app

COPY --from=browser-mcp /usr/local/bin/node /usr/local/bin/node
COPY --from=browser-mcp /usr/local/LICENSE /app/third-party/node/LICENSE
COPY --from=browser-mcp --chown=gromozeka:gromozeka /browser-mcp /app/browser-mcp
COPY --chown=gromozeka:gromozeka deploy/distribution/gromozeka-browser-mcp /app/bin/gromozeka-browser-mcp
COPY --chown=gromozeka:gromozeka deploy/distribution/runtime-bootstrap.sh /app/bin/runtime-bootstrap.sh
COPY --chown=gromozeka:gromozeka deploy/distribution/runtime-versions.properties /app/bin/runtime-versions.properties
COPY --chown=gromozeka:gromozeka worker/build/libs/gromozeka-worker.jar /app/gromozeka-worker.jar
COPY --chown=gromozeka:gromozeka LICENSE /app/LICENSE

ENV GROMOZEKA_MODE=prod \
    GROMOZEKA_HOME=/var/lib/gromozeka \
    GROMOZEKA_BROWSER_MCP_LAUNCHER=/app/bin/gromozeka-browser-mcp \
    GROMOZEKA_BROWSER_MCP_HOME=/app/browser-mcp \
    GROMOZEKA_RUNTIME_BOOTSTRAP=/app/bin/runtime-bootstrap.sh \
    GROMOZEKA_NODE_EXECUTABLE=/usr/local/bin/node

RUN chmod +x /app/bin/gromozeka-browser-mcp /app/bin/runtime-bootstrap.sh \
    && mkdir -p /var/lib/gromozeka \
    && chown gromozeka:gromozeka /var/lib/gromozeka

USER gromozeka

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/gromozeka-worker.jar"]
