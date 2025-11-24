# ADR-Domain-002: Value Objects for Type-Safe IDs

**Status:** Accepted

**Date:** 2025-01-20

**Context:**

String-based IDs are error-prone:
```kotlin
// Easy to confuse
fun loadMessages(threadId: String, messageId: String)

// Can accidentally pass wrong ID
loadMessages(messageId, threadId) // Compiles, but wrong!
```

Need compile-time protection against ID type confusion.

**Decision:**

Use Kotlin inline value classes for all entity IDs:

```kotlin
@JvmInline
value class ThreadId(val value: String)

@JvmInline
value class MessageId(val value: String)

// Now this won't compile:
fun loadMessages(threadId: ThreadId, messageId: MessageId)
loadMessages(messageId, threadId) // Compile error!
```

**Consequences:**

### Positive
- ✅ Compile-time type safety (impossible to confuse IDs)
- ✅ Zero runtime overhead (inline classes)
- ✅ Self-documenting signatures
- ✅ IDE autocomplete helps

### Negative
- ❌ Slight boilerplate when creating IDs
- ❌ Need conversions at boundaries (JSON, DB)

**Alternatives Considered:**

### Alternative 1: Plain String
**Description:** Just use `String` for all IDs
**Rejected because:** No type safety, easy to confuse different entity IDs

### Alternative 2: Data classes
**Description:** `data class ThreadId(val value: String)`
**Rejected because:** Runtime overhead (object allocation)

### Alternative 3: Typealias
**Description:** `typealias ThreadId = String`
**Rejected because:** No compile-time safety (just an alias)

**Related Decisions:**
- ADR-Domain-001: Repository pattern

---
🤖 Generated with [Claude Code](https://claude.ai/code)
