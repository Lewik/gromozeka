# Agent Architecture

Knowledge base about agent structure, composition, and design in Gromozeka system.

## Core Concept: Identity + Role Composition

**Gromozeka uses a compositional agent architecture:**

- **One Identity** - shared by all agents, defined in `common-prompt.md`
- **Multiple Roles** - composable building blocks, mixed as needed

**Identity** is minimal and context-focused:
```
"You are Gromozeka, a multi-armed AI assistant."
```

We don't emphasize personality or character - just function and context.

**Roles** are functional specializations that define what an agent can do. Roles compose - one agent can have multiple roles.

### Example: Multi-Role Agent

```json
{
  "name": "Full-Stack Developer",
  "prompts": [
    "common-prompt.md",           // Identity (shared)
    "developer.role.md",          // Role: Code writing
    "architect.role.md",          // Role: System design
    "repository.role.md",         // Role: Data persistence
    "env"                         // Context (always last)
  ]
}
```

This agent can design systems, write code, AND implement database layer.

## File Naming Conventions

**Extension-based type system:**

| Extension | Purpose | Example |
|-----------|---------|---------|
| `.agent.json` | Agent configuration | `architect.agent.json` |
| `.identity.md` | Shared identity (one for all agents) | `common.identity.md` |
| `.role.md` | Role definition (composable) | `developer.role.md` |
| `.knowledge.md` | Reference information, project knowledge | `architecture.knowledge.md`, `project-common.knowledge.md` |
| `.md` | Reserved prompts | `common-prompt-prefix.md` |

**Why extensions matter:**
- Immediately visible file purpose
- Easy filtering (`*.role.md` = all available roles)
- Clear separation of concerns
- Identity stands out as special (shared by all)

## Directory Structure

```
presentation/src/jvmMain/resources/
├── agents/
│   ├── default-gromozeka.agent.json    # Builtin agent config
│   └── meta.agent.json                 # Builtin agent config
└── prompts/
    ├── common-prompt-prefix.md         # Reserved (currently empty)
    ├── common.identity.md              # Identity + philosophy
    ├── developer.role.md               # Role: Developer
    ├── default.role.md                 # Role: Default coordinator
    ├── meta.role.md                    # Role: Meta-agent
    └── agent-architecture.knowledge.md # This document

.gromozeka/
├── agents/
│   ├── architect.agent.json            # Project agent config
│   ├── repository.agent.json           # Project agent config
│   └── ...                             # More project agents
└── prompts/
    ├── project-common.knowledge.md     # Project-wide knowledge
    ├── architect.role.md               # Role: Architect
    ├── repository.role.md              # Role: Repository
    ├── ui.role.md                      # Role: UI
    └── ...                             # More roles & knowledge
```

## Agent Configuration Schema

Use this schema as reference when creating or validating agent configurations.

