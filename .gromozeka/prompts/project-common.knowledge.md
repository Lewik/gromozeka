# Project Common Rules for Gromozeka Development Agents

Project-wide rules for specialized development agents working on Gromozeka itself.

## Project Context

Gromozeka is a Kotlin Multiplatform + Compose Desktop multi-agent assistant that is also used to develop itself.

Current project goals:
- Keep the codebase maintainable and parallel-friendly
- Use typed code and KDoc as the main coordination mechanism
- Dogfood the current OpenAI subscription runtime path while keeping legacy integrations working

## Current Runtime Reality

This prompt should describe the project as it exists now, not as an old migration plan.

- Main chat dogfooding path: `OPEN_AI_SUBSCRIPTION`
- Dedicated OpenAI subscription backend lives in `:infrastructure-ai/openai-subscription`
- `:infrastructure-ai` still contains Spring AI-backed integrations for Anthropic, Gemini, embeddings, and auxiliary runtime pieces
- MCP servers, tools, memory extraction, search, and code tooling also live in `:infrastructure-ai`
- `:presentation` is both the desktop UI layer and the current application composition root / startup module

## Working Model

- Each specialist agent owns one module or one narrow cross-cutting concern
- Agents coordinate primarily through code, not chat
- Domain contracts in `:domain` are the main handoff mechanism
- The compiler and targeted build commands are the integration verifier

Stay in your lane unless the task is explicitly cross-cutting.

## Architecture Summary

Clean Architecture intent still matters, but follow current codebase truth when they differ.

Practical dependency rules:
- UI and viewmodel code should depend on domain and application abstractions
- Application orchestrates use cases and business workflows
- Infrastructure implements domain abstractions
- Domain stays as technology-light as practical
- `:presentation` currently also wires startup and therefore has direct module dependencies on infrastructure for bootstrapping

For full layer definitions and ownership boundaries, `architecture.knowledge.md` is the source of truth.

## Domain-First Workflow

If your role implements behavior outside `:domain`, treat domain contracts as the primary specification.

Contracts may live in:
- `domain/model/`
- `domain/repository/`
- `domain/service/`
- `domain/presentation/`
- `domain/tool/`

Recommended order:
1. Search domain specs or knowledge graph first
2. Read the exact interface, data class, tool contract, or KDoc
3. Implement in your own layer
4. Verify with a module-scoped build

Do not guess contract semantics from naming alone when the domain file already exists.

## Knowledge Graph Workflow

- Search first, read files second
- Use `unified_search` for concepts, patterns, and existing domain contracts
- Read files when you know the target symbol or are about to modify code
- Ask the user to re-index only after meaningful `:domain` changes

## Dependency Research Pattern

When API behavior matters, prefer code truth over assumptions.

- If the project already has a useful dependency mirror in repository-level `.sources/`, reuse it
- Module specialists may also use a module-local `.sources/` directory when that keeps research scoped to their lane
- Search dependency tests and examples before guessing
- Clone dependency sources only when needed for a real implementation question
- Treat `.sources/` inspection as research, not as a project change

## Repository Instances

Three working checkouts exist:

```text
gromozeka/
├── dev/      - primary development checkout, branch: main
├── beta/     - pre-release dogfood checkout, branch: beta
└── release/  - stable checkout, branch: release
```

Default workflow:
1. Make code changes in `dev/`
2. Stop after local edits and verification unless the user explicitly asked for git writes
3. When git writes are explicitly approved, commit and push the appropriate branch
4. Sync `beta/` or `release/` via git inside that checkout

Prefer git-based synchronization over manual file copying between checkouts.

Direct edits in `beta/` or `release/` are an exception and should happen only when the user explicitly asks for a local-only change there.

## Team Model

Other specialists exist for neighboring layers. Respect their ownership boundaries and use typed contracts to coordinate. Full roster lives in `agents-roster.knowledge.md`.

## Defaults

- Do not create tests unless the user explicitly asks
- Do not create extra markdown recap files
- Do not modify code outside your owned layer unless the task is explicitly cross-cutting
- Do not delete committed comments without user permission
- Verify your own module after code changes
