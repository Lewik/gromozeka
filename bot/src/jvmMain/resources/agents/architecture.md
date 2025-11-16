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
- Define DataService interfaces (for all data access)
- Define Domain Service interfaces
- Own complete application model

**What lives here:**
```
domain/
  ├── model/           - Entities, Value Objects
  └── service/         - DataService interfaces, Domain Service interfaces
```

**Key principle:** Domain has NO dependencies. Pure business logic and contracts.

**Example:**
```kotlin
// domain/model/Thread.kt
data class Thread(
    val id: Id,
    val title: String,
    val createdAt: Instant
) {
    @JvmInline
    value class Id(val value: String)
}

// domain/service/ThreadDataService.kt
interface ThreadDataService {
    suspend fun findById(id: Thread.Id): Thread?
    suspend fun create(thread: Thread): Thread
    suspend fun findAll(): List<Thread>
}
```

**Important:** Architect creates **DataService** interfaces, NOT Repository interfaces. Repository is a Spring Data implementation detail.

---

### 2. Application Layer

**Module:** `:application`
**Agent:** Business Logic Agent
**Spring:** YES (`@Service`)

**Responsibilities:**
- Implement Use Cases (orchestration of domain logic)
- Coordinate multiple DataServices
- Business logic that spans multiple entities
- Application-specific workflows

**What lives here:**
```
application/
  └── service/         - Use Case implementations, Application Services
```

**Dependencies:** `:domain` only

**Example:**
```kotlin
// application/service/ConversationService.kt
import com.gromozeka.shared.uuid.uuid7

@Service
class ConversationService(
    private val threadDataService: ThreadDataService,
    private val messageDataService: MessageDataService,
    private val vectorMemory: VectorMemoryService
) {
    suspend fun startConversation(title: String, agentId: String): Thread {
        // Use Case: orchestrate multiple data services
        val thread = Thread(
            id = uuid7(),
            title = title,
            createdAt = Clock.System.now()
        )

        threadDataService.create(thread)
        vectorMemory.indexThread(thread.id)

        return thread
    }
}
```

---

### 3. Infrastructure Layer

Infrastructure implements Domain interfaces. Each subdomain has its own module.

#### Infrastructure/DB Module

**Module:** `:infrastructure-db`
**Agent:** DataServices Agent
**Spring:** YES (`@Service`, `@Repository`)

**Responsibilities:**
- Database access (Exposed, SQL)
- Vector storage (Qdrant)
- Knowledge graph (Neo4j)
- Implement DataService interfaces from Domain

**What lives here:**
```
infrastructure/db/
  ├── persistence/     - Database implementations (Exposed ORM)
  ├── vector/          - Qdrant vector storage implementation
  └── graph/           - Neo4j knowledge graph implementation
```

**Dependencies:** `:domain` only (NOT `:application`)

**Key pattern:**
- **Private:** Exposed Table definitions (internal implementation detail)
- **Public:** DataService implementations (expose to other modules)

**Example:**
```kotlin
// Private Exposed Table definition (INTERNAL)
internal object ThreadsTable : Table("threads") {
    val id = varchar("id", 255)
    val title = varchar("title", 500)
    val createdAt = long("created_at")
}

// Public DataService implementation
@Service
class ExposedThreadDataService : ThreadDataService {
    override suspend fun findById(id: Thread.Id): Thread? = dbQuery {
        ThreadsTable.select { ThreadsTable.id eq id.value }
            .map { it.toThread() }
            .singleOrNull()
    }

    override suspend fun create(thread: Thread): Thread = dbQuery {
        ThreadsTable.insert {
            it[id] = thread.id.value
            it[title] = thread.title
            it[createdAt] = thread.createdAt.toEpochMilliseconds()
        }
        thread
    }
}
```

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