**JSON Schema:**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Gromozeka Agent Configuration",
  "description": "Configuration schema for Gromozeka AI agents",
  "type": "object",
  "required": ["id", "name", "prompts"],
  "properties": {
    "id": {
      "type": "string",
      "description": "Unique agent identifier with scope prefix",
      "pattern": "^(builtin|global|project):.+\\.agent\\.json$",
      "examples": [
        "builtin:meta.agent.json",
        "project:architect.agent.json"
      ]
    },
    "name": {
      "type": "string",
      "description": "Human-readable agent name displayed in UI",
      "minLength": 1,
      "examples": [
        "Architect",
        "Meta Agent",
        "Архитектор"
      ]
    },
    "description": {
      "type": "string",
      "description": "Brief description of agent's purpose",
      "examples": [
        "Domain architect",
        "Agent builder"
      ]
    },
    "prompts": {
      "type": "array",
      "description": "Ordered list of prompts to load (concatenated in order)",
      "minItems": 1,
      "items": {
        "type": "string",
        "description": "Prompt reference with scope prefix or 'env' for environment context",
        "oneOf": [
          {
            "const": "env",
            "description": "Environment context (platform, date, paths) - MUST be last"
          },
          {
            "pattern": "^builtin:.+\\.(identity|role|knowledge)\\.md$",
            "description": "Builtin prompt (shipped with Gromozeka)"
          },
          {
            "pattern": "^builtin:.+\\.md$",
            "description": "Other builtin prompt (reserved, prefix, etc.)"
          },
          {
            "pattern": "^global:.+\\.(identity|role|knowledge)\\.md$",
            "description": "Global user prompt (~/.gromozeka/)"
          },
          {
            "pattern": "^project:.+\\.(identity|role|knowledge)\\.md$",
            "description": "Project prompt (.gromozeka/)"
          },
          {
            "pattern": "^project:.+\\.md$",
            "description": "Other project prompt"
          }
        ]
      }
    },
    "createdAt": {
      "type": "string",
      "format": "date-time",
      "description": "ISO-8601 timestamp when agent was created"
    },
    "updatedAt": {
      "type": "string",
      "format": "date-time",
      "description": "ISO-8601 timestamp when agent was last updated"
    }
  },
  "additionalProperties": false
}
```

**Example configuration:**

```json
{
  "id": "project:architect.agent.json",
  "name": "Архитектор",
  "description": "Domain architect",
  "prompts": [
    "builtin:common-prompt-prefix.md",
    "builtin:common.identity.md",
    "builtin:developer.role.md",
    "project:project-common.knowledge.md",
    "project:architect.role.md",
    "env"
  ],
  "createdAt": "2025-11-22T19:11:52Z",
  "updatedAt": "2025-12-06T00:00:00Z"
}
```

### Prompt Assembly Order

Prompts concatenate in the order specified:

```
1. common-prompt-prefix.md   → For rare special egde cases
2. common-prompt.md          → Identity + philosophy + coordination
3. [role].role.md            → One or more roles (composable)
4. project-common-prompt.md  → Project-specific rules
5. [project-role].role.md    → Project-specific roles
6. env                       → Current context (ALWAYS LAST)
```

**Why ENV is last:** It contains current date, paths, platform - should override any examples in prompts.

### Required Elements

**MUST have:**
- `common.identity.md` - Identity and philosophy
- At least one `.role.md` - What agent can do
- `env` as **last** prompt - Current context

**SHOULD have:**
- `project-common-prompt.md` if project-specific agent
- Multiple roles if cross-domain expertise needed

## Type System (Builtin/Global/Project)

**1. Builtin** - Shipped with Gromozeka
- **Location:** `presentation/src/jvmMain/resources/`
- **Content:** Foundation agents and universal roles
- **Example:** `developer.role.md`, `meta.role.md`

**2. Global** - User-wide across all projects
- **Location:** `~/.gromozeka/`
- **Content:** User's personal roles and preferences
- **Example:** Personal code review checklist

**3. Project** - Project-specific, versioned with code
- **Location:** `.gromozeka/` in project root
- **Content:** Project-specific roles and knowledge
- **Example:** `architect.role.md` with Clean Architecture rules

## Role Design Principles

### Single Responsibility per Role

Each **role** should have one clear responsibility. Agents can have multiple roles, but each role stays focused.

**Good examples:**
- `researcher.role.md` - Information gathering and analysis
- `writer.role.md` - Content creation and editing
- `reviewer.role.md` - Quality checking and feedback

**Why:** Roles are building blocks. Keep them atomic for maximum composability. A focused role can be reused in many different agent combinations.

### Role Composability

Roles should work independently AND together:

```json
// Valid: Single role
"prompts": ["common-prompt.md", "developer.role.md", "env"]

// Valid: Multiple roles
"prompts": [
  "common-prompt.md", 
  "developer.role.md",
  "architect.role.md",
  "env"
]
```

**Design roles to be:**
- **Independent** - Each role works alone
- **Complementary** - Roles enhance each other when combined
- **Non-conflicting** - No contradictory instructions

### Dense, High-Signal Content

- Every sentence adds value
- No redundant instructions
- Concrete examples that clarify, not repeat
- Avoid laundry lists of edge cases

**Note:** Dense ≠ short. Optimize for signal-to-noise ratio, not length.

### Clear Boundaries

Define what role CAN and CANNOT do:

```markdown
## Scope

**Read access:**
- `domain/` module

**Can modify:**
- `domain/model/` - Entity definitions
- `domain/repository/` - Repository interfaces

**Cannot touch:**
- `infrastructure/` - Implementation layers
```

## Role Prompt Template

**Recommended structure for `.role.md` files:**

```markdown
# Role: [Name]

**Alias:** [Short name for UI]

**Expertise:** [Knowledge domains]

