# Domain Architecture Summary

**Last Updated:** 2025-11-27

---

## Overview

This directory contains Architectural Decision Records (ADR) documenting WHY decisions were made for Gromozeka domain layer architecture.

**Key principle:** Domain specifications control development through interfaces, type safety, and compiler enforcement.

---

## ADR Index

### Core Patterns

- **[ADR 001: Repository Pattern](001-repository-pattern.md)**
  - DDD repository pattern for data access abstraction
  - Separation: domain interfaces → infrastructure implementations
  - Used by: ThreadRepository, MessageRepository, PromptRepository

- **[ADR 002: Value Objects for IDs](002-value-objects-for-ids.md)**
  - Type-safe IDs using `@JvmInline value class`
  - Prevents ID confusion (ThreadId vs MessageId)
  - Compiler enforces correctness

- **[ADR 003: Internal Reasoning Instructions](003-internal-reasoning-instructions.md)**
  - How AI reasoning should be structured
  - Guidelines for internal thought processes
  - Communication patterns

### Spring AI Agent Architecture

- **[ADR 004: Spring AI Agent Architecture](004-spring-ai-agent-architecture.md)**
  - WHY Spring AI framework chosen
  - Tool architecture pattern (domain specs → infrastructure impl)
  - Delegation to domain services pattern
  - Tool registration and MCP integration
  - **Key benefit:** Specifications control development through compiler enforcement

- **[ADR 005: Tool Specification Pattern](005-tool-specification-pattern.md)**
  - How to design tool interfaces as complete specifications
  - Type safety through request classes
  - KDoc requirements for specifications
  - Evolution and backwards compatibility strategies
  - **Key benefit:** Implementation agents have everything needed from domain specs

---

## Domain Layer Structure

```
domain/src/
├── commonMain/kotlin/com/gromozeka/domain/
│   ├── model/              # Entities, Value Objects
│   ├── repository/         # Data access specifications (DDD pattern)
│   ├── service/            # Business operation specifications
│   └── presentation/       # UI contract specifications
│
└── jvmMain/kotlin/com/gromozeka/domain/
    └── tool/               # Tool specifications (MCP/Spring AI)
        ├── Tool.kt         # Base interface
        ├── filesystem/     # File system tools (4)
        ├── memory/         # Knowledge graph tools (6)
        └── web/            # Web search tools (3)
```

---

## Domain Services

### Core Services

- **KnowledgeGraphService** - Knowledge graph operations, entity/relationship management
- **VectorMemoryService** - Semantic search, vector embeddings
- **ConversationSearchService** - Hybrid search (keyword + semantic) across conversations
- **FileSystemService** - File read/write/edit operations
- **CommandExecutionService** - Shell command execution
- **WebSearchService** - Brave Search, Jina Reader integration
- **SpeechToTextService** - Audio transcription (OpenAI Whisper)
- **TextToSpeechService** - Speech synthesis
- **AudioController** - Audio playback control
- **SettingsProvider** - Application configuration access
- **PromptPersistenceService** - Prompt template storage
- **ImportedPromptsRegistry** - External prompt management

### Provider Interfaces

- **ChatModelProvider** - LLM client access (Claude, Gemini, OpenAI)
- **McpToolProvider** - MCP server lifecycle management

---

## Tool Architecture (13 Tools)

### File System Tools (4)
- `GrzReadFileTool` → FileSystemService.readFile()
- `GrzWriteFileTool` → FileSystemService.writeFile()
- `GrzEditFileTool` → FileSystemService.editFile()
- `GrzExecuteCommandTool` → CommandExecutionService.execute()

### Memory Tools (6)
- `AddMemoryLinkTool` → MemoryManagementService.addFactDirectly()
- `BuildMemoryFromTextTool` → KnowledgeGraphService.extractAndSaveToGraph()
- `GetMemoryObjectTool` → MemoryManagementService.getMemoryObject()
- `UpdateMemoryObjectTool` → MemoryManagementService.updateMemoryObject()
- `InvalidateMemoryLinkTool` → MemoryManagementService.invalidateFact()
- `DeleteMemoryObjectTool` → MemoryManagementService.deleteMemoryObject()

