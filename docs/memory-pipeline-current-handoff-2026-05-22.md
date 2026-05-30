# Gromozeka Memory Pipeline Handoff

Date: 2026-05-22
Checkout: `/Users/lewik/code/gromozeka/dev`
Current HEAD observed by Codex: `e3f0052a9`
Audience: another Codex/agent working in a fork/worktree

This is a curated handoff, not a blind copy of Claude's work-machine report. Treat Claude's report as useful telemetry, but verify code before changing architecture.

## Short judgement

The memory pipeline is no longer "missing basic document ingest". It already has:

- explicit remember/enrich MCP tools;
- arbitrary provided text and document ingest;
- `file_path` and `raw_url` document inputs;
- optional readable namespaces;
- a document ingest queue;
- `MemoryRun` status and queue status tools;
- persistent memory trace/run infrastructure;
- stage-specific runtime assignment direction;
- cascade stale/superseded notes on superseded claims.

The current highest-value work is not embeddings. It is write-pipeline correctness and observability:

1. stop one bad LLM entity reference from killing an entire document section;
2. reduce entity inflation and type-divergent duplicates;
3. enforce predicate cardinality deterministically;
4. make runs/traces/source documents visible enough to debug quality;
5. only after storage is clearer, move toward Postgres + JSONB + pgvector and then revisit embeddings.

## Current strategic constraints

- No backward compatibility is required for dev memory data. If a clean model needs wiping Mongo/settings, wipe them.
- Do not over-invest in Mongo-only embedding infrastructure. The intended direction is Postgres with JSONB plus pgvector.
- Cost/tokens are not the limiting factor during development. First make memory behave correctly; optimize later.
- Prefer deterministic guards around invariants. LLM prompts are helpful, but not enough for correctness.
- Document chunks should not become a permanent domain object just because large docs are hard. Current direction is document/source first-class storage plus structured section ingest, not a premature chunk ontology.

## What was recently done or appears present

### Document ingest

Relevant files:

- `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/MemoryRememberToolCallback.kt`
- `application/src/jvmMain/kotlin/com/gromozeka/application/service/MemoryToolApplicationService.kt`
- `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/MemoryDocumentIngestQueue.kt`
- `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/MemoryMessageRoutingApplicationService.kt`

Current behavior:

- `memory_remember` supports:
  - normal conversation memory via message references;
  - `provided_text`;
  - `provided_document`;
  - local `file_path`;
  - `raw_url` for raw text/markdown;
  - optional `namespace`;
  - `document_type='markdown'`;
  - `force_write`;
  - explicit consent gate for provided content modes.
- Document ingest returns a queued `run_id` and continues in background.
- `MemoryRun.Type.DOCUMENT_INGEST` exists.
- `MemoryDocumentIngestQueue` tracks parent/child runs and progress.

Implication: if a document is not remembered well, debug quality and run trace; do not start by adding a whole new document ingestion subsystem.

### Memory tools

Relevant files:

- `MemoryRememberToolCallback.kt`
- `MemoryEnrichContextToolCallback.kt`
- `MemoryListNamespacesToolCallback.kt`
- `MemoryQueueStatusToolCallback.kt`
- `MemoryRunStatusToolCallback.kt`
- `MemoryToolApplicationService.kt`

Current direction:

- `unified_search` is deprecated/should not be revived as the primary user-facing recall API.
- The read tool is `memory_enrich_context`, not "ask memory a question". It should receive a context/topic/phrase/task to enrich.
- Namespace support exists in remember/enrich/list paths. Continue using readable namespaces such as `global`, `user:lewik`, `work:hebrew`, `project:<project-id>`.

### Run/trace infrastructure

Relevant files:

- `domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryModels.kt`
- `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/PersistentMemoryTraceSink.kt`
- `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/MemoryToolSupport.kt`

Current state:

- `MemoryRun` exists and stores lineage/progress/details.
- Persistent trace sink exists and writes read/write/maintenance trace details into runs.
- MCP tools can inspect run and queue status.

Gap:

- Human-readable UI/HTTP status/trace viewer is still weak. Debugging still requires MCP/log/DB spelunking.

## Confirmed or likely current problems

### 1. Bad LLM entity IDs can kill an entire section

This is the highest-priority correctness issue.

Observed code evidence:

