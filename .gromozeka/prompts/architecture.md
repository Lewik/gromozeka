# Gromozeka Clean Architecture

This document defines the Clean Architecture structure for Gromozeka project. All development agents must follow this architecture.

## Overview

Gromozeka follows Clean Architecture principles with clear layer separation and dependency rules.

**Core principle:** Dependencies point inward only.

```
Infrastructure → Domain
Application    → Domain
Presentation   → Domain + Application
```

## Layers

### 1. Domain Layer

**Module:** `:domain`
**Agent:** Architect Agent
**Spring:** NO (pure Kotlin)

**Responsibilities:**
- Define Entities and Value Objects
- Define Repository interfaces (for all data access)
- Define Domain Service interfaces
- Own complete application model

**What lives here:**
```
domain/
  ├── model/           - Entities, Value Objects
  └── repository/      - Repository interfaces, Domain Service interfaces
```

**Key principle:** Domain has NO dependencies. Pure business logic and contracts.

**Pattern:**
- Entities as immutable data classes with nested value class IDs
- Repository interfaces with suspend functions for async operations
- No framework annotations, pure Kotlin

**See Architect Agent for concrete examples.**

**Important:** Architect creates **Repository** interfaces in `domain/repository/`, NOT in `domain/service/`. This follows DDD Repository Pattern.

**Note on Spring Data Repository:** Spring Data Repository (JpaRepository, etc.) is a **Spring technology** that lives in Infrastructure layer as private implementation detail. It's NOT the same as DDD Repository Pattern!

---

### 2. Application Layer

**Module:** `:application`
**Agent:** Business Logic Agent
**Spring:** YES (`@Service`)

**Responsibilities:**
- Implement Use Cases (orchestration of domain logic)
- Coordinate multiple Repositories
- Business logic that spans multiple entities
- Application-specific workflows

**What lives here:**
```
application/
  └── service/         - Use Case implementations, Application Services
```

**Dependencies:** `:domain` only

**Pattern:**
- Use `@Service` annotation for Spring DI
- Inject Repository interfaces via constructor
- Coordinate multiple repositories in use cases
- Add `@Transactional` for multi-repository operations

**See Business Logic Agent for concrete examples.**

---

### 3. Infrastructure Layer

Infrastructure implements Domain interfaces. Each subdomain has its own module.

#### Infrastructure/DB Module

**Module:** `:infrastructure-db`
**Agent:** Repository Agent
**Spring:** YES (`@Service`, `@Repository`)

**Responsibilities:**
- Database access (Exposed, SQL)
- Vector storage (Qdrant)
- Knowledge graph (Neo4j)
- Implement Repository interfaces from Domain

**What lives here:**
```
infrastructure/db/
  ├── persistence/     - Database implementations (Exposed ORM)
  ├── vector/          - Qdrant vector storage implementation
  └── graph/           - Neo4j knowledge graph implementation
```

**Dependencies:** `:domain` only (NOT `:application`)

**Key pattern:**
- **Private:** Exposed Table definitions, Spring Data JPA repositories (internal implementation detail)
- **Public:** DDD Repository implementations (expose to other modules)

**Three levels of abstraction:**
1. **DDD Repository interface** (domain/repository/) - PUBLIC, what other layers see
2. **DDD Repository implementation** (infrastructure/db/persistence/) - your code
3. **Spring Data Repository** (infrastructure/db/persistence/, internal) - ORM tool you use privately

#### Infrastructure/AI Module

**Module:** `:infrastructure-ai`
**Agent:** Spring AI Agent
**Spring:** YES (`@Service`, `@Configuration`)

**Responsibilities:**
- Spring AI integration
- Claude Code CLI integration
- MCP tools and servers
- AI provider abstractions

**What lives here:**
```
infrastructure/ai/
  ├── springai/        - Spring AI ChatModel implementations
  ├── claudecode/      - Claude Code CLI integration
  └── mcp/             - MCP servers, tools, clients
```

**Dependencies:** `:domain` only

**Note:** MCP is in infrastructure because it integrates external systems, though it resembles presentation layer (communication protocol).

---

### 4. Presentation Layer

**Module:** `:presentation`
**Agent:** UI Agent
**Primary Framework:** Compose Desktop
**Spring:** YES (transitive, for DI and app startup)

**Responsibilities:**
- User interface (Compose Desktop)
- ViewModels
- Application entry point (Main.kt)
- Spring Boot startup

**What lives here:**
```
presentation/
  ├── ui/              - Compose UI components
  ├── viewmodel/       - ViewModels
  └── Main.kt          - Application entry point + Spring Boot
```

**Dependencies:** `:domain`, `:application`

**Framework priority:**
- **Primary:** Compose Desktop (UI framework)
- **Secondary:** Spring (appears transitively for DI)