**Usage pattern:**
```kotlin
// shared/uuid/UUID7.kt
fun uuid7(): String = ...

// Used in Application layer
import com.gromozeka.shared.uuid.uuid7

val thread = Thread(
    id = uuid7(),
    ...
)
```

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

### DataService vs Repository

- **DataService** - Interface defined in Domain layer by Architect
- **Repository/Table** - Exposed ORM implementation detail, private to Infrastructure module

**Example:**
```kotlin
// Domain: Architect defines this
interface ThreadDataService {
    suspend fun findById(id: Thread.Id): Thread?
}

// Infrastructure: DataServices Agent implements this
internal object ThreadsTable : Table("threads") { ... }  // Private Exposed Table!

@Service
class ExposedThreadDataService : ThreadDataService {  // Public implementation
    override suspend fun findById(id: Thread.Id) = dbQuery {
        ThreadsTable.select { ThreadsTable.id eq id.value }...
    }
}
```

### Use Case vs Application Service

- **Use Case** - Orchestration of domain logic + data services (in Application layer)
- **Application Service** - Same thing, different name

Both refer to services in `:application` module that coordinate multiple DataServices.

---

## Spring Framework Usage

### Domain - NO Spring
```kotlin
// Pure Kotlin interfaces and data classes
interface ThreadDataService {  // No @Service!
    suspend fun findById(id: Thread.Id): Thread?
}
```

### Application - YES Spring
```kotlin
@Service  // Spring annotation here
class ConversationService(
    private val threadDataService: ThreadDataService  // Constructor injection
) { ... }
```

### Infrastructure - YES Spring
```kotlin
@Service  // Spring annotation here
class ExposedThreadDataService : ThreadDataService { ... }

@Configuration  // Spring configuration
class DbConfiguration { ... }
```

### Presentation - YES Spring (transitive)
```kotlin
// Main.kt - Spring Boot startup
@SpringBootApplication
class GromozemkaApplication

fun main(args: Array<String>) {
    runApplication<GromozemkaApplication>(*args)
}

// ViewModels - use Spring DI via @Component if needed
@Component
class TabViewModel(...) { ... }
```

---

## Dependency Injection

**How it works:**

1. Domain defines interfaces (no Spring)
2. Infrastructure implements interfaces with `@Service`
3. Application uses interfaces via constructor injection
4. Spring automatically wires implementations

**Example:**
```kotlin
// Domain
interface ThreadDataService { ... }

// Infrastructure
@Service
class ExposedThreadDataService : ThreadDataService { ... }

// Application
@Service
class ConversationService(
    private val threadDataService: ThreadDataService  // Spring injects ExposedThreadDataService
) { ... }
```

**Configuration:**

Each module can have its own `@Configuration` if needed:
- `:infrastructure-db` → `DbConfiguration.kt`
- `:infrastructure-ai` → `AiConfiguration.kt`
- `:application` → Usually not needed (auto-configuration via `@Service`)

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
| Architect | `:domain` | Domain | NO | Entities, DataService interfaces |
| Shared | `:shared` | Cross-cutting | NO | Common utilities, value objects (created later) |
| Business Logic | `:application` | Application | YES | Use Cases, orchestration |
| DataServices | `:infrastructure-db` | Infrastructure | YES | DB, Vector, Graph implementations |
| Spring AI | `:infrastructure-ai` | Infrastructure | YES | AI, MCP integrations |
| UI | `:presentation` | Presentation | YES* | Compose UI, ViewModels |

*Spring in Presentation is transitive (for DI and app startup)

**Note:** Shared Agent will be created later. Currently, each agent creates utilities in their own module.

---

## Key Principles

1. **Domain is pure** - No dependencies, no framework code
2. **Dependencies point inward** - Outer layers depend on inner, never reverse
3. **Interfaces in Domain** - Implementations in Infrastructure
4. **DataService not Repository** - Exposed Table/Repository is implementation detail
5. **Spring in outer layers only** - Application and Infrastructure (NOT Domain)
6. **Each module is independent** - Can be compiled separately
7. **DI wiring is automatic** - Spring finds implementations by interface

