# Gromozeka Development Guide

This document is the repository-level entry point for development without a
running Gromozeka instance. AI connections, model specifications, runtime
assignments, Agents, and Prompts are managed by the central Server. Repository
resources are import templates, not a second live configuration source.

## Current Architecture

Gromozeka is a Kotlin Multiplatform application with Compose clients, a central
Server, and standalone Workers.

- `:shared` contains cross-cutting primitives and utilities.
- `:domain` contains technology-light models, repository contracts, service
  contracts, presentation contracts, and tool contracts.
- `:application` implements use cases, orchestration, and transactional
  workflows.
- `:infrastructure-db` implements PostgreSQL persistence.
- `:infrastructure-ai` contains provider integrations, MCP, memory, embeddings,
  and tool implementations.
- `:state-sync` provides storage- and transport-neutral invalidation, snapshot
  coalescing, shared loads, and stale-replica protection.
- `:remote-protocol` and `:remote-client` define the client-to-Server boundary.
- `:server` is the control plane and web endpoint.
- `:worker` is a trusted standalone executor.
- `:worker-runtime` contains the shared KMP Worker registration client and
  Gateway runtime: handshake, reconnect, heartbeat, request/response routing,
  cancellation, capability/tool publication, and the durable event outbox with
  bounded HTTP batch delivery. JVM/Spring lifecycle, TLS
  configuration, and tool implementations stay in `:worker`; mobile lifecycle
  and storage stay in `:mobile-worker`.
- `:presentation` contains the shared Compose clients and presentation state.
- `:presentation-android` packages the Android client application.
- `:mobile-worker` contains the shared mobile Worker runtime.
- `:mobile-worker-android` packages the Android mobile Worker application.

Dependencies should point toward domain contracts where practical. Framework,
storage, provider, and transport details stay in their infrastructure modules.
Presentation bootstrap may wire infrastructure, but ordinary UI code should use
domain and application abstractions.

Declarative client state is synchronized by revisions rather than transported
as mutation deltas. Application services publish affected resource keys after
their transaction commits. Clients conflate invalidations, pull the latest
revision, and then reuse the existing typed read request for the current
snapshot. Reconnect starts from a fresh snapshot; it does not replay missed
declarative mutations. Conversation messages and other ordered event streams
remain separate because they cannot be safely conflated.
Active model-call presentation is a separate cumulative state-sync snapshot.
It is intentionally transient: losing it must not affect model execution,
conversation history, cancellation, or terminal runtime events.

The main dogfood chat path is `OPEN_AI_SUBSCRIPTION`, implemented by
`:infrastructure-ai:openai-subscription`. Spring AI adapters remain responsible
for other providers, embeddings, and auxiliary integrations. Provider quirks
belong behind those infrastructure boundaries rather than in domain workflows.

## Corporate Compatibility

External integrations must respect the operator's selected provider policy. A
disabled or unconfigured provider must receive no requests, including discovery
calls, health probes, fallback traffic, embeddings, speech, or auxiliary tool
calls. Provider-specific integrations and optimizations are welcome, but they
must stay explicit, isolated, and operator-controlled rather than silently
bypassing deployment policy.

Before adding a runtime or distributable dependency, verify that its license
permits the intended closed-source commercial use and distribution without
reciprocal source-disclosure obligations. Proprietary CLIs and services should
be installed, licensed, and authenticated separately unless their terms clearly
permit redistribution. Experimental adapters around separately installed tools
must be opt-in and identified as unsupported integration paths.

Network destinations, credential ownership, and relevant provider data
retention behavior should be visible to the operator. Do not claim privacy,
zero-data-retention, or compliance properties that are not guaranteed by the
selected provider and account contract. These dependency rules do not change
Gromozeka's own license: commercial use still requires the permission described
in `LICENSE`.

