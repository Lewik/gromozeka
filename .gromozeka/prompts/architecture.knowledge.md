# Gromozeka Clean Architecture

Reference architecture for the Gromozeka project.

Use this document to keep agent responsibilities aligned with the current codebase, not with an outdated idealized diagram.

## Two Architectural Goals

### 1. Specifications Through Code

The project coordinates development through typed domain contracts.

- The Architect Agent defines and maintains specifications in `:domain`
- Specifications are expressed as interfaces, data classes, sealed hierarchies, enums, and KDoc
- Other agents implement those contracts in their own layers
- The compiler is the main conformance check

This lets agents coordinate primarily through code instead of long chat handoffs.

### 2. Pragmatic Clean Architecture

Dependencies should still point toward domain concepts where practical.

Conceptual intent:
- application logic depends on domain contracts
- infrastructure implements domain contracts
- UI code consumes domain/application abstractions

Current runtime reality:
- `:presentation` is also the desktop app composition root and startup module
- because of that, the module graph currently includes direct presentation dependencies on infrastructure modules for bootstrapping and packaging
- keep that exception in bootstrap/config wiring, not in ordinary UI code

## Layer Overview

### `:shared`

**Purpose:** Cross-cutting primitives and utilities used by multiple modules.

Typical contents:
- common value objects
- UUID helpers
- shared utility types

**Nature:** Pure Kotlin, no Spring.

### `:domain`

**Primary owner:** Architect Agent

**Purpose:** Application specifications and domain contracts.

Typical contents:
- `domain/model/` - entities, value objects, enums, result types, exceptions
- `domain/repository/` - repository interfaces
- `domain/service/` - business/service contracts
- `domain/presentation/` - UI contracts and presentation-facing specifications
- `domain/tool/` - AI-facing and tool-facing contracts

**Nature:** Technology-light Kotlin. Keep framework and storage details out unless a contract genuinely requires them.

**Current dependency reality:** `:domain` exports `:shared` transitively and also contains some JVM-specific contracts where needed.

### `:application`

**Primary owner:** Business Logic Agent

**Purpose:** Use cases, orchestration, business workflows, transactional boundaries.

Typical contents:
- `application/service/` - application services and workflow implementations

**Rules:**
- implement domain service contracts
- coordinate repositories and domain services
- keep decision-making here, not in infrastructure

**Nature:** Spring-enabled service layer.

### `:infrastructure-db`

**Primary owner:** Repository Agent

**Purpose:** Persistence, graph storage, vector storage, and related repository implementations.

Typical contents:
- `infrastructure-db/src/.../persistence/`
- `infrastructure-db/src/.../graph/`

**Rules:**
- implement domain repository contracts
- keep ORM/database details private to infrastructure
- do not move business workflows here

### `:infrastructure-ai`

**Primary owner:** AI Integration Agent

**Purpose:** AI runtimes, provider integrations, MCP, memory, embeddings, code tooling, and tool implementations.

Typical contents:
- `infrastructure-ai/openai-subscription/`
- `infrastructure-ai/src/.../springai/`
- `infrastructure-ai/src/.../mcp/`
- `infrastructure-ai/src/.../memory/`
- `infrastructure-ai/src/.../tool/`
- adjacent runtime/config/platform code

**Rules:**
- implement domain tool and service contracts where applicable
- keep provider quirks and transport details inside infrastructure
- prefer streaming and defensive boundary handling

### `:presentation`

**Primary owner:** UI Agent

**Purpose:** Compose Desktop UI, presentation state, startup wiring, and desktop shell.

Typical contents:
- `presentation/src/.../ui/`
- `presentation/src/.../ui/viewmodel/`
- `presentation/src/.../services/`
- `presentation/src/.../config/`
- `presentation/src/.../AppBootstrap.kt`
- `presentation/src/.../Main.kt`

**Rules:**
- keep composables and viewmodels focused on presentation concerns
- call application/domain abstractions from UI logic
- keep infrastructure coupling concentrated in startup/config/composition-root code

## Ownership Summary

- **Architect Agent** → `:domain`
- **Business Logic Agent** → `:application`
- **Repository Agent** → `:infrastructure-db`
- **AI Integration Agent** → `:infrastructure-ai`
- **UI Agent** → `:presentation`

## Specification Mechanisms

Preferred mechanisms for precise contracts:
1. interfaces
2. value classes and typed IDs
3. sealed classes / sealed interfaces
4. enums for finite states
5. KDoc for semantics, parameters, and failure modes

## Error Handling Defaults

### Nullable return
Use `T?` when absence is normal.

```kotlin
suspend fun findById(id: Entity.Id): Entity?
```

### Exception
Use exceptions for business rule violations or unexpected failures.

```kotlin
class DuplicateEntityException(val fieldValue: String) : Exception()
```

### Explicit result type
Use sealed results when an operation has multiple meaningful failure modes.

```kotlin
sealed interface CreateEntityResult {
    data class Success(val entity: Entity) : CreateEntityResult
    data class Duplicate(val existingId: Entity.Id) : CreateEntityResult
    data class Invalid(val reason: String) : CreateEntityResult
}
```

## Design Heuristics

- Keep domain contracts more stable than implementations
- Prefer explicit types over comments when encoding invariants
- Put workflows with decisions in application, not infrastructure
- Keep provider/storage/UI toolkit details out of domain unless contractually necessary
- Treat `:presentation` infrastructure dependencies as composition-root wiring, not as permission to leak infrastructure into ordinary UI code

## Important Anti-Patterns

- Domain polluted with storage schema or Spring wiring
- Infrastructure containing business workflows that belong in application
- UI talking directly to storage/provider implementations for ordinary features
- Prompt/agent design based on historical architecture text instead of current repo truth
