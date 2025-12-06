# Project Development Agents Roster

List of Gromozeka development agents. All agents work in parallel, each in their area of responsibility.

## Agent Roster

### Architect Agent
**Role:** Domain Designer  
**Module:** `:domain`
**Spring:** NO (pure Kotlin)
**Output:** `domain/model/`, `domain/repository/`  
**Key focus:** Technology-agnostic abstractions, comprehensive KDoc, immutable data classes

### Repository Agent
**Role:** Data Persistence Specialist  
**Module:** `:infrastructure-db`
**Spring:** YES (`@Service`, `@Repository`)
**Output:** `infrastructure/db/persistence/`, `infrastructure/db/vector/`, `infrastructure/db/graph/`  
**Key focus:** DDD Repository implementation (NOT Spring Data Repository), data access patterns

### Business Logic Agent
**Role:** Use Case Orchestrator  
**Module:** `:application`
**Spring:** YES (`@Service`)
**Output:** `application/service/`  
**Key focus:** Multi-repository coordination, transactional workflows, business invariants

### Spring AI Agent
**Role:** AI Integration Specialist  
**Module:** `:infrastructure-ai`
**Spring:** YES (`@Service`, `@Configuration`)
**Output:** `infrastructure/ai/springai/`, `infrastructure/ai/claudecode/`, `infrastructure/ai/mcp/`  
**Key focus:** Streaming responses, ChatModel implementations, MCP tools/servers

### UI Agent
**Role:** User Interface Developer  
**Module:** `:presentation`
**Spring:** YES (transitive, for DI and app startup)
**Output:** `presentation/ui/`, `presentation/viewmodel/`  
**Key focus:** Material 3 design, reactive StateFlow/SharedFlow, UX patterns

### Build/Release Agent
**Role:** Build Engineer  
**Module:** Cross-cutting
**Output:** Build artifacts, git tags, DMG/AppImage/MSI packages  
**Key focus:** Quiet mode verification, version management, GitHub releases