Standalone Server and Worker archives are self-contained. Release builds
download pinned official Temurin JRE and Node.js archives, verify their SHA-256
checksums, and preserve their legal notices inside the resulting packages. The
Server includes Java. The Worker includes Java, Node.js, and the pinned
Gromozeka Browser MCP package. Installed applications never select a system
runtime or download executable code during first launch. Docker images follow
the same runtime boundary. Claude Code, GitHub Copilot CLI, and browser binaries
are never bundled.

## Diagnostic Logging

Persistent runtime logs are size-bounded and rotated. Set `GROMOZEKA_LOG_DIR`
to redirect the complete installation to one base directory; Worker logs stay
under its `workers` child directory. JVM launches can use
`-Dgromozeka.log.dir=/path` or `--gromozeka.log.dir=/path` instead. Standard
Spring Boot `LOGGING_FILE_PATH`, `-Dlogging.file.path=/path`, and
`--logging.file.path=/path` remain exact per-process directory overrides.
Desktop client verbosity can be changed with `GROMOZEKA_LOG_LEVEL` or
`-Dgromozeka.log.level`; production defaults to `INFO` and development to
`DEBUG`.

Android and iOS clients and Mobile Workers keep approximately 3 MB of
diagnostic logs inside their application sandbox. Browser clients log only to
the browser console. Diagnostic logs must not include conversation text,
credentials, raw authorization headers, or exact device locations.

## Runtime Language

- A **Project** is a logical working context.
- A **Conversation** belongs to a Project, is not bound to a Workspace, and has
  an explicit set of connected User and Agent participants. Project membership
  makes a User eligible to join; a connected User participant is required to
  access that Conversation.
- An **Agent** is a server-managed model, prompt, and behavior configuration. It
  is not an executor.
- A **Worker** is a named execution process. The Server is not a Worker.
- Workers have one resource model. Platform, advertised capabilities, ownership,
  and optional user-context binding are independent properties. A user-bound
  Worker can both report context and execute supported operations. Context
  ingestion requires a binding established during approved registration, not a
  particular platform or Worker kind.
- A **Workspace** is a logical Project resource. The only current kind is a
  filesystem tree.
- A **Workspace Mount** records that one Worker sees one Workspace at a
  Worker-local root path.

Worker-scoped operations select an exact Worker. Workspace-scoped operations,
including shell, filesystem, and Git tools, select an exact Workspace Mount.
The Server does not inspect a Worker's filesystem, guess a target, or reassign a
call. It may redeliver the same persisted request ID, never automatically repeat
an action with an uncertain outcome. See [Worker request delivery](worker-request-delivery.md).

Conversation turns and memory pipelines always run on the Server. A Worker can
execute configured tools and finite AI request-response operations, but it does
not own conversation or memory orchestration.

Posting a User message and invoking an Agent are separate operations. A plain
post only appends the message through the serialized Conversation runtime. An
Agent invocation appends the User message and starts that connected Agent's AI
and memory pipeline.

Every AI connection has an exact execution target: the Server or one named
Worker. Finite LLM calls, embeddings, speech transcription, and speech synthesis
use that target. Realtime and long-lived streaming AI sessions are Server-only.
Claude Code is the exception to general target selection: its connection always
targets a Worker where Claude Code is separately installed and authenticated.
GitHub Copilot can target the Server or one exact Worker. Gromozeka bundles the
MIT-licensed Java SDK, but the operator installs and licenses Copilot CLI
separately on the selected target. Server-targeted connections can use either
that Server's CLI login or an encrypted per-user GitHub token. Worker-targeted
connections use only that Worker's local CLI login; user tokens are never sent
to Workers. A connection never falls back between auth modes, execution targets,
Workers, or models.
A known offline target can receive a queued finite request within its delivery
TTL. Missing or incompatible targets fail explicitly; Gromozeka does not fall
back to another Worker or to the Server. Live audio stream operations still
require an online target and retain process-local identity checks.

