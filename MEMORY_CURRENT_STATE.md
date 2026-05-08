# Memory Current State

This document is a short engineering snapshot of the current memory MVP after the Mongo-based typed memory implementation and real-model E2E stabilization.

## What Exists Now

Gromozeka now has one active runtime memory path: typed project-scoped memory stored in MongoDB through `MemoryStore`.

The core domain model lives in:

- `domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryModels.kt`
- `domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryStore.kt`
- `domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryWritePipeline.kt`
- `domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryReadPipeline.kt`
- `domain/src/commonMain/kotlin/com/gromozeka/domain/model/memory/MemoryMaintenancePipeline.kt`

The main storage implementation is:

- `infrastructure-db/src/jvmMain/kotlin/com/gromozeka/infrastructure/db/memory/MongoMemoryStore.kt`

The old SQLite memory storage and the old Neo4j-backed knowledge-memory prototype were removed. SQLite may still exist for non-memory application persistence.

## Runtime Write Path

Every chat message is routed through the new write pipeline by `MemoryMessageRoutingApplicationService`.

The current write pipeline shape is:

1. Capture source.
2. Route with `LlmMemoryWriteRouter`.
3. If needed, plan write-time retrieval with `LlmMemoryWriteRetrievalPlanner`.
4. Reuse or create entities with `LlmMemoryEntityCanonicalizer`.
5. Build note candidates with `LlmMemoryNoteConstructor`.
6. Reconcile notes with `LlmMemoryNoteReconciler`.
7. Extract claims with `LlmMemoryClaimExtractor`.
8. Reconcile claims with `LlmMemoryClaimReconciler`.
9. Update tasks with `LlmMemoryTaskUpdater`.
10. Materialize the resulting batch into `MemoryStore`.
11. Update projections such as profiles.

Assistant chat turns are captured as sources, but are not materialized as structured memory in the hot path.

## Runtime Read Path

`ConversationEngineService` calls `MemoryApplicationService.buildRuntimeMemoryPrompt(...)` before the main LLM call when memory is enabled.

The read pipeline shape is:

1. Plan whether memory is needed with `LlmMemoryReadPlanner`.
2. If the planner says no memory is needed, run the model-based no-memory verifier before accepting that decision.
3. Search Mongo through `MemoryStore`.
4. Apply source retrieval policy and retrieval budget.
5. Select final context with `LlmMemoryReadSelector`.
6. Compose a runtime memory prompt.
7. Inject that prompt as a runtime developer message into the main LLM request.

The current implementation intentionally avoids hard-coded lexical recall heuristics.

## Manual Maintenance

Maintenance is currently manual and synchronous from the UI/service calls:

- note consolidation
- memory repair
- entity maintenance
- retention

These flows are wired through `MemoryApplicationService` and produce trace events for E2E reports.

## Verification

Normal build and targeted JVM tests pass:

```bash
./gradlew :presentation:build -q
./gradlew :application:jvmTest --tests 'com.gromozeka.application.service.memory.MemoryMaintenancePipelineTest' -q
```

The full memory real-model suite is covered by LLM cassettes. Normal verification should use replay-only mode:

```bash
./gradlew :presentation:jvmTest \
  --tests 'com.gromozeka.presentation.MemoryRealModelE2eTest' \
  -Dgromozeka.memory.e2e=true \
  -Dgromozeka.llm.cassette.mode=replay-only \
  -q
```

Use `record-missing` intentionally when new or changed prompts need fresh live LLM responses:

```bash
./gradlew :presentation:jvmTest \
  --tests 'com.gromozeka.presentation.MemoryRealModelE2eTest' \
  -Dgromozeka.memory.e2e=true \
  -Dgromozeka.llm.cassette.mode=record-missing \
  -q
```

Latest full recorded/replayable result:

- cases: 52/52
- status: PASS
- failures: 0
- retained E2E database: `gromozeka_memory_e2e_019e0960_4b91_7450_9ae3_feb26b0e3507`
- summary artifact: `presentation/build/test-artifacts/memory-e2e/MemoryRealModelE2eTest/2026-05-08T20-56-06.291501Z-gromozeka_memory_e2e_019e0960_4b91_7450_9ae3_feb26b0e3507/summary.md`

The suite covers direct claims, abstention, deduplication, temporal updates, multilingual source bridge, notes, tasks, profiles, explicit forget, note consolidation, episodes, selector/reranker behavior, and repair/retention maintenance.

## Known Rough Edges

The implementation is intentionally still MVP-grade:

- Live `record-missing` E2E can be slow; replay-only verification should be fast.
- Search is still Mongo/local scoring, not vector search.
- Runtime memory prompt wording and assistant answer style are not polished.
- Maintenance is manual, not background async.
- Logs are intentionally verbose for development.
- The current E2E database is retained after runs for inspection and must be dropped manually when needed.