- `LlmMemoryNoteConstructor.toNoteMemoryIdTextOrNull()` calls `require(value.isValidMemoryEntityId())`.
- `LlmMemoryTaskUpdater.toTaskEntityIdOrNull()` calls `require(value.isValidMemoryEntityId())`.
- `LlmMemoryClaimExtractor.toMemoryRefTextOrNull()` also calls `require(value.isValidMemoryEntityId())`.

Claude's report says NoteConstructor/TaskUpdater often return invalid refs such as:

- `"user"`;
- `"project:<ns>:user"`;
- bare hash without the `entity:` prefix.

Important nuance:

- The prompts already say "Use only entity IDs listed in Resolved entities".
- Therefore "tighten prompt" alone is not enough.
- LLM output is an external boundary, not trusted internal state. Invalid LLM references should be handled defensively.

Recommended fix:

- Introduce a shared helper for LLM-produced entity refs, for example `parseLlmEntityRefOrNull(stageName, fieldName, raw, context)`.
- It should:
  - treat blank/null/placeholders as null;
  - accept only `isValidMemoryEntityId()`;
  - log a structured warning with stage/source/run;
  - record a trace/run warning if feasible;
  - return null or drop only the affected candidate/op.
- Do not let one invalid entity ref crash the whole document section.
- Keep fail-fast validation for internal/domain construction paths. The relaxed behavior is only for LLM-output mapping.

Suggested tests:

- NoteConstructor returns one valid note and one note with `entity_id="user"`: valid note survives, invalid ref/candidate is dropped or stripped, run records warning.
- TaskUpdater returns owner/assignee `"user"`: task op is dropped or owner becomes null according to chosen policy, section does not fail.
- ClaimExtractor returns invalid subject: claim candidate is dropped, section does not fail.

Preferred policy:

- For required semantic anchors such as claim `subject_entity_id`, drop the whole candidate.
- For optional refs such as note secondary mentions, drop only that ref.
- For task owner/assignee, prefer null unless the task itself becomes meaningless; related invalid refs should be dropped individually.

### 2. Entity dedup/type policy is underdefined

Claude's symptom is plausible:

- same `normalizedName` appears as both `FILE` and `DOCUMENT`;
- repo appears as both `REPO` and `PROJECT`;
- ticket/project/concept IDs drift between `CONCEPT` and `PROJECT`.

Code nuance:

- `InMemoryMemoryStore.findEntitiesByNormalizedNames()` searches names/aliases across all entity types, so Claude's "InMemory merge key is `(normalizedName, entityType)`" is not fully accurate.
- However `LlmMemoryEntityCanonicalizer.provisionalEntityId()` includes `namespace|entityType|canonicalName` in the stable key, so creating the same name with another type produces a different ID.
- Maintenance/dedup code has type-aware filtering in places, so cross-type duplicates can survive.

Do not fix by simply removing `entityType` from identity globally. That risks merging genuinely different entities with the same name, e.g. project/person/product collisions.

Recommended direction:

- Keep `entityType` as meaningful metadata.
- Add a central "entity identity policy" with compatible/collapsible type families:
  - `FILE` + `DOCUMENT`: usually collapse to `DOCUMENT` when the source is an ingested document or the path denotes the document itself.
  - `REPO` + `PROJECT`: collapse only when the source context says the repo is the project artifact.
  - `CONCEPT` + `PROJECT`: collapse only with strong evidence; otherwise keep separate.
- Make canonicalizer look up exact normalized-name hits across all types and force a decision:
  - link existing compatible entity;
  - add alias;
  - create new only if distinct identity is justified.
- Add tests for same-name-but-different-kind collisions. Include a negative test for legitimate same-name distinct entities.

### 3. FILE/DOCUMENT entity inflation

Claude's reported symptom is believable: many file paths mentioned in documents become durable `FILE` or `DOCUMENT` entities.

Current risk:

- Markdown documents and code handoffs naturally mention many paths.
- A path mention is not necessarily a durable entity.
- If each `.kt`, `.md`, or config path becomes an entity, graph recall gets noisy and dedup gets harder.

Recommended fix:

- Tighten canonicalizer prompt and mapping policy:
  - create `DOCUMENT` when the source itself is a document being ingested or the text asserts something about a document as an artifact;
  - create `FILE` only when the file is itself the subject/object of a durable memory item;
  - do not create file entities for incidental examples, stack traces, reference lists, or code-path mentions unless they anchor a real claim/note.
