# Gromozeka Project Agent Context

This document describes project-specific agent architecture, coordination patterns, and development workflow for Gromozeka.

## Project Overview

**Gromozeka** is a multi-armed AI assistant built with Kotlin Multiplatform and Compose Desktop. It features:
- Hybrid memory architecture (knowledge graph + vector store)
- Multi-agent collaboration system
- Spring AI integration with Claude Code CLI
- Clean Architecture with DDD patterns

## Architecture Approach

Gromozeka follows **Clean Architecture** with strict layer separation:

```
Infrastructure → Domain
Application    → Domain
Presentation   → Domain + Application
```

**Key principles:**
- Dependencies point inward only
- Domain layer is pure Kotlin (no framework dependencies)
- Each layer has dedicated agents
- Agents communicate through typed interfaces and KDoc (Code-as-Contract)

## Code-as-Contract Coordination

### Pattern 1: Interface First
1. Architect designs interface with complete KDoc
   - KDoc must be **sufficient** to understand WHAT to implement
   - Include: operation semantics, parameters, return values, exceptions
   - **Avoid:** Implementation details, step-by-step HOW
   - Think: **specification, not tutorial**
2. Implementation agent reads interface from filesystem
3. Implements based on KDoc specification
4. Compiler validates contract adherence

**Why KDoc completeness matters:**
Domain contracts drive development. Complete KDoc enables implementation agents to work independently without chat clarifications.

**Key insight:** Architecture leverages Clean Architecture to enable **AI-driven development management**. Domain layer allows AI to control development without loading detailed implementation code.

### Pattern 2: Handoff via Filesystem
```
Architect writes:     domain/repository/XRepository.kt
                     domain/service/XService.kt
Repository Agent reads:  domain/repository/XRepository.kt
Repository Agent writes: infrastructure/db/persistence/ExposedXRepository.kt
Business Logic Agent reads: domain/service/XService.kt
Business Logic Agent writes: application/service/XServiceImpl.kt
```

Chat is available for clarifications, but domain contracts drive development.

### Pattern 3: Knowledge Graph Context
Before implementing, agent queries graph:
- "What repository patterns have we used?"
- "How did we handle pagination last time?"
- "What error handling approach for external APIs?"

**Key Principle:** Agents communicate **primarily** through typed code and comprehensive KDoc. Chat supplements for clarifications and coordination. The compiler validates integration.

## Technology Stack

**Core:**
- Kotlin Multiplatform
- Spring Framework (DI, configuration)
- Spring AI (LLM integrations)

**UI:**
- JetBrains Compose Desktop
- Material 3 components

**Data:**
- Exposed (SQL ORM)
- Neo4j (knowledge graph and vector store)

**AI:**
- Claude Code CLI (custom integration)
- Gemini (via Spring AI)
- Model Context Protocol (MCP)

## Multi-Agent Workflow Example

**Task:** "Add note storage feature"

**Step 1: Architect Agent**
Creates domain contracts:
```kotlin
// domain/model/Note.kt
data class Note(
    val id: Id,
    val content: String,
    val createdAt: Instant
)

// domain/repository/NoteRepository.kt
interface NoteRepository {
    suspend fun findById(id: Note.Id): Note?
    suspend fun save(note: Note): Note
}
```

**Step 2: Repository Agent**
Reads interface from filesystem, implements:
```kotlin
// infrastructure/db/persistence/ExposedNoteRepository.kt
@Service
class ExposedNoteRepository : NoteRepository {
    override suspend fun findById(id: Note.Id): Note? = transaction {
        // Implementation based on KDoc contract
    }
}
```

**Step 3: Business Logic Agent**
Reads interfaces, implements orchestration:
```kotlin
// application/service/NoteServiceImpl.kt
@Service
class NoteServiceImpl(
    private val noteRepository: NoteRepository
) : NoteService {
    override suspend fun createNote(content: String): Note {
        require(content.isNotBlank()) { "Content cannot be blank" }
        // Orchestration logic
    }
}
```

**Result:**
- Minimal coordination overhead (domain contracts are self-sufficient)
- Compiler validates all contracts
- Clean layer separation enforced by module dependencies
- Build succeeds - integration verified through compilation

## Verification Strategy

**Build verification command:**
```bash
./gradlew :presentation:build :presentation:jvmTest --tests ApplicationContextTest -q || \
  ./gradlew :presentation:build :presentation:jvmTest --tests ApplicationContextTest
```

**Quiet mode pattern:**
- Always try `-q` first (saves 80-90% tokens)
- Full output only on errors
- Verify both compilation AND Spring context

## Project-Specific Guidelines

### Practicality Over Elegance
- Working solutions over beautiful abstractions
- Simple implementations over clever patterns
- Maintainable code over theoretical purity

### Fail Fast - No Guessing on Errors
When errors occur, **NEVER** attempt recovery through guessing:
- Better to fail loudly than silently corrupt data
- Eloquent error message > wrong operation that looks correct

**Internal vs External:**
- Internal components: fail immediately on invalid state (`require()`, `check()`)
- External interfaces: defensive error handling (user input, network, APIs)

### Code Comments - Avoid Noise
- Clear naming over comments
- Comments for non-obvious business logic only
- Avoid "moved from X to Y", "TODO: refactor", obvious explanations
- Commit history explains WHY, code explains WHAT

### DDD Patterns We Use
- **Repository Pattern** - abstraction over data persistence
- **Value Objects** - typed wrappers for primitives (Thread.Id, Message.Id)
- **Domain Services** - business logic that doesn't fit entities
- **Immutable Entities** - data classes with `val` properties

We DON'T use (yet): Aggregate Roots, Bounded Contexts, Domain Events, Specification Pattern.
Keep architecture pragmatic. Add complexity only when needed.

## Remember

- **Code-as-Contract** - typed interfaces and KDoc drive development
- **Domain contracts are self-sufficient** - enable AI-driven development management
- **Chat supplements** - clarifications only, contracts specify
- **Strict layer boundaries** - each agent respects Clean Architecture
- **Compiler validates** - integration verified through compilation
- **Knowledge graph is memory** - shared organizational context
- **Parallel independent development** - enabled by Code-as-Contract
