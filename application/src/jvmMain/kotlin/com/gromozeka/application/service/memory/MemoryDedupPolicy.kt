package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryClaimReconciliationOp
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryEpisodeCandidate
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryNoteLifecycleOp
import com.gromozeka.domain.model.memory.MemoryNoteCandidate
import com.gromozeka.domain.model.memory.MemoryNoteReconciliationOp
import com.gromozeka.domain.model.memory.MemoryReconciliationAction
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTask
import com.gromozeka.domain.model.memory.MemoryTaskUpdateOp
import com.gromozeka.domain.model.memory.NoteConsolidationResult
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

object MemoryDedupPolicy {
    fun deduplicateWriteClaimOps(
        rawOps: List<MemoryClaimReconciliationOp>,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): List<MemoryClaimReconciliationOp> {
        val activeClaimsByKey = retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
            .map { it.claim }
            .filter { it.status == MemoryClaim.Status.ACTIVE }
            .mapNotNull { claim -> claim.dedupKey()?.let { it to claim } }
            .groupBy({ it.first }, { it.second })

        val insertedKeys = mutableSetOf<MemoryClaimDedupKey>()
        return rawOps.map { op ->
            if (op.action != MemoryReconciliationAction.INSERT) return@map op
            val candidate = op.candidate ?: return@map op
            val key = candidate.dedupKey() ?: return@map op
            val existingClaim = activeClaimsByKey[key]?.firstOrNull()

            when {
                existingClaim != null -> op.copy(
                    action = MemoryReconciliationAction.NOOP,
                    targetClaimId = existingClaim.id,
                    reason = "Write dedup guard skipped duplicate of active claim ${existingClaim.id.value}: ${op.reason}",
                )

                !insertedKeys.add(key) -> op.copy(
                    action = MemoryReconciliationAction.NOOP,
                    reason = "Write dedup guard skipped duplicate claim candidate in the same run: ${op.reason}",
                )

                else -> op
            }
        }
    }

    fun deduplicateWriteNoteOps(
        rawOps: List<MemoryNoteReconciliationOp>,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): List<MemoryNoteReconciliationOp> {
        val activeNotesByKey = retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.NoteHit>()
            .map { it.note }
            .filter { it.status == MemoryNote.Status.ACTIVE }
            .mapNotNull { note -> note.dedupKey()?.let { it to note } }
            .groupBy({ it.first }, { it.second })

        val insertedKeys = mutableSetOf<MemoryNoteDedupKey>()
        return rawOps.map { op ->
            if (op.action != MemoryReconciliationAction.INSERT) return@map op
            val candidate = op.candidate ?: return@map op
            val key = candidate.dedupKey() ?: return@map op
            val existingNote = activeNotesByKey[key]?.firstOrNull()

            when {
                existingNote != null -> op.copy(
                    action = MemoryReconciliationAction.NOOP,
                    targetNoteId = existingNote.id,
                    reason = "Write dedup guard skipped duplicate of active note ${existingNote.id.value}: ${op.reason}",
                )

                !insertedKeys.add(key) -> op.copy(
                    action = MemoryReconciliationAction.NOOP,
                    reason = "Write dedup guard skipped duplicate note candidate in the same run: ${op.reason}",
                )

                else -> op
            }
        }
    }

    fun deduplicateWriteTaskOps(
        rawOps: List<MemoryTaskUpdateOp>,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): List<MemoryTaskUpdateOp> {
        val activeTasksByKey = retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.TaskHit>()
            .map { it.task }
            .mapNotNull { task -> task.dedupKey()?.let { it to task } }
            .groupBy({ it.first }, { it.second })

        val insertedKeys = mutableSetOf<MemoryTaskDedupKey>()
        return rawOps.map { op ->
            if (op.action != MemoryTaskUpdateOp.Action.INSERT) return@map op
            val draft = op.task ?: return@map op
            val key = draft.dedupKey() ?: return@map op
            val existingTask = activeTasksByKey[key]?.firstOrNull()

            when {
                existingTask != null -> op.copy(
                    action = MemoryTaskUpdateOp.Action.UPDATE,
                    targetTaskId = existingTask.id,
                    reason = "Write dedup guard converted duplicate task insert to update of ${existingTask.id.value}: ${op.reason}",
                )

                !insertedKeys.add(key) -> op.copy(
                    action = MemoryTaskUpdateOp.Action.NOOP,
                    reason = "Write dedup guard skipped duplicate task candidate in the same run: ${op.reason}",
                )

                else -> op
            }
        }
    }

