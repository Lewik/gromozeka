# Project Development Agents Directory

Перечень агентов разработки Gromozeka. Все агенты работают параллельно, каждый в своей зоне ответственности.

## Agent Roster

### Architect Agent
**Role:** Domain Designer  
**Responsibility:** Design domain interfaces, entities, repositories  
**Output:** `domain/model/`, `domain/repository/`  
**Key focus:** Technology-agnostic abstractions, comprehensive KDoc, immutable data classes

### Repository Agent
**Role:** Data Persistence Specialist  
**Responsibility:** Implement Repository interfaces, database access (SQL, vector, graph)  
**Output:** `infrastructure/db/persistence/`, `infrastructure/db/vector/`, `infrastructure/db/graph/`  
**Key focus:** DDD Repository implementation (NOT Spring Data Repository), data access patterns

### Business Logic Agent
**Role:** Use Case Orchestrator  
**Responsibility:** Implement service interfaces, orchestrate repositories, enforce business rules  
**Output:** `application/service/`  
**Key focus:** Multi-repository coordination, transactional workflows, business invariants

### Spring AI Agent
**Role:** AI Integration Specialist  
**Responsibility:** Spring AI, Claude Code CLI, MCP integrations  
**Output:** `infrastructure/ai/springai/`, `infrastructure/ai/claudecode/`, `infrastructure/ai/mcp/`  
**Key focus:** Streaming responses, ChatModel implementations, MCP tools/servers

### UI Agent
**Role:** User Interface Developer  
**Responsibility:** Compose Desktop UI, ViewModels, state management  
**Output:** `presentation/ui/`, `presentation/viewmodel/`  
**Key focus:** Material 3 design, reactive StateFlow/SharedFlow, UX patterns

### Build/Release Agent
**Role:** Build Engineer  
**Responsibility:** Compilation, versioning, packaging, multi-platform releases  
**Output:** Build artifacts, git tags, DMG/AppImage/MSI packages  
**Key focus:** Quiet mode verification, version management, GitHub releases

## Coordination Model

**Code-as-Contract:**
- **Primary communication:** Typed interfaces with comprehensive KDoc
- **Secondary communication:** Chat for clarifications and coordination
- **Key principle:** Domain contracts drive development, not chat instructions

**Why this matters:**
- Domain layer enables **AI-driven development management**
- AI controls development from domain without loading detailed implementation code
- Clean Architecture approach: lightweight domain contracts drive heavyweight implementation
- Complete KDoc enables parallel independent development
- Chat supplements, contracts specify

**Layer boundaries:**
```
Infrastructure → Domain
Application    → Domain
Presentation   → Domain + Application
```

Dependencies point inward only. Each agent respects strict layer separation.
