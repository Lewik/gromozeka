package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityMaintenanceCandidateGroup
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryReadSelectorTrace
import com.gromozeka.domain.model.memory.MemoryReadTrace
import com.gromozeka.domain.model.memory.MemoryRepairCandidateCluster
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.stereotype.Service

@Service
class PersistentMemoryTraceSink(
    private val store: MemoryStore,
) : MemoryReadTraceSink, MemoryWriteTraceSink, MemoryMaintenanceTraceSink {
    private val log = KLoggers.logger(this)
    private val idFactory = UuidMemoryIdFactory("trace")
    private val traceJson = Json { encodeDefaults = true }

    override fun onMemoryRead(event: MemoryReadTraceEvent) {
        persist(
            run = MemoryRun(
                id = idFactory.newRunId(),
                namespace = event.namespace,
                runType = MemoryRun.Type.READ_PLAN,
                triggerMode = MemoryRun.TriggerMode.HOT_PATH,
                summary = event.readTraceSummary(),
                retrievedItemRefs = event.result.retrievedHits.map { it.toTraceItemRef() },
                retrievalBudget = event.result.plan.retrievalBudget,
                promptName = "RuntimeMemoryReadPipeline",
                promptVersion = "trace-v1",
                output = event.toTraceOutput(),
                llmCalls = event.llmCalls,
                latencyMs = event.latencyMs,
                status = MemoryRun.Status.SUCCESS,
                createdAt = event.startedAt,
                startedAt = event.startedAt,
                completedAt = event.completedAt,
            ),
            kind = "read",
            conversationId = event.conversationId,
            targetMessageId = event.targetMessageId,
        )
    }

    override fun onMemoryWrite(event: MemoryWriteTraceEvent) {
        val now = Clock.System.now()
        val existingRuns = event.result.memoryBatch.runs
        val runs = if (existingRuns.isNotEmpty()) {
            existingRuns.map { it.withWriteTrace(event) }
        } else {
            listOf(
                MemoryRun(
                    id = idFactory.newRunId(),
                    namespace = event.namespace,
                    runType = MemoryRun.Type.ROUTE,
                    triggerMode = MemoryRun.TriggerMode.HOT_PATH,
                    summary = event.result.writeTraceSummary(),
                    sourceIds = event.result.sourceBatch.sources.map { it.id },
                    retrievedItemRefs = event.result.retrievedHits.map { it.toTraceItemRef() },
                    retrievalBudget = event.result.retrievalPlan?.retrievalBudget,
                    promptName = "DirectStructuredMemoryWritePipeline",
                    promptVersion = "trace-v1",
                    output = event.toTraceOutput(),
                    appliedOps = event.result.memoryBatch.toAppliedTraceOps(),
                    llmCalls = event.llmCalls,
                    latencyMs = event.latencyMs,
                    status = MemoryRun.Status.SUCCESS,
                    createdAt = event.startedAt ?: now,
                    startedAt = event.startedAt,
                    completedAt = event.completedAt ?: now,
                )
            )
        }
        persistRuns(
            runs = runs,
            kind = if (existingRuns.isNotEmpty()) "write:update_existing" else "write:fallback_trace",
            conversationId = event.conversationId,
            targetMessageId = event.targetMessageId,
        )
    }

    override fun onMemoryMaintenance(event: MemoryMaintenanceTraceEvent) {
        val now = Clock.System.now()
        val existingRuns = event.memoryBatchRuns()
        val runs = if (existingRuns.isNotEmpty()) {
            existingRuns.map { it.withMaintenanceTrace(event) }
        } else {
            listOf(
                MemoryRun(
                    id = idFactory.newRunId(),
                    namespace = event.namespace,
                    runType = event.stage.toRunType(),
                    triggerMode = MemoryRun.TriggerMode.MANUAL,
                    summary = event.maintenanceTraceSummary(),
                    retrievedItemRefs = event.maintenanceTraceRefs(),
                    promptName = "MemoryMaintenancePipeline",
                    promptVersion = "trace-v1",
                    output = event.toTraceOutput(),
                    appliedOps = event.maintenanceAppliedTraceOps(),
                    llmCalls = event.llmCalls,
                    status = MemoryRun.Status.SUCCESS,
                    createdAt = now,
                    completedAt = now,
                )
            )
        }
        persistRuns(
            runs = runs,
            kind = if (existingRuns.isNotEmpty()) "maintenance:${event.stage.name}:update_existing" else "maintenance:${event.stage.name}:fallback_trace",
            conversationId = event.conversationId,
            targetMessageId = null,
        )
    }

    private fun persist(
        run: MemoryRun,
        kind: String,
        conversationId: Conversation.Id,
        targetMessageId: Conversation.Message.Id?,
    ) {
        persistRuns(
            runs = listOf(run),
            kind = kind,
            conversationId = conversationId,
            targetMessageId = targetMessageId,
        )
    }

    private fun persistRuns(
        runs: List<MemoryRun>,
        kind: String,
        conversationId: Conversation.Id,
        targetMessageId: Conversation.Message.Id?,
    ) {
        runBlocking {
            store.apply(MemoryUpdateBatch(runs = runs))
        }
        runs.forEach { run ->
            log.info {
                "Memory trace persisted: traceRun=${run.id.value} kind=$kind namespace=${run.namespace.value} " +
                    "conversation=${conversationId.value} target=${targetMessageId?.value ?: "none"} " +
                    "runType=${run.runType.name} summary=${run.summary.oneLineTrace(300)}"
            }
        }
    }

    private fun MemoryRun.withWriteTrace(event: MemoryWriteTraceEvent): MemoryRun =
        copy(
            summary = summary.ifBlank { event.result.writeTraceSummary() },
            sourceIds = sourceIds.ifEmpty { event.result.sourceBatch.sources.map { it.id } },
            retrievedItemRefs = retrievedItemRefs.ifEmpty { event.result.retrievedHits.map { it.toTraceItemRef() } },
            retrievalBudget = retrievalBudget ?: event.result.retrievalPlan?.retrievalBudget,
            promptName = promptName ?: "DirectStructuredMemoryWritePipeline",
            promptVersion = promptVersion ?: "trace-v1",
            output = output ?: event.toTraceOutput(),
            appliedOps = if (appliedOps.isEmpty()) event.result.memoryBatch.toAppliedTraceOps() else appliedOps,
            llmCalls = llmCalls.ifEmpty { event.llmCalls },
            latencyMs = latencyMs ?: event.latencyMs,
            startedAt = startedAt ?: event.startedAt,
            completedAt = completedAt ?: event.completedAt,
        )

    private fun MemoryRun.withMaintenanceTrace(event: MemoryMaintenanceTraceEvent): MemoryRun =
        copy(
            summary = summary.ifBlank { event.maintenanceTraceSummary() },
            retrievedItemRefs = retrievedItemRefs.ifEmpty { event.maintenanceTraceRefs() },
            promptName = promptName ?: "MemoryMaintenancePipeline",
            promptVersion = promptVersion ?: "trace-v1",
            output = output ?: event.toTraceOutput(),
            appliedOps = if (appliedOps.isEmpty()) event.maintenanceAppliedTraceOps() else appliedOps,
            llmCalls = llmCalls.ifEmpty { event.llmCalls },
        )

    private fun MemoryMaintenanceTraceEvent.memoryBatchRuns(): List<MemoryRun> =
        when (val payload = payload) {
            is MemoryMaintenanceTraceEvent.Payload.NoteConsolidation -> payload.result.memoryBatch.runs
            is MemoryMaintenanceTraceEvent.Payload.MemoryRepair -> payload.result.memoryBatch.runs
            is MemoryMaintenanceTraceEvent.Payload.EntityMaintenance -> payload.result.memoryBatch.runs
            is MemoryMaintenanceTraceEvent.Payload.Retention -> payload.result.memoryBatch.runs
        }

    private fun MemoryReadTraceEvent.readTraceSummary(): String =
        "Memory read trace: need=${result.plan.needMemory} mode=${result.plan.contextMode.name} coverage=${result.plan.coverageMode.name} " +
            "retrieved=${result.retrievedHits.size} selected=${result.trace.selectedHits.size} " +
            "promptChars=${result.runtimePrompt?.length ?: 0}"

    private fun DirectStructuredMemoryWriteResult.writeTraceSummary(): String =
        "Memory write trace: decision=${routeDecision.decision.name} types=${routeDecision.memoryTypes.joinToString("|") { it.name }} " +
            "retrieved=${retrievedHits.size} entities=${entityOps.size} notes=${memoryBatch.notes.size} " +
            "claims=${memoryBatch.claims.size} actionItems=${memoryBatch.actionItems.size} profiles=${memoryBatch.profiles.size} episodes=${memoryBatch.episodes.size}"

    private fun MemoryMaintenanceTraceEvent.maintenanceTraceSummary(): String =
        when (val payload = payload) {
            is MemoryMaintenanceTraceEvent.Payload.NoteConsolidation ->
                "Memory maintenance trace: stage=${stage.name} selectedNotes=${payload.result.selectedNotes.size} " +
                    "relatedHits=${payload.result.relatedHits.size} ${payload.result.memoryBatch.countsForTraceSummary()}"

            is MemoryMaintenanceTraceEvent.Payload.MemoryRepair ->
                "Memory maintenance trace: stage=${stage.name} clusters=${payload.result.candidateClusters.size} " +
                    "suspiciousHits=${payload.result.suspiciousHits.size} actions=${payload.result.repairPlan.repairActions.size} " +
                    payload.result.memoryBatch.countsForTraceSummary()

            is MemoryMaintenanceTraceEvent.Payload.EntityMaintenance ->
                "Memory maintenance trace: stage=${stage.name} groups=${payload.result.candidateGroups.size} " +
                    "actions=${payload.result.maintenancePlan.actions.size} ${payload.result.memoryBatch.countsForTraceSummary()}"

            is MemoryMaintenanceTraceEvent.Payload.Retention ->
                "Memory maintenance trace: stage=${stage.name} candidates=${payload.result.candidates.size} " +
                    "actions=${payload.result.retentionPlan.retentionActions.size} ${payload.result.memoryBatch.countsForTraceSummary()}"
        }

    private fun MemoryMaintenanceTraceEvent.Stage.toRunType(): MemoryRun.Type =
        when (this) {
            MemoryMaintenanceTraceEvent.Stage.NOTE_CONSOLIDATION -> MemoryRun.Type.CONSOLIDATE_NOTES
            MemoryMaintenanceTraceEvent.Stage.MEMORY_REPAIR -> MemoryRun.Type.REPAIR_MEMORY
            MemoryMaintenanceTraceEvent.Stage.ENTITY_MAINTENANCE -> MemoryRun.Type.MAINTAIN_ENTITIES
            MemoryMaintenanceTraceEvent.Stage.RETENTION -> MemoryRun.Type.APPLY_RETENTION
        }

    private fun MemoryReadTraceEvent.toTraceOutput(): JsonObject =
        buildJsonObject {
            put("kind", "memory_read")
            putConversationFields(conversationId, threadId, targetMessageId)
            put("plan", traceJson.encodeToJsonElement(result.plan))
            put("retrievedHits", result.retrievedHits.toTraceHitsJson())
            put("runtimePromptChars", result.runtimePrompt?.length ?: 0)
            put("trace", result.trace.toTraceJson())
        }

    private fun MemoryWriteTraceEvent.toTraceOutput(): JsonObject =
        buildJsonObject {
            put("kind", "memory_write")
            putConversationFields(conversationId, threadId, targetMessageId)
            put("sourceBatch", result.sourceBatch.toTraceBatchJson(includeSourcePreviews = true))
            put("routeDecision", traceJson.encodeToJsonElement(result.routeDecision))
            result.retrievalPlan?.let { put("retrievalPlan", traceJson.encodeToJsonElement(it)) }
            put("predicateCatalogSize", result.predicateCatalog.size)
            put("retrievedHits", result.retrievedHits.toTraceHitsJson())
            put("entityOps", traceJson.encodeToJsonElement(result.entityOps))
            put("noteCandidates", traceJson.encodeToJsonElement(result.noteCandidates))
            put("rawNoteOps", traceJson.encodeToJsonElement(result.rawNoteOps))
            put("noteOps", traceJson.encodeToJsonElement(result.noteOps))
            put("claimCandidates", traceJson.encodeToJsonElement(result.claimCandidates))
            put("rawClaimOps", traceJson.encodeToJsonElement(result.rawClaimOps))
            put("claimOps", traceJson.encodeToJsonElement(result.claimOps))
            put("rawActionItemOps", traceJson.encodeToJsonElement(result.rawActionItemOps))
            put("actionItemOps", traceJson.encodeToJsonElement(result.actionItemOps))
            put("memoryBatch", result.memoryBatch.toTraceBatchJson(includeSourcePreviews = false))
        }

    private fun MemoryMaintenanceTraceEvent.toTraceOutput(): JsonObject =
        buildJsonObject {
            put("kind", "memory_maintenance")
            put("conversationId", conversationId.value)
            put("stage", stage.name)
            when (val payload = payload) {
                is MemoryMaintenanceTraceEvent.Payload.NoteConsolidation -> {
                    val result = payload.result
                    putJsonArray("selectedNotes") {
                        result.selectedNotes.forEach {
                            add(
                                buildJsonObject {
                                    put("id", it.id.value)
                                    put("type", it.noteType.name)
                                    put("status", it.status.name)
                                    put("maturity", it.maturity.name)
                                    put("title", it.title.oneLineTrace(300))
                                    put("summary", it.summary.oneLineTrace(600))
                                }
                            )
                        }
                    }
                    put("relatedHits", result.relatedHits.toTraceHitsJson())
                    put("rawConsolidationResult", traceJson.encodeToJsonElement(result.rawConsolidationResult))
                    put("consolidationResult", traceJson.encodeToJsonElement(result.consolidationResult))
                    put("memoryBatch", result.memoryBatch.toTraceBatchJson(includeSourcePreviews = false))
                }

                is MemoryMaintenanceTraceEvent.Payload.MemoryRepair -> {
                    val result = payload.result
                    put("candidateClusters", result.candidateClusters.toRepairClustersTraceJson())
                    put("suspiciousHits", result.suspiciousHits.toTraceHitsJson())
                    put("repairPlan", traceJson.encodeToJsonElement(result.repairPlan))
                    put("memoryBatch", result.memoryBatch.toTraceBatchJson(includeSourcePreviews = false))
                }

                is MemoryMaintenanceTraceEvent.Payload.EntityMaintenance -> {
                    val result = payload.result
                    put("candidateGroups", result.candidateGroups.toEntityGroupsTraceJson())
                    put("maintenancePlan", traceJson.encodeToJsonElement(result.maintenancePlan))
                    put("memoryBatch", result.memoryBatch.toTraceBatchJson(includeSourcePreviews = false))
                }

                is MemoryMaintenanceTraceEvent.Payload.Retention -> {
                    val result = payload.result
                    put("candidates", result.candidates.toTraceHitsJson())
                    put("retentionPlan", traceJson.encodeToJsonElement(result.retentionPlan))
                    put("memoryBatch", result.memoryBatch.toTraceBatchJson(includeSourcePreviews = false))
                }
            }
        }

    private fun JsonObjectBuilder.putConversationFields(
        conversationId: Conversation.Id,
        threadId: Conversation.Thread.Id,
        targetMessageId: Conversation.Message.Id,
    ) {
        put("conversationId", conversationId.value)
        put("threadId", threadId.value)
        put("targetMessageId", targetMessageId.value)
    }

    private fun MemoryReadTrace.toTraceJson(): JsonObject =
        buildJsonObject {
            put("targetText", targetText.oneLineTrace(800))
            putJsonArray("searchSteps") {
                searchSteps.forEach { step ->
                    add(
                        buildJsonObject {
                            put("stage", step.stage)
                            put("query", step.query.oneLineTrace(500))
                            put("scope", step.scope)
                            put("requestedLimit", step.requestedLimit)
                            put("rawCount", step.rawCount)
                            put("candidateCount", step.candidateCount)
                            put("selectedCount", step.selectedCount)
                            put("rawTopHits", step.rawTopHits.toReadHitsJson())
                            put("selectedTopHits", step.selectedTopHits.toReadHitsJson())
                        }
                    )
                }
            }
            put("selectorTrace", selectorTrace.toTraceJson())
            putJsonArray("selectorDecisions") {
                selectorDecisions.forEach { decision ->
                    add(
                        buildJsonObject {
                            put("ref", decision.ref.toTraceJson())
                            put("selected", decision.selected)
                            put("rank", decision.rank)
                            put("summary", decision.summary.oneLineTrace(800))
                            put("reason", decision.reason.oneLineTrace(800))
                        }
                    )
                }
            }
            put("selectedHits", selectedHits.toReadHitsJson())
            put(
                "sourceSafety",
                buildJsonObject {
                    put("suppressedSources", sourceSafety.suppressedSources.toReadHitsJson())
                    put("restoredTypedHits", sourceSafety.restoredTypedHits.toReadHitsJson())
                }
            )
            injectedPrompt?.let {
                put(
                    "injectedPrompt",
                    buildJsonObject {
                        put("chars", it.chars)
                        put("preview", it.preview.oneLineTrace(1200))
                    }
                )
            }
        }

    private fun MemoryReadSelectorTrace.toTraceJson(): JsonObject =
        buildJsonObject {
            put("initialCandidateCount", initialCandidateCount)
            put("finalCandidateCount", finalCandidateCount)
            put("selectedCount", selectedCount)
            putJsonArray("stages") {
                stages.forEach { stage ->
                    add(
                        buildJsonObject {
                            put("mode", stage.mode.name)
                            put("level", stage.level)
                            put("batchIndex", stage.batchIndex)
                            put("batchCount", stage.batchCount)
                            put("inputCount", stage.inputCount)
                            put("llmSelectedCount", stage.llmSelectedCount)
                            put("llmCarriedCount", stage.llmCarriedCount)
                            put("safetyAddedCount", stage.safetyAddedCount)
                            put("outputCount", stage.outputCount)
                            put("inputRefs", stage.inputRefs.toTraceRefsJson())
                            put("llmSelectedRefs", stage.llmSelectedRefs.toTraceRefsJson())
                            put("llmCarriedRefs", stage.llmCarriedRefs.toTraceRefsJson())
                            put("safetyAddedRefs", stage.safetyAddedRefs.toTraceRefsJson())
                            put("outputRefs", stage.outputRefs.toTraceRefsJson())
                        }
                    )
                }
            }
        }

    private fun List<MemoryItemRef>.toTraceRefsJson(): JsonElement =
        buildJsonArray {
            this@toTraceRefsJson.forEach { ref ->
                add(ref.toTraceJson())
            }
        }

    private fun List<MemoryReadTrace.Hit>.toReadHitsJson(): JsonElement =
        buildJsonArray {
            this@toReadHitsJson.forEach { hit ->
                add(
                    buildJsonObject {
                        put("ref", hit.ref.toTraceJson())
                        put("score", hit.score)
                        put("summary", hit.summary.oneLineTrace(800))
                        hit.predicate?.let { put("predicate", it) }
                        hit.status?.let { put("status", it) }
                    }
                )
            }
        }

    private fun List<MemoryStore.SearchHit>.toTraceHitsJson(limit: Int = 30): JsonElement =
        buildJsonArray {
            this@toTraceHitsJson.take(limit).forEach { hit ->
                add(hit.toTraceHitJson())
            }
            if (size > limit) {
                add(
                    buildJsonObject {
                        put("truncated", true)
                        put("remaining", size - limit)
                    }
                )
            }
        }

    private fun MemoryStore.SearchHit.toTraceHitJson(): JsonObject =
        buildJsonObject {
            val ref = toTraceItemRef()
            put("ref", ref.toTraceJson())
            put("score", score)
            put("summary", traceSummary())
            val evidenceSourceIds = evidenceSourceIds()
            if (evidenceSourceIds.isNotEmpty()) {
                putJsonArray("evidenceSourceIds") {
                    evidenceSourceIds.forEach { sourceId ->
                        add(JsonPrimitive(sourceId.value))
                    }
                }
            }
            when (this@toTraceHitJson) {
                is MemoryStore.SearchHit.SourceHit -> {
                    put("sourceKind", source.traceKind())
                    put("allowRecall", source.usagePolicy.allowRecall)
                    put("allowStructuredExtraction", source.usagePolicy.allowStructuredExtraction)
                    put("searchText", source.searchText?.oneLineTrace(500) ?: "")
                }

                is MemoryStore.SearchHit.EntityHit -> {
                    put("entityType", entity.entityType.name)
                    put("status", entity.status.name)
                }

                is MemoryStore.SearchHit.ClaimHit -> {
                    put("predicate", claim.predicate)
                    put("status", claim.status.name)
                }

                is MemoryStore.SearchHit.NoteHit -> {
                    put("noteType", note.noteType.name)
                    put("status", note.status.name)
                    put("maturity", note.maturity.name)
                }

                is MemoryStore.SearchHit.ActionItemHit -> {
                    put("status", actionItem.status.name)
                    put("priority", actionItem.priority.name)
                }

                is MemoryStore.SearchHit.ProfileHit -> {
                    put("ownerEntityId", profile.ownerEntityId.value)
                    put("version", profile.version)
                }

                is MemoryStore.SearchHit.EpisodeHit -> {
                    put("tags", episode.tags.joinToString("|").oneLineTrace(300))
                    episode.successScore?.let { put("successScore", it) }
                }

                is MemoryStore.SearchHit.RunHit -> {
                    put("runType", run.runType.name)
                    put("status", run.status.name)
                }
            }
        }

    private fun MemoryStore.SearchHit.evidenceSourceIds(): List<MemorySource.Id> =
        when (this) {
            is MemoryStore.SearchHit.SourceHit -> listOf(source.id)
            is MemoryStore.SearchHit.ClaimHit -> claim.evidenceRefs.map { it.sourceId }.distinct()
            is MemoryStore.SearchHit.NoteHit -> note.evidenceRefs.map { it.sourceId }.distinct()
            is MemoryStore.SearchHit.ActionItemHit -> actionItem.evidenceRefs.map { it.sourceId }.distinct()
            is MemoryStore.SearchHit.EpisodeHit -> episode.evidenceRefs.map { it.sourceId }.distinct()
            is MemoryStore.SearchHit.EntityHit,
            is MemoryStore.SearchHit.ProfileHit,
            is MemoryStore.SearchHit.RunHit,
            -> emptyList()
        }

    private fun MemoryStore.SearchHit.traceSummary(): String =
        when (this) {
            is MemoryStore.SearchHit.SourceHit -> source.contentText.oneLineTrace(900)
            is MemoryStore.SearchHit.EntityHit -> listOfNotNull(entity.canonicalName, entity.summary).joinToString(": ").oneLineTrace(900)
            is MemoryStore.SearchHit.ClaimHit -> claim.normalizedText.oneLineTrace(900)
            is MemoryStore.SearchHit.NoteHit -> "${note.title}: ${note.summary}".oneLineTrace(900)
            is MemoryStore.SearchHit.ActionItemHit -> "${actionItem.title}: ${actionItem.description.orEmpty()}".oneLineTrace(900)
            is MemoryStore.SearchHit.ProfileHit -> profile.profileText.oneLineTrace(900)
            is MemoryStore.SearchHit.EpisodeHit -> "situation=${episode.situation}; action=${episode.action}; result=${episode.result}; lesson=${episode.lesson}".oneLineTrace(900)
            is MemoryStore.SearchHit.RunHit -> run.summary.oneLineTrace(900)
        }

    private fun MemoryStore.SearchHit.toTraceItemRef(): MemoryItemRef =
        when (this) {
            is MemoryStore.SearchHit.SourceHit -> MemoryItemRef(MemoryItemRef.Type.SOURCE, source.id.value)
            is MemoryStore.SearchHit.EntityHit -> MemoryItemRef(MemoryItemRef.Type.ENTITY, entity.id.value)
            is MemoryStore.SearchHit.ClaimHit -> MemoryItemRef(MemoryItemRef.Type.CLAIM, claim.id.value)
            is MemoryStore.SearchHit.NoteHit -> MemoryItemRef(MemoryItemRef.Type.NOTE, note.id.value)
            is MemoryStore.SearchHit.ActionItemHit -> MemoryItemRef(MemoryItemRef.Type.ACTION_ITEM, actionItem.id.value)
            is MemoryStore.SearchHit.ProfileHit -> MemoryItemRef(MemoryItemRef.Type.PROFILE, profile.id.value)
            is MemoryStore.SearchHit.EpisodeHit -> MemoryItemRef(MemoryItemRef.Type.EPISODE, episode.id.value)
            is MemoryStore.SearchHit.RunHit -> MemoryItemRef(MemoryItemRef.Type.RUN, run.id.value)
        }

    private fun MemoryItemRef.toTraceJson(): JsonObject =
        buildJsonObject {
            put("type", type.name)
            put("id", id)
        }

    private fun MemoryUpdateBatch.toTraceBatchJson(includeSourcePreviews: Boolean): JsonObject =
        buildJsonObject {
            put("counts", countsJson())
            put("refs", refsJson())
            if (includeSourcePreviews) {
                putJsonArray("sourcePreviews") {
                    sources.forEach { source ->
                        add(source.toSourcePreviewJson())
                    }
                }
            }
        }

    private fun MemoryUpdateBatch.countsJson(): JsonObject =
        buildJsonObject {
            put("predicateDefinitions", predicateDefinitions.size)
            put("sources", sources.size)
            put("runs", runs.size)
            put("entities", entities.size)
            put("claims", claims.size)
            put("notes", notes.size)
            put("actionItems", actionItems.size)
            put("profiles", profiles.size)
            put("episodes", episodes.size)
            put("embeddings", embeddings.size)
        }

    private fun MemoryUpdateBatch.refsJson(): JsonObject =
        buildJsonObject {
            putJsonArray("sources") { sources.forEach { add(JsonPrimitive(it.id.value)) } }
            putJsonArray("runs") { runs.forEach { add(JsonPrimitive(it.id.value)) } }
            putJsonArray("entities") { entities.forEach { add(JsonPrimitive(it.id.value)) } }
            putJsonArray("claims") { claims.forEach { add(JsonPrimitive(it.id.value)) } }
            putJsonArray("notes") { notes.forEach { add(JsonPrimitive(it.id.value)) } }
            putJsonArray("actionItems") { actionItems.forEach { add(JsonPrimitive(it.id.value)) } }
            putJsonArray("profiles") { profiles.forEach { add(JsonPrimitive(it.id.value)) } }
            putJsonArray("episodes") { episodes.forEach { add(JsonPrimitive(it.id.value)) } }
            putJsonArray("embeddings") { embeddings.forEach { add(JsonPrimitive(it.id.value)) } }
        }

    private fun MemoryUpdateBatch.toAppliedTraceOps(): JsonArray =
        buildJsonArray {
            fun addMany(kind: String, ids: List<String>) {
                ids.forEach { id ->
                    add(
                        buildJsonObject {
                            put("op", "persist_$kind")
                            put("id", id)
                        }
                    )
                }
            }
            addMany("source", sources.map { it.id.value })
            addMany("run", runs.map { it.id.value })
            addMany("entity", entities.map { it.id.value })
            addMany("claim", claims.map { it.id.value })
            addMany("note", notes.map { it.id.value })
            addMany("actionItem", actionItems.map { it.id.value })
            addMany("profile", profiles.map { it.id.value })
            addMany("episode", episodes.map { it.id.value })
            addMany("embedding", embeddings.map { it.id.value })
        }

    private fun MemorySource.toSourcePreviewJson(): JsonObject =
        buildJsonObject {
            put("id", id.value)
            put("kind", traceKind())
            put("contentHash", contentHash)
            put("contentChars", contentText.length)
            put("contentPreview", contentText.oneLineTrace(900))
            put("searchText", searchText?.oneLineTrace(900) ?: "")
            put("retentionClass", retentionClass.name)
            put("allowStructuredExtraction", usagePolicy.allowStructuredExtraction)
            put("allowRecall", usagePolicy.allowRecall)
            put("allowEvidenceHydration", usagePolicy.allowEvidenceHydration)
            put("usageReason", usagePolicy.reason)
        }

    private fun MemorySource.traceKind(): String =
        when (this) {
            is MemorySource.ChatTurn -> "CHAT_TURN:${speakerRole.name}"
            is MemorySource.ToolOutput -> "TOOL_OUTPUT:${toolName.orEmpty()}"
            is MemorySource.ImportedNote -> "IMPORTED_NOTE:${importRef.orEmpty()}"
            is MemorySource.ExternalRecord -> "EXTERNAL_RECORD:$recordRef"
        }

    private fun List<MemoryRepairCandidateCluster>.toRepairClustersTraceJson(): JsonElement =
        buildJsonArray {
            this@toRepairClustersTraceJson.forEach { cluster ->
                add(
                    buildJsonObject {
                        put("id", cluster.id)
                        put("kind", cluster.kind.name)
                        put("reason", cluster.reason.oneLineTrace(900))
                        put("hits", cluster.hits.toTraceHitsJson())
                    }
                )
            }
        }

    private fun List<MemoryEntityMaintenanceCandidateGroup>.toEntityGroupsTraceJson(): JsonElement =
        buildJsonArray {
            this@toEntityGroupsTraceJson.forEach { group ->
                add(
                    buildJsonObject {
                        put("id", group.id)
                        put("reason", group.reason.oneLineTrace(900))
                        putJsonArray("entities") {
                            group.entities.forEach { entity ->
                                add(entity.toEntityTraceJson())
                            }
                        }
                    }
                )
            }
        }

    private fun MemoryEntity.toEntityTraceJson(): JsonObject =
        buildJsonObject {
            put("id", id.value)
            put("type", entityType.name)
            put("canonicalName", canonicalName)
            put("normalizedName", normalizedName)
            put("status", status.name)
            summary?.let { put("summary", it.oneLineTrace(900)) }
            mergedIntoEntityId?.let { put("mergedIntoEntityId", it.value) }
            putJsonArray("aliases") {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias.text.oneLineTrace(200)))
                }
            }
        }

    private fun MemoryMaintenanceTraceEvent.maintenanceTraceRefs(): List<MemoryItemRef> =
        when (val payload = payload) {
            is MemoryMaintenanceTraceEvent.Payload.NoteConsolidation ->
                payload.result.selectedNotes.map { MemoryItemRef(MemoryItemRef.Type.NOTE, it.id.value) } +
                    payload.result.relatedHits.map { it.toTraceItemRef() }

            is MemoryMaintenanceTraceEvent.Payload.MemoryRepair ->
                payload.result.suspiciousHits.map { it.toTraceItemRef() }

            is MemoryMaintenanceTraceEvent.Payload.EntityMaintenance ->
                payload.result.candidateGroups.flatMap { group ->
                    group.entities.map { MemoryItemRef(MemoryItemRef.Type.ENTITY, it.id.value) }
                }

            is MemoryMaintenanceTraceEvent.Payload.Retention ->
                payload.result.candidates.map { it.toTraceItemRef() }
        }.distinctBy { "${it.type.name}:${it.id}" }

    private fun MemoryMaintenanceTraceEvent.maintenanceAppliedTraceOps(): JsonArray =
        when (val payload = payload) {
            is MemoryMaintenanceTraceEvent.Payload.NoteConsolidation -> payload.result.memoryBatch.toAppliedTraceOps()
            is MemoryMaintenanceTraceEvent.Payload.MemoryRepair -> payload.result.memoryBatch.toAppliedTraceOps()
            is MemoryMaintenanceTraceEvent.Payload.EntityMaintenance -> payload.result.memoryBatch.toAppliedTraceOps()
            is MemoryMaintenanceTraceEvent.Payload.Retention -> payload.result.memoryBatch.toAppliedTraceOps()
        }

    private fun MemoryUpdateBatch.countsForTraceSummary(): String =
        "runs=${runs.size} entities=${entities.size} claims=${claims.size} notes=${notes.size} actionItems=${actionItems.size} profiles=${profiles.size} episodes=${episodes.size} embeddings=${embeddings.size}"

    private fun String.oneLineTrace(maxChars: Int): String =
        replace(Regex("\\s+"), " ").trim().let { text ->
            if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
        }
}
