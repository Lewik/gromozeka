# Gromozeka Development Guide

This document is the repository-level entry point for development without a
running Gromozeka instance. Runtime Agent definitions and project Prompt
fragments are managed by the central Server; repository files are
documentation, not a second agent configuration source.

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
- `:infrastructure-runtime` contains RabbitMQ runtime transport.
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
| AI providers, MCP, memory, tools | `:infrastructure-ai` |
| Runtime transport | `:infrastructure-runtime` |
| Compose UI and presentation state | `:presentation` |
| Server endpoints and composition | `:server` |
| Worker process and local execution | `:worker` |

Repository dependency sources may be cloned into `.sources/` when exact
third-party behavior matters. They are research material and stay gitignored.

## Agent And Prompt Design

Runtime Agents and Prompts are Server-managed data. Builtin definitions are
application blueprints that can be copied into a Project; repository markdown
is not read as live runtime configuration.

- Keep stable cross-project behavior in builtin prompts.
- Keep project-specific behavior in project-scoped Prompts and Agent
  definitions stored by the Server.
- Keep each prompt focused on one class of information instead of repeating
  mutable facts across a stack.
- Put dynamic execution environment data in the runtime environment context,
  not in static prompt definitions.
- Change prompts in response to observed behavior and validate the assembled
  stack rather than only checking individual fragments.

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

Use the smallest relevant build first:

```bash
./gradlew :domain:compileKotlinMetadata -q
./gradlew :application:build -q
./gradlew :infrastructure-db:build -q
./gradlew :infrastructure-ai:build -q
./gradlew :presentation:build -q
./gradlew :server:test -q
```

After a cross-cutting change, run:

```bash
./gradlew :presentation:build :server:test -q
```
