package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryEpisodeCandidate
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryMaintenanceRequest
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryNoteConsolidator
import com.gromozeka.domain.model.memory.MemoryNoteLifecycleOp
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTask
import com.gromozeka.domain.model.memory.MemoryTaskUpdateOp
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.memory.NoteConsolidationResult
import com.gromozeka.domain.model.memory.resolvePredicateDefinition
import klog.KLoggers
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class MemoryNoteConsolidationPipelineResult(
    val selectedNotes: List<MemoryNote>,
    val relatedHits: List<MemoryStore.SearchHit>,
    val rawConsolidationResult: NoteConsolidationResult,
    val consolidationResult: NoteConsolidationResult,
    val memoryBatch: MemoryUpdateBatch,
)

class MemoryNoteConsolidationPipeline(
    private val store: MemoryStore,
    private val consolidator: MemoryNoteConsolidator,
    private val idFactory: MemoryIdFactory,
    private val profileUpdater: ProjectionMemoryProfileUpdater,
    private val clock: MemoryClock = SystemMemoryClock,
) {
    private val log = KLoggers.logger(this)

    suspend fun run(request: MemoryMaintenanceRequest): MemoryNoteConsolidationPipelineResult {
        val startedAt = clock.now()
        val snapshot = store.loadNamespaceSnapshot(request.namespace)
        val selectedNotes = snapshot.selectNotesForConsolidation()
        val relatedHits = snapshot.relatedHitsForConsolidation(selectedNotes)

        log.info {
            "Memory note consolidation selected: namespace=${request.namespace.value} conversation=${request.conversationId?.value ?: "none"} " +
                "snapshot=${snapshot.countsForConsolidationLog()} selected=${selectedNotes.size} " +
                "selectedNotes=${selectedNotes.joinToString("|") { "${it.id.value}:${it.noteType.name}:${it.status.name}/${it.maturity.name}:${it.title.oneLineForMaintenancePipelineLog(140)}" }.ifBlank { "none" }} " +
                "relatedHits=${relatedHits.size}"
        }

        val rawConsolidation = if (selectedNotes.isEmpty()) {
            NoteConsolidationResult(summary = "No active notes selected for consolidation.")
        } else {
            consolidator.consolidate(
                request = request,
                selectedNotes = selectedNotes,
                relatedHits = relatedHits,
                snapshot = snapshot,
            )
        }
        val dedup = MemoryDedupPolicy.deduplicateNoteConsolidation(
            rawConsolidation = rawConsolidation,
            snapshot = snapshot,
            selectedById = selectedNotes.associateBy { it.id },
        )
        val consolidation = dedup.result

        if (consolidation != rawConsolidation) {
            log.info {
                "Memory note consolidation dedup guard adjusted output: namespace=${request.namespace.value} " +
                    "claims=${rawConsolidation.claimCandidates.size}->${consolidation.claimCandidates.size} " +
                    "tasks=${rawConsolidation.taskActions.taskActionBreakdownForMaintenanceLog()}->${consolidation.taskActions.taskActionBreakdownForMaintenanceLog()} " +
                    "episodes=${rawConsolidation.episodeCandidates.size}->${consolidation.episodeCandidates.size} " +
                    "notes=${rawConsolidation.noteActions.noteLifecycleActionBreakdownForMaintenanceLog()}->${consolidation.noteActions.noteLifecycleActionBreakdownForMaintenanceLog()} " +
                    "dedupConsolidatedNotes=${dedup.consolidatedOriginNoteIds.joinToString(",") { it.value }.ifBlank { "none" }}"
            }
        }

        val completedAt = clock.now()
        val structuredBatch = materialize(
            request = request,
            startedAt = startedAt,
            completedAt = completedAt,
            snapshot = snapshot,
            selectedNotes = selectedNotes,
            relatedHits = relatedHits,
            consolidation = consolidation,
        )

        store.apply(structuredBatch)

        val profileBatch = profileUpdater.updateNamespaceProfiles(
            namespace = request.namespace,
            logSubject = "maintenance=note_consolidation",
            appliedBatch = structuredBatch,
            completedAt = completedAt,
        )

        if (profileBatch.isNotEmptyForMaintenance()) {
            store.apply(profileBatch)
        }

        val memoryBatch = structuredBatch + profileBatch

        log.info {
            "Memory note consolidation completed: namespace=${request.namespace.value} " +
                "selectedNotes=${selectedNotes.size} rawClaimCandidates=${rawConsolidation.claimCandidates.size} finalClaimCandidates=${consolidation.claimCandidates.size} " +
                "rawTaskActions=${rawConsolidation.taskActions.size} finalTaskActions=${consolidation.taskActions.size} " +
                "rawNoteActions=${rawConsolidation.noteActions.size} finalNoteActions=${consolidation.noteActions.size} " +
                "rawEpisodes=${rawConsolidation.episodeCandidates.size} finalEpisodes=${consolidation.episodeCandidates.size} " +
                "appliedRuns=${memoryBatch.runs.size} appliedClaims=${memoryBatch.claims.size} appliedTasks=${memoryBatch.tasks.size} " +
                "appliedNotes=${memoryBatch.notes.size} appliedEpisodes=${memoryBatch.episodes.size} appliedProfiles=${memoryBatch.profiles.size} " +
                "summary=${consolidation.summary.oneLineForMaintenancePipelineLog(500)}"
        }

        return MemoryNoteConsolidationPipelineResult(
            selectedNotes = selectedNotes,
            relatedHits = relatedHits,
            rawConsolidationResult = rawConsolidation,
            consolidationResult = consolidation,
            memoryBatch = memoryBatch,
        )
    }

    private fun materialize(
        request: MemoryMaintenanceRequest,
        startedAt: Instant,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        selectedNotes: List<MemoryNote>,
        relatedHits: List<MemoryStore.SearchHit>,
        consolidation: NoteConsolidationResult,
    ): MemoryUpdateBatch {
        val runId = idFactory.newRunId()
        val selectedById = selectedNotes.associateBy { it.id }
        val existingTasks = snapshot.tasks.filter { it.archivedAt == null }

        val claims = consolidation.claimCandidates.mapNotNull { candidate ->
            candidate.toConsolidatedClaim(
                request = request,
                runId = runId,
                completedAt = completedAt,
                selectedById = selectedById,
                snapshot = snapshot,
            )
        }

        val tasks = consolidation.taskActions.flatMap { op ->
            op.toConsolidatedTasks(
                request = request,
                runId = runId,
                completedAt = completedAt,
                selectedById = selectedById,
                existingTasks = existingTasks,
            )
        }

        val episodes = consolidation.episodeCandidates.mapNotNull { candidate ->
            candidate.toConsolidatedEpisode(
                request = request,
                runId = runId,
                completedAt = completedAt,
                selectedById = selectedById,
            )
        }

        val syntheticConsolidatedNoteActions = (claims.mapNotNull { it.originNoteId } + tasks.mapNotNull { it.originNoteId } + episodes.mapNotNull { it.originNoteId })
            .filter { noteId -> consolidation.noteActions.none { it.noteId == noteId } }
            .distinct()
            .map {
                MemoryNoteLifecycleOp(
                    noteId = it,
                    action = MemoryNoteLifecycleOp.Action.MARK_CONSOLIDATED,
                    reason = "Durable memory was materialized from this note.",
                )
            }

        val noteActions = consolidation.noteActions + syntheticConsolidatedNoteActions
        val notes = noteActions.mapNotNull { op ->
            selectedById[op.noteId]?.applyLifecycle(op, completedAt)
        }

        val profileProjection = consolidation.profileProjection
        if (profileProjection != null) {
            log.info {
                "Memory note consolidation profile_patch ignored in favor of deterministic profile rebuild: namespace=${request.namespace.value} " +
                    "reason=${profileProjection.reason.oneLineForMaintenancePipelineLog(300)}"
            }
        }

        val run = MemoryRun(
            id = runId,
            namespace = request.namespace,
            runType = MemoryRun.Type.CONSOLIDATE_NOTES,
            triggerMode = request.triggerMode,
            summary = consolidation.summary.ifBlank { "Note consolidation completed." },
            sourceIds = selectedNotes.flatMap { note -> note.evidenceRefs.map { it.sourceId } }.distinct(),
            retrievedItemRefs = (selectedNotes.map { MemoryItemRef(MemoryItemRef.Type.NOTE, it.id.value) } +
                relatedHits.map { it.toMaintenancePipelineItemRef() }).distinctBy { "${it.type.name}:${it.id}" },
            promptName = "NoteConsolidator",
            promptVersion = "v2",
            output = consolidation.toOutputJson(),
            appliedOps = buildJsonArray {
                claims.forEach { claim ->
                    add(buildJsonObject {
                        put("op", "insert_claim")
                        put("claim_id", claim.id.value)
                        put("origin_note_id", claim.originNoteId?.value ?: "")
                        put("predicate", claim.predicate)
                    })
                }
                tasks.forEach { task ->
                    add(buildJsonObject {
                        put("op", "upsert_task")
                        put("task_id", task.id.value)
                        put("origin_note_id", task.originNoteId?.value ?: "")
                        put("status", task.status.name)
                    })
                }
                episodes.forEach { episode ->
                    add(buildJsonObject {
                        put("op", "insert_episode")
                        put("episode_id", episode.id.value)
                        put("origin_note_id", episode.originNoteId?.value ?: "")
                    })
                }
                notes.forEach { note ->
                    add(buildJsonObject {
                        put("op", "update_note")
                        put("note_id", note.id.value)
                        put("status", note.status.name)
                        put("maturity", note.maturity.name)
                    })
                }
            },
            status = MemoryRun.Status.SUCCESS,
            createdAt = startedAt,
            completedAt = completedAt,
        )

        return MemoryUpdateBatch(
            runs = listOf(run),
            claims = claims,
            tasks = tasks,
            notes = notes,
            episodes = episodes,
        )
    }

    private fun MemoryClaimCandidate.toConsolidatedClaim(
        request: MemoryMaintenanceRequest,
        runId: MemoryRun.Id,
        completedAt: Instant,
        selectedById: Map<MemoryNote.Id, MemoryNote>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryClaim? {
        val originNote = originNoteId?.let(selectedById::get) ?: return null
        if ((objectEntityId == null) == (objectValue == null)) return null

        val predicateDefinition = predicatePolicy
            ?: snapshot.predicateDefinitions.resolvePredicateDefinition(predicate, predicateFamily)

        return MemoryClaim(
            id = idFactory.newClaimId(),
            namespace = request.namespace,
            subjectEntityId = subjectEntityId,
            predicate = predicate,
            predicateFamily = predicateFamily ?: predicateDefinition?.predicate,
            predicatePolicy = predicateDefinition,
            objectEntityId = objectEntityId,
            objectValue = objectValue,
            normalizedText = normalizedText,
            contextText = contextText,
            scope = scope,
            qualifiers = qualifiers,
            confidence = confidence,
            importance = importance,
            status = MemoryClaim.Status.ACTIVE,
            validFrom = validFrom,
            validTo = validTo,
            originNoteId = originNote.id,
            firstSeenAt = originNote.createdAt,
            lastSeenAt = completedAt,
            createdFromRunId = runId,
            evidenceRefs = originNote.evidenceRefs.toDerivedEvidenceRefs(evidenceQuote),
            createdAt = completedAt,
            updatedAt = completedAt,
        )
    }

    private fun MemoryTaskUpdateOp.toConsolidatedTasks(
        request: MemoryMaintenanceRequest,
        runId: MemoryRun.Id,
        completedAt: Instant,
        selectedById: Map<MemoryNote.Id, MemoryNote>,
        existingTasks: List<MemoryTask>,
    ): List<MemoryTask> =
        when (action) {
            MemoryTaskUpdateOp.Action.INSERT -> {
                val draft = task ?: return emptyList()
                val originNote = draft.originNoteId?.let(selectedById::get) ?: return emptyList()
                listOf(draft.toConsolidatedTask(request, runId, completedAt, originNote))
            }

            MemoryTaskUpdateOp.Action.UPDATE,
            MemoryTaskUpdateOp.Action.CLOSE,
            MemoryTaskUpdateOp.Action.CANCEL,
            -> {
                val target = targetTaskId?.let { id -> existingTasks.firstOrNull { it.id == id } } ?: return emptyList()
                listOf(target.applyTaskUpdate(this, completedAt))
            }

            MemoryTaskUpdateOp.Action.NOOP -> emptyList()
        }

    private fun MemoryTaskUpdateOp.Draft.toConsolidatedTask(
        request: MemoryMaintenanceRequest,
        runId: MemoryRun.Id,
        completedAt: Instant,
        originNote: MemoryNote,
    ): MemoryTask =
        MemoryTask(
            id = idFactory.newTaskId(),
            namespace = request.namespace,
            ownerEntityId = ownerEntityId,
            assigneeEntityId = assigneeEntityId,
            title = title,
            description = description,
            status = status,
            priority = priority,
            dueAt = dueAt,
            scope = scope,
            acceptanceCriteria = acceptanceCriteria,
            blockers = blockers,
            relatedEntityIds = relatedEntityIds,
            originNoteId = originNote.id,
            createdFromRunId = runId,
            confidence = confidence,
            evidenceRefs = originNote.evidenceRefs.toDerivedEvidenceRefs(evidenceQuote),
            createdAt = completedAt,
            updatedAt = completedAt,
            closedAt = if (status == MemoryTask.Status.DONE || status == MemoryTask.Status.CANCELLED) completedAt else null,
        )

    private fun MemoryEpisodeCandidate.toConsolidatedEpisode(
        request: MemoryMaintenanceRequest,
        runId: MemoryRun.Id,
        completedAt: Instant,
        selectedById: Map<MemoryNote.Id, MemoryNote>,
    ): MemoryEpisode? {
        val originNote = originNoteId?.let(selectedById::get) ?: return null
        val cleanSituation = situation.trim().take(1_200)
        val cleanAction = action.trim().take(1_200)
        val cleanResult = result.trim().take(1_200)
        val cleanLesson = lesson.trim().take(1_200)
        if (cleanSituation.isBlank() || cleanAction.isBlank() || cleanResult.isBlank() || cleanLesson.isBlank()) return null

        val ownerId = ownerEntityId ?: originNote.anchorEntityId ?: originNote.entityRefs.firstOrNull {
            it.role == MemoryNote.EntityRef.Role.OWNER ||
                it.role == MemoryNote.EntityRef.Role.SUBJECT ||
            it.role == MemoryNote.EntityRef.Role.PRIMARY
        }?.entityId

        return MemoryEpisode(
            id = idFactory.newEpisodeId(),
            namespace = request.namespace,
            ownerEntityId = ownerId,
            situation = cleanSituation,
            action = cleanAction,
            result = cleanResult,
            lesson = cleanLesson,
            tags = tags.distinct().take(12),
            successScore = successScore?.coerceIn(0.0, 1.0),
            originNoteId = originNote.id,
            createdFromRunId = runId,
            evidenceRefs = originNote.evidenceRefs.toDerivedEvidenceRefs(originNote.summary),
            createdAt = completedAt,
            updatedAt = completedAt,
        )
    }

    private fun MemoryTask.applyTaskUpdate(
        op: MemoryTaskUpdateOp,
        completedAt: Instant,
    ): MemoryTask {
        val draft = op.task
        val status = when (op.action) {
            MemoryTaskUpdateOp.Action.CLOSE -> MemoryTask.Status.DONE
            MemoryTaskUpdateOp.Action.CANCEL -> MemoryTask.Status.CANCELLED
            else -> draft?.status ?: this.status
        }

        return copy(
            ownerEntityId = draft?.ownerEntityId ?: ownerEntityId,
            assigneeEntityId = draft?.assigneeEntityId ?: assigneeEntityId,
            title = draft?.title?.takeIf { it.isNotBlank() } ?: title,
            description = draft?.description ?: description,
            status = status,
            priority = draft?.priority ?: priority,
            dueAt = draft?.dueAt ?: dueAt,
            scope = draft?.scope ?: scope,
            acceptanceCriteria = draft?.acceptanceCriteria?.takeIf { it.isNotEmpty() } ?: acceptanceCriteria,
            blockers = draft?.blockers ?: blockers,
            relatedEntityIds = (relatedEntityIds + (draft?.relatedEntityIds ?: emptyList())).distinct(),
            originNoteId = draft?.originNoteId ?: originNoteId,
            confidence = maxOf(confidence, draft?.confidence ?: 0.0),
            updatedAt = completedAt,
            closedAt = if (status == MemoryTask.Status.DONE || status == MemoryTask.Status.CANCELLED) completedAt else closedAt,
        )
    }
}

private fun MemoryNamespaceSnapshot.selectNotesForConsolidation(): List<MemoryNote> =
    notes
        .filter { it.archivedAt == null }
        .filter { it.status == MemoryNote.Status.ACTIVE }
        .filter { it.maturity != MemoryNote.Maturity.CONSOLIDATED }
        .filter { it.importance >= 6 || it.confidence >= 0.55 || it.useCount > 0 || it.evidenceCount > 1 }
        .sortedWith(
            compareByDescending<MemoryNote> { it.maturity.consolidationRank() }
                .thenByDescending { it.importance }
                .thenByDescending { it.confidence }
                .thenByDescending { it.useCount }
                .thenBy { it.updatedAt }
        )
        .take(12)

private fun MemoryNote.Maturity.consolidationRank(): Int =
    when (this) {
        MemoryNote.Maturity.MATURE -> 4
        MemoryNote.Maturity.STABILIZING -> 3
        MemoryNote.Maturity.FRESH -> 2
        MemoryNote.Maturity.CONSOLIDATED -> 1
    }

private fun MemoryNamespaceSnapshot.relatedHitsForConsolidation(selectedNotes: List<MemoryNote>): List<MemoryStore.SearchHit> {
    val selectedNoteIds = selectedNotes.mapTo(mutableSetOf()) { it.id }
    val selectedEntityIds = selectedNotes
        .flatMap { note -> note.entityRefs.map { it.entityId } + listOfNotNull(note.anchorEntityId) }
        .toSet()

    return buildList {
        claims
            .filter { it.archivedAt == null && it.status == MemoryClaim.Status.ACTIVE }
            .sortedWith(compareByDescending<MemoryClaim> { it.subjectEntityId in selectedEntityIds }.thenByDescending { it.importance }.thenByDescending { it.updatedAt })
            .take(40)
            .mapTo(this) { MemoryStore.SearchHit.ClaimHit(it, score = 1.0) }

        notes
            .filter { it.id !in selectedNoteIds }
            .filter { it.archivedAt == null && it.status == MemoryNote.Status.ACTIVE }
            .sortedWith(compareByDescending<MemoryNote> { it.entityRefs.any { ref -> ref.entityId in selectedEntityIds } || it.anchorEntityId in selectedEntityIds }.thenByDescending { it.importance }.thenByDescending { it.updatedAt })
            .take(24)
            .mapTo(this) { MemoryStore.SearchHit.NoteHit(it, score = 0.8) }

        tasks
            .filter { it.archivedAt == null }
            .sortedWith(compareByDescending<MemoryTask> { it.ownerEntityId in selectedEntityIds || it.relatedEntityIds.any { entityId -> entityId in selectedEntityIds } }.thenByDescending { it.updatedAt })
            .take(24)
            .mapTo(this) { MemoryStore.SearchHit.TaskHit(it, score = 0.7) }

        profiles
            .filter { selectedEntityIds.isEmpty() || it.ownerEntityId in selectedEntityIds }
            .take(12)
            .mapTo(this) { MemoryStore.SearchHit.ProfileHit(it, score = 0.9) }
    }
}

private fun MemoryNote.applyLifecycle(
    op: MemoryNoteLifecycleOp,
    completedAt: kotlinx.datetime.Instant,
): MemoryNote =
    when (op.action) {
        MemoryNoteLifecycleOp.Action.KEEP_ACTIVE -> this
        MemoryNoteLifecycleOp.Action.MARK_RESOLVED -> copy(
            status = MemoryNote.Status.RESOLVED,
            updatedAt = completedAt,
        )
        MemoryNoteLifecycleOp.Action.MARK_STALE -> copy(
            status = MemoryNote.Status.STALE,
            updatedAt = completedAt,
        )
        MemoryNoteLifecycleOp.Action.SUPERSEDE -> copy(
            status = MemoryNote.Status.SUPERSEDED,
            updatedAt = completedAt,
        )
        MemoryNoteLifecycleOp.Action.MARK_CONSOLIDATED -> copy(
            status = MemoryNote.Status.RESOLVED,
            maturity = MemoryNote.Maturity.CONSOLIDATED,
            maturityScore = 1.0,
            updatedAt = completedAt,
        )
    }

private fun List<MemoryEvidenceRef>.toDerivedEvidenceRefs(fallbackQuote: String?): List<MemoryEvidenceRef> =
    map {
        it.copy(
            kind = MemoryEvidenceRef.Kind.DERIVED_FROM_NOTE,
            cachedQuote = it.cachedQuote ?: fallbackQuote?.take(500),
        )
    }

private fun MemoryStore.SearchHit.toMaintenancePipelineItemRef(): MemoryItemRef =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> MemoryItemRef(MemoryItemRef.Type.SOURCE, source.id.value)
        is MemoryStore.SearchHit.EntityHit -> MemoryItemRef(MemoryItemRef.Type.ENTITY, entity.id.value)
        is MemoryStore.SearchHit.ClaimHit -> MemoryItemRef(MemoryItemRef.Type.CLAIM, claim.id.value)
        is MemoryStore.SearchHit.NoteHit -> MemoryItemRef(MemoryItemRef.Type.NOTE, note.id.value)
        is MemoryStore.SearchHit.TaskHit -> MemoryItemRef(MemoryItemRef.Type.TASK, task.id.value)
        is MemoryStore.SearchHit.ProfileHit -> MemoryItemRef(MemoryItemRef.Type.PROFILE, profile.id.value)
        is MemoryStore.SearchHit.EpisodeHit -> MemoryItemRef(MemoryItemRef.Type.EPISODE, episode.id.value)
        is MemoryStore.SearchHit.RunHit -> MemoryItemRef(MemoryItemRef.Type.RUN, run.id.value)
    }

