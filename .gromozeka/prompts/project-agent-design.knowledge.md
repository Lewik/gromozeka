# Gromozeka Project Agent Design Notes

Project-specific guidance for designing and maintaining the Gromozeka agent system.

## What This Document Is For

Use this document to keep project agent prompts:
- compositional
- thin
- non-contradictory
- aligned with the current codebase and workflow

It is not a replacement for:
- `agent-architecture.knowledge.md` for generic prompt composition rules
- `architecture.knowledge.md` for layer definitions
- `agents-roster.knowledge.md` for current specialist roster

## Composition Defaults

For code-writing project agents, the default stack should usually be:

```json
[
  "builtin:common-prompt-prefix.md",
  "builtin:common.identity.md",
  "builtin:common.knowledge.md",
  "builtin:knowledge-graph.knowledge.md",
  "builtin:developer.role.md",
  "project:project-common.knowledge.md",
  "project:<specialist>.role.md",
  "env"
]
```

For non-domain module implementation agents, the preferred stack usually inserts the shared module-boundary prompt:

```json
[
  "builtin:common-prompt-prefix.md",
  "builtin:common.identity.md",
  "builtin:common.knowledge.md",
  "builtin:knowledge-graph.knowledge.md",
  "builtin:developer.role.md",
  "project:project-common.knowledge.md",
  "project:module-implementation.knowledge.md",
  "project:<specialist>.role.md",
  "env"
]
```

Use extra prompts only when they add unique information.

Add `builtin:common.multi-agent.knowledge.md` only for agents that actually coordinate other agents or need explicit delegation rules.

## Keep Information In One Place

Prompt design rule:
- stable cross-project behavior belongs in builtin prompts
- current project reality belongs in `project-common.knowledge.md`
- shared writable-surface and escalation rules for non-domain module implementers belong in `module-implementation.knowledge.md`
- lane ownership and build commands belong in specialist `.role.md`
- roster belongs in `agents-roster.knowledge.md`
- architecture belongs in `architecture.knowledge.md`

If a fact changes often, it should exist in one prompt only.

Treat prompt content as different classes of information:
- stable cross-project principles
- current project reality
- role-specific workflow and ownership
- temporary task-specific examples

Do not mix these casually in one file when they can be kept orthogonal.

## Anti-Duplication Rules

Avoid repeating the same material across multiple prompts:
- technology stack summaries
- Clean Architecture explanations
- repository synchronization workflow
- team roster
- code-as-contract philosophy

Reference the prompt that owns the concept instead of copying its content again.

If a new instruction is needed, first ask:
- does this idea already exist elsewhere in the stack?
- is the problem duplication, contradiction, or missing guidance?
- can the fix be done by deleting or narrowing existing text instead of adding more text?

## Drift Control

When prompts are updated, prefer current operational truth over historical wording.

Typical drift smells:
- old provider/runtime names after a backend migration
- outdated branch names or checkout roles
- stale build commands or test names
- prompts describing removed directories or integrations

If runtime facts changed, fix the shared knowledge prompt instead of patching the same fact in many role prompts.

## Prompt Change Workflow

When changing prompts:
1. Identify the concrete failure mode or desired behaviour
2. Find the owning prompt for that concept
3. Prefer the smallest effective patch
4. Prefer removing or narrowing conflicting text before adding new text
5. Re-check the full assembled stack for duplication and contradictions
6. Validate the changed behaviour, not just file syntax

Prompt changes should be driven by observed behaviour, user feedback, or current runtime truth.
Avoid speculative prompt ornamentation.

## Prompt Editing Heuristics

- Prefer positive defaults over reactive prohibitions
- Prefer conditional defaults over absolute rules unless the rule is a true invariant
- Prefer compact examples that constrain behaviour over long didactic explanations
- Prefer explicit conflict resolution when heuristics are likely to collide
- Prefer one strong instruction over three weaker paraphrases of the same idea

OpenAI-style practical takeaway:
- stable instructions and variable project facts should not live in the same conceptual layer
- prompt improvement should be failure-driven
- adding more guidance is not always the right fix; sometimes the right fix is removal

## When To Create A New Agent

Create a new agent only if at least one of these is true:
- it owns a distinct module or boundary
- it needs a different instruction stack than existing agents
- it represents a stable repeated workflow, not a one-off task

Do not create a new agent just to encode a temporary task.

## Meta-Agent Validation Checklist

Before saving prompt changes, check:
1. Every loaded prompt adds unique value
2. No prompt repeats mutable project facts already owned elsewhere
3. Prompt order still makes sense
4. `env` is last
5. All referenced files exist
6. The resulting stack matches the current codebase and current repo workflow
7. The specific failure mode or target behaviour was actually addressed

## Current Project Reality To Respect

- `OPEN_AI_SUBSCRIPTION` is the main dogfood runtime path
- `:infrastructure-ai/openai-subscription` is first-class, not an experiment
- Spring AI integrations still exist, but they no longer describe the whole AI stack
- `beta/` tracks branch `beta`, not `main`
- `AppStartupSmokeTest` is the current lightweight app smoke test in `:presentation`
