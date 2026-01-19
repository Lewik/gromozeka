# Knowledge Graph: Your Organizational Memory

**Your persistent memory stored in Neo4j graph database.**

Shared memory across all agents - prevents reinventing solutions, enables learning from past decisions.

## What's Stored

**All stored as MemoryObject entities in Neo4j with same vectorization mechanism.**

**1. Memory Objects** - Facts, concepts, technologies, decisions
- Created via `add_memory_link` or `build_memory_from_text`
- Vectorized: entity name/summary
- Examples: "Spring AI", "Neo4j", "Clean Architecture"

**2. Code Specs** - ALL domain layer symbols
- Classes, interfaces, methods, properties, constructors
- **Indexed:** Symbol name, KDoc, file location, relationships
- **NOT indexed:** Method bodies, local variables
- Vectorized: KDoc + structure summary

**3. Relationships** - Connections between entities
- Code: DEFINES_METHOD, HAS_PROPERTY, IMPLEMENTS, EXTENDS, RETURNS_TYPE
- Knowledge: uses, implements, related to

**Vectorization (same for all):** OpenAI text-embedding-3-large (3072 dimensions) → enables semantic search via hybrid BM25 + Vector similarity.

## How to Search

**Use `unified_search` tool** - searches memory objects, code specs, conversations.

## Index Synchronization

- ✅ **Synchronized:** `git status` clean → all committed code indexed
- ⚠️ **Out of sync:** Uncommitted changes → index may be stale
- 🔄 **Re-index:** User triggers via UI button (`index_domain_to_graph` tool)

**Ask user to re-index when:**
- You modified domain code significantly
- Search results seem outdated
- New interfaces/classes not appearing

**How to ask:**
> "I modified domain interfaces. Please re-index domain code via UI button to update search index."

**Don't ask when:**
- Modified only implementation code (not domain)
- Minor changes (typos, formatting)

## Code Navigation Pattern

**Search first, read second.**

```kotlin
// ❌ DON'T: Read file blindly
grz_read_file("domain/model/Thread.kt")

// ✅ DO: Search first, understand context
unified_search(query = "Thread model structure", scopes = ["code_specs:class"])
// Then read specific file if needed
```

**Why:** Saves tokens, finds related patterns, gives overview.

**Read directly when:** User asks for specific file, modifying code, already know location.

## Usage Patterns

**Find domain specs:**
```kotlin
unified_search(query = "thread repository", scopes = ["code_specs:interface"])
```

**Research decisions:**
```kotlin
unified_search(query = "pagination decision", scopes = ["memory_objects", "conversation_messages"])
```

**Discover related code:**
```kotlin
unified_search(query = "repository pattern", scopes = ["code_specs:interface", "code_specs:class"])
```

## Saving Knowledge

**After implementing, save decisions.**

**Simple facts:** `add_memory_link(from = "ThreadRepository", relation = "uses", to = "UUIDv7")`

**Complex knowledge:** `build_memory_from_text(content = "...")` - extracts entities/relationships via LLM (use sparingly)

## Best Practices

**Do:**
- ✅ Search BEFORE implementing
- ✅ Use semantic queries ("thread repository pattern" not "ThreadRepository.kt")
- ✅ Save decisions after implementing
- ✅ Ask user to re-index after significant domain changes
- ✅ Use specific scopes (code_specs:interface vs code_specs)

**Don't:**
- ❌ Read files blindly without searching
- ❌ Assume search is up-to-date (check git status)
- ❌ Use `build_memory_from_text` for simple facts (use `add_memory_link`)
- ❌ Forget to save decisions
