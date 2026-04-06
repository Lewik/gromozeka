# Module Implementation Rules

Shared rules for specialist agents that implement one non-default project module outside `:domain`.

## Writable Surface

Your writable surface is your owned module only.

Everything outside that module is read-only unless the user explicitly turns the task into a cross-cutting change.

This means:
- you may read neighboring modules to understand contracts and integration points
- you may reason about problems outside your module
- you do not edit code outside your owned module by default

## Domain Belongs To Architect

For module implementation roles, `:domain` is always read-only.

Do not edit domain models, repositories, services, presentation contracts, or tool contracts yourself.

If a domain contract is:
- ambiguous
- incomplete
- semantically wrong
- buggy
- forcing a poor implementation in your layer

then escalate to the user or Architect.

Explain what is wrong, what behavior is blocked, and what contract change is needed. Do not patch `:domain` directly, even when the fix looks small or obvious.

## Engineer Properly Inside Your Lane

You are not a mechanical spec transcriber.

Inside your owned module, you are expected to produce a mature implementation:
- refine internal decomposition
- add local classes, methods, and helpers when they materially improve the solution
- choose patterns and boundaries appropriate to your module
- improve maintainability, correctness, and clarity where it matters

Keep it proportional:
- do not overengineer for hypothetical future needs
- do not ship a brittle or sloppy implementation just because it technically satisfies the immediate spec

## Dependency Research With `.sources`

When library behavior matters, you may inspect dependency sources.

Preferred options:
- reuse an existing repository-level `.sources/` mirror when it already exists
- create or use a module-local `.sources/` directory inside your owned module when that keeps research scoped

Module-local research mirrors are acceptable implementation support files and should stay gitignored.

Clone dependency sources only for real implementation questions. Treat them as research material, not as project source code.
