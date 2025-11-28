# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for Gromozeka project.

## What is an ADR?

ADR documents important architectural decisions with their context, consequences, and alternatives.

## Structure

- `domain/` - Domain layer decisions (Architect Agent)
- `application/` - Application layer decisions (Business Logic Agent)
- `infrastructure/` - Infrastructure layer decisions (Repository, Spring AI Agents)
- `presentation/` - UI layer decisions (UI Agent)
- `agents/` - Cross-cutting decisions (Meta-Agent)

## Active ADRs

### Agents

### Domain
- [001-repository-pattern](domain/001-repository-pattern.md) - Repository pattern usage
- [002-value-objects-for-ids](domain/002-value-objects-for-ids.md) - Value objects for entity IDs
- [003-internal-reasoning-instructions](domain/003-internal-reasoning-instructions.md) - New Instruction types for delegation

### Application
*Business Logic Agent will create ADRs here*

### Infrastructure
*Repository and Spring AI Agents will create ADRs here*

### Presentation
*UI Agent will create ADRs here*

## How to write ADR

1. Copy `template.md`
2. Fill in all sections
3. Save as `{area}/NNN-short-title.md`
4. Index in Knowledge Graph

## Agents and ADR

- **Architect Agent** - writes domain-level ADRs
- **Business Logic Agent** - writes application-level ADRs
- **Spring AI Agent** - writes infrastructure-ai ADRs
- **Repository Agent** - writes infrastructure-db ADRs
- **UI Agent** - writes presentation ADRs
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
- `application/001-...`, `application/002-...`
- `infrastructure/001-...`, `infrastructure/002-...`
- etc.

## Status Values

- **Proposed** - Under discussion
- **Accepted** - Approved and implemented
- **Deprecated** - No longer relevant
- **Superseded** - Replaced by newer ADR