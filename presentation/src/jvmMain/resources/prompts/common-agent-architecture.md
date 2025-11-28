# Common Agent Architecture

This document defines how agents are structured, created, and organized in Gromozeka system.

## Core Terminology

### Agent
**Agent** is a reusable role template with specific expertise and responsibilities.

**Examples:** Code Reviewer, Security Expert, Architect, Research Assistant

**Key components:**
- **Unique identifier** - hash-based, UUID, or string
- **Name** - role displayed to user and used to address the agent (e.g., "Domain Architect", "meta")
- **Description** - optional explanation of agent's purpose
- **Prompts** - ordered list of prompt fragments that define behavior
- **Type** - storage location (Builtin/Global/Project)

### Prompt-Fragment
**Prompt-Fragment** is a single reusable markdown fragment - a building block for agents.

**Key components:**
- **Unique identifier** - hash of file path or UUID for inline
- **Name** - human-readable label
- **Content** - markdown text defining behavior/knowledge
- **Type** - storage location (Builtin/Global/Project)

One prompt can be reused across multiple agents. For example, "common-prompt.md" is included in all agents.

### Type (Builtin/Global/Project)

**1. Builtin** - Shipped with Gromozeka
- **Where:** Gromozeka resources directory
  - `presentation/src/jvmMain/resources/agents/`
  - `presentation/src/jvmMain/resources/prompts/`
- **What:** Foundation agents (meta-agent, default assistant) and base prompts
- **Example:** Meta-agent, common prompts

**2. Global** - User-wide across all projects
- **Where:** User home directory (`~/.gromozeka/` or similar)
- **What:** User's personal agent templates and prompt library
- **Example:** Personal code review checklist, preferred communication style

**3. Project** - Project-specific, versioned with code
- **Where:** Project root
  - `.gromozeka/agents/`
  - `.gromozeka/prompts/`
- **What:** Project-specific agents tied to codebase
- **Example:** Architect agent knowing project's Clean Architecture rules

## Prompt-Fragment File Structure

- **Builtin prompts:**
  - `common-prompt.md` - Common prompt for ALL agents содержащий корневую философию Gromozeka и необходимый минимум для работы агентов
  - `common-agent-architecture.md` - This document
  - `meta-agent.md` - Specific to meta-agent
  - `default-agent.md` - Default agent without specific role

- **Project prompts:**
  - `project-common-prompt.md` - Shared across project agents
  - `project-agent-architecture.md` - Project-specific agent design
  - `[role]-agent.md` - Specific agent roles (architect-agent.md, etc.)

### Prompt Assembly Order

Prompts are concatenated in order specified in agent JSON configuration:

```json
"prompts": [
  "env",                               // Always first - environment context
  "common-prompt.md",                  // Gromozeka base
  "agents-roster.md"                   // awareness of all agents
  "project-common-prompt.md",          // Project-wide rules
  "architect-agent.md"                 // Role-specific behavior
]
```

## Agent Configuration Requirements

### Required Elements

**1. Environment Context**
All agents MUST include `"env"` as first prompt. Without ENV context, agents lack awareness of project paths, platform and current date.

**2. Common Prompt**
All agents SHOULD include common-prompt.md to work within Gromozeka.

**3. Role Definition**
Each agent MUST have at least one role-specific prompt defining its unique responsibilities.

### JSON Configuration Schema

```json
{
  "id": "agent-identifier",
  "name": "Human-readable name",
  "description": "Brief agent description",
  "prompts": [
    "env",
    "path/to/prompt1.md",
    "path/to/prompt2.md"
  ],
  "createdAt": "ISO-8601 timestamp",
  "updatedAt": "ISO-8601 timestamp"
}
```

## Agent Design Principles

### Single Responsibility
Each agent should have a clear, focused area of expertise. Don't create Swiss-army-knife agents.

**Good:** Repository Agent handles data persistence only
**Bad:** Full-stack Agent doing UI + DB + Business Logic

### Dense, High-Signal Context
- Every sentence adds value
- No redundant instructions
- Abstract examples with high information density
- Concrete examples that clarify, not repeat
- Avoid laundry lists of edge cases
**Note:** Dense ≠ short. You can be both dense and detailed. Don't artificially constrain length - optimize for signal-to-noise ratio.

