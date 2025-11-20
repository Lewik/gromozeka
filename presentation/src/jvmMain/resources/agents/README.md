# Multi-Agent Development System

This directory contains system prompts for specialized development agents that collaborate through code-as-contract to build Gromozeka.

## Core Concept

**Communication via Code, Not Chat**

Agents communicate through typed Kotlin interfaces and comprehensive KDoc, not through chat messages. This eliminates coordination overhead and leverages the compiler for contract validation.

## Agent Roles

### 1. Architect Agent (`architect-agent.md`)
**Responsibility:** Design interfaces and domain models

**Output:**
- `domain/model/` - Data classes and entities
- `domain/repository/` - Repository interfaces (DDD pattern)

**Key Trait:** Creates comprehensive KDoc explaining WHAT and WHY

### 2. Repository Agent (`repository-agent.md`)
**Responsibility:** Implement Repository interfaces

**Input:** Reads `domain/repository/` interfaces
**Output:** `infrastructure/db/persistence/` implementations

**Key Trait:** Pure persistence logic, no business rules

**CRITICAL:** Distinguishes between DDD Repository (public interface) and Spring Data Repository (private ORM tool)

### 3. Business Logic Agent (`business-logic-agent.md`)
**Responsibility:** Implement service interfaces

**Input:** Reads `domain/repository/` interfaces
**Output:** `application/service/` implementations

**Key Trait:** Orchestrates repositories, enforces business rules

### 4. Infrastructure Agent (`infrastructure-agent.md`)
**Responsibility:** Integrate external systems

**Output:**
- `infrastructure/ai/` - AI provider integrations
- `infrastructure/vector/` - Qdrant operations
- `infrastructure/graph/` - Neo4j operations
- `infrastructure/mcp/` - MCP servers/tools

**Key Trait:** Handles external system complexity, provides clean internal APIs

### 5. UI Agent (`ui-agent.md`)
**Responsibility:** Build Compose Desktop UI

**Input:** Reads `domain/model/` and `domain/repository/` interfaces
**Output:**
- `presentation/ui/` - Compose components
- `presentation/viewmodel/` - ViewModels

**Key Trait:** Focuses on UX, delegates all logic to ViewModels

## Communication Protocol

### Code-as-Contract

```kotlin
// Architect creates this:
package com.gromozeka.bot.domain.repository

/**
 * Repository for conversation threads.
 *
 * Implementation should:
 * - Use database transactions for create/update/delete
 * - Handle constraint violations by throwing DuplicateThreadException
 * - Return null from findById if thread doesn't exist
 */
interface ThreadRepository {
    suspend fun findById(id: Thread.Id): Thread?
    suspend fun save(thread: Thread): Thread
}

// Repository Agent reads this and implements:
package com.gromozeka.bot.infrastructure.db.persistence

@Service
class ExposedThreadRepository : ThreadRepository {
    override suspend fun findById(id: Thread.Id): Thread? = transaction {
        // Implementation follows contract exactly
    }
}
```

### Knowledge Graph Integration

All agents save key decisions to knowledge graph after completing work:

```kotlin
build_memory_from_text(
  content = """
  Implemented ThreadRepository using Exposed ORM.
  Decision: Used UUID for primary keys (distributed system ready).
  Added index on createdAt for recent threads query performance.
  """
)
```

Next agent retrieves this context:
```kotlin
search_memory("repository implementation patterns")
```

## Architecture Enforcement

### Layer Boundaries

```
presentation/  →  domain/  ←  application/  ←  infrastructure/
    (UI)         (contracts)   (business)      (external systems)
```

**Dependency Rules:**
- Presentation depends on Domain
- Application depends on Domain
- Infrastructure depends on Domain
- Domain depends on NOTHING (pure interfaces)

### Physical Enforcement

Agents have restricted file access:
- **Architect:** Can only write to `domain/`
- **Repository Agent:** Can only write to `infrastructure/db/`
- **Business Logic:** Can only write to `application/`
- **UI:** Can only write to `presentation/`

This physically prevents layer violations.

## Workflow Example

**Task:** "Add note storage feature"

### Step 1: Architect Agent

```kotlin
// domain/model/Note.kt
data class Note(
    val id: Id,
    val content: String,
    val createdAt: Instant
) {
    @JvmInline
    value class Id(val value: String)
}

// domain/repository/NoteRepository.kt
interface NoteRepository {
    suspend fun findById(id: Note.Id): Note?
    suspend fun save(note: Note): Note
    suspend fun findAll(): List<Note>
}
```

Saves to graph: "Designed note storage with simple CRUD operations"

### Step 2: Repository Agent

Reads `domain/repository/NoteRepository.kt`, implements:

```kotlin
// infrastructure/db/persistence/ExposedNoteRepository.kt
@Service
class ExposedNoteRepository : NoteRepository {
    override suspend fun findById(id: Note.Id): Note? = transaction {
        Notes.selectAll().where { Notes.id eq UUID.fromString(id.value) }
            .singleOrNull()?.let { rowToNote(it) }
    }
    // ... other methods
}
```