    fun deduplicateNoteConsolidation(
        rawConsolidation: NoteConsolidationResult,
        snapshot: MemoryNamespaceSnapshot,
        selectedById: Map<MemoryNote.Id, MemoryNote>,
    ): MemoryNoteConsolidationDedupResult {
        val consolidatedOriginNoteIds = linkedSetOf<MemoryNote.Id>()
        val finalClaimCandidates = rawConsolidation.claimCandidates.deduplicateConsolidatedClaimCandidates(
            snapshot = snapshot,
            consolidatedOriginNoteIds = consolidatedOriginNoteIds,
        )
        val finalTaskActions = rawConsolidation.taskActions.deduplicateConsolidatedTaskActions(snapshot)
        val finalEpisodeCandidates = rawConsolidation.episodeCandidates.deduplicateConsolidatedEpisodeCandidates(
            snapshot = snapshot,
            selectedById = selectedById,
            consolidatedOriginNoteIds = consolidatedOriginNoteIds,
        )
        val finalNoteActions = rawConsolidation.noteActions.withDedupConsolidatedNotes(consolidatedOriginNoteIds)

        return MemoryNoteConsolidationDedupResult(
            result = rawConsolidation.copy(
                claimCandidates = finalClaimCandidates,
                taskActions = finalTaskActions,
                episodeCandidates = finalEpisodeCandidates,
                noteActions = finalNoteActions,
            ),
            consolidatedOriginNoteIds = consolidatedOriginNoteIds,
        )
    }

    private fun List<MemoryClaimCandidate>.deduplicateConsolidatedClaimCandidates(
        snapshot: MemoryNamespaceSnapshot,
        consolidatedOriginNoteIds: MutableSet<MemoryNote.Id>,
    ): List<MemoryClaimCandidate> {
        val activeClaimsByKey = snapshot.claims
            .filter { it.archivedAt == null && it.status == MemoryClaim.Status.ACTIVE }
            .mapNotNull { claim -> claim.dedupKey()?.let { it to claim } }
            .groupBy({ it.first }, { it.second })
        val insertedKeys = mutableSetOf<MemoryClaimDedupKey>()

        return mapNotNull { candidate ->
            val key = candidate.dedupKey() ?: return@mapNotNull candidate
            val duplicateExists = activeClaimsByKey[key]?.isNotEmpty() == true || !insertedKeys.add(key)
            if (duplicateExists) {
                candidate.originNoteId?.let(consolidatedOriginNoteIds::add)
                null
            } else {
                candidate
            }
        }
    }

    private fun List<MemoryTaskUpdateOp>.deduplicateConsolidatedTaskActions(
        snapshot: MemoryNamespaceSnapshot,
    ): List<MemoryTaskUpdateOp> {
        val activeTasksByKey = snapshot.tasks
            .filter { it.archivedAt == null }
            .mapNotNull { task -> task.dedupKey()?.let { it to task } }
            .groupBy({ it.first }, { it.second })
        val insertedKeys = mutableSetOf<MemoryTaskDedupKey>()

        return map { op ->
            if (op.action != MemoryTaskUpdateOp.Action.INSERT) return@map op
            val draft = op.task ?: return@map op
            val key = draft.dedupKey() ?: return@map op
            val existingTask = activeTasksByKey[key]?.firstOrNull()

            when {
                existingTask != null -> op.copy(
                    action = MemoryTaskUpdateOp.Action.UPDATE,
                    targetTaskId = existingTask.id,
                    reason = "Note consolidation dedup guard converted duplicate task insert to update of ${existingTask.id.value}: ${op.reason}",
                )

                !insertedKeys.add(key) -> op.copy(
                    action = MemoryTaskUpdateOp.Action.NOOP,
                    reason = "Note consolidation dedup guard skipped duplicate task candidate in the same run: ${op.reason}",
                )

                else -> op
            }
        }
    }

