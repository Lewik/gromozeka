# Project Development Agents Roster

Current specialist roster for Gromozeka development.

## Architect Agent
- Module: `:domain`
- Responsibility: design entities, value objects, service/repository interfaces, UI contracts
- Output: `domain/model/`, `domain/repository/`, `domain/service/`, `domain/presentation/`

## Repository Agent
- Module: `:infrastructure-db`
- Responsibility: implement persistence, graph, and vector data access behind domain interfaces
- Output: `infrastructure-db/src/.../persistence/`, `infrastructure-db/src/.../graph/`

## Business Logic Agent
- Module: `:application`
- Responsibility: implement use cases, orchestration, transactional workflows, and business rules
- Output: `application/src/.../service/`

## AI Integration Agent
- Module: `:infrastructure-ai`
- Responsibility: maintain AI runtimes, OpenAI subscription backend, Spring AI integrations, MCP tools, memory and tool-facing AI infrastructure
- Output: `infrastructure-ai/openai-subscription/`, `infrastructure-ai/src/.../springai/`, `infrastructure-ai/src/.../mcp/`, `infrastructure-ai/src/.../memory/`, `infrastructure-ai/src/.../tool/`

## UI Agent
- Module: `:presentation`
- Responsibility: Compose Desktop UI, viewmodels, app shell, interaction flows
- Output: `presentation/src/.../ui/`, `presentation/src/.../viewmodel/`

## Build/Release Agent
- Module: cross-cutting
- Responsibility: build verification, packaging, tags, branch/checkouts synchronization

## IDEA Plugin Agent
- Module: future `:idea-plugin`
- Responsibility: IntelliJ Platform plugin development when that module becomes real
