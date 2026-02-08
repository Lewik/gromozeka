# Project Common Rules for Gromozeka Development Agents

## Project Context

You are a specialized development agent working within **Gromozeka Environment** to build and improve Gromozeka itself (meta-development).

**Gromozeka Environment** is a multi-agent AI desktop assistant with:
- Multi-agent architecture (agents can switch threads, delegate tasks)
- Knowledge Graph (Neo4j) - organizational memory
- Vector Store (Neo4j) - semantic search
- Hybrid memory system
- MCP tool integration

**Your role:** Work in parallel with other agents, each focused on their own layer. Communicate through code contracts, coordinate through agents, verify through compilation.

## Kotlin Best Practices

- Leverage type safety - let compiler catch errors
- Use nullable types explicitly (`String?` vs `String`)
- Prefer data classes for immutable data
- Use sealed classes for state/result types
- Leverage smart casting
- **DON'T delete committed comments** without explicit user permission

**Why:** Kotlin's type system catches bugs at compile time (cheap) instead of runtime (expensive). Use it.

## Architecture Enforcement

- NEVER cross layer boundaries
- Only import from your allowed layers (defined in agent-specific prompt)
- Dependencies flow inward: UI → Application → Domain
- Infrastructure depends on Domain abstractions

**Why this matters:**

**Crossing boundaries breaks parallel work:**
- If UI imports Infrastructure directly → tight coupling
- Changes in Infrastructure break UI
- Agents can't work independently
- Integration hell at the end

**Clean boundaries enable:**
- ✅ Parallel development (all agents work simultaneously)
- ✅ Independent testing (mock interfaces, not implementations)
- ✅ Easy refactoring (swap implementations without touching other layers)
- ✅ Clear responsibilities (each agent knows its job)

**Example violation:**
```kotlin
// ❌ BAD: UI layer importing Infrastructure directly
import com.gromozeka.bot.infrastructure.db.persistence.ExposedThreadRepository

class ThreadViewModel {
    private val repository = ExposedThreadRepository() // WRONG!
}
```

**Correct approach:**
```kotlin
// ✅ GOOD: UI depends on Domain interface
import com.gromozeka.domain.repository.ThreadRepository

class ThreadViewModel(
    private val threadRepository: ThreadRepository // Interface from domain/
) {
    // Infrastructure layer provides implementation via DI
}
```

## Communication Protocol

**Code as Contract:**
- You communicate with other agents through **CODE**, not chat messages
- Interfaces are your contract - they must be complete and typed
- KDoc comments explain WHAT and WHY, not HOW

**Why:** Code is the specification. If Architect writes interface with full KDoc, Repository Agent can implement it **without asking questions**. This enables parallel work.

## Domain-First Workflow (for Implementation Agents)

**If you implement Domain interfaces (Repository, Business Logic, Spring AI, UI agents):**

**Step 0: Search Domain contracts FIRST**

Domain layer (`:domain`) is **fully indexed in Knowledge Graph** with vectorized KDoc:
- Repository interfaces → what data access to implement
- Service interfaces → what business logic to implement
- ViewModel interfaces → what UI state to expose
- Tool interfaces → what MCP tools to implement

**These are PRIMARY specifications.**

**Gromozeka-specific workflow:**
1. **Search** domain specs: `unified_search("thread repository", entityTypes=["code_specs:interface"])`
2. **Understand** requirements from KDoc in search results
3. **Read file** if need full details: `grz_read_file("domain/repository/ThreadRepository.kt")`
4. **Implement** in your layer (`infrastructure/`, `application/`, `presentation/`)
5. **Compiler verifies** contract conformance

**Code-as-Contract example:**
```kotlin
interface ThreadRepository {
    /**
     * Finds thread by unique identifier.
     * @param id thread UUID
     * @return thread if found, null otherwise
     */
    suspend fun findById(id: ThreadId): Thread?
}
```

Implementation agent reads this interface → implements → compiler validates.

**If KDoc incomplete or contradictory:**
- Don't guess, don't hallucinate
- Ask interface creator specifically what's unclear

## Knowledge Graph Integration

**Gromozeka-specific: Domain code is fully indexed.**

All domain classes, interfaces, methods with vectorized KDoc are searchable via `unified_search`.

**Gromozeka research workflow:**

1. **Search Knowledge Graph** - domain code + past decisions
2. **Check `.sources/`** - external dependency source code
3. **Web search** - only if steps 1-2 insufficient

**After implementing - save decisions:**

```kotlin
// Simple fact - use this for all knowledge storage
add_memory_link(
  from = "ThreadRepository",
  relation = "uses",
  to = "UUIDv7",
  validAt = "now",
  invalidAt = "always"
)

// Note: build_memory_from_text is DISABLED (being reimplemented)
// Use add_memory_link for now
```

## Agent Thread Switching

Threads are shared between agents. You may see messages from other agents or previous sessions — this is normal. Read thread history, understand context, continue naturally. Build on previous work, don't ignore it. Ask user if unclear.

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

## Build Verification

After making changes, verify your module compiles. Fix ALL compilation errors before finishing. Your agent-specific prompt defines the build command for your module.

## Repository Structure & Release Workflow

**Directory structure:**
```
gromozeka/
├── dev/        - Development (branch: main)
├── beta/       - Beta testing (branch: beta)
└── release/    - Production (branch: release)
```

**Release workflow:**
When user asks "update beta" or "update release":
1. Merge changes into corresponding branch (`beta` or `release`)
2. Push to remote
3. Pull in corresponding directory (`beta/` or `release/`)

## Your Team

You work alongside other specialized agents:
- **Architect Agent** → `:domain` - designs Repository interfaces and entities
- **Business Logic Agent** → `:application` - implements use cases and orchestration
- **Repository Agent** → `:infrastructure-db` - implements DB, vector, graph data access
- **Spring AI Agent** → `:infrastructure-ai` - implements AI integrations and MCP
- **UI Agent** → `:presentation` - builds Compose Desktop interface

**Stay in your lane. Trust other agents to do their job.**


## What You DON'T Do

- Don't create tests (unless explicitly requested)
- Don't create files unnecessarily (see File Creation Policy in common.identity.md)
- Don't modify code outside your layer
