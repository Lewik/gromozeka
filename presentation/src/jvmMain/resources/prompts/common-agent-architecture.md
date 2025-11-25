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

### Prompt
**Prompt** is a single reusable markdown fragment - a building block for agents.

**Key components:**
- **Unique identifier** - hash of file path or UUID for inline
- **Name** - human-readable label
- **Content** - markdown text defining behavior/knowledge
- **Type** - storage location (Builtin/Global/Project)

One prompt can be reused across multiple agents. For example, "common-prompt.md" is included in all agents.

### Storage Locations

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

## Prompt File Structure

### Naming Convention
- **Builtin prompts:**
  - `common-prompt.md` - Philosophy for ALL agents
  - `common-agent-architecture.md` - This document
  - `meta-agent.md` - Specific to meta-agent role
  - `default-agent.md` - Default assistant behavior

- **Project prompts:**
  - `project-common-prompt.md` - Shared across project agents
  - `project-agent-architecture.md` - Project-specific agent design
  - `[role]-agent.md` - Specific agent roles (architect-agent.md, etc.)

### Prompt Assembly Order

Prompts are concatenated in order specified in agent JSON configuration:

```json
"prompts": [
  "env",                               // Always first - environment context
  "common-prompt.md",                  // Gromozeka philosophy
  "project-common-prompt.md",          // Project-wide rules
  "project-agent-architecture.md",     // Project architecture
  "architect-agent.md"                 // Role-specific behavior
]
```

**Order matters:** Later prompts can reference concepts from earlier ones.

## Agent Configuration Requirements

### Required Elements

**1. Environment Context**
All agents MUST include `"env"` as first prompt. Without ENV context, agents lack awareness of project paths, platform and current date.

**2. Common Philosophy**
All agents SHOULD include common-prompt.md to maintain consistent Gromozeka philosophy.

**3. Role Definition**
Each agent MUST have at least one role-specific prompt defining its unique responsibilities.

### JSON Configuration Schema

```json
{
  "id": "agent-identifier",
  "name": "Human-readable name",
  "prompts": [
    "env",
    "path/to/prompt1.md",
    "path/to/prompt2.md"
  ],
  "description": "Brief agent description",
  "createdAt": "ISO-8601 timestamp",
  "updatedAt": "ISO-8601 timestamp"
}
```

## Agent Design Principles

### Single Responsibility
Each agent should have a clear, focused area of expertise. Don't create Swiss-army-knife agents.

**Good:** Repository Agent handles data persistence only
**Bad:** Full-stack Agent doing UI + DB + Business Logic

### Composability
Prompts should be reusable building blocks. Extract common patterns into shared prompts.

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
- Available tools

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

**Location:** `.gromozeka/adr/coordination/` for agent architecture decisions

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

Before deploying new agent, verify:

- [ ] Has clear single responsibility
- [ ] Includes `env` as first prompt
- [ ] Uses common-prompt.md for philosophy
- [ ] Has well-defined scope (read/write/forbidden)
- [ ] Follows naming conventions
- [ ] JSON configuration is valid
- [ ] Documented in Knowledge Graph
- [ ] ADR created if significant decision