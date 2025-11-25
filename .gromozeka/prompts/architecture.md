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

**Pattern:**
- Entities as immutable data classes with nested value class IDs
- Repository interfaces with suspend functions for async operations
- No framework annotations, pure Kotlin

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

---

### 5. Shared Module

**Module:** `:shared`
**Agent:** Shared Agent (will be created later)
**Spring:** NO (pure Kotlin)

**Responsibilities:**
- Cross-cutting types used by multiple modules
- Common value objects (IDs, timestamps, etc.)
- Universal utilities (UUID generation, date/time helpers)

**What lives here:**
```
shared/
  ├── model/           - Common value objects, primitives
  └── uuid/            - UUID generation utilities (uuid7, etc.)
```

**Current status:** Each agent creates utilities locally. Shared Agent will be created later to consolidate common code.

## DDD Patterns Used

### What we USE:
- **Repository Pattern** - abstraction over data persistence
- **Value Objects** - typed wrappers for primitives (Thread.Id, Message.Id)
- **Domain Services** - business logic that doesn't fit entities
- **Immutable Entities** - data classes with `val` properties

### What we DON'T USE (yet):
- **Aggregate Roots** - we don't enforce aggregate boundaries
- **Bounded Contexts** - single context for now
- **Domain Events** - no event sourcing
- **Specification Pattern** - queries are simple

Keep architecture pragmatic. Add complexity only when needed.

## DDD Repository vs Spring Data Repository

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
- `:shared` and `:domain` have NO dependencies
- All other modules can depend on `:domain` and `:shared`
- Modules do NOT depend on each other (except presentation → application)

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

## Architecture Decision Records (ADR)

Gromozeka uses ADR to document significant architectural decisions.

**Location:** `.gromozeka/adr/`

**Structure:**
```
.gromozeka/adr/
  ├── template.md          - Standard ADR template
  ├── README.md            - How to work with ADRs
  ├── domain/              - Architect Agent decisions
  ├── infrastructure/      - Repository/Spring AI Agent decisions
  ├── presentation/        - UI Agent decisions
  └── coordination/        - Meta-Agent, cross-cutting decisions
```

**Responsible agents:**
- **Architect Agent** - Domain-level decisions
- **Other agents** - Infrastructure/presentation decisions in their areas
- **Meta-Agent** - Coordination and validation

## Error Handling Patterns

### 1. Nullable Return - Absence is Normal

Use `T?` when absence of result is a valid, expected outcome.

```kotlin
interface EntityRepository {
    suspend fun findById(id: Entity.Id): Entity?  // null = not found (normal)
}
```

### 2. Exceptions - Business Rule Violations

Use exceptions when operation fails due to violated business rules or unexpected errors.

**Domain exceptions location:** `domain/model/` package

```kotlin
class DuplicateEntityException(
    val fieldValue: String
) : Exception("Entity with field '$fieldValue' already exists")
```

### 3. Result<T> - Multiple Failure Modes

Use sealed classes/Result when operation has multiple distinct failure cases.

```kotlin
sealed interface CreateEntityResult {
    data class Success(val entity: Entity) : CreateEntityResult
    data class DuplicateField(val existingId: Entity.Id) : CreateEntityResult
    data class InvalidField(val reason: String) : CreateEntityResult
}
```

### Decision Tree

1. **Not finding something?** → Nullable (`Entity?`)
2. **Business rule violated?** → Exception
3. **Multiple failure types?** → Result/Sealed class
4. **Unexpected infrastructure error?** → Exception (let it propagate)

## Key Architecture Principles

1. **Domain is pure** - No dependencies, no framework code
2. **Dependencies point inward** - Outer layers depend on inner, never reverse
3. **Interfaces in Domain** - Implementations in Infrastructure
4. **DDD Repository not Spring Data Repository** - Different concepts!
5. **Spring in outer layers only** - Application and Infrastructure (NOT Domain)
6. **Each module is independent** - Can be compiled separately
7. **DI wiring is automatic** - Spring finds implementations by interface
