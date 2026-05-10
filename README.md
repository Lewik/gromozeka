# Gromozeka

<img src="presentation/src/jvmMain/resources/logos/logo-128x128.png" alt="Gromozeka Logo" width="64" height="64" align="left" />

Gromozeka is an experimental Kotlin Multiplatform + Compose Desktop AI assistant.

It is both a desktop agent UI and a dogfooding environment for building a more durable agent runtime: multi-tab conversations, tool execution, MCP integration, voice input, and typed long-term memory.

The project is currently a local research/development application, not a polished packaged product.

## Current Runtime Reality

- Main dogfooding runtime: `OPEN_AI_SUBSCRIPTION`.
- Runtime composition root: `:server`.
- Desktop UI entrypoint: `:presentation`.
- Business workflows: `:application`.
- Domain contracts and memory models: `:domain`.
- AI integrations and tool implementations: `:infrastructure-ai`.
- Persistence and search implementations: `:infrastructure-db`.
- Active long-term memory backend: MongoDB through `MemoryStore`.

Legacy and auxiliary integrations still exist in the codebase, but the current default development path is the OpenAI subscription runtime plus Mongo-backed typed memory.

## What Gromozeka Does

- Runs a native Compose Desktop chat UI with tabs and project-aware sessions.
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

Start MongoDB:

```bash
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" \
docker compose -f "$PWD/server/src/main/resources/docker-compose.yml" up
```

Run the server:

```bash
GROMOZEKA_MODE=dev ./gradlew :server:run
```

Run the desktop UI client:

```bash
./gradlew :presentation:run
```

Stop MongoDB:

```bash
docker compose -f "$PWD/server/src/main/resources/docker-compose.yml" stop mongodb
```

Codex desktop run actions are configured in:

```text
.codex/environments/environment.toml
```

The high-value actions are `Start Mongo`, `Run`, `Stop Mongo`, `Build`, `Server Tests`, `Memory Unit Tests`, and `Memory Real E2E`.

## Verification

Build:

```bash
./gradlew :presentation:build -q
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
server/             Spring/server runtime composition and server-owned resources
presentation/       Compose Desktop remote UI client and client-owned resources
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