**Scope:** [Module/boundaries]

**Primary responsibility:** [Main task]

## Responsibilities

- Task 1
- Task 2
- Task 3

## Scope

**Read access:**
- [Files/dirs/tools]

**Can modify:**
- [What role writes]

**Cannot touch:**
- [Forbidden areas]
```

**Optional sections** (add when they clarify):
- Detailed workflow
- Examples (✅ Good / ❌ Bad)
- Key principles
- Verification steps

**DO NOT include:**
- ❌ `**Identity:** You are...` - Identity is in `common-prompt.md`
- ❌ Personality traits - Keep functional
- ❌ Redundant philosophy - That's in common prompts

## Creating New Agents

### Step 1: Identify Needed Roles

Ask:
- What expertise is needed?
- Can existing roles be composed?
- Or do we need a new role?

### Step 2: Compose or Create

**If roles exist:**
```json
{
  "prompts": [
    "common-prompt.md",
    "existing-role-1.role.md",
    "existing-role-2.role.md",
    "env"
  ]
}
```

**If new role needed:**
1. Create `new-role.role.md` following template
2. Add to agent config
3. Test independently
4. Document in Knowledge Graph

### Step 3: Validate

- [ ] Role has single clear responsibility
- [ ] Works independently
- [ ] Works with other roles (if multi-role)
- [ ] No identity/personality in role file
- [ ] ENV is last prompt
- [ ] JSON config is valid
- [ ] Documented in Knowledge Graph

## Multi-Role Agent Examples

### Example 1: Data Layer Expert

```json
{
  "name": "Data Layer Expert",
  "prompts": [
    "common-prompt.md",
    "developer.role.md",         // Knows code patter
    "architect.role.md",         // Designs interfaces
    "repository.role.md",        // Implements persistence
    "env"
  ]
}
```

Can design repository interfaces AND implement them.

### Example 2: Full Application Developer

```json
{
  "name": "Full App Developer",
  "prompts": [
    "common-prompt.md",
    "developer.role.md",
    "architect.role.md",
    "business-logic.role.md",
    "repository.role.md",
    "ui.role.md",
    "env"
  ]
}
```

Can work across entire application stack.

**When to use:** Prototyping, small projects, or when coordination overhead exceeds parallel work benefits.

**When NOT to use:** Large projects where specialized agents work in parallel on different modules.

## Integration with Knowledge Graph

### Before Creating Role

Search for similar patterns:
```
unified_search("repository patterns in Kotlin")
```

### After Creating Role

Document the decision:
```
add_memory_link(
  from = "architect.role.md",
  relation = "defines",
  to = "Domain layer interfaces",
  summary = "Role for designing Clean Architecture domain layer"
)
```

## Quality Checklist

Before deploying new role:

- [ ] Single clear responsibility
- [ ] No identity/personality statements
- [ ] Clear scope (read/write/forbidden)
- [ ] Works independently
- [ ] Compatible with other roles
- [ ] Follows naming convention (`.role.md`)
- [ ] Examples are concrete and useful
- [ ] Documented in Knowledge Graph

## Common Patterns

### Pattern: Specialized Development Agent

```json
{
  "prompts": [
    "common-prompt.md",
    "developer.role.md",           // Base code skills
    "project-common-prompt.md",    // Kotlin + Spring
    "specific-role.role.md",       // Specialization
    "env"
  ]
}
```

Most Gromozeka development agents follow this pattern.

### Pattern: Coordinator Agent

```json
{
  "prompts": [
    "common.identity.md",
    "default.role.md",    // Delegation, coordination
    "env"
  ]
}
```

No development roles - focuses on task routing and agent creation.

### Pattern: Meta Agent

```json
{
  "prompts": [
    "common-prompt.md",
    "developer.role.md",                    // Understands code
    "agent-architecture.knowledge.md",      // This document
    "meta.role.md",                         // Agent design
    "project-agent-design.knowledge.md",    // Project patterns
    "env"
  ]
}
```

Loads knowledge bases about agent design itself.

## Remember

- **Identity is shared** - Don't repeat it in roles
- **Roles compose** - Design for reuse
- **ENV always last** - Current context overrides examples
- **Extensions matter** - `.agent.json`, `.role.md`, `.knowledge.md`
- **One responsibility per role** - But agents can have multiple roles
- **Keep roles functional** - Not personality-based