---

## Error Handling Patterns

Gromozeka uses different error handling strategies depending on the scenario.

### 1. Nullable Return - Absence is Normal

Use `T?` when absence of result is a valid, expected outcome.

**When to use:**
- Query operations that may not find results
- Optional data retrieval

**Example:**
```kotlin
// Domain
interface ThreadDataService {
    suspend fun findById(id: Thread.Id): Thread?  // null = not found (normal)
}

// Usage
val thread = threadDataService.findById(id)
if (thread != null) {
    // work with thread
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

class DuplicateThreadException(
    val title: String
) : Exception("Thread with title '$title' already exists")

class ThreadNotFoundException(
    val threadId: Thread.Id
) : Exception("Thread not found: ${threadId.value}")
```

**Usage example:**
```kotlin
// Domain
interface ThreadDataService {
    suspend fun create(thread: Thread): Thread
    // throws DuplicateThreadException if thread with title exists
}

// Infrastructure implementation
@Service
class ExposedThreadDataService : ThreadDataService {
    override suspend fun create(thread: Thread): Thread = dbQuery {
        // Check for duplicate
        val existing = ThreadsTable.select { 
            ThreadsTable.title eq thread.title 
        }.singleOrNull()
        
        if (existing != null) {
            throw DuplicateThreadException(thread.title)
        }
        
        // Insert new thread
        ThreadsTable.insert { ... }
        thread
    }
}
```

### 3. Result<T> - Multiple Failure Modes

Use sealed classes/Result when operation has multiple distinct failure cases that caller needs to handle.

**When to use:**
- Validation with multiple error types
- Complex operations with different failure modes
- When you want caller to handle each case explicitly

**Example:**
```kotlin
// Domain
sealed interface CreateThreadResult {
    data class Success(val thread: Thread) : CreateThreadResult
    data class DuplicateTitle(val existingId: Thread.Id) : CreateThreadResult
    data class InvalidTitle(val reason: String) : CreateThreadResult
}

interface ThreadDataService {
    suspend fun createThread(thread: Thread): CreateThreadResult
}

// Usage in Application
when (val result = threadDataService.createThread(thread)) {
    is Success -> result.thread
    is DuplicateTitle -> // handle duplicate
    is InvalidTitle -> // handle invalid
}
```

### Decision Tree

**Use this guide to choose error handling strategy:**

1. **Not finding something?** → Nullable (`Thread?`)
2. **Business rule violated?** → Exception (`DuplicateThreadException`)
3. **Multiple failure types?** → Result/Sealed class
4. **Unexpected infrastructure error?** → Exception (let it propagate)

### Fail Fast Principle

- **Internal components** - Use `require()`, `check()` - fail immediately on invalid input
- **External interfaces** - Defensive error handling, return Result or null

**Example:**
```kotlin
// Internal - fail fast
internal fun processThread(thread: Thread) {
    require(thread.id.value.isNotBlank()) { "Thread ID cannot be blank" }
    // Caller should never pass invalid thread
}

// External - defensive
@Service
class ExposedThreadDataService : ThreadDataService {
    override suspend fun findById(id: Thread.Id): Thread? = try {
        dbQuery { ... }
    } catch (e: Exception) {
        logger.error("Database error finding thread", e)
        null  // Don't expose infrastructure errors
    }
}
```

### Exception Guidelines

**Naming:**
- Use specific names: `DuplicateThreadException`, not `ThreadException`
- Include entity name: `ThreadNotFoundException`, not `NotFoundException`

**Location:**
- Domain exceptions: `domain/model/`
- Infrastructure exceptions: Can stay in infrastructure modules
- Application exceptions: `application/exceptions/` if needed

**Documentation:**
- Always document thrown exceptions in KDoc with `@throws`
- Explain WHEN exception is thrown, not just what it is