Each Worker registration advertises a stable environment profile collected at
startup. The execution topology uses that profile without changing on every
heartbeat. `grz_get_worker_environment` recollects the complete profile and
volatile capacity, process, executable, and project-mount data on the selected
Worker when current facts are needed.

## Computer Use

Computer Use is pixel-based control of one exact Worker's real interactive
desktop. Prefer Browser Use when DOM and accessibility state are available;
Computer Use is the intrusive fallback for native applications, remote desktop
content, OS dialogs, and other surfaces exposed only as pixels.

The model uses three synchronous tools:

1. `grz_computer_targets` lists displays on the selected Worker.
2. `grz_computer_observe` returns a PNG and a short opaque `observation_ref`
   for that screenshot's coordinate frame.
3. `grz_computer_act` applies one bounded ordered action list exactly once and
   returns a fresh screenshot.

Computer Use has no durable session or reconciliation state. Each request is a
plain Worker-targeted tool execution. A cryptographically random process-local
reference resolves to immutable coordinate geometry, expires after five
minutes, and is consumed by one action request. It is valid only for the exact
Worker process that captured it, but it does not claim that the visible desktop
has remained unchanged. Calls on the same display are serialized only while
they execute.

Desktop actions are never repeated or reassigned automatically. A Gateway
disconnect leaves execution running and its result is saved until Server
acknowledgement. A Worker process crash after execution starts but before the
result is saved produces `OUTCOME_UNKNOWN`; the model must observe again before
deciding what to do. Delivery TTL limits when an action may start, independently
of its execution timeout and the caller's wait. Cancelling the turn persists a
request-scoped cancellation, delivered on reconnect if necessary; the backend
checks it between actions and releases any pressed keys or mouse buttons in a
`finally` block. Cancellation and execution timeout can leave partial effects.

Screenshots are ordinary tool-result Artifacts. Only the three latest Computer
Use screenshots are materialized into an LLM request; older images remain
durable but become compact text placeholders in provider context. Clients
communicate only with the Server and never connect directly to a Worker.

The current JVM backend supports interactive macOS, Windows, and X11 sessions.
Headless and Wayland Workers omit the Computer Use tools. macOS Workers query
Screen Recording and Accessibility before advertising the tools and again
before each request. The standalone macOS LaunchAgent uses a stable native app
launcher installed and signed only once, so these permissions do not attach to
a versioned shell or JRE and survive Worker updates.
Computer Use intentionally controls the real pointer, keyboard, focus, and
clipboard; there is no separate ownership or takeover UI.

The macOS and Windows applications bundle a self-contained Worker BootJar,
Browser MCP, Temurin, and Node.js as Local Worker resources. Client and Worker
remain separate processes with isolated classpaths; presentation dependencies
can never replace Worker dependencies at runtime. The duplicated Java runtime
is intentional until packaging can share it without coupling the two
applications. The Client owns the visible tray item and enrollment UI; the
Local Worker remains behind the standard Worker Gateway. macOS
uses a hidden LaunchAgent, while Windows launches the Worker in the current
interactive session so Computer Use is never isolated in service Session 0.
Closing the Client window hides it, while an explicit application quit stops
the managed Local Worker. The stable macOS helper copied under Application
Support owns Screen Recording, Accessibility, and microphone consent across app
updates. Browser Bridge and Claude Code remain separately installed user tools.
The managed Worker keeps its configuration under `~/.gromozeka/local-worker`
and uses a `-local` Worker ID suffix, so a standalone Worker on the same machine
retains a separate identity and credential. Standalone Worker packages use the
same Worker Gateway protocol but keep their own lifecycle.

Deployments may attach a human-facing interactive desktop to a Worker. When
configured, `grz_worker_interactive_access_get` returns a stable Server URL;
opening it checks the current Gromozeka session and Worker access before issuing
a short-lived, one-time handoff to the deployment's desktop transport. Transport
details such as Amazon DCV remain optional Server/deployment adapters rather
than part of the generic Worker protocol.

