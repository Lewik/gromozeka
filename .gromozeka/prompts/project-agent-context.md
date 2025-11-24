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
- Domain layer is pure Kotlin (no framework dependencies, sdk-like or common dependencies allowed)
- Each layer has dedicated agents
- Agents communicate mainly through typed interfaces and KDoc (Code-as-Contract)

## Code-as-Contract Patterns

**Pattern 1: Interface First**
1. Architect designs interface with complete KDoc
   - KDoc must be **sufficient** to understand WHAT to implement (purpose, behavior, contracts)
   - Include: operation semantics, parameters, return values, exceptions, side effects
   - **Avoid:** Implementation details, code snippets, step-by-step HOW
   - Think: **specification, not tutorial**
2. Implementation agent reads interface from filesystem
3. Implements based on KDoc specification
4. Compiler validates contract adherence

**Why KDoc completeness matters:**
Domain contracts drive development. Complete KDoc enables implementation agents to work independently without chat clarifications. 

**Key insight:** Architecture leverages Clean Architecture approach to enable **AI-driven development management**. Domain layer allows AI to control development without loading detailed implementation code. Complete KDoc serves as lightweight control layer for autonomous implementation across modules.

**Pattern 2: Handoff via Filesystem**
**Architect** writes domain contracts to filesystem.  
**Implementation agents** read contracts and implement accordingly.

```
Architect writes:     domain/repository/XRepository.kt
                     domain/service/XService.kt
Repository Agent reads:  domain/repository/XRepository.kt
Repository Agent writes: infrastructure/db/persistence/ExposedXRepository.kt
Business Logic Agent reads: domain/service/XService.kt
Business Logic Agent writes: application/service/XServiceImpl.kt
```

Chat is available for clarifications, but domain contracts drive development.

**Pattern 3: Knowledge Graph Context**
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
- Neo4j (knowledge graph)
- Qdrant (vector store)

**AI:**
- Claude Code CLI (custom integration)
- Gemini (via Spring AI)
- Model Context Protocol (MCP)

## Coordination Between Agents

### Multi-Agent Workflow Example

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
        Notes.selectAll().where { Notes.id eq UUID.fromString(id.value) }
            .singleOrNull()?.let { rowToNote(it) }
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
        val note = Note(...)
        return noteRepository.save(note)
    }
}
```

**Step 4: UI Agent**
Builds UI consuming services:
```kotlin
// presentation/viewmodel/NoteListViewModel.kt
class NoteListViewModel(
    private val noteService: NoteService
) : ViewModel() {
    fun loadNotes() {
        viewModelScope.launch {
            _notes.value = noteService.getAllNotes()
        }
    }
}
```

**Result:**
- ✅ Minimal coordination overhead (domain contracts are self-sufficient)
- ✅ Compiler validates all contracts
- ✅ Clean layer separation enforced by module dependencies
- ✅ Build succeeds - integration verified through compilation

## Project-Specific Guidelines

### Practicality Over Elegance
- Working solutions over beautiful abstractions
- Simple implementations over clever patterns
- Maintainable code over theoretical purity

**Example implementations prioritize:**
- Straightforward Repository patterns
- Clear service orchestration
- Self-documenting code structure

### Fail Fast - No Guessing on Errors
When errors occur, **NEVER** attempt recovery through guessing or assumptions:
- Guessing introduces incorrect system state
- Better to fail loudly than silently corrupt data
- Eloquent error message > wrong operation that looks correct

**Example:**
```kotlin
// Fail fast on invalid state
require(content.isNotBlank()) { "Content cannot be blank" }

// Not: return Note(id, "", ...) // Creates invalid state
```

**Internal vs External:**
- Internal components: fail immediately on invalid state (`require()`, `check()`)
- External interfaces: defensive error handling (user input, network, APIs)

### Code Comments - Avoid Noise
Code examples in this project demonstrate self-documenting style:
- Clear naming over comments
- Comments for non-obvious business logic only
- Avoid "moved from X to Y", "TODO: refactor", obvious explanations
- Commit history explains WHY, code explains WHAT

### DDD Patterns We Use and DON'T Use

**What we USE:**
- **Repository Pattern** - abstraction over data persistence
- **Value Objects** - typed wrappers for primitives (Thread.Id, Message.Id)
- **Domain Services** - business logic that doesn't fit entities
- **Immutable Entities** - data classes with `val` properties

**What we DON'T USE (yet):**
- **Aggregate Roots** - we don't enforce aggregate boundaries
- **Bounded Contexts** - single context for now, no context mapping
- **Domain Events** - no event sourcing or domain event patterns
- **Specification Pattern** - queries are simple, no need for specifications

**Why this matters:**
Keep architecture pragmatic. We add complexity only when needed. These patterns can be introduced later if requirements demand them.

### DDD Repository vs Spring Data Repository

**CRITICAL DISTINCTION:**

**1. DDD Repository Pattern (Domain-Driven Design)**
- **What:** Architectural pattern, domain abstraction
- **Where:** Interface in `domain/repository/` (PUBLIC)
- **Created by:** Architect Agent
- **Implemented by:** Repository Agent

**2. Spring Data Repository (Spring Framework)**
- **What:** ORM technology/library from Spring
- **Where:** `infrastructure/db/persistence/` (PRIVATE/INTERNAL)
- **Used by:** Repository Agent internally

**How they relate:**
```
Domain Layer (PUBLIC):
  interface ThreadRepository  ← DDD Repository Pattern
     ↓
Infrastructure Layer:
  @Service
  class ExposedThreadRepository : ThreadRepository  ← Your implementation
     ↓ uses internally (PRIVATE)
  @Repository
  internal interface ThreadJpaRepository : JpaRepository<...>  ← Spring Data
```

### Verification Strategy

**Build verification command:**
```bash
./gradlew :presentation:build :presentation:jvmTest --tests ApplicationContextTest -q || \
  ./gradlew :presentation:build :presentation:jvmTest --tests ApplicationContextTest
```

**Quiet mode pattern:**
- Always try `-q` first (saves 80-90% tokens)
- Full output only on errors
- Verify both compilation AND Spring context

## Remember

- **Code-as-Contract** - typed interfaces and KDoc drive development
- **Domain contracts are self-sufficient** - enable AI-driven development management
- **Chat supplements** - clarifications only, contracts specify
- **Strict layer boundaries** - each agent respects Clean Architecture
- **Compiler validates** - integration verified through compilation
- **Knowledge graph is memory** - shared organizational context
- **Parallel independent development** - enabled by Code-as-Contract
