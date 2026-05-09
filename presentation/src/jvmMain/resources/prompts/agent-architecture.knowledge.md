# Agent Architecture

Reference document for how agent prompts and agent definitions are structured in Gromozeka.

## Composition Model

Gromozeka agents are assembled from ordered prompt layers.

Common builtin layers:
- `common-prompt-prefix.md` for rare reserved prefix behavior
- `common.identity.md` for shared identity and communication style
- `common.knowledge.md` for shared operational rules
- `common.multi-agent.knowledge.md` for delegation and coordination rules
- `memory.knowledge.md` for the runtime memory contract

Specialized layers:
- `*.role.md` for capability and responsibility
- `*.knowledge.md` for reference information
- `env` for current runtime context

## Default Stack Shapes

Typical code-writing specialist:

```json
[
  "builtin:common-prompt-prefix.md",
  "builtin:common.identity.md",
  "builtin:common.knowledge.md",
  "builtin:memory.knowledge.md",
  "builtin:developer.role.md",
  "project:project-common.knowledge.md",
  "project:<specialist>.role.md",
  "env"
]
```

Coordinator or agent-constructor:

```json
[
  "builtin:common-prompt-prefix.md",
  "builtin:common.identity.md",
  "builtin:common.knowledge.md",
  "builtin:common.multi-agent.knowledge.md",
  "...",
  "env"
]
```

Rules:
- keep `env` last
- load `common.multi-agent.knowledge.md` only for agents that actually coordinate or create other agents
- each prompt in the stack should add unique value

## File Types

| File type | Purpose | Example |
|-----------|---------|---------|
| `.agent.json` | Agent definition | `architect.agent.json` |
| `.identity.md` | Shared identity | `common.identity.md` |
| `.role.md` | Role and responsibility | `developer.role.md` |
| `.knowledge.md` | Reference knowledge | `architecture.knowledge.md` |
| `.md` | Reserved prompt | `common-prompt-prefix.md` |

## Storage Layout

Builtin:

```text
presentation/src/jvmMain/resources/
├── agents/
└── prompts/
```

Project:

```text
.gromozeka/
├── agents/
└── prompts/
```

Global user prompts and agents live in `~/.gromozeka/`.

## Scope Rules

- `builtin:` → shipped with the application
- `global:` → user-wide local customization
- `project:` → project-specific, versioned with the repository

Prompt references are concatenated in the order listed in the agent JSON.

## Agent Definition Rules

Agent ids for builtin and filesystem-backed agents are derived from file names.

Implications:
- file name stability matters
- renaming an `.agent.json` file changes the persisted id
- prefer changing prompt content, display name, and description before renaming the file itself

Minimal fields:
- `id`
- `name`
- `prompts`

Common optional fields:
- `description`
- `aiProvider`
- `modelName`
- `maxTokens`
- `thinking`
- `outputConfig`

## Ownership Rules

Keep concepts in one place:
- shared behavior across projects → builtin prompts
- current project reality → `project-common.knowledge.md`
- project-specific responsibilities and workflows → specialist `.role.md`
- agent roster → `agents-roster.knowledge.md`
- architecture reference → `architecture.knowledge.md`

If mutable information is duplicated across prompts, prompt drift is inevitable.

## Role Design Rules

- one role should own one clear responsibility
- use multiple roles only when an agent truly needs combined expertise
- do not repeat identity or shared policy inside specialist roles
- project roles should describe ownership, workflow, and constraints, not restate the whole stack

## Validation Checklist

Before saving agent changes, verify:
1. every referenced prompt exists
2. prompt order still makes sense
3. `env` is last
4. no mutable project fact is duplicated in multiple prompts
5. `common.multi-agent.knowledge.md` is loaded only where it is actually needed