    private fun List<MemoryEpisodeCandidate>.deduplicateConsolidatedEpisodeCandidates(
        snapshot: MemoryNamespaceSnapshot,
        selectedById: Map<MemoryNote.Id, MemoryNote>,
        consolidatedOriginNoteIds: MutableSet<MemoryNote.Id>,
    ): List<MemoryEpisodeCandidate> {
        val activeEpisodesByKey = snapshot.episodes
            .filter { it.archivedAt == null }
            .mapNotNull { episode -> episode.dedupKey()?.let { it to episode } }
            .groupBy({ it.first }, { it.second })
        val insertedKeys = mutableSetOf<MemoryEpisodeDedupKey>()

        return mapNotNull { candidate ->
            val key = candidate.dedupKey(selectedById) ?: return@mapNotNull candidate
            val duplicateExists = activeEpisodesByKey[key]?.isNotEmpty() == true || !insertedKeys.add(key)
            if (duplicateExists) {
                candidate.originNoteId?.let(consolidatedOriginNoteIds::add)
                null
            } else {
                candidate
            }
        }
    }

    private fun List<MemoryNoteLifecycleOp>.withDedupConsolidatedNotes(
        originNoteIds: Set<MemoryNote.Id>,
    ): List<MemoryNoteLifecycleOp> {
        if (originNoteIds.isEmpty()) return this
        val retainedActions = filterNot { it.noteId in originNoteIds && it.action == MemoryNoteLifecycleOp.Action.KEEP_ACTIVE }
        val existingActionNoteIds = retainedActions.mapTo(mutableSetOf()) { it.noteId }
        val guardActions = originNoteIds
            .filter { it !in existingActionNoteIds }
            .map { noteId ->
                MemoryNoteLifecycleOp(
                    noteId = noteId,
                    action = MemoryNoteLifecycleOp.Action.MARK_CONSOLIDATED,
                    reason = "Durable memory already exists; note consolidation dedup guard skipped duplicate materialization.",
                )
            }
        return retainedActions + guardActions
    }
}

data class MemoryNoteConsolidationDedupResult(
    val result: NoteConsolidationResult,
    val consolidatedOriginNoteIds: Set<MemoryNote.Id>,
)

private data class MemoryClaimDedupKey(
    val subjectEntityId: String,
    val semanticPredicate: String,
    val objectKey: String,
    val scopeKey: String,
    val validFrom: Instant?,
    val validTo: Instant?,
)

private fun MemoryClaim.dedupKey(): MemoryClaimDedupKey? {
    val objectKey = claimObjectDedupKey(objectEntityId, objectValue) ?: return null
    return MemoryClaimDedupKey(
        subjectEntityId = subjectEntityId.value,
        semanticPredicate = (predicateFamily ?: predicatePolicy?.predicate ?: predicate).normalizeForMemoryDedup(),
        objectKey = objectKey,
        scopeKey = scope.dedupKey(),
        validFrom = validFrom,
        validTo = validTo,
    )
}

private fun MemoryClaimCandidate.dedupKey(): MemoryClaimDedupKey? {
    val objectKey = claimObjectDedupKey(objectEntityId, objectValue) ?: return null
    return MemoryClaimDedupKey(
        subjectEntityId = subjectEntityId.value,
        semanticPredicate = (predicateFamily ?: predicatePolicy?.predicate ?: predicate).normalizeForMemoryDedup(),
        objectKey = objectKey,
        scopeKey = scope.dedupKey(),
        validFrom = validFrom,
        validTo = validTo,
    )
}

private fun claimObjectDedupKey(
    objectEntityId: MemoryEntity.Id?,
    objectValue: JsonElement?,
): String? =
    objectEntityId?.let { "entity:${it.value}" }
        ?: objectValue?.dedupValueKey()

private fun JsonElement.dedupValueKey(): String {
    val value = if (this is JsonPrimitive && isString) {
        content
    } else {
        toString()
    }
    return "value:${value.normalizeForMemoryDedup()}"
}

private data class MemoryNoteDedupKey(
    val noteType: String,
    val title: String,
    val summary: String,
    val scopeKey: String,
    val anchorEntityId: String?,
    val validFrom: Instant?,
    val validTo: Instant?,
)

private fun MemoryNote.dedupKey(): MemoryNoteDedupKey? =
    MemoryNoteDedupKey(
        noteType = noteType.name,
        title = title.normalizeForMemoryDedup(),
        summary = summary.normalizeForMemoryDedup(),
        scopeKey = scope.dedupKey(),
        anchorEntityId = anchorEntityId?.value ?: entityRefs.primaryDedupEntityId(),
        validFrom = validFrom,
        validTo = validTo,
    )

private fun MemoryNoteCandidate.dedupKey(): MemoryNoteDedupKey? =
    MemoryNoteDedupKey(
        noteType = noteType.name,
        title = title.normalizeForMemoryDedup(),
        summary = summary.normalizeForMemoryDedup(),
        scopeKey = scope.dedupKey(),
        anchorEntityId = entityRefs.primaryDedupEntityId(),
        validFrom = validFrom,
        validTo = validTo,
    )