Saves to graph: "Implemented NoteRepository with Exposed, added table definition"

### Step 3: Business Logic Agent

Reads interfaces, implements:

```kotlin
// application/service/NoteServiceImpl.kt
@Service
class NoteServiceImpl(
    private val noteRepository: NoteRepository
) : NoteService {
    override suspend fun createNote(content: String): Note {
        require(content.isNotBlank()) { "Content cannot be blank" }

        val note = Note(
            id = UUID.randomUUID().toString(),
            content = content,
            createdAt = Clock.System.now()
        )
        return noteRepository.save(note)
    }
}
```

Saves to graph: "Implemented NoteService with validation, generates UUID for new notes"

### Step 4: UI Agent

Reads interfaces, implements:

```kotlin
// presentation/viewmodel/NoteListViewModel.kt
class NoteListViewModel(
    private val noteService: NoteService
) : ViewModel() {
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes = _notes.asStateFlow()

    fun loadNotes() {
        viewModelScope.launch {
            _notes.value = noteService.getAllNotes()
        }
    }
}

// presentation/ui/NoteListScreen.kt
@Composable
fun NoteListScreen(viewModel: NoteListViewModel) {
    val notes by viewModel.notes.collectAsState()

    LazyColumn {
        items(notes) { note ->
            NoteItem(note)
        }
    }
}
```

Saves to graph: "Implemented note list UI with ViewModel pattern, auto-loads on init"

### Result

- ✅ Zero coordination overhead (no chat between agents)
- ✅ Compiler validates all contracts
- ✅ Clean layer separation
- ✅ Knowledge graph captures decisions
- ✅ Build succeeds

## Benefits vs Traditional Multi-Agent

### Traditional (Chat-Based) Multi-Agent Problems:
- 15x cost overhead (coordination rounds)
- 60-80% failure rate in production
- Error cascading (ambiguous communication)
- Context explosion

### Code-as-Contract Approach Benefits:
- **1x cost** (agents work independently on their layer)
- **Compiler catches errors** before runtime
- **Zero ambiguity** (typed interfaces)
- **Scalable context** (only read relevant interfaces)

## Common Patterns

### Pattern 1: Interface First

1. Architect designs interface with complete KDoc
2. Implementation agent reads interface
3. Implements exactly as specified
4. Compiler validates contract adherence

### Pattern 2: Handoff via Filesystem

Agents don't pass messages. They write code to filesystem:

```
Architect writes:     domain/repository/XRepository.kt
Repository Agent reads:  domain/repository/XRepository.kt
Repository Agent writes: infrastructure/db/persistence/ExposedXRepository.kt
```

### Pattern 3: Knowledge Graph Context

Before implementing, agent queries graph:

```
"What repository patterns have we used?"
"How did we handle pagination last time?"
"What error handling approach for external APIs?"
```

## Meta-Awareness

Each agent knows other agents exist but doesn't interact directly:

**Repository Agent prompt includes:**
> "Business Logic Agent handles validation and business rules. You implement pure persistence. Stay in your lane."

This prevents scope creep and layer violations.

## Getting Started

### For New Features

1. Start with Architect Agent (create interfaces)
2. Then layer-specific agents (implement in parallel)
3. UI Agent last (depends on all layers)

## Future: Orchestrator

**Planned:** Orchestrator agent to coordinate workflow automatically

```
User: "Add note storage"
  ↓
Orchestrator:
  1. Invokes Architect Agent
  2. Waits for domain/ files
  3. Invokes Repository + Business Logic agents in parallel
  4. Waits for implementations
  5. Invokes UI Agent
  6. Verifies build
  7. Reports completion
```

## References

- **SEMAP Protocol:** https://arxiv.org/html/2510.12120
- **Multi-Agent Failures Study:** Research shows 60-80% failure rate for chat-based coordination
- **Code-as-Contract:** Inspired by Design by Contract (DbC) principles

## File Structure

```
docs/agents/
├── agent-builder-v2.md          # Meta-agent for creating agents
└── gromozeka/
    ├── README.md                 # This file
    ├── architect-agent.md        # Domain interfaces and model design
    ├── architecture.md           # Gromozeka Clean Architecture
    ├── shared-base.md            # Common rules for all Gromozeka agents
    ├── repository-agent.md       # DDD Repository implementations
    ├── business-logic-agent.md   # Service implementations
    ├── infrastructure-agent.md   # External integrations
    ├── spring-ai-agent.md        # Spring AI & MCP integrations
    └── ui-agent.md               # Compose Desktop UI
```

Each agent prompt is self-contained and includes:
- Role and responsibilities
- Scope (read/write access)
- Implementation patterns
- Examples (good vs bad)
- Workflow steps
- Knowledge graph integration

## Key Principle

> "Agents communicate through typed code and comprehensive documentation, not through chat messages. The compiler is the coordinator."
