# Knowledge Graph: Your Organizational Memory

**Your persistent memory stored in Neo4j graph database.**

Shared memory across all agents - prevents reinventing solutions, enables learning from past decisions.

## What's Stored

**All stored as MemoryObject entities in Neo4j with same vectorization mechanism.**

**1. Memory Objects** - Facts, concepts, technologies, decisions
- Created via `add_memory_link` or `build_memory_from_text`
- Vectorized: entity name/summary
- Examples: "Spring AI", "Neo4j", "Clean Architecture"

**2. Code Specs** - indexed code symbols from the project
- Classes, interfaces, methods, properties, constructors
- **Indexed:** Symbol name, KDoc, file location, relationships
- **NOT indexed:** Method bodies, local variables
- Vectorized: KDoc + structure summary

**3. Relationships** - Connections between entities
- Code: `DEFINES_METHOD`, `HAS_PROPERTY`, `IMPLEMENTS`, `EXTENDS`, `RETURNS_TYPE`
- Knowledge: `uses`, `implements`, `related to`

**Vectorization (same for all):** OpenAI text-embedding-3-large (3072 dimensions) → enables semantic search via hybrid BM25 + vector similarity.

## How to Search

**Use `unified_search`** for knowledge objects, code specs, and conversation history.

Use exact tool fields, not pseudo-parameters.

### Common Queries

**Find domain specs:**
```json
{
  "query": "thread repository",
  "entityTypes": ["CODE_SPECS_INTERFACE"],
  "searchMode": "HYBRID",
  "limit": 5,
  "projectIds": [],
  "conversationIds": [],
  "roles": [],
  "threadId": "",
  "dateFrom": "",
  "dateTo": "",
  "asOf": "",
  "useReranking": true
}
```

**Research decisions:**
```json
{
  "query": "pagination decision",
  "entityTypes": ["MEMORY_OBJECTS", "CONVERSATION_MESSAGES"],
  "searchMode": "HYBRID",
  "limit": 5,
  "projectIds": [],
  "conversationIds": [],
  "roles": [],
  "threadId": "",
  "dateFrom": "",
  "dateTo": "",
  "asOf": "",
  "useReranking": true
}
```

**Discover related code:**
```json
{
  "query": "repository pattern",
  "entityTypes": ["CODE_SPECS_INTERFACE", "CODE_SPECS_CLASS"],
  "searchMode": "HYBRID",
  "limit": 5,
  "projectIds": [],
  "conversationIds": [],
  "roles": [],
  "threadId": "",
  "dateFrom": "",
  "dateTo": "",
  "asOf": "",
  "useReranking": true
}
```

## Index Synchronization

- ✅ **Synchronized:** `git status` clean → all committed code indexed
- ⚠️ **Out of sync:** Uncommitted changes → index may be stale
- 🔄 **Re-index:** User triggers via UI button (`index_domain_to_graph` tool)

**Ask user to re-index when:**
- You modified domain code significantly
- Search results seem outdated
- New interfaces/classes are missing from search results

**How to ask:**
> "I modified domain interfaces. Please re-index domain code via UI button to update search index."

**Don't ask when:**
- Modified only implementation code (not domain)
- Minor changes (typos, formatting)

## Code Navigation Pattern

**Search first, read second.**

```json
{
  "query": "Thread model structure",
  "entityTypes": ["CODE_SPECS_CLASS"],
  "searchMode": "HYBRID",
  "limit": 5,
  "projectIds": [],
  "conversationIds": [],
  "roles": [],
  "threadId": "",
  "dateFrom": "",
  "dateTo": "",
  "asOf": "",
  "useReranking": true
}
```

Then read the specific file if needed.

**Why:** Saves tokens, finds related patterns, gives overview.

**Read directly when:**
- the user asks for a specific file
- you are about to modify a known file
- you already know the exact location from search results

## Saving Knowledge

**After implementing, save decisions.**

**Simple facts:** `add_memory_link(from = "ThreadRepository", relation = "uses", to = "UUIDv7")`

**Complex knowledge:** `build_memory_from_text(content = "...")` - extracts entities/relationships via LLM (use sparingly)

## Best Practices

**Do:**
- ✅ Search BEFORE implementing
- ✅ Use semantic queries ("thread repository pattern" not just a file name)
- ✅ Save decisions after implementing
- ✅ Ask user to re-index after significant domain changes
- ✅ Use specific scopes (`CODE_SPECS_INTERFACE` vs `CODE_SPECS`)

**Don't:**
- ❌ Read files blindly without searching when discovery is the real task
- ❌ Assume search is up-to-date without considering git state
- ❌ Use `build_memory_from_text` for simple facts (use `add_memory_link`)
- ❌ Forget to save decisions
