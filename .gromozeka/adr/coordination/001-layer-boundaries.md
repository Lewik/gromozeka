# ADR-Coordination-001: Strict Layer Boundary Enforcement

**Status:** Accepted

**Date:** 2025-01-20

**Context:**

Multi-agent development requires clear boundaries:
- Architect Agent → :domain
- Repository Agent → :infrastructure-db
- UI Agent → :presentation
- Spring AI Agent → :infrastructure-ai

Without enforcement:
- Agents can accidentally cross boundaries
- Circular dependencies possible
- Parallel work breaks

**Decision:**

Enforce dependency rules through Gradle module structure:

```
:domain                    → NO dependencies
:application               → :domain
:infrastructure-db         → :domain
:infrastructure-ai         → :domain
:presentation              → :domain, :application
```

**Build fails if violated.**

**Consequences:**

### Positive
- ✅ Parallel agent work (no conflicts)
- ✅ Clear responsibilities
- ✅ Domain testable in isolation
- ✅ Build catches violations early

### Negative
- ❌ Sometimes need extra interface for abstraction
- ❌ Can't "just import" from another layer

**Alternatives Considered:**

### Alternative 1: Convention-based (no enforcement)
**Description:** Document rules, trust agents to follow
**Rejected because:** Easy to violate accidentally, no automatic checks

### Alternative 2: Modular monolith (no layers)
**Description:** Single module, organize by features
**Rejected because:** Harder to coordinate multiple agents, unclear boundaries

### Alternative 3: Separate repositories per layer
**Description:** Each layer in its own Git repo
**Rejected because:** Too much overhead, slows down development

**Related Decisions:**
- ADR-Coordination-002: Agent specialization model (to be created)

---
🤖 Generated with [Claude Code](https://claude.ai/code)
