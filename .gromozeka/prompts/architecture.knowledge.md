# Gromozeka Clean Architecture

This document defines the Clean Architecture structure for Gromozeka project. All development agents must follow this architecture.

Current architecture pursues two goals:
 - Managing development through specifications in code
 - Classic Clean Architecture


## Managing development through specifications in code
 - Architector-agent defines and maintains specifications for whole project. 
 - This specifications described with Kotlin high-level abstractions (Interfaces, data classes, enums, etc.) and KDoc. 
 - This allows architector to focus on the whole project, business logic and architecture details. 
 - This allows architector to describe code with a small number of tokens but with enough details to understand it. 
 - This allows other agents to deeply focus on their areas of responsibility, patterns, and implementation details without being distracted by what is outside their scope.
 - Architector writes specifications.
 - Other agents track current ':domain' changes using `git status` and implement them. They:
   - read specifications
   - what is new
   - what was removed
   - what was changed
 - Other agents can verify their implementations against specifications using git history.

**Possible specification mechanisms:**
1. **Interfaces** - contracts that must be implemented
2. **Type safety** - prevent confusion
3. **Sealed classes** - explicit type variants
4. **Enums** - finite state machines
5. **KDoc** - operation semantics, parameters, errors

Gromozeka follows Clean Architecture principles with clear layer separation and dependency rules.

**Core principle:** Dependencies point inward only.

```
Infrastructure → Domain
Application    → Domain
Presentation   → Domain + Application
```

## Classic Clean Architecture Layers

### 1. Domain Layer

**Module:** `:domain`
**Agent:** Architect Agent

Use only kotlin and sdk-like libraries (like kotlinx-coroutines-core)
Try to put all possible code into common module.

**Responsibilities:**
- Define all specifications to manage development 
  - Entities and Value Objects
  - Repository interfaces (for all data access)
  - Domain Service interfaces
  - ViewModel interfaces (UI contracts)
  - All other interfaces, value objects, enums, sealed classes, etc to describe what to implement
  - Own complete application model

**What lives here:**
```
domain/
  ├── model/           - Entities, Value Objects
  ├── repository/      - Repository interfaces (data access abstraction)
  ├── service/         - Domain Service interfaces (business logic)
  └── presentation/    - UI contract specifications
      ├── desktop/
      │   ├── component/  - UI components (MUST have ASCII diagrams)
      │   └── logic/      - Orchestration (NO UI details)
```

**Dependencies:**
- `kotlinx-coroutines-core` - for StateFlow/SharedFlow in ViewModel interfaces

**ViewModel naming convention:**
- `XXXComponentVM` - for UI component ViewModels (e.g., `ThreadPanelComponentVM`)
- `XXXLogicVM` - for logic/service ViewModels (e.g., `ConversationLogicVM`)

**Why desktop/ only:** Mobile/Web will be added as needed.

---

### 2. Application Layer

**Module:** `:application`
**Agent:** Business Logic Agent

**Responsibilities:**
- Implement Use Cases and Application logic
- Services with business rules and decision-making
- Coordination of repositories and domain services
- Workflows with filtering, ranking, formatting
- "Smart" operations (not just dumb technical IO)

**Key principle:** If there's LOGIC (decisions, rules, workflows) → Application layer.
If it's just technical code without decisions → Infrastructure.

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

---

### 3. Infrastructure Layer

Infrastructure implements Domain interfaces. Each subdomain has its own module.

#### Infrastructure/DB Module

**Module:** `:infrastructure-db`
**Agent:** Repository Agent
**Spring:** YES (`@Service`, `@Repository`)

**Responsibilities:**
- System code
- Database access (Exposed, SQL)
- Vector indexes (Neo4j)
- Knowledge graph (Neo4j)
- Implement Repository interfaces from Domain

**What lives here:**
```
infrastructure/db/
  ├── persistence/     - Database implementations (Exposed ORM)
  └── graph/           - Neo4j knowledge graph and vector indexes
```

**Three levels of abstraction:**
1. **DDD Repository interface** (domain/repository/) - PUBLIC, what other layers see
2. **DDD Repository implementation** (infrastructure/db/persistence/) - code
3. **Spring Data Repository** (infrastructure/db/persistence/, internal) - ORM tool you use privately

#### Infrastructure/AI Module

**Module:** `:infrastructure-ai`
**Agent:** Spring AI Agent
**Spring:** YES (`@Service`, `@Configuration`)

**Responsibilities:**
- System code
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
- **Repository Pattern** - abstraction over data persistence (`domain/repository/`)
- **Domain Services** - business logic that doesn't fit entities (`domain/service/`)
- **Value Objects** - typed wrappers for primitives (Thread.Id, Message.Id)
- **Entities** - data classes with .Id

### What we DON'T USE (yet):
- **Aggregate Roots** - we don't enforce aggregate boundaries
- **Bounded Contexts** - single context for now
- **Domain Events** - no event sourcing
- **Specification Pattern** - queries are simple

Keep architecture pragmatic. Add complexity only when needed.

## DDD Repository vs Spring Data Repository


**1. DDD Repository Pattern (Domain-Driven Design)**
- **What:** Architectural pattern, domain abstraction
- **Where:** Interface in `domain/repository/` (PUBLIC)
- **Created by:** Architect Agent
- **Implemented by:** Repository Agent

**2. Spring Data Repository (Spring Framework)**
- **What:** ORM technology/library from Spring
- **Where:** `infrastructure/db/persistence/` (PRIVATE/INTERNAL)
- **Used by:** Repository Agent internally

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

## Error Handling Patterns

### 1. Nullable Return - Absence is Normal

Use `T?` when absence of a result is a valid, expected outcome, when the `null` value has a particular meaning.

```kotlin
interface EntityRepository {
    suspend fun findById(id: Entity.Id): Entity?  // null = not found (normal)
}
```

### 2. Exceptions - Business Rule Violations

Use exceptions when an operation fails due to violated business rules or unexpected errors.

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