private fun NoteConsolidationResult.toOutputJson(): JsonObject =
    buildJsonObject {
        put("summary", summary)
        put("claim_candidates", claimCandidates.size)
        put("task_actions", taskActions.size)
        put("profile_patch", profileProjection != null)
        put("episode_candidates", episodeCandidates.size)
        put("note_actions", noteActions.size)
    }

private fun MemoryUpdateBatch.isNotEmptyForMaintenance(): Boolean =
    predicateDefinitions.isNotEmpty() ||
        sources.isNotEmpty() ||
        runs.isNotEmpty() ||
        entities.isNotEmpty() ||
        claims.isNotEmpty() ||
        notes.isNotEmpty() ||
        tasks.isNotEmpty() ||
        profiles.isNotEmpty() ||
        episodes.isNotEmpty()

private operator fun MemoryUpdateBatch.plus(other: MemoryUpdateBatch): MemoryUpdateBatch =
    MemoryUpdateBatch(
        predicateDefinitions = predicateDefinitions + other.predicateDefinitions,
        sources = sources + other.sources,
        runs = runs + other.runs,
        entities = entities + other.entities,
        claims = claims + other.claims,
        notes = notes + other.notes,
        tasks = tasks + other.tasks,
        profiles = profiles + other.profiles,
        episodes = episodes + other.episodes,
    )

private fun MemoryNamespaceSnapshot.countsForConsolidationLog(): String =
    "sources=${sources.size},runs=${runs.size},entities=${entities.size},claims=${claims.size},notes=${notes.size},tasks=${tasks.size},profiles=${profiles.size},episodes=${episodes.size}"

private fun List<MemoryTaskUpdateOp>.taskActionBreakdownForMaintenanceLog(): String {
    if (isEmpty()) return "none"
    return groupingBy { it.action.name }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun List<MemoryNoteLifecycleOp>.noteLifecycleActionBreakdownForMaintenanceLog(): String {
    if (isEmpty()) return "none"
    return groupingBy { it.action.name }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
}

private fun String.oneLineForMaintenancePipelineLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