---

### 5. Shared Module

**Module:** `:shared`
**Agent:** Shared Agent (will be created later)
**Spring:** NO (pure Kotlin)

**Responsibilities:**
- Cross-cutting types used by multiple modules
- Common value objects (IDs, timestamps, etc.)
- Universal utilities (UUID generation, date/time helpers)
- Shared domain primitives

**What lives here:**
```
shared/
  ├── model/           - Common value objects, primitives
  └── uuid/            - UUID generation utilities (uuid7, etc.)
```

**Dependencies:** NONE (like Domain, completely independent)

**Pattern:**
- Utility functions for cross-cutting concerns
- Value objects used across multiple modules
- No Spring, pure Kotlin

**See Shared Agent (future) for examples.**

**Important notes:**
- `:shared` is for truly cross-cutting code only
- Each module still creates its own module-specific utilities
- Shared Agent will consolidate duplicated utilities later
- During initial development, agents create utilities in their own modules
- Future refactoring will move common utilities to `:shared`

**Current status:** Each agent creates utilities locally. Shared Agent will be created later to consolidate common code.

---

## Gradle Modules Structure

```
:shared                    - NO Spring, pure Kotlin, NO dependencies
:domain                    - NO Spring, pure Kotlin, NO dependencies
:application               → :domain, :shared
:infrastructure-db         → :domain, :shared
:infrastructure-ai         → :domain, :shared
:presentation              → :domain, :application, :shared
```

**Dependency rules:**
- `:shared` and `:domain` have NO dependencies (completely independent)
- All other modules can depend on `:domain` and `:shared`
- Modules do NOT depend on each other (except presentation → application)

---

## Terminology

### DDD Repository vs Spring Data Repository

**CRITICAL DISTINCTION - Read Carefully!**

These are **two different things** with the same name "Repository":

**1. DDD Repository Pattern (Domain-Driven Design)**
- **What it is:** Architectural pattern, domain abstraction
- **Where:** Interface in `domain/repository/` (PUBLIC)
- **Purpose:** Technology-agnostic data access contract
- **Created by:** Architect Agent
- **Implemented by:** Repository Agent in Infrastructure layer
- **Example:** `interface ThreadRepository { suspend fun findById(...) }`

**2. Spring Data Repository (Spring Framework)**
- **What it is:** ORM technology/library from Spring
- **Where:** `infrastructure/db/persistence/` (PRIVATE/INTERNAL)
- **Purpose:** Database mapping tool (JPA/MongoDB/Redis abstraction)
- **Used by:** Repository Agent internally
- **Example:** `internal interface ThreadJpaRepository : JpaRepository<ThreadEntity, String>`

**How they relate:**

```
Domain Layer (PUBLIC):
  interface ThreadRepository  ← DDD Repository Pattern (architectural abstraction)
     ↓
Infrastructure Layer:
  @Service
  class ExposedThreadRepository : ThreadRepository  ← Your implementation
     ↓ uses internally (PRIVATE)
  @Repository
  internal interface ThreadJpaRepository : JpaRepository<...>  ← Spring Data (ORM tool)
```

**Key points:**
- DDD Repository = architectural pattern, Spring Data Repository = technology/library
- Spring Data Repository is PRIVATE implementation detail inside DDD Repository
- Other modules see only DDD Repository interface, never Spring Data Repository
- Don't confuse them - they're fundamentally different concepts!

**Example:**
```kotlin
// Domain: Architect defines this (DDD Repository Pattern)
interface ThreadRepository {
    suspend fun findById(id: Thread.Id): Thread?
}

// Infrastructure: Repository Agent implements this
@Repository  // Spring annotation
internal interface ThreadJpaRepository : JpaRepository<ThreadEntity, String>  // Private Spring Data!

@Service
class ExposedThreadRepository(
    private val jpaRepo: ThreadJpaRepository  // Uses Spring Data internally
) : ThreadRepository {  // Implements DDD Repository
    override suspend fun findById(id: Thread.Id) = dbQuery {
        jpaRepo.findById(id.value)  // Spring Data does SQL
            .map { it.toDomain() }  // Convert to domain object
            .orElse(null)
    }
}
```

### Use Case vs Application Service

- **Use Case** - Orchestration of domain logic + repositories (in Application layer)
- **Application Service** - Same thing, different name

Both refer to services in `:application` module that coordinate multiple Repositories.

---

## Spring Framework Usage

**Domain - NO Spring:**
- Pure Kotlin interfaces and data classes
- No annotations, no framework code

**Application - YES Spring:**
- Use `@Service` annotation
- Constructor injection for dependencies

**Infrastructure - YES Spring:**
- Use `@Service` for implementations
- Use `@Configuration` for setup

