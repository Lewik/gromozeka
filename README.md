# Gromozeka

<img src="presentation/src/jvmMain/resources/logos/logo-128x128.png" alt="Gromozeka Logo" width="64" height="64" align="left" />

Gromozeka is an experimental Kotlin Multiplatform AI assistant.

It is both an agent UI and a dogfooding environment for building a more durable agent runtime: multi-tab conversations, tool execution, MCP integration, voice input, and typed long-term memory.

The project is currently a local research/development application, not a polished packaged product.

## Current Runtime Reality

- Main dogfooding runtime: `OPEN_AI_SUBSCRIPTION`.
- Runtime composition roots: `:server` for the control/API plane and `:worker` for execution.
- UI clients: `:presentation` JVM Desktop and Wasm web/PWA.
- Business workflows: `:application`.
- Domain contracts and memory models: `:domain`.
- AI integrations and tool implementations: `:infrastructure-ai`.
- Persistence and search implementations: `:infrastructure-db`.
- Active long-term memory backend: PostgreSQL through `MemoryStore`.

The current development shape is split:

- `:server` accepts client commands, persists runtime state, publishes durable work, streams events, and exposes the Ktor remote endpoint.
- `:worker` claims durable tasks and executes conversation, LLM, tool, and memory work according to its declared capabilities and exact task targets.
- `:presentation` owns UI code and can run either as a JVM desktop client or as a Wasm web client.
- The server listens on `/ws` for remote UI traffic and serves already-built Wasm static files from `presentation/build/dist/wasmJs/developmentExecutable` by default.

Legacy and auxiliary integrations still exist in the codebase, but the current default development path is the OpenAI subscription runtime plus PostgreSQL-backed typed memory.

## Always YOLO

Gromozeka intentionally does not implement per-command approvals, command denylists, filesystem sandboxes, or a second application-level permission system. Isolation, when required, must be provided by the operating system, a dedicated account, container, virtual machine, credential scope, network policy, and backups.

A Gromozeka Worker is a trusted, unsandboxed executor. Enrolling a Worker authorizes the Gromozeka control plane and its selected models to invoke configured tools with the effective permissions of the Worker process. The Worker is not an autonomous agent and does not choose goals or policy. It executes durable tasks assigned by the control plane; depending on its declared capabilities, those tasks can include conversation orchestration, LLM calls, memory pipelines, and tool execution.

`Readonly` and `Writable` are behavioral instructions for supported models, not security boundaries. A model that cannot reliably follow these instructions is unsupported.

The Server is a control plane whose authority is explicitly delegated by its enrolled Workers. It does not elevate a Worker beyond the effective permissions of its process or enlarge the underlying permissions of its machines; it provides another path for exercising authority that already exists. Authentication, transport security, private network access, narrowly scoped infrastructure credentials, and auditability protect that path; they are infrastructure requirements, not model-facing per-command permissions.

### Prompt Injection and Phishing