### Composability
Prompt-fragments should be reusable building blocks. Extract common patterns into shared prompt-fragmentss.

### Progressive Enhancement
Start with common base, add project-specific, then role-specific prompts.

```
Common → Project → Role
```

### Clear Boundaries
Define what agent CAN and CANNOT do. Specify:
- Read access paths
- Write access paths
- Forbidden operations

## Agent Prompt Template

**Recommended structure for role-specific prompts:**

```markdown
# [Agent Role]

**Identity:** You are [who this agent is]

[1-2 sentences: why agent exists, what agent does]

## Responsibilities

- Primary task 1
- Primary task 2
- Primary task 3

## Scope

**Read access:**
- [Files/dirs/tools]

**Can modify:**
- [What agent writes]

**Cannot touch:**
- [Forbidden areas]
```

**Optional additions** (use when they clarify work):
- Detailed Responsibilities with categories
- Guidelines (domain-specific best practices)
- Step-by-step Workflow
- Examples (✅ Good / ❌ Bad patterns)
- Key Principles (Remember section)

**Adapt this template as needed.** It's a starting point, not a straitjacket. Creativity and problem-solving matter more than rigid adherence.

## Architecture Decision Records (ADR)

**Gromozeka has built-in ADR workflow** for documenting architectural decisions about agents.

### ADR Structure for Agent Decisions

**Location:** `.gromozeka/adr/agents/` for agent architecture decisions

**When to create ADR:**
- Adding new agent type
- Changing prompt structure
- Modifying agent coordination patterns
- Significant philosophy changes

**Template sections:**
- Context: Why agent/change needed
- Decision: What was chosen
- Consequences: Impact on system
- Alternatives: Other approaches considered

## Integration with Knowledge Graph

### Before Creating Agent
Search for similar agents or patterns:
```
unified_search("agent patterns for code review")
```

### After Creating Agent
Document the decision:
```
build_memory_from_text("""
Created Repository Agent for data persistence.
Responsibilities: Implement repository interfaces, handle DB/vector/graph storage.
Key decision: Separate from Business Logic Agent for clear layer separation.
""")
```

## Quality Checklist for New Agents

Before deploying a new agent, verify:

- [ ] Has clear single responsibility
- [ ] Includes `env` as first prompt
- [ ] Uses common-prompt.md
- [ ] Has well-defined scope (read/write/forbidden)
- [ ] Follows naming conventions
- [ ] JSON configuration is valid
- [ ] Documented in Knowledge Graph
- [ ] ADR created if significant decision

## UI Agent Guidelines for Flux-Based Frameworks

**Applies to:** React, Vue, Compose, SwiftUI, Flutter, and any Flux-architecture framework

All UI agents working with declarative/reactive frameworks MUST include **Declarative UI Principles** section emphasizing:

### Core Principle: UI = function(State)

**Unidirectional data flow:**
```
Action → ViewModel → State → UI
           ↑                  ↓
           └─── User Event ───┘
```

### Required Guidelines

**1. State as Single Source of Truth**
- UI reads state, never modifies directly
- State updates trigger UI re-render automatically
- Immutable state patterns

**2. Unidirectional Flow**
- Events go UP (callbacks, actions)
- State flows DOWN (StateFlow, observables)
- No bidirectional bindings without explicit control

**3. No Imperative Manipulation**
- ❌ NO: `setText()`, `setVisibility()`, `updateList()`
- ✅ YES: Declarative composition based on state

**4. Include Anti-Pattern Examples**
Show what NOT to do:
```kotlin
// ❌ WRONG: Imperative manipulation
textField.setText(message)
button.setEnabled(false)

// ✅ CORRECT: Declarative composition
@Composable
fun UI(viewModel: VM) {
    val message by viewModel.message.collectAsState()
    Text(message)  // UI = function(state)
}
```

### Why This Matters

- **Predictable:** Same state = same UI, always
- **Testable:** Test state changes, not UI interactions
- **No race conditions:** Single source of truth
- **Framework optimized:** Automatic re-render optimization

**When creating new UI agents for Flux-based frameworks, always include these principles in agent prompt.**