**Presentation - YES Spring (transitive):**
- `@SpringBootApplication` for Main.kt
- `@Component` for ViewModels if using Spring DI

**See agent-specific prompts for concrete examples.**

---

## Dependency Injection

**How it works:**

1. Domain defines interfaces (no Spring)
2. Infrastructure implements interfaces with `@Service`
3. Application uses interfaces via constructor injection
4. Spring automatically wires implementations

**Pattern:**
- Domain layer: Interface definitions (pure Kotlin)
- Infrastructure layer: Implementations with `@Service`
- Application layer: Constructor injection of interfaces
- Spring autowiring: Finds implementation by interface type

**Configuration:**

Each module can have its own `@Configuration` if needed:
- `:infrastructure-db` → `DbConfiguration.kt`
- `:infrastructure-ai` → `AiConfiguration.kt`
- `:application` → Usually not needed (auto-configuration via `@Service`)

**See agent-specific prompts for concrete DI examples.**

---

## Utilities

**Current approach:** Each module has its own utilities.

**Why:**
- Utilities are cross-cutting and hard to coordinate between agents
- Shared utilities would require special agent and careful coordination
- Each agent creates utilities in their module scope

**Future:** Utility Agent may consolidate common utilities after all other work is done.

**Example:**
```
infrastructure/db/utils/      - DB-specific utilities (private to module)
application/utils/            - Application-specific utilities
presentation/utils/           - UI-specific utilities
```

---

## Agent Responsibilities Summary

| Agent | Module | Layer | Spring | Responsibilities |
|-------|--------|-------|--------|------------------|
| Architect | `:domain` | Domain | NO | Entities, Repository interfaces |
| Shared | `:shared` | Cross-cutting | NO | Common utilities, value objects (created later) |
| Business Logic | `:application` | Application | YES | Use Cases, orchestration |
| Repository | `:infrastructure-db` | Infrastructure | YES | DB, Vector, Graph implementations |
| Spring AI | `:infrastructure-ai` | Infrastructure | YES | AI, MCP integrations |
| UI | `:presentation` | Presentation | YES* | Compose UI, ViewModels |

*Spring in Presentation is transitive (for DI and app startup)

**Note:** Shared Agent will be created later. Currently, each agent creates utilities in their own module.

---

## Key Principles

1. **Domain is pure** - No dependencies, no framework code
2. **Dependencies point inward** - Outer layers depend on inner, never reverse
3. **Interfaces in Domain** - Implementations in Infrastructure
4. **DDD Repository not Spring Data Repository** - Exposed Table/JpaRepository is implementation detail
5. **Spring in outer layers only** - Application and Infrastructure (NOT Domain)
6. **Each module is independent** - Can be compiled separately
7. **DI wiring is automatic** - Spring finds implementations by interface

---

## Multi-Agent Workflow Example

**Task:** "Add note storage feature"

Demonstrates how development agents work in parallel through Code-as-Contract, with zero chat overhead.

### Step 1: Architect Agent

Creates domain contracts:

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

Saves to Knowledge Graph: "Designed note storage with simple CRUD operations"

### Step 2: Repository Agent

Reads `domain/repository/NoteRepository.kt` from filesystem, implements:

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

Saves to Knowledge Graph: "Implemented NoteRepository with Exposed, added table definition"

### Step 3: Business Logic Agent

Reads interfaces from filesystem, implements orchestration:

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

Saves to Knowledge Graph: "Implemented NoteService with validation, generates UUID for new notes"

### Step 4: UI Agent

Reads interfaces from filesystem, builds UI:

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

Saves to Knowledge Graph: "Implemented note list UI with ViewModel pattern, auto-loads on init"

### Result

- ✅ Zero coordination overhead (no chat between agents)
- ✅ Compiler validates all contracts
- ✅ Clean layer separation enforced by module dependencies
- ✅ Knowledge Graph captures decisions for future reference
- ✅ Build succeeds - integration verified through compilation

**Key takeaway:** Agents work independently on their layer, communicate through typed interfaces written to filesystem. Compiler is the coordinator.

---

## Architecture Decision Records (ADR)

Gromozeka uses ADR to document significant architectural decisions.

**Location:** `docs/adr/`

**Structure:**
```
docs/adr/
  ├── template.md          - Standard ADR template
  ├── README.md            - How to work with ADRs
  ├── domain/              - Architect Agent decisions
  ├── infrastructure/      - Repository/Spring AI Agent decisions
  ├── presentation/        - UI Agent decisions
  └── coordination/        - Meta-Agent, cross-cutting decisions
```

**Responsible agents:**
- **Architect Agent** - Domain-level decisions (Repository pattern, Value Objects, etc.)
- **Other agents** - Infrastructure/presentation decisions in their areas
- **Meta-Agent** - Coordination and validation

