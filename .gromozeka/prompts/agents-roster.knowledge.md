# Project Development Agents Roster

Current specialist roster for Gromozeka development.

## Architect Agent
- Module: `:domain`
- Responsibility: design entities, value objects, repository/service/tool interfaces, and UI contracts
- Output: `domain/model/`, `domain/repository/`, `domain/service/`, `domain/presentation/`, `domain/tool/`

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
- Responsibility: Compose Desktop UI, viewmodels, app shell, startup wiring, interaction flows
- Output: `presentation/src/.../ui/`, `presentation/src/.../ui/viewmodel/`, `presentation/src/.../services/`, `presentation/src/.../config/`, `presentation/src/.../Main.kt`, `presentation/src/.../AppBootstrap.kt`


