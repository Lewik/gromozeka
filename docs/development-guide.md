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
- `:remote-protocol` and `:remote-client` define the client-to-Server boundary.
- `:server` is the control plane and web endpoint.
- `:worker` is a trusted standalone executor.
- `:presentation` contains the Compose clients and presentation state.

Dependencies should point toward domain contracts where practical. Framework,
storage, provider, and transport details stay in their infrastructure modules.
Presentation bootstrap may wire infrastructure, but ordinary UI code should use
domain and application abstractions.

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

Standalone Server and Worker archives stay thin. Their launchers prefer an
explicit or compatible system Java runtime and otherwise cache a pinned,
checksum-verified official Temurin JRE under the Gromozeka home directory. The
Worker includes the pinned Gromozeka Browser MCP package; it resolves Node in
the same way and downloads the official pinned runtime only when Browser Use is
first started. Docker images remain self-contained and do not download runtimes
at startup. Claude Code and browser binaries are never bundled.

## Runtime Language

- A **Project** is a logical working context.
- A **Conversation** belongs to a Project and is not bound to a Workspace.
- An **Agent** is a server-managed model, prompt, and behavior configuration. It
  is not an executor.
- A **Worker** is a named execution process. The Server is not a Worker.
- A **Workspace** is a logical Project resource. The only current kind is a
  filesystem tree.
- A **Workspace Mount** records that one Worker sees one Workspace at a
  Worker-local root path.

Worker-scoped operations select an exact Worker. Workspace-scoped operations,
including shell, filesystem, and Git tools, select an exact Workspace Mount.
The Server does not inspect a Worker's filesystem, guess a target, reassign a
call, or retry work automatically.

Conversation turns and memory pipelines always run on the Server. A Worker can
execute configured tools and finite AI request-response operations, but it does
not own conversation or memory orchestration.

Every AI connection has an exact execution target: the Server or one named
Worker. Finite LLM calls, embeddings, speech transcription, and speech synthesis
use that target. Realtime and long-lived streaming AI sessions are Server-only.
Claude Code is the exception to general target selection: its connection always
targets a Worker where Claude Code is separately installed and authenticated.
An unavailable or incompatible target fails explicitly; Gromozeka does not fall
back to another Worker or to the Server and does not automatically retry an
operation whose outcome may be unknown.

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

Desktop actions are never retried or reassigned. If a timeout or disconnect
happens after dispatch, the outcome is reported as unknown and the model must
observe again before deciding what to do. Cancelling the turn sends a
request-scoped cancellation signal through the Worker Gateway; the backend
checks it between actions and releases any pressed keys or mouse buttons in a
`finally` block. A Gateway disconnect cancels every request still executing on
that connection.

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

The macOS application bundles the Worker dependencies, Browser MCP, and a
second `jpackage` launcher into the same DMG and shared Java runtime. The Client
owns the visible menu-bar item and enrollment UI; the Local Worker remains a
separate hidden LaunchAgent process behind the standard Worker Gateway. Closing
the Client window hides it, while an explicit application quit stops the
managed Local Worker. The stable helper copied under Application Support owns
macOS Screen Recording, Accessibility, and microphone consent across app
updates. Standalone Worker packages use the same helper protocol.

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
| Session-addressed Worker control | `:server`, `:remote-protocol`, and `:worker` |
| Compose UI and presentation state | `:presentation` |
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
- Treat Agent Skills as imported project-scoped packages. Update them by
  importing the package from disk again; do not edit package files in the
  runtime catalog UI.

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

## Verification

Default to the cheapest check that covers the changed boundary:

```bash
./gradlew :<module>:assemble -q
./gradlew :<module>:compileKotlin<Target> -q
./gradlew :<module>:test --tests '<focused test>' -q
```

Use a full build for cross-cutting, build-system, packaging, or release changes:

```bash
./gradlew :presentation:build :server:test -q
```