## Identity And Authentication

The Server owns user identity. Local username/password credentials are the
initial login method; future OAuth or OIDC identities must attach to the same
stable User instead of creating a parallel account model.

One Server deployment is one isolated Gromozeka Runtime. A Runtime can contain
multiple Users, Projects, and Workers, but it does not contain several pooled
customer organizations. A future managed control plane may provision several
Runtimes for one account or organization; each Runtime still owns its own
database, queues, secrets, users, and workers. On-premises installations use
the same Runtime shape without the managed control plane.

Public registration is closed. An empty Server prints a one-time first-owner
bootstrap token to its log. Clients use that token once to create the first
User, after which ordinary login issues an opaque, revocable Server session.
Only a hash of each session token is persisted. Local passwords are stored as
Argon2id hashes, and repeated failed logins are rate-limited.

Browser clients use an HttpOnly, SameSite session cookie. Secure cookies are
enabled by default when the Server listens on a non-loopback address and can be
overridden explicitly with `GROMOZEKA_AUTH_SECURE_COOKIE=true|false`.

Runtime Owners can inspect an append-only audit trail of successful identity
and access changes. Audit events contain typed actor, target, project, and
non-secret change metadata. They never contain passwords, raw access or
enrollment tokens, prompts, tool payloads, or conversation content. Login
attempts and ordinary activity remain operational logs rather than durable
security-audit events.

## Memory Banks

A `MemoryNamespace` is an internal memory-bank boundary selected by trusted
runtime context, not by model or client input.

- Conversation memory uses the Conversation Project bank:
  `project:<project-id>`. Existing Project permissions govern access.
- External memory MCP uses the authenticated User's personal bank:
  `user:<user-id>`.
- Tool arguments and hidden MCP context cannot select or override a bank.
- Run status, queue status, maintenance, embeddings, reads, and writes remain
  inside the same selected bank.
- `global` is reserved for explicit tests and benchmarks. Production code must
  never fall back to it implicitly.

Adding shared or cross-project memory later requires an explicit grant model.
Do not infer access from a supplied namespace string.

## Development Model

Typed domain contracts and KDoc are the primary coordination mechanism. Read
the relevant model and service contract before changing an implementation.
Use current source code as final truth when documentation has drifted.

Prefer interfaces, typed identifiers, sealed hierarchies, and enums over
stringly typed coordination. Use nullable values when absence is normal,
exceptions for violated invariants, and explicit result types when callers need
to distinguish several meaningful outcomes. Application services own workflows;
repositories should not make business decisions.

Layer ownership for focused work:

| Concern | Primary module |
| --- | --- |
| Domain design and contracts | `:domain` |
| Use cases and orchestration | `:application` |
| PostgreSQL persistence | `:infrastructure-db` |
| AI providers, live external MCP clients, memory, tools | `:infrastructure-ai` |
| External MCP definitions and accepted tool snapshots | Server database through `:infrastructure-db` |
| Immutable AI tool contracts and stable model-facing names | Server database through `:infrastructure-db` |
| Durable runtime scheduling | `:application` and `:infrastructure-db` |
| Durable Worker requests and event delivery | `:worker-runtime`, `:server`, `:remote-protocol`, and platform storage |
| Compose UI and presentation state | `:presentation` |
| Android client packaging | `:presentation-android` |
| Mobile Worker runtime | `:mobile-worker` |
| Android mobile Worker packaging | `:mobile-worker-android` |
| Server endpoints and composition | `:server` |
| Worker process and local execution | `:worker` |

Every active Server, Worker, or external MCP tool resolves through the
`ai_tool_contracts` registry. Its fingerprint includes the full definition and
runtime metadata, including documentation. Equal contracts share one
model-facing name across compatible executors; different contracts coexist as
stable versioned names. Runtime routing translates that model-facing name back
to the executor's original tool name without changing the tool argument body.

