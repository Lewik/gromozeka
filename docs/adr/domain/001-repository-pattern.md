# ADR-Domain-001: Repository Pattern for Data Access

**Status:** Accepted

**Date:** 2025-01-20

**Context:**

Gromozeka needs data access abstraction for multiple storage types:
- SQL database (Exposed ORM)
- Vector database (Qdrant)
- Graph database (Neo4j)

Requirements:
- Clean Architecture boundaries (Infrastructure → Domain)
- Testability through mocks
- Ability to swap implementations

**Decision:**

Define Repository interfaces in `domain/repository/` package.
Implementations reside in `infrastructure-db/` modules.

```kotlin
// domain/repository/ThreadRepository.kt
interface ThreadRepository {
    suspend fun findById(id: ThreadId): Thread?
    suspend fun save(thread: Thread): Thread
}

// infrastructure/db/persistence/ExposedThreadRepository.kt
@Service
class ExposedThreadRepository : ThreadRepository {
    override suspend fun findById(id: ThreadId): Thread? = dbQuery { ... }
}
```

**Consequences:**

### Positive
- ✅ Clean dependency direction: Infrastructure → Domain
- ✅ Testability via interface mocks
- ✅ Swappable implementations (can change DB without touching domain)
- ✅ Clear contracts for multi-agent development

### Negative
- ❌ More code (interface + implementation)
- ❌ Additional abstraction layer

**Alternatives Considered:**

### Alternative 1: Active Record Pattern
**Description:** Entities know how to persist themselves
**Rejected because:** Violates Clean Architecture, domain would depend on infrastructure

### Alternative 2: DAO Pattern
**Description:** Generic Data Access Objects
**Rejected because:** Too generic, Repository is more domain-focused

### Alternative 3: Direct ORM usage in Application layer
**Description:** Skip repository abstraction, use Exposed directly
**Rejected because:** Tight coupling, hard to test, violates Clean Architecture

**Related Decisions:**
- ADR-Domain-002: Nullable returns in Repository methods
- ADR-Coordination-001: Layer boundary enforcement

---
🤖 Generated with [Claude Code](https://claude.ai/code)
