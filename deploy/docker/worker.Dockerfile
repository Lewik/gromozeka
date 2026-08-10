FROM node:24.19.0-bookworm-slim@sha256:3638d9a6fe4030bd716be989438248074489337ba3275657f93595428be4fc03 AS browser-mcp

WORKDIR /browser-mcp

COPY browser-mcp/package.json browser-mcp/package-lock.json ./
COPY browser-mcp/scripts ./scripts
COPY browser-mcp/LICENSE browser-mcp/NOTICE browser-mcp/UPSTREAM.md browser-mcp/THIRD_PARTY_NOTICES.txt ./

RUN npm ci --omit=dev --no-audit --no-fund \
    && npm run verify

FROM eclipse-temurin:21-jre-noble@sha256:ca397720325ceefe39ce397f186759fc87d9efafb2dc4ce53315980844c2f4f2

RUN groupadd --gid 10001 gromozeka \
    && useradd --uid 10001 --gid gromozeka --create-home gromozeka

WORKDIR /app

COPY --from=browser-mcp /usr/local/bin/node /usr/local/bin/node
COPY --from=browser-mcp /usr/local/LICENSE /app/third-party/node/LICENSE
COPY --from=browser-mcp --chown=gromozeka:gromozeka /browser-mcp /app/browser-mcp
COPY --chown=gromozeka:gromozeka deploy/distribution/gromozeka-browser-mcp /app/bin/gromozeka-browser-mcp
COPY --chown=gromozeka:gromozeka worker/build/libs/gromozeka-worker.jar /app/gromozeka-worker.jar
COPY --chown=gromozeka:gromozeka LICENSE /app/LICENSE

ENV GROMOZEKA_MODE=prod \
    GROMOZEKA_HOME=/var/lib/gromozeka \
    GROMOZEKA_BROWSER_MCP_LAUNCHER=/app/bin/gromozeka-browser-mcp \
    GROMOZEKA_BROWSER_MCP_HOME=/app/browser-mcp \
    GROMOZEKA_NODE_EXECUTABLE=/usr/local/bin/node

RUN chmod +x /app/bin/gromozeka-browser-mcp \
    && mkdir -p /var/lib/gromozeka \
    && chown gromozeka:gromozeka /var/lib/gromozeka

USER gromozeka

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/gromozeka-worker.jar"]