Repository dependency sources may be cloned into `.sources/` when exact
third-party behavior matters. They are research material and stay gitignored.

## Runtime Configuration Design

AI configuration, runtime Agents, and Prompts are Server-managed database
entities. Bundled definitions are application templates used to initialize an
empty catalog or prefill an explicit create/import flow. Updating a bundled
resource never silently changes an existing runtime entity.

- Keep stable cross-project behavior in global Prompts and Agents.
- Keep project-specific behavior in project-scoped Prompts and Agents.
- Keep each prompt focused on one class of information instead of repeating
  mutable facts across a stack.
- Put dynamic execution environment data in the runtime environment context,
  not in static prompt definitions.
- Change prompts in response to observed behavior and validate the assembled
  stack rather than only checking individual fragments.
- Treat Agent Skills as imported project-scoped packages. Prefer
  `grz_skill_export_to_directory` and `grz_skill_import_from_directory` for
  model-driven editing so package bytes stay outside model context. Inline
  import/export is for small integrations and carries text and base64 binaries
  through model context.
- Agent Skill import derives a workspace materialization plan. Runtime opening
  exposes instructions, a compact resource index, and an
  immutable `skill_id` plus `content_hash` handle. Model-readable resources are
  fetched only on demand through that exact handle, and binary resources are
  never copied into model context.
- `grz_skill_materialize` replaces the stable runtime package at
  `<workspace>/.gromozeka/skills/<name>` on the selected Worker
  mount. It accepts the same immutable handle returned by `grz_skill_activate`;
  materialization does not execute files, install dependencies, or grant
  permissions. It is effectively read-only because it is managed runtime setup
  and may be required for otherwise read-only operations. Behavioral `Readonly`
  mode still forbids editing that copy, the Skill package, or project files.

## Repository Checkouts

The usual local checkouts are:

```text
gromozeka/
|-- dev/      primary development checkout, branch main
|-- beta/     pre-release dogfood checkout, branch beta
`-- release/  stable checkout, branch release
```

Develop and verify in `dev/`. Synchronize other checkouts through Git instead
of copying files manually.

## Release Versioning

Published releases follow Semantic Versioning as `MAJOR.MINOR.PATCH`.

- `PATCH` contains backward-compatible fixes.
- `MINOR` adds backward-compatible functionality.
- `MAJOR` permits incompatible changes to the public compatibility surface.

That public surface includes documented Server APIs, Client and Worker
protocols, configuration formats, and other contracts consumed outside the
implementing component. A newer Server must accept older Clients and Workers
from the same major version. A newer Client or Worker may use functionality
that an older Server does not provide, so compatibility in that direction is
not guaranteed.

Internal refactoring and database schema changes do not require a major version
when existing persisted data is migrated forward automatically without loss.
Breaking an external contract requires a major version even when the code
change itself is small.

All artifacts produced by one release carry the same product version. This
policy applies beginning with `1.7.0`; earlier releases are not retroactively
reclassified.

The release workflow resolves versions from remote GitHub tags at the first
metadata step. Manual published releases and generated versions must be greater
than the latest remote `v*` SemVer tag before any expensive verification or
packaging job starts. Prefer leaving the manual version field empty and
selecting `patch`, `minor`, or `major`; the workflow generates the next SemVer
version from the latest stable remote tag.

## Verification

Default to the cheapest check that covers the changed boundary:

```bash
./gradlew :<module>:assemble -q
./gradlew :<module>:compileKotlin<Target> -q
./gradlew :<module>:test --tests '<focused test>' -q
```

Use a full build for cross-cutting, build-system, packaging, or release changes:

```bash
./gradlew build -q
```

Android application artifacts are owned by the launcher modules:

```bash
./gradlew :presentation-android:assemble -q
./gradlew :mobile-worker-android:assemble -q
```
