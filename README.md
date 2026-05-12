# Gromozeka

<img src="presentation/src/jvmMain/resources/logos/logo-128x128.png" alt="Gromozeka Logo" width="64" height="64" align="left" />

Gromozeka is an experimental Kotlin Multiplatform AI assistant.

It is both an agent UI and a dogfooding environment for building a more durable agent runtime: multi-tab conversations, tool execution, MCP integration, voice input, and typed long-term memory.

The project is currently a local research/development application, not a polished packaged product.

## Current Runtime Reality

- Main dogfooding runtime: `OPEN_AI_SUBSCRIPTION`.
- Runtime composition root: `:server`.
- UI clients: `:presentation` JVM Desktop and Wasm web/PWA.
- Business workflows: `:application`.
- Domain contracts and memory models: `:domain`.
- AI integrations and tool implementations: `:infrastructure-ai`.
- Persistence and search implementations: `:infrastructure-db`.
- Active long-term memory backend: MongoDB through `MemoryStore`.

The current development shape is split:

- `:server` owns Spring composition, LLM runtimes, tools, persistence, memory, and a Ktor remote endpoint.
- `:presentation` owns UI code and can run either as a JVM desktop client or as a Wasm web client.
- The server listens on `/ws` for remote UI traffic and serves already-built Wasm static files from `presentation/build/dist/wasmJs/developmentExecutable` by default.

Legacy and auxiliary integrations still exist in the codebase, but the current default development path is the OpenAI subscription runtime plus Mongo-backed typed memory.

## What Gromozeka Does

- Runs Compose chat UI with tabs and project-aware sessions.
- Supports remote UI clients over WebSocket: JVM desktop locally, Wasm web/PWA in a browser.
- Calls LLM runtimes through domain-level `AiRuntime` abstractions.
- Exposes internal tools for filesystem, shell, web search, code navigation, planning, and inter-agent workflows.
- Supports MCP configuration for external tools and servers.
- Provides voice-oriented UI pieces such as push-to-talk and TTS services.
- Writes and recalls structured project memory automatically during chat.

## Typed Memory MVP

The active memory system is not a plain vector database. It stores typed, provenance-aware memory objects:

- `Source`: immutable evidence, usually a chat turn or tool output.
- `Entity`: canonical project/user/product/concept anchor.
- `Claim`: atomic factual memory with predicate, scope, evidence, lifecycle, and temporal fields.
- `Note`: rationale, design note, or reusable reasoning chunk.
- `Task`: durable follow-up or commitment memory with lifecycle state.
- `Profile`: projection built from profile-sync claims.
- `Episode`: reusable experience or lesson pattern.
- `MemoryRun`: debug/audit trace for memory pipeline execution.

Runtime write path:

1. Capture the current message as a source.
2. Route the message with `LlmMemoryWriteRouter`.
3. Retrieve relevant existing memory when needed.
4. Canonicalize entities.
5. Build/reconcile notes, claims, and tasks.
6. Materialize changes into `MemoryStore`.
7. Update projections such as profiles.

Runtime read path:

1. Plan whether memory is needed with `LlmMemoryReadPlanner`.
2. Verify no-memory decisions with a model-based verifier when needed.
3. Search typed memory in MongoDB.
4. Select/rerank final memory context.
5. Inject the retrieved memory into the main LLM call as runtime-only context.

Maintenance flows are currently explicit/manual and synchronous: note consolidation, repair, entity maintenance, and retention.

Useful memory docs:

- [MEMORY_IMPLEMENTATION.md](MEMORY_IMPLEMENTATION.md)
- [MEMORY_CURRENT_STATE.md](MEMORY_CURRENT_STATE.md)
- [LLM_CASSETTE_NOTE.md](LLM_CASSETTE_NOTE.md)
- [agent_memory_handoff/README.md](agent_memory_handoff/README.md)
- [domain memory architecture](domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryArchitecture.md)

## Prerequisites

- macOS development machine.
- JDK 21.
- Docker, for local MongoDB.
- OpenAI subscription auth file in Gromozeka home for the current dogfooding runtime.
- Microphone permissions if you want to use voice input.

## Running Locally

MongoDB is intentionally explicit. The app should fail fast if Mongo is not available.

The server and UI clients are separate processes. Start MongoDB first, then the server, then one of the UI clients.

Start MongoDB:

```bash
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" \
docker compose -f "$PWD/server/src/main/resources/docker-compose.yml" up
```

Run the server:

```bash
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" \
GROMOZEKA_MODE=dev \
./gradlew :server:run
```

The server defaults to `127.0.0.1:8765`. It prints a line like:

```bash
==== Gromozeka server started: ws://127.0.0.1:8765/ws ====
```

The current private tailnet endpoint for remote clients is:

```text
wss://macbook-pro.tail05115b.ts.net/ws
```

The server also prints the matching Tailscale Serve command for the configured port.

### JVM Desktop Client

Run the desktop UI client against the local server:

```bash
GROMOZEKA_REMOTE_URL="wss://macbook-pro.tail05115b.ts.net/ws" \
GROMOZEKA_CLIENT_HOME="$PWD/dev-data/client/.gromozeka-remote-client" \
./gradlew :presentation:run
```

### Web/PWA Client

Build the Wasm web client:

```bash
./gradlew :presentation:wasmJsBrowserDevelopmentExecutableDistribution
```

Then run the server and open locally:

```text
http://127.0.0.1:8765/
```

The web client resolves its WebSocket endpoint from the browser URL, so `http://127.0.0.1:8765/` uses `ws://127.0.0.1:8765/ws`.

For raw HTTP testing through LAN/VPN without Tailscale Serve, bind the server to all interfaces:

```bash
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" \
GROMOZEKA_MODE=dev \
GROMOZEKA_REMOTE_HOST=0.0.0.0 \
./gradlew :server:run
```

Then open:

```text
http://<machine-tailscale-or-lan-ip>:8765/
```

### HTTPS Web/PWA through Tailscale

For private phone/laptop access inside a tailnet, use Tailscale Serve instead of exposing Gromozeka publicly. The Wasm client does not need a separate HTTPS build. It resolves `/ws` from the page URL, so an `https://` page uses `wss://` automatically.

Build the Wasm web client:

```bash
./gradlew :presentation:wasmJsBrowserDevelopmentExecutableDistribution
```

Run the server:

```bash
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" \
GROMOZEKA_MODE=dev \
./gradlew :server:run
```

Expose the local server through Tailscale Serve:

```bash
GROMOZEKA_REMOTE_PORT="${GROMOZEKA_REMOTE_PORT:-8765}"
tailscale serve --bg "http://127.0.0.1:${GROMOZEKA_REMOTE_PORT}"
```

Then open:

```text
https://macbook-pro.tail05115b.ts.net/
```

If you run the server on a non-default port, rerun the `tailscale serve --bg ...` command with the same `GROMOZEKA_REMOTE_PORT`.

Check the current Serve configuration:

```bash
tailscale serve status
```

Stop serving Gromozeka through Tailscale:

```bash
tailscale serve reset
```

If static files need to be served from a custom directory, set:

```bash
GROMOZEKA_WEB_STATIC_DIR="/absolute/path/to/web/dist"
```

Stop MongoDB:

```bash
docker compose -f "$PWD/server/src/main/resources/docker-compose.yml" stop mongodb
```

## Verification

Build:

```bash
./gradlew :presentation:build -q
```

Server build:

```bash
./gradlew :server:build -q
```

Web client distribution only:

```bash
./gradlew :presentation:wasmJsBrowserDevelopmentExecutableDistribution -q
```

Application context smoke test:

```bash
./gradlew :server:test -q
```

Memory maintenance unit tests:

```bash
./gradlew :application:jvmTest --tests 'com.gromozeka.application.service.memory.MemoryMaintenancePipelineTest' -q
```

Memory real-model E2E in deterministic replay mode:

```bash
./gradlew :server:test \
  --tests 'com.gromozeka.server.MemoryRealModelE2eTest' \
  -Dgromozeka.memory.e2e=true \
  -Dgromozeka.llm.cassette.mode=replay-only \
  -q
```

Record missing LLM cassettes intentionally:

```bash
./gradlew :server:test \
  --tests 'com.gromozeka.server.MemoryRealModelE2eTest' \
  -Dgromozeka.memory.e2e=true \
  -Dgromozeka.llm.cassette.mode=record-missing \
  -q
```

For `gromozeka.memory.e2e=true`, cassette mode defaults to `replay-only`. This prevents accidental live LLM calls during normal verification.

## Project Structure

```text
domain/             Domain models, service contracts, tool contracts
application/        Use cases and orchestration pipelines
infrastructure-ai/  LLM runtimes, tools, MCP-related integrations
infrastructure-db/  Mongo persistence, search, repository implementations
server/             Spring/server runtime composition, Ktor remote endpoint, server-owned resources
presentation/       Compose JVM Desktop and Wasm web UI clients
shared/             Shared utilities
agent_memory_handoff/  Memory design handoff and reference architecture
docs/               Architecture notes, diagrams, research, guides
```

## Development Notes

- Domain contracts should explain what the system must be able to do without forcing a storage or UI implementation.
- Memory debug quality is judged by traces and E2E artifacts, not only by compilation.
- The current E2E suite intentionally uses real LLM calls when recording cassettes and deterministic replay afterward.
- Logs are verbose by design while the memory MVP is still being hardened.
- MongoDB data lives under `GROMOZEKA_HOME/mongo` when started through the provided compose file.

## Philosophy

Gromozeka is an attempt to move beyond "just chat" toward an assistant that can work with code, tools, projects, and its own accumulated context.

The goal is human-AI collaboration where the system remembers useful project knowledge, exposes its traces, and stays debuggable enough that a developer can understand why it did what it did.

## License

Custom License - free for non-commercial use, commercial use requires permission. See [LICENSE](LICENSE) for details.
