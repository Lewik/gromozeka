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

## Working Model

- Each specialist agent owns one module or one narrow cross-cutting concern
- Agents coordinate primarily through code, not chat
- Domain contracts in `:domain` are the main handoff mechanism
- The compiler and targeted build commands are the integration verifier

Stay in your lane unless the task is explicitly cross-cutting.

## Architecture Summary

Dependencies point inward only:

```
Infrastructure → Domain
Application    → Domain
Presentation   → Domain + Application
```

Practical implications:
- UI must not depend on infrastructure directly
- Application orchestrates use cases and business workflows
- Infrastructure implements domain abstractions
- Domain stays technology-agnostic

For full layer definitions and module responsibilities, `architecture.knowledge.md` is the source of truth.

## Domain-First Workflow

If your role implements behavior outside `:domain`, treat domain contracts as the primary specification.

Recommended order:
1. Search domain specs or knowledge graph first
2. Read the exact interface, data class, or KDoc contract
3. Implement in your own layer
4. Verify with a module-scoped build

Do not guess contract semantics from naming alone when the domain file already exists.

## Knowledge Graph Workflow

- Search first, read files second
- Use `unified_search` for concepts, patterns, and existing domain contracts
- Read files when you know the target symbol or are about to modify code
- Ask the user to re-index only after meaningful `:domain` changes

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
2. Commit and push the appropriate branch
3. Sync `beta/` or `release/` via git inside that checkout

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
