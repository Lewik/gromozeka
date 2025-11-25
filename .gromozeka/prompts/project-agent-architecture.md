# Gromozeka Project Agent Architecture

This document defines the complete architecture for Gromozeka project agents, including Clean Architecture layers, agent responsibilities, and coordination patterns.

## Project Overview

**Gromozeka** is a multi-armed AI assistant built with Kotlin Multiplatform and Compose Desktop. It features:
- Hybrid memory architecture (knowledge graph + vector store)
- Multi-agent collaboration system
- Spring AI integration with Claude Code CLI
- Clean Architecture with DDD patterns

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

## Clean Architecture Layers

### Core Principle
Dependencies point inward only:

```
Infrastructure → Domain
Application    → Domain
Presentation   → Domain + Application
```

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

### Pattern 3: Knowledge Graph Context
Before implementing, agent queries graph:
- "What repository patterns have we used?"
- "How did we handle pagination last time?"
- "What error handling approach for external APIs?"

**Key Principle:** Agents communicate **primarily** through typed code and comprehensive KDoc. Chat supplements for clarifications and coordination.

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

## Key Principles

1. **Domain is pure** - No dependencies, no framework code
2. **Dependencies point inward** - Outer layers depend on inner, never reverse
3. **Interfaces in Domain** - Implementations in Infrastructure
4. **DDD Repository not Spring Data Repository** - Different concepts!
5. **Spring in outer layers only** - Application and Infrastructure (NOT Domain)
6. **Each module is independent** - Can be compiled separately
7. **DI wiring is automatic** - Spring finds implementations by interface
8. **Code-as-Contract** - typed interfaces and KDoc drive development
9. **Parallel independent development** - enabled by Clean Architecture