This trust model does not make external content trustworthy. [Indirect prompt injection](https://openai.com/safety/prompt-injections/) is a social-engineering and confused-deputy attack: attacker-controlled text in a file, repository, web page, email, or tool result tries to make an already-authorized model use its existing authority against the user's intent. It is closer to phishing than to an operating-system privilege escalation.

The important difference is how the target receives information. A human usually sees the channel, sender, and content as distinct signals. An LLM reasons over instructions and data in the same context, even when the API labels their roles. Modern models are trained with an explicit [instruction hierarchy](https://openai.com/index/instruction-hierarchy-challenge/) to prioritize higher-authority instructions and ignore malicious tool content, but this remains learned model behavior rather than a kernel-enforced boundary.

Available measurements show that neither humans nor models are reliably immune, but the numbers describe different populations and must not be treated as one comparable prevalence rate:

- The [FBI 2025 IC3 report](https://www.ic3.gov/AnnualReport/Reports/2025_IC3Report.pdf) recorded 191,561 phishing/spoofing complaints and $215.8 million in reported losses. These are real-world reports, not a click or attack-success rate.
- A [controlled spear-phishing study](https://arxiv.org/abs/2412.00586) with 101 participants measured a 12% click-through rate for arbitrary phishing emails and 54% for both human-expert and fully AI-automated personalized emails.
- The 2026 [LivePI preprint](https://arxiv.org/abs/2605.17986) measured 10.7% to 29.6% total attack success across five frontier agent systems in a live but test-controlled environment.
- A [NAACL 2025 adaptive-attack study](https://aclanthology.org/2025.findings-naacl.395/) bypassed all eight evaluated indirect-prompt-injection defenses with attack success rates above 50%, demonstrating that results against static attacks do not establish a hard boundary.

Gromozeka accepts this residual risk explicitly instead of presenting command filters or approval dialogs as a complete solution. It relies on supported models' instruction-hierarchy behavior, explicit source and role context, observable execution, and infrastructure-level isolation chosen by the operator. Logs and backups support detection and recovery; they do not prevent prompt injection.

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
3. Search typed memory in PostgreSQL.
4. Select/rerank final memory context.
5. Inject the retrieved memory into the main LLM call as runtime-only context.

Maintenance flows are explicit/manual durable operations handled asynchronously by a Worker: note consolidation, repair, entity maintenance, retention, and embedding rebuilds.

Useful memory docs:

- [MEMORY_IMPLEMENTATION.md](MEMORY_IMPLEMENTATION.md)
- [LLM_CASSETTE_NOTE.md](LLM_CASSETTE_NOTE.md)
- [agent_memory_handoff/README.md](agent_memory_handoff/README.md)
- [domain memory architecture](domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryArchitecture.md)

## Prerequisites

- macOS development machine.
- JDK 21.
- Docker, for local PostgreSQL with pgvector and RabbitMQ runtime work queues.
- OpenAI subscription auth file in Gromozeka home for the current dogfooding runtime.
- Microphone permissions if you want to use voice input.

## Running Locally

PostgreSQL and RabbitMQ are intentionally explicit. The app should fail fast if either runtime dependency is not available.

The server, Worker, and UI clients are separate processes. Start local infrastructure first, then the server, a Worker, and one of the UI clients.

Start local infrastructure:

```bash
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" \
docker compose -f "$PWD/server/src/main/resources/docker-compose.yml" up -d postgres rabbitmq
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

The Server only accepts commands, persists runtime state, publishes work, and
streams events. Start the local all-capabilities Worker:

```bash
SPRING_CONFIG_ADDITIONAL_LOCATION="file:$PWD/worker/config/dev-worker.yaml" \
GROMOZEKA_HOME="$PWD/dev-data/client/.gromozeka" \
GROMOZEKA_MODE=dev \
./gradlew :worker:run
```

See `worker/README.md` for cloud/local Worker configuration and the trusted executor contract.

The default remote client endpoint is local:

```text
ws://127.0.0.1:8765/ws
```

Override it with `GROMOZEKA_REMOTE_URL` when connecting through LAN, VPN, or Tailscale.

### JVM Desktop Client

Run the desktop UI client against the local server:

```bash
GROMOZEKA_REMOTE_URL="ws://127.0.0.1:8765/ws" \
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
https://<machine>.<tailnet>.ts.net/
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

Stop local infrastructure:

```bash
docker compose -f "$PWD/server/src/main/resources/docker-compose.yml" stop postgres rabbitmq
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
infrastructure-db/  PostgreSQL persistence, search, repository implementations
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
- Postgres data lives under `GROMOZEKA_HOME/postgres` when started through the provided compose file.

## Philosophy

Gromozeka is an attempt to move beyond "just chat" toward an assistant that can work with code, tools, projects, and its own accumulated context.

The goal is human-AI collaboration where the system remembers useful project knowledge, exposes its traces, and stays debuggable enough that a developer can understand why it did what it did.

## License

Custom License - free for non-commercial use, commercial use requires permission. See [LICENSE](LICENSE) for details.