- Consider deterministic suppression before/after canonicalizer for obvious incidental path mentions if they are not used by notes/claims/tasks.

Suggested tests:

- A handoff document with many reference paths but no facts about those files should not create one entity per path.
- A document explicitly saying "`MemoryRememberToolCallback.kt` is the MCP entrypoint for remember" may create/link that file if it anchors a note/claim.

### 4. SINGLE + REPLACE cardinality is probably not enforced strongly enough

The predicate catalog contains cardinality/conflict policy such as `SINGLE` and `REPLACE`.

Risk:

- If enforcement lives only in the LLM reconciler prompt, multiple ACTIVE claims can survive for the same exclusive slot.
- Claude reported a path predicate with four ACTIVE rows. I did not verify that exact DB state in this worktree, but the architectural risk is real.

Recommended fix:

- Add deterministic write-time post-pass after claim reconciliation/materialization planning:
  - group active claims by namespace + subject + predicate family/canonical predicate + compatible scope slot;
  - for `cardinality=SINGLE` and `conflictPolicy=REPLACE`, allow only the newest/highest-confidence/current candidate;
  - supersede older conflicting active claims;
  - cascade stale/superseded notes through the existing cascade path.
- Do not rely only on MemoryRepair later. Repair is useful, but write-time invariants should be maintained.

Suggested tests:

- Two updates to `primary_storage_backend` leave one ACTIVE current claim.
- Historical/time-scoped claims do not get incorrectly deleted when `RANGE_SPLIT` or explicit dates make coexistence valid.
- Superseded claim cascades affected notes to stale/superseded.

### 5. Document source retrieval/reconstruction is still product-critical

Current ingest can store document/source material, but the user wants to later ask for "that document" and inspect the original.

Need:

- first-class source/document viewer;
- "show source/document by title/ref/path/run" tool or UI;
- source reconstruction from stored source/sections;
- clear source kind/ref/title/import time in display.

This should happen before embeddings, because without it quality debugging is blind.

### 6. Observability is still the main missing product layer

Existing pieces:

- `MemoryRun`;
- queue status;
- run status;
- persistent trace sink;
- logs.

Missing practical surface:

- progress UI: active run, queue size, current stage, document/section progress, errors;
- run/trace viewer: router decision, retrieval plan, canonicalizer ops, extracted notes/claims/tasks, reconciler ops, materialized changes;
- quick HTTP/JSON endpoint for scripts/browser monitoring would be useful, but it is secondary to trace semantics.

Recommended order:

1. Finish correctness fixes above.
2. Add/extend status output enough that failed document ingest tells exactly which stage/section failed and why.
3. Then build UI/HTTP viewer.

### 7. Embeddings are not an immediate task

Current code has AI model specs/config for embeddings, for example `text-embedding-3-large`, but I did not find a real `MemoryEmbedding` domain/storage flow in this checkout.

Claude's complaint that memory collections have no embedding field is probably true in spirit. But the current agreed direction is:

- do not build deep embedding infrastructure on Mongo;
- move memory storage toward Postgres JSONB + pgvector;
- then implement embeddings/vector retrieval properly.

Action now:

- Do not wire a quick Mongo embedding writer unless explicitly asked.
- It is okay to document current recall as graph/text/name based for now.

### 8. Note maturity is likely dead state, but not urgent

Claude says notes are always `FRESH`/`ACTIVE`. That sounds plausible.

This is not blocking memory correctness. Options later:

- implement maturity transitions based on use count, age, confirmation, supersession, confidence drift;
- or remove/hide the field from product surfaces until it has behavior.

### 9. Bedrock prompt caching issue is separate from memory semantics

Claude reports Bedrock cache read/create tokens are always zero while direct Anthropic has cache usage.

This may matter for cost/performance, but it is provider infrastructure, not memory model correctness.

If someone investigates:

- inspect actual outgoing Bedrock JSON;
- compare direct Anthropic vs Bedrock tool/system block order;
- verify whether synthetic `respond_with_structured_json` changes the cached prefix;
- confirm the Bedrock model/inference profile supports per-block cache control.

Do not let this distract from memory-write correctness unless the user is actively testing Bedrock at work.

## Suggested implementation plan

### Step 1: LLM entity-ref tolerance and trace warnings

Bounded, high impact.

Files to inspect/change:

- `LlmMemoryNoteConstructor.kt`
- `LlmMemoryTaskUpdater.kt`
- `LlmMemoryClaimExtractor.kt`
- possibly `MemoryStructuredResponseFormats.kt`
- trace/run warning support in `DirectStructuredMemoryWritePipeline.kt` or `PersistentMemoryTraceSink.kt`

Acceptance:

- Invalid LLM entity IDs do not crash document section ingest.
- Bad refs are visible in logs/run details.
- Tests cover note/task/claim invalid refs.

### Step 2: Entity identity policy and file/document suppression

Files to inspect/change:

- `LlmMemoryEntityCanonicalizer.kt`
- `DirectStructuredMemoryWritePipeline.kt`
- `MemoryEntityMaintenancePipeline.kt`
- `MemoryEntityIdentitySummary.kt`
- tests in `MemoryMaintenancePipelineTest.kt` or a narrower canonicalizer test.

Acceptance:

- Incidental path mentions do not explode into file entities.
- Same markdown document is not both `FILE` and `DOCUMENT`.
- Same repo/project is handled by explicit policy, not by accidental type-sensitive IDs.
- Legitimate same-name distinct entities still survive.

### Step 3: Deterministic SINGLE/REPLACE enforcement

Files to inspect/change:

- `DirectStructuredMemoryWritePipeline.kt`
- `LlmMemoryClaimReconciler.kt`
- `MemoryPredicateCatalogDefaults.kt`
- `MemoryRepairPipeline.kt` for reference only.

Acceptance:

- A write with a replacement claim leaves one active claim in an exclusive semantic slot.
- Historical/time-scoped facts are preserved where appropriate.
- Existing note-stale cascade is reused.

### Step 4: Clean reingest and audit

After steps 1-3:

- wipe dev memory DB;
- ingest the same handoff/corpus documents;
- inspect:
  - section failures;
  - entity count/type distribution;
  - duplicate normalized names by type;
  - active claims for SINGLE/REPLACE predicates;
  - note/task counts;
  - sample source/document recall.

Do not judge success only by "no crash". Read several entities/claims/notes manually.

### Step 5: Observability and document/source viewer

Only after the write path is not obviously poisoning data:

- improve `memory_run_status` output if needed;
- add plain HTTP/JSON status if useful;
- build UI/trace viewer;
- build document/source viewer and retrieval.

## Explicit non-goals for the next pass

- Do not implement Mongo-first embeddings.
- Do not optimize document ingest concurrency before correctness.
- Do not redesign the whole memory ontology.
- Do not add backward-compatible migrations for old dev memory data.
- Do not revive `unified_search` as primary recall. Use/extend `memory_enrich_context`.
- Do not treat document-derived tasks as automatic personal tasks unless the document explicitly represents task memory or the user asks to import tasks.

## Useful reference files

- External audit from work machine: `/Users/lewik/Downloads/gromozeka-memory-pipeline-handoff-todo.md`
- Roadmap memory note: `/Users/lewik/.codex/memories/extensions/ad_hoc/notes/20260520T105414-gromozeka-memory-roadmap.md`
- Memory write pipeline: `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/DirectStructuredMemoryWritePipeline.kt`
- Entity canonicalizer: `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/LlmMemoryEntityCanonicalizer.kt`
- Claim extractor: `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/LlmMemoryClaimExtractor.kt`
- Note constructor: `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/LlmMemoryNoteConstructor.kt`
- Task updater: `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/LlmMemoryTaskUpdater.kt`
- Predicate catalog: `domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryPredicateCatalogDefaults.kt`
- Memory models: `domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryModels.kt`
- Store interface: `domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryStore.kt`
- In-memory store: `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/InMemoryMemoryStore.kt`
- Document ingest queue: `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/MemoryDocumentIngestQueue.kt`
- Memory tools support/rendering: `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/MemoryToolSupport.kt`
- Namespace resolution: `application/src/jvmMain/kotlin/com/gromozeka/application/service/memory/MemoryNamespaceResolution.kt`

## Final recommendation to the next agent

Start with Step 1. It is the cleanest and most directly tied to observed failures:

- LLM sometimes emits invalid refs.
- Current code treats those refs as internal invariants.
- Document ingest dies at section granularity.
- Prompt-only fixes are not sufficient.

Then do entity policy and deterministic cardinality. Those three fixes should make the next clean corpus ingest much more informative. After that, observability/source viewer will become a product layer instead of a desperate debugging crutch.
