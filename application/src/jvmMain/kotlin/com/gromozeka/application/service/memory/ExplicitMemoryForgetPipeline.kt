package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryForgetPlan
import com.gromozeka.domain.model.memory.MemoryForgetPlanner
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryProfileUpdater
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import klog.KLoggers
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ExplicitMemoryForgetPipelineResult(
    val candidates: List<MemoryStore.SearchHit>,
    val forgetPlan: MemoryForgetPlan,
    val memoryBatch: MemoryUpdateBatch,
    val forgottenSourceIds: Set<MemorySource.Id>,
)

class ExplicitMemoryForgetPipeline(
    private val store: MemoryStore,
    private val planner: MemoryForgetPlanner,
    private val idFactory: MemoryIdFactory,
    private val profileUpdater: MemoryProfileUpdater = NoOpMemoryProfileUpdater,
    private val embeddingIndexer: MemoryEmbeddingIndexer = NoOpMemoryEmbeddingIndexer,
    private val clock: MemoryClock = SystemMemoryClock,
) {
    private val log = KLoggers.logger(this)
    private val sourceForgetCascade = MemorySourceForgetCascade()

    suspend fun run(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
    ): ExplicitMemoryForgetPipelineResult {
        val startedAt = clock.now()
        val activeSnapshot = store.loadNamespaceSnapshot(request.namespace)
        val candidates = selectCandidates(request, routeDecision, activeSnapshot)

        log.info {
            "Memory forget selected: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "query=${forgetQuery(request, routeDecision).oneLineForForgetPipelineLog(200)} candidates=${candidates.size} " +
                "items=${candidates.joinToString("|") { it.forgetHitForLog() }.ifBlank { "none" }}"
        }

        val plan = planner.plan(request, routeDecision, candidates, activeSnapshot)
        val completedAt = clock.now()
        val materialized = materialize(
            request = request,
            routeDecision = routeDecision,
            startedAt = startedAt,
            completedAt = completedAt,
            snapshot = store.loadNamespaceSnapshot(request.namespace, includeArchived = true),
            candidates = candidates,
            plan = plan,
        )

        val indexedStructuredBatch = embeddingIndexer.withEmbeddings(materialized.memoryBatch)
        store.apply(indexedStructuredBatch)

        val profileBatch = profileUpdater.update(
            request = request,
            appliedBatch = indexedStructuredBatch,
            completedAt = completedAt,
        )

        val indexedProfileBatch = embeddingIndexer.withEmbeddings(profileBatch)
        if (indexedProfileBatch.isNotEmptyForForget()) {
            store.apply(indexedProfileBatch)
        }
        sourceForgetCascade.verify(
            activeSnapshot = store.loadNamespaceSnapshot(request.namespace),
            forgottenSourceIds = materialized.forgottenSourceIds,
        )

        val memoryBatch = indexedStructuredBatch + indexedProfileBatch

        log.info {
            "Memory forget completed: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "candidates=${candidates.size} actions=${plan.forgetActions.size} appliedRuns=${memoryBatch.runs.size} " +
                "forgottenSources=${materialized.forgottenSourceIds.size} appliedSources=${memoryBatch.sources.size} " +
                "appliedEntities=${memoryBatch.entities.size} appliedClaims=${memoryBatch.claims.size} appliedNotes=${memoryBatch.notes.size} " +
                "appliedTasks=${memoryBatch.actionItems.size} appliedProfiles=${memoryBatch.profiles.size} " +
                "summary=${plan.summary.oneLineForForgetPipelineLog(500)}"
        }

        return ExplicitMemoryForgetPipelineResult(
            candidates = candidates,
            forgetPlan = plan,
            memoryBatch = memoryBatch,
            forgottenSourceIds = materialized.forgottenSourceIds,
        )
    }

    private suspend fun selectCandidates(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        snapshot: MemoryNamespaceSnapshot,
    ): List<MemoryStore.SearchHit> {
        val query = forgetQuery(request, routeDecision)
        val searchHits = store.search(
            MemoryStore.SearchRequest(
                query = query,
                namespace = request.namespace,
                scopes = setOf(MemoryStore.SearchScope.ALL),
                embedding = embeddingIndexer.searchEmbedding(query),
                includeArchived = false,
                limit = 80,
            )
        )
            .filterNot { it.toForgetItemRef() == MemoryItemRef(MemoryItemRef.Type.SOURCE, request.source.id.value) }

        val evidenceSourceIds = searchHits.flatMap { hit ->
            when (hit) {
                is MemoryStore.SearchHit.ClaimHit -> hit.claim.evidenceRefs.map { it.sourceId }
                is MemoryStore.SearchHit.NoteHit -> hit.note.evidenceRefs.map { it.sourceId }
                is MemoryStore.SearchHit.ActionItemHit -> hit.actionItem.evidenceRefs.map { it.sourceId }
                is MemoryStore.SearchHit.EpisodeHit -> hit.episode.evidenceRefs.map { it.sourceId }
                is MemoryStore.SearchHit.SourceHit -> listOf(hit.source.id)
                is MemoryStore.SearchHit.EntityHit,
                is MemoryStore.SearchHit.ProfileHit,
                is MemoryStore.SearchHit.RunHit,
                -> emptyList()
            }
        }.toSet()

        val evidenceSourceHits = snapshot.sources
            .filter { it.id in evidenceSourceIds && it.id != request.source.id }
            .map { MemoryStore.SearchHit.SourceHit(it, score = 0.7) }

        return (searchHits + evidenceSourceHits)
            .distinctBy { "${it.toForgetItemRef().type.name}:${it.toForgetItemRef().id}" }
            .take(100)
    }

    private fun materialize(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        startedAt: Instant,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        candidates: List<MemoryStore.SearchHit>,
        plan: MemoryForgetPlan,
    ): ForgetMaterialization {
        val runId = idFactory.newRunId()
        val candidateRefs = candidates.mapTo(mutableSetOf()) { it.toForgetItemRef() }
        val forgottenSources = mutableMapOf<MemorySource.Id, MemorySource>()
        val forgottenEntities = mutableMapOf<MemoryEntity.Id, MemoryEntity>()
        val forgottenClaims = mutableMapOf<MemoryClaim.Id, MemoryClaim>()
        val forgottenNotes = mutableMapOf<MemoryNote.Id, MemoryNote>()
        val forgottenActionItems = mutableMapOf<MemoryActionItem.Id, MemoryActionItem>()
        val forgottenEpisodes = mutableMapOf<MemoryEpisode.Id, MemoryEpisode>()
        val sourceActions = plan.forgetActions.filter { action ->
            if (
                action.action != MemoryForgetPlan.Action.Type.SOFT_DELETE_SOURCE ||
                action.targetType != MemoryItemRef.Type.SOURCE
            ) {
                false
            } else if (!action.isInsideForgetCandidates(candidateRefs)) {
                log.info {
                    "Memory forget skipped action outside candidate set: action=${action.action.name} targetType=${action.targetType.name} " +
                        "targetIds=${action.targetIds.joinToString("|")} reason=${action.reason.oneLineForForgetPipelineLog(240)}"
                }
                false
            } else {
                true
            }
        }
        val sourceIds = sourceActions
            .flatMap { it.targetIds }
            .map(MemorySource::Id)
            .filterTo(mutableSetOf()) { it != request.source.id }
        val sourceReason = sourceActions
            .map { it.reason.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")
            .ifBlank { "Forget selected source evidence and its direct dependents." }
        val cascadeResult = sourceIds.takeIf { it.isNotEmpty() }?.let { selectedSourceIds ->
            sourceForgetCascade.build(
                snapshot = snapshot,
                requestedSourceIds = selectedSourceIds,
                protectedSourceIds = setOf(request.source.id),
                completedAt = completedAt,
                reason = sourceReason,
            )
        }
        cascadeResult?.memoryBatch?.let { cascadeBatch ->
            cascadeBatch.sources.associateByTo(forgottenSources) { it.id }
            cascadeBatch.entities.associateByTo(forgottenEntities) { it.id }
            cascadeBatch.claims.associateByTo(forgottenClaims) { it.id }
            cascadeBatch.notes.associateByTo(forgottenNotes) { it.id }
            cascadeBatch.actionItems.associateByTo(forgottenActionItems) { it.id }
            cascadeBatch.episodes.associateByTo(forgottenEpisodes) { it.id }
        }

        val appliedOps = buildJsonArray {
            cascadeResult?.appliedOps.orEmpty().forEach { op ->
                add(op.toForgetOpJson())
            }
            plan.forgetActions
                .filter { it.action == MemoryForgetPlan.Action.Type.ARCHIVE_ITEM }
                .forEach { action ->
                if (!action.isInsideForgetCandidates(candidateRefs)) {
                    log.info {
                        "Memory forget skipped action outside candidate set: action=${action.action.name} targetType=${action.targetType.name} " +
                            "targetIds=${action.targetIds.joinToString("|")} reason=${action.reason.oneLineForForgetPipelineLog(240)}"
                    }
                    return@forEach
                }
                val applied = archiveItems(
                    action = action,
                    completedAt = completedAt,
                    snapshot = snapshot,
                    forgottenClaims = forgottenClaims,
                    forgottenNotes = forgottenNotes,
                    forgottenActionItems = forgottenActionItems,
                    forgottenEpisodes = forgottenEpisodes,
                )
                applied.forEach { op ->
                    add(op.toForgetOpJson())
                }
            }
        }

        val run = MemoryRun(
            id = runId,
            namespace = request.namespace,
            runType = MemoryRun.Type.FORGET_MEMORY,
            triggerMode = request.triggerMode,
            summary = plan.summary.ifBlank { "Explicit memory forget request completed." },
            sourceIds = (
                listOf(request.source.id) +
                    candidates.sourceIdsForForgetRun() +
                    cascadeResult?.forgottenSourceIds.orEmpty()
                ).distinct(),
            retrievedItemRefs = candidateRefs.toList(),
            output = plan.toForgetOutputJson(routeDecision),
            appliedOps = appliedOps,
            llmCalls = currentMemoryRunLlmCalls(),
            latencyMs = completedAt.toEpochMilliseconds() - startedAt.toEpochMilliseconds(),
            status = MemoryRun.Status.SUCCESS,
            createdAt = startedAt,
            startedAt = startedAt,
            completedAt = completedAt,
        )

        return ForgetMaterialization(
            memoryBatch = MemoryUpdateBatch(
                sources = forgottenSources.values.toList(),
                runs = listOf(run),
                entities = forgottenEntities.values.toList(),
                claims = forgottenClaims.values.toList(),
                notes = forgottenNotes.values.toList(),
                actionItems = forgottenActionItems.values.toList(),
                episodes = forgottenEpisodes.values.toList(),
            ),
            forgottenSourceIds = cascadeResult?.forgottenSourceIds.orEmpty(),
        )
    }

    private fun archiveItems(
        action: MemoryForgetPlan.Action,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        forgottenClaims: MutableMap<MemoryClaim.Id, MemoryClaim>,
        forgottenNotes: MutableMap<MemoryNote.Id, MemoryNote>,
        forgottenActionItems: MutableMap<MemoryActionItem.Id, MemoryActionItem>,
        forgottenEpisodes: MutableMap<MemoryEpisode.Id, MemoryEpisode>,
    ): List<MemorySourceForgetOp> =
        when (action.targetType) {
            MemoryItemRef.Type.CLAIM -> action.targetIds.mapNotNull { id -> snapshot.claims.firstOrNull { it.id.value == id } }
                .filter { it.archivedAt == null }
                .map { claim ->
                    forgottenClaims[claim.id] = claim.copy(archivedAt = completedAt, updatedAt = completedAt)
                    MemorySourceForgetOp("archive_claim", MemoryItemRef.Type.CLAIM, claim.id.value, action.reason)
                }

            MemoryItemRef.Type.NOTE -> action.targetIds.mapNotNull { id -> snapshot.notes.firstOrNull { it.id.value == id } }
                .filter { it.archivedAt == null }
                .map { note ->
                    forgottenNotes[note.id] = note.copy(archivedAt = completedAt, updatedAt = completedAt)
                    MemorySourceForgetOp("archive_note", MemoryItemRef.Type.NOTE, note.id.value, action.reason)
                }

            MemoryItemRef.Type.ACTION_ITEM -> action.targetIds.mapNotNull { id -> snapshot.actionItems.firstOrNull { it.id.value == id } }
                .filter { it.archivedAt == null }
                .map { actionItem ->
                    forgottenActionItems[actionItem.id] = actionItem.copy(archivedAt = completedAt, updatedAt = completedAt)
                    MemorySourceForgetOp("archive_task", MemoryItemRef.Type.ACTION_ITEM, actionItem.id.value, action.reason)
                }

            MemoryItemRef.Type.EPISODE -> action.targetIds.mapNotNull { id -> snapshot.episodes.firstOrNull { it.id.value == id } }
                .filter { it.archivedAt == null }
                .map { episode ->
                    forgottenEpisodes[episode.id] = episode.copy(archivedAt = completedAt, updatedAt = completedAt)
                    MemorySourceForgetOp("archive_episode", MemoryItemRef.Type.EPISODE, episode.id.value, action.reason)
                }

            MemoryItemRef.Type.SOURCE,
            MemoryItemRef.Type.ENTITY,
            MemoryItemRef.Type.PROFILE,
            MemoryItemRef.Type.RUN,
            -> emptyList()
        }
}

private data class ForgetMaterialization(
    val memoryBatch: MemoryUpdateBatch,
    val forgottenSourceIds: Set<MemorySource.Id>,
)

private fun MemoryForgetPlan.Action.isInsideForgetCandidates(candidateRefs: Set<MemoryItemRef>): Boolean =
    targetIds.all { MemoryItemRef(targetType, it) in candidateRefs }

private fun MemorySourceForgetOp.toForgetOpJson() =
    buildJsonObject {
        put("op", op)
        put("target_type", targetType.name)
        put("target_id", targetId)
        put("reason", reason)
    }

private fun forgetQuery(
    request: DirectStructuredMemoryWriteRequest,
    routeDecision: MemoryRouteDecision,
): String =
    routeDecision.sourceSearchText?.trim()?.takeIf { it.isNotBlank() }
        ?: request.source.contentText

private fun List<MemoryStore.SearchHit>.sourceIdsForForgetRun() =
    flatMap { hit ->
        when (hit) {
            is MemoryStore.SearchHit.ClaimHit -> hit.claim.evidenceRefs.map { it.sourceId }
            is MemoryStore.SearchHit.NoteHit -> hit.note.evidenceRefs.map { it.sourceId }
            is MemoryStore.SearchHit.ActionItemHit -> hit.actionItem.evidenceRefs.map { it.sourceId }
            is MemoryStore.SearchHit.EpisodeHit -> hit.episode.evidenceRefs.map { it.sourceId }
            is MemoryStore.SearchHit.SourceHit -> listOf(hit.source.id)
            is MemoryStore.SearchHit.EntityHit,
            is MemoryStore.SearchHit.ProfileHit,
            is MemoryStore.SearchHit.RunHit,
            -> emptyList()
        }
    }.distinct()

private fun MemoryForgetPlan.toForgetOutputJson(routeDecision: MemoryRouteDecision) =
    buildJsonObject {
        put("summary", summary)
        put("forget_query", routeDecision.sourceSearchText.orEmpty())
        put("forget_actions", buildJsonArray {
            forgetActions.forEach { action ->
                add(buildJsonObject {
                    put("action", action.action.name)
                    put("target_type", action.targetType.name)
                    put("target_ids", action.targetIds.joinToString("|"))
                    put("reason", action.reason)
                })
            }
        })
    }

private fun MemoryStore.SearchHit.forgetHitForLog(): String =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> "claim:${claim.id.value}:${claim.status.name}:${claim.predicate}:${claim.normalizedText.oneLineForForgetPipelineLog(120)}"
        is MemoryStore.SearchHit.NoteHit -> "note:${note.id.value}:${note.status.name}/${note.maturity.name}:${note.title.oneLineForForgetPipelineLog(120)}"
        is MemoryStore.SearchHit.ActionItemHit -> "actionItem:${actionItem.id.value}:${actionItem.status.name}:${actionItem.title.oneLineForForgetPipelineLog(120)}"
        is MemoryStore.SearchHit.ProfileHit -> "profile:${profile.id.value}:${profile.ownerEntityId.value}"
        is MemoryStore.SearchHit.EpisodeHit -> "episode:${episode.id.value}:${episode.lesson.oneLineForForgetPipelineLog(120)}"
        is MemoryStore.SearchHit.EntityHit -> "entity:${entity.id.value}:${entity.canonicalName.oneLineForForgetPipelineLog(80)}"
        is MemoryStore.SearchHit.SourceHit -> "source:${source.id.value}:${source.contentText.oneLineForForgetPipelineLog(80)}"
        is MemoryStore.SearchHit.RunHit -> "run:${run.id.value}:${run.runType.name}"
    }

private fun MemoryUpdateBatch.isNotEmptyForForget(): Boolean =
    predicateDefinitions.isNotEmpty() ||
        sources.isNotEmpty() ||
        runs.isNotEmpty() ||
        entities.isNotEmpty() ||
        claims.isNotEmpty() ||
        notes.isNotEmpty() ||
        actionItems.isNotEmpty() ||
        profiles.isNotEmpty() ||
        episodes.isNotEmpty() ||
        embeddings.isNotEmpty()

private operator fun MemoryUpdateBatch.plus(other: MemoryUpdateBatch): MemoryUpdateBatch =
    MemoryUpdateBatch(
        predicateDefinitions = predicateDefinitions + other.predicateDefinitions,
        sources = sources + other.sources,
        runs = runs + other.runs,
        entities = entities + other.entities,
        claims = claims + other.claims,
        notes = notes + other.notes,
        actionItems = actionItems + other.actionItems,
        profiles = profiles + other.profiles,
        episodes = episodes + other.episodes,
        embeddings = embeddings + other.embeddings,
    )

private fun String.oneLineForForgetPipelineLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
