# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for Gromozeka project.

## What is an ADR?

ADR documents important architectural decisions with their context, consequences, and alternatives.

## Structure

- `domain/` - Domain layer decisions (Architect Agent)
- `infrastructure/` - Infrastructure layer decisions (Repository, Spring AI Agents)
- `presentation/` - UI layer decisions (UI Agent)
- `coordination/` - Cross-cutting decisions (Meta-Agent)

## How to write ADR

1. Copy `template.md`
2. Fill in all sections
3. Save as `{area}/NNN-short-title.md`
4. Index in Knowledge Graph

## Agents and ADR

- **Architect Agent** - writes domain-level ADRs
- **Other agents** - write infrastructure/presentation ADRs
- **Meta-Agent** - coordinates and validates

## When to create ADR

✅ **Create when:**
- Decision affects multiple modules
- Trade-offs were considered
- Alternatives were evaluated
- Reasoning must be preserved

❌ **Don't create for:**
- Routine implementations
- Obvious technical choices
- Local refactorings

## Numbering

ADRs are numbered sequentially within each area:
- `domain/001-...`, `domain/002-...`
- `infrastructure/001-...`, `infrastructure/002-...`
- etc.

## Status Values

- **Proposed** - Under discussion
- **Accepted** - Approved and implemented
- **Deprecated** - No longer relevant
- **Superseded** - Replaced by newer ADR
