# Memory Architecture

This package defines the `Memory IR` for Gromozeka.

The design is namespace-centric, typed, provenance-aware, and lifecycle-aware.
Storage technology is intentionally secondary to the model itself.

## Core memory objects

- `MemorySource`
  Raw evidence. This is the immutable or append-mostly layer.
- `MemoryEntity`
  Canonical referent that lets memory converge on stable subjects.
- `MemoryClaim`
  Atomic truth-bearing proposition with scope, qualifiers, validity, and provenance.
- `MemoryNote`
  Reusable semantic fragment for rationale, direction, evolving plans, and document-heavy context.
- `MemoryActionItem`
  Operational future-action memory.
- `MemoryProfile`
  Compact always-in-context projection built from stable facts and selected long-lived context.
- `MemoryRun`
  Audit trail for how memory was searched, updated, repaired, or composed.

## Optional extension objects

- `MemoryEpisode`
  Distilled reusable experience or lesson. This is not part of the earliest MVP but belongs in the IR.
- `MemoryPredicateDefinition`
  Not a memory item, but a domain rule object for reconciliation semantics.

## Cross-cutting objects

- `MemoryScope`
  Two-layer applicability boundary. `text` explains the boundary to humans and LLMs; the sealed subtype (`Global`, `Project`, `Conversation`, `Entity`, `Environment`, `Document`) makes it filterable and prevents invalid nullable descriptor combinations.
  `basis` records whether the boundary was explicit, inferred, summarized, or imported.
  Runtime visibility is intentionally not part of scope; retrieval policy can model that separately if needed.
- `MemoryEvidenceRef`
  Provenance reference from interpreted memory back to raw evidence.

## Design rules

- `Source` is evidence, not interpretation.
- `Claim` is atomic and precise. One claim should correspond to one predicate application.
- `Note` is not a softer duplicate of `Claim`; it exists for richer semantic context that should not be over-cemented too early.
- `Profile` is a projection, never the source of truth.
- `scope` must be explicit and machine-readable.
- provenance should stay attached to remembered objects through evidence refs and run lineage.
- retrieval ranking is query-relative and should be computed by read-side policy, not stored as a global item temperature.

## Write-side intent

The intended write flow is:

1. capture raw source
2. decide whether anything durable should be remembered
3. retrieve nearby memory for dedupe, contradiction detection, and scope alignment
4. canonicalize entities
5. extract and reconcile claims, notes, and tasks
6. rebuild profile projection
7. persist typed objects plus audit trail

## Read-side intent

The intended read flow is:

1. decide whether memory is needed at all
2. choose answer mode: `factual`, `rationale`, `task`, or `mixed`
3. retrieve bounded memory slices by type
4. fall back to evidence when uncertainty or conflict matters
5. compose the answer without flattening note-like memory into false certainty

## Modeling bias

- prefer `Claim` over `Note` when the information is explicit, stable, and reusable as a fact
- prefer `Note` over `Claim` when the information is still rationale-heavy, provisional, or context-rich
- prefer explicit scope over global generalization
- prefer lineage and provenance over unexplained summaries
- prefer later maintenance (`consolidate`, `repair`, `retain`) over forcing every turn into final form at write time
