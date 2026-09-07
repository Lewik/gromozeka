# Emergency Repair Guide (Plan B)

**You are Codex, launched with this file because Gromozeka system is broken.**

Normal agent system isn't available. User will tell you what's broken. Your job: fix it.

## Understanding the System

Read `docs/development-guide.md` first. It describes the current modules,
runtime language, ownership boundaries, and verification commands. Runtime
Agent and Prompt configuration lives in the central Server and is not a
repository documentation mechanism.

## Quick Start Strategy

**1. Get context:**
```bash
cat docs/development-guide.md
```

**2. Read the relevant domain contracts and neighboring implementation.**

**3. Fix the issue.**

## Verification

Default to `:<module>:assemble -q` or the smallest affected target compile.
Run focused tests for changed behavior. Use a full build only for cross-cutting,
build-system, packaging, or release changes. Retry without `-q` only on error.

## Priority AI Providers

Primary providers: Claude Code, OpenAI Subscription, OpenAI API, and Anthropic API.
Live integration checks are especially important for the first two.

- Claude Code: use Haiku for routine checks. The subscription is dedicated to
  tests, but its small quota runs out quickly. Use it economically without
  sacrificing necessary verification.
- OpenAI Subscription: the quota is generous, so thorough testing is welcome.
  Use low reasoning effort for API/protocol checks; run Astra/Sol only when needed.
- Model differences: GPT-5.6 and GPT-6 may have different subscription API behavior.
  When integration changes touch those differences, test both generations.
  Low reasoning effort is sufficient for protocol checks.

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

## Parallel Development Checkouts

Sibling checkouts may be used concurrently by other development agents. Read them when a task requires comparison, but write only inside the current checkout unless the user explicitly directs otherwise. Never stop another checkout's processes or infrastructure unless the user asks.

Each checkout has a local `.env` with a slot from 1 through 5: `dev=1`, `dev0=2`, `dev1=3`, `dev2=4`, `dev3=5`. Server and PostgreSQL ports are their defaults plus the slot (`8765 + slot` and `5432 + slot`). The Gradle run tasks and root Compose configuration read this file and reject inconsistent port values. Slot settings are development-only; do not apply them to `deploy/` configurations.

Use the standard commands from the checkout root:

```bash
docker compose up -d postgres
./gradlew :server:run
./gradlew :worker:run -q
./gradlew :presentation:run
docker compose stop postgres
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
source .env && PLAYWRIGHT_MCP_DEVICE="iPhone 15" npx --yes @playwright/cli@latest -s=gromozeka-iphone15 open "http://127.0.0.1:${GROMOZEKA_REMOTE_PORT}/"
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

## iOS Local Build And Install

For command-line iPhone builds, pass `ARCHS=arm64`; otherwise KMP/Compose iOS
resource tasks may fail to infer target architecture:
```bash
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -target iosApp \
  -configuration Debug \
  -sdk iphoneos26.2 \
  -destination 'id=<device-uuid>' \
  ARCHS=arm64 \
  -allowProvisioningUpdates \
  build
```

Install the freshly built app from the project build directory:
```bash
xcrun devicectl device install app \
  --device <device-uuid> \
  iosApp/build/Debug-iphoneos/Gromozeka.app
```

Do not install `DerivedData/.../Gromozeka.app` unless you verified the embedded
`GromozekaPresentation.framework` timestamp. DerivedData can keep stale Kotlin
frameworks while `iosApp/build/Debug-iphoneos/Gromozeka.app` contains the actual
fresh command-line build.

## Your Approach

1. User tells you what's broken
2. Read `docs/development-guide.md` and relevant domain contracts
3. Fix the issue
4. Verify build passes

Agent prompts are your documentation.
