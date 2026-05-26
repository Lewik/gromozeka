# Emergency Repair Guide (Plan B)

**You are Codex, launched with this file because Gromozeka system is broken.**

Normal agent system isn't available. User will tell you what's broken. Your job: fix it.

## Understanding the System

**Problem:** You don't know how Gromozeka works yet.

**Solution:** Read agent prompts - they contain all knowledge about the system.

## Where Agent Knowledge Lives

**Agent prompts = system documentation**

**Builtin prompts:**
```
server/src/main/resources/prompts/*.md
```

**Project prompts:**
```
.gromozeka/prompts/*.md
```

## Quick Start Strategy

**1. Get context:**
```bash
cat .gromozeka/prompts/project-common.knowledge.md
cat .gromozeka/prompts/architecture.knowledge.md
cat .gromozeka/prompts/agents-roster.knowledge.md
```

**2. Find the expert for your problem:**
- Build broken? → `build-release.role.md`
- Database issue? → `repository.role.md`
- AI integration? → `spring-ai.role.md` (AI Integration Agent)
- UI problem? → `ui.role.md`
- Architecture question? → `architect.role.md`

**3. Read that prompt** - it contains all their knowledge.

**4. Fix the issue.**

## Critical Build Command

After any fix, verify:
```bash
./gradlew :presentation:build :server:test -q || \
  ./gradlew :presentation:build :server:test
```

Pattern: `-q` first (saves tokens), full output only on error.

## Current Product Priorities

- Queued messages UI: allow typing while a turn is running, show queued
  messages, edit/cancel them, and choose whether to send at the nearest safe
  point after a `tool_result` or at the end of the turn. Never insert a user
  message between `tool_call` and `tool_result`.
- Progress/loader UI: make active work visible near the input, including agent
  thinking, tools, memory read/write, and queue state.
- Voice/STT awareness: pass metadata that text came from voice/STT so the agent
  can account for recognition mistakes and spoken phrasing.
- External-world awareness: research and design future device/location/audio/
  screenshot/camera context as possible Gromozeka inputs.
- Postgres direction: memory/vector work should use PostgreSQL JSONB plus pgvector.
  Do not reintroduce Mongo-only embedding infrastructure.

## Codex Run Actions

Codex App run buttons are configured in `.codex/environments/environment.toml`.
Keep common local commands there so both the user and future Codex agents use the same entry points.

Current high-value commands:
```bash
./gradlew :presentation:run -q
./gradlew :presentation:build -q
./gradlew :presentation:wasmJsBrowserDevelopmentExecutableDistribution -q
./gradlew :server:test -q
./gradlew :application:jvmTest --tests 'com.gromozeka.application.service.memory.MemoryMaintenancePipelineTest' -q
./gradlew :server:test --tests 'com.gromozeka.server.MemoryRealModelE2eTest' -Dgromozeka.memory.e2e=true -Dgromozeka.llm.cassette.mode=replay-only -q
```

Postgres is intentionally explicit:
```bash
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" docker compose -f "$PWD/server/src/main/resources/docker-compose.yml" up -d postgres
docker compose -f "$PWD/server/src/main/resources/docker-compose.yml" stop postgres
```

## Tailscale Web Access

Current private web endpoint:
```text
https://macbook-pro.tail05115b.ts.net/
```

Current remote client WebSocket endpoint:
```text
wss://macbook-pro.tail05115b.ts.net/ws
```

Use Tailscale Serve for private HTTPS inside the tailnet. Do not add Caddy or Let's Encrypt for this mode unless the user explicitly asks for public internet access.

Start/stop commands are available as Codex run actions:
```text
Start Tailscale Web
Stop Tailscale Web
```

Manual start:
```bash
GROMOZEKA_REMOTE_PORT="${GROMOZEKA_REMOTE_PORT:-8765}"
tailscale serve --bg "http://127.0.0.1:${GROMOZEKA_REMOTE_PORT}"
```

## Local Logs

IDEA dev run configurations save full output here:
```bash
logs/server-dev.log  # Gromozeka Server [dev], Gradle :server:run
logs/client-dev.log  # Gromozeka Client JVM, Gradle :presentation:run
```

The IDEA server run configuration may build `:presentation:wasmJsBrowserDevelopmentExecutableDistribution`
before `:server:run` for local web/PWA convenience, but keep that as run-configuration behavior.
Do not make `:server:run` depend on the presentation Wasm build in Gradle: the server only serves
already-built static artifacts and must stay independently runnable from the console.

If Gradle fails before the JVM app starts, the failure is still in the same run-config log file.
Older monolithic runs may still write to `logs/dev.log` or `presentation/logs/dev.log`, but do not treat those as the primary server/client logs.

## Playwright Web UI Checks

For mobile web UI checks, do not approximate iPhone with `resize`.
Use Playwright device emulation so viewport, screen size, DPR, touch, and user agent match:
```bash
PLAYWRIGHT_MCP_DEVICE="iPhone 15" npx --yes @playwright/cli@latest -s=gromozeka-iphone15 open http://127.0.0.1:8765/
```

Compose/Wasm renders mostly into `canvas`, but text input can still work after a correct click because Compose creates a hidden `INPUT`.
Use screenshot coordinates for canvas UI, then verify focus/value if typing is suspicious:
```bash
npx --yes @playwright/cli@latest -s=gromozeka-iphone15 run-code 'async page => {
  await page.mouse.click(120, 82)
  await page.keyboard.type("dev", { delay: 30 })
  return await page.evaluate(() => ({
    activeTag: document.activeElement?.tagName,
    activeValue: document.activeElement?.value
  }))
}'
```

## Your Approach

1. User tells you what's broken
2. Read relevant agent prompts to understand that area
3. Fix the issue
4. Verify build passes

Agent prompts are your documentation.