**When to create ADR:**
- ✅ Decision affects multiple modules/layers
- ✅ Trade-offs were considered
- ✅ Alternatives were evaluated
- ✅ Reasoning must be preserved

**When NOT to create ADR:**
- ❌ Routine implementations
- ❌ Obvious technical choices
- ❌ Local refactorings

**Process:**
1. Agent makes architectural decision
2. Writes ADR following `docs/adr/template.md`
3. Saves to appropriate subdirectory
4. Optionally indexes in Knowledge Graph

**Benefits:**
- Shared context for multi-agent teams
- History of decisions and reasoning
- Onboarding reference
- Avoid revisiting settled questions

**See:** `docs/adr/README.md` for detailed guidelines.

---

## Error Handling Patterns

Gromozeka uses different error handling strategies depending on the scenario.

### 1. Nullable Return - Absence is Normal

Use `T?` when absence of result is a valid, expected outcome.

**When to use:**
- Query operations that may not find results
- Optional data retrieval

**Pattern:**
```kotlin
interface EntityRepository {
    suspend fun findById(id: Entity.Id): Entity?  // null = not found (normal)
}

// Usage
val entity = repository.findById(id)
if (entity != null) {
    // work with entity
} else {
    // handle absence
}
```

### 2. Exceptions - Business Rule Violations

Use exceptions when operation fails due to violated business rules or unexpected errors.

**When to use:**
- Constraint violations (duplicate, invalid state)
- Precondition failures
- Infrastructure errors

**Domain exceptions location:**
```kotlin
// Domain exceptions live in domain/model/ package
package com.gromozeka.domain.model

class DuplicateEntityException(
    val fieldValue: String
) : Exception("Entity with field '$fieldValue' already exists")

class EntityNotFoundException(
    val entityId: Entity.Id
) : Exception("Entity not found: ${entityId.value}")
```

**Pattern:**
```kotlin
// Domain interface
interface EntityRepository {
    suspend fun save(entity: Entity): Entity
    // throws DuplicateEntityException if constraint violated
}

// Infrastructure implementation
@Service
class EntityRepositoryImpl : EntityRepository {
    override suspend fun save(entity: Entity): Entity = dbQuery {
        // Check for constraint violation
        val existing = checkDuplicate(entity)
        
        if (existing != null) {
            throw DuplicateEntityException(entity.field)
        }
        
        // Persist entity
        persistToDatabase(entity)
        entity
    }
}
```

### 3. Result<T> - Multiple Failure Modes

Use sealed classes/Result when operation has multiple distinct failure cases that caller needs to handle.

**When to use:**
- Validation with multiple error types
- Complex operations with different failure modes
- When you want caller to handle each case explicitly

**Pattern:**
```kotlin
// Domain
sealed interface CreateEntityResult {
    data class Success(val entity: Entity) : CreateEntityResult
    data class DuplicateField(val existingId: Entity.Id) : CreateEntityResult
    data class InvalidField(val reason: String) : CreateEntityResult
}

interface EntityRepository {
    suspend fun createEntity(entity: Entity): CreateEntityResult
}

// Usage in Application
when (val result = repository.createEntity(entity)) {
    is Success -> result.entity
    is DuplicateField -> // handle duplicate
    is InvalidField -> // handle invalid
}
```

### Decision Tree

**Use this guide to choose error handling strategy:**

1. **Not finding something?** → Nullable (`Entity?`)
2. **Business rule violated?** → Exception (`DuplicateEntityException`)
3. **Multiple failure types?** → Result/Sealed class
4. **Unexpected infrastructure error?** → Exception (let it propagate)

### Fail Fast Principle

- **Internal components** - Use `require()`, `check()` - fail immediately on invalid input
- **External interfaces** - Defensive error handling, return Result or null

**Pattern:**
```kotlin
// Internal - fail fast
internal fun processEntity(entity: Entity) {
    require(entity.id.value.isNotBlank()) { "Entity ID cannot be blank" }
    // Caller should never pass invalid entity
}

// External - defensive
@Service
class EntityRepositoryImpl : EntityRepository {
    override suspend fun findById(id: Entity.Id): Entity? = try {
        dbQuery { ... }
    } catch (e: Exception) {
        logger.error("Database error finding entity", e)
        null  // Don't expose infrastructure errors
    }
}
```

### Exception Guidelines

**Naming:**
- Use specific names: `DuplicateEntityException`, not `EntityException`
- Include entity name: `EntityNotFoundException`, not `NotFoundException`

**Location:**
- Domain exceptions: `domain/model/`
- Infrastructure exceptions: Can stay in infrastructure modules
- Application exceptions: `application/exceptions/` if needed

**Documentation:**
- Always document thrown exceptions in KDoc with `@throws`
- Explain WHEN exception is thrown, not just what it is