private fun List<MemoryNote.EntityRef>.primaryDedupEntityId(): String? =
    firstOrNull {
        it.role == MemoryNote.EntityRef.Role.PRIMARY ||
            it.role == MemoryNote.EntityRef.Role.SUBJECT ||
            it.role == MemoryNote.EntityRef.Role.OWNER
    }?.entityId?.value

private data class MemoryTaskDedupKey(
    val title: String,
    val scopeKey: String,
    val ownerEntityId: String?,
    val assigneeEntityId: String?,
    val relatedEntityIds: List<String>,
)

private fun MemoryTask.dedupKey(): MemoryTaskDedupKey? {
    if (status !in activeTaskStatusesForDedup) return null
    return MemoryTaskDedupKey(
        title = title.normalizeForMemoryDedup(),
        scopeKey = scope.dedupKey(),
        ownerEntityId = ownerEntityId?.value,
        assigneeEntityId = assigneeEntityId?.value,
        relatedEntityIds = relatedEntityIds.map { it.value }.sorted(),
    )
}

private fun MemoryTaskUpdateOp.Draft.dedupKey(): MemoryTaskDedupKey? {
    if (status !in activeTaskStatusesForDedup) return null
    return MemoryTaskDedupKey(
        title = title.normalizeForMemoryDedup(),
        scopeKey = scope.dedupKey(),
        ownerEntityId = ownerEntityId?.value,
        assigneeEntityId = assigneeEntityId?.value,
        relatedEntityIds = relatedEntityIds.map { it.value }.sorted(),
    )
}

private val activeTaskStatusesForDedup = setOf(
    MemoryTask.Status.OPEN,
    MemoryTask.Status.IN_PROGRESS,
    MemoryTask.Status.BLOCKED,
)

private data class MemoryEpisodeDedupKey(
    val ownerEntityId: String?,
    val situation: String,
    val action: String,
    val result: String,
    val lesson: String,
)

private fun MemoryEpisode.dedupKey(): MemoryEpisodeDedupKey =
    MemoryEpisodeDedupKey(
        ownerEntityId = ownerEntityId?.value,
        situation = situation.normalizeForMemoryDedup(),
        action = action.normalizeForMemoryDedup(),
        result = result.normalizeForMemoryDedup(),
        lesson = lesson.normalizeForMemoryDedup(),
    )

private fun MemoryEpisodeCandidate.dedupKey(
    selectedById: Map<MemoryNote.Id, MemoryNote>,
): MemoryEpisodeDedupKey? {
    val originNote = originNoteId?.let(selectedById::get)
    val ownerId = ownerEntityId ?: originNote?.anchorEntityId ?: originNote?.entityRefs?.firstOrNull {
        it.role == MemoryNote.EntityRef.Role.OWNER ||
            it.role == MemoryNote.EntityRef.Role.SUBJECT ||
            it.role == MemoryNote.EntityRef.Role.PRIMARY
    }?.entityId
    val cleanSituation = situation.trim()
    val cleanAction = action.trim()
    val cleanResult = result.trim()
    val cleanLesson = lesson.trim()
    if (cleanSituation.isBlank() || cleanAction.isBlank() || cleanResult.isBlank() || cleanLesson.isBlank()) return null
    return MemoryEpisodeDedupKey(
        ownerEntityId = ownerId?.value,
        situation = cleanSituation.normalizeForMemoryDedup(),
        action = cleanAction.normalizeForMemoryDedup(),
        result = cleanResult.normalizeForMemoryDedup(),
        lesson = cleanLesson.normalizeForMemoryDedup(),
    )
}

private fun MemoryScope.dedupKey(): String =
    when (this) {
        is MemoryScope.Global -> "global"
        is MemoryScope.Project -> "project:${projectId.value}"
        is MemoryScope.Conversation -> "conversation:${conversationId.value}:${projectId?.value ?: "null"}"
        is MemoryScope.Entity -> "entity:${subjectEntityId.value}"
        is MemoryScope.Environment -> "environment:${environment.normalizeForMemoryDedup()}"
        is MemoryScope.Document -> "document:${documentRef.normalizeForMemoryDedup()}"
    }

private fun String.normalizeForMemoryDedup(): String =
    lowercase().replace(Regex("\\s+"), " ").trim()