### Web Tools (3)
- `BraveWebSearchTool` → WebSearchService.webSearch()
- `BraveLocalSearchTool` → WebSearchService.localSearch()
- `JinaReadUrlTool` → WebSearchService.readUrl()

---

## Specification Quality Standards

**Domain service ready when:**
- ✅ Full KDoc for interface
- ✅ Each method documented (WHAT, params, returns, errors, side effects)
- ✅ Examples (for MCP tools: JSON usage examples)
- ✅ Transactionality specified where applicable
- ✅ Relationship to infrastructure through @see

**Domain tool ready when:**
- ✅ Interface extends Tool<TRequest, TResponse>
- ✅ Request data class defined with all properties documented
- ✅ Tool exposure (MCP tool name) specified
- ✅ Full KDoc with JSON usage examples
- ✅ All error cases documented
- ✅ Relationship to domain services specified
- ✅ Relationship to infrastructure impl through @see

**Infrastructure ready when:**
- ✅ Implementation implements domain interface
- ✅ Documentation minimal (reference to domain spec)
- ✅ Code compiles: `./gradlew :infrastructure-ai:compileKotlinJvm -q`

---

## Core Principles Applied

### 1. Specifications Through Code
- Domain interfaces = complete specifications
- KDoc = only documentation needed
- Compiler enforces contracts (signature changes break implementations)

### 2. Type Safety
- Value classes for IDs (`ThreadId`, `MessageId`)
- Request/response classes for tools
- Nullable types explicit (`Entity?` vs `Entity`)
- Sealed classes for state variants

### 3. Technology Independence
- Domain: pure Kotlin, no framework dependencies
- Infrastructure: Spring AI, annotations, specific implementations
- Can swap frameworks without touching domain

### 4. Parallel Work Enablement
- Architect writes domain specs first
- All agents start simultaneously
- UI, Application, Infrastructure agents read same specs
- Integration verified through compilation

### 5. Single Source of Truth
- Domain interface = complete specification
- Infrastructure implementation = minimal documentation
- Changes documented once
- No documentation drift

---

## Verification

**Build commands:**
```bash
# Domain layer (pure Kotlin)
./gradlew :domain:compileKotlinJvm -q

# Infrastructure AI (Spring AI integration)
./gradlew :infrastructure-ai:compileKotlinJvm -q

# Full project
./gradlew build -q
```

**Last verification:** 2025-11-27 ✅ All modules compile successfully

---

## Related Documentation

- **Project Common Rules** - `.claude/prompts/system/architect-agent.md`
- **Tool Implementations** - `infrastructure-ai/src/jvmMain/kotlin/com/gromozeka/infrastructure/ai/tool/`
- **Service Implementations** - `application/src/`, `infrastructure-ai/src/`
- **Source Investigation** - `.sources/spring-ai/`, `.sources/exposed/`

---

## Migration Notes

**When adding new tool:**
1. Architect creates domain interface + request class (follow ADR 005)
2. Infrastructure Agent creates *Impl class with @Service
3. Spring auto-discovers and registers
4. Verify: `./gradlew :infrastructure-ai:compileKotlinJvm -q`

**When changing existing tool:**
1. Architect updates domain interface (signature or KDoc)
2. Signature change → infrastructure build breaks → forced update
3. KDoc change → infrastructure should review and update if needed
4. Verify compilation after changes

---

## References

- Spring AI: https://docs.spring.io/spring-ai/reference/
- MCP Protocol: https://modelcontextprotocol.io/
- Kotlin Multiplatform: https://kotlinlang.org/docs/multiplatform.html
- DDD Patterns: Domain-Driven Design by Eric Evans
