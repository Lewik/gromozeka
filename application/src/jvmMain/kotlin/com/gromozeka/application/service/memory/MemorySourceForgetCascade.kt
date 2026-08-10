package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class MemorySourceForgetCascadeResult(
    val forgottenSourceIds: Set<MemorySource.Id>,
    val memoryBatch: MemoryUpdateBatch,
    val appliedOps: List<MemorySourceForgetOp>,
)

internal data class MemorySourceForgetOp(
    val op: String,
    val targetType: MemoryItemRef.Type,
    val targetId: String,
    val reason: String,
)

internal class MemorySourceForgetCascade {
    fun build(
        snapshot: MemoryNamespaceSnapshot,
        requestedSourceIds: Set<MemorySource.Id>,
        protectedSourceIds: Set<MemorySource.Id>,
        completedAt: Instant,
        reason: String,
    ): MemorySourceForgetCascadeResult {
        require(requestedSourceIds.isNotEmpty()) { "At least one memory source id is required." }
        val sourcesById = snapshot.sources.associateBy { it.id }
        val missingSourceIds = requestedSourceIds - sourcesById.keys
        require(missingSourceIds.isEmpty()) {
            "Memory sources not found: ${missingSourceIds.map { it.value }.sorted().joinToString(", ")}"
        }

        val forgottenSourceIds = requestedSourceIds.flatMapTo(mutableSetOf()) { requestedSourceId ->
            val sourceClosure = logicalSourceClosure(snapshot.sources, setOf(requestedSourceId))
            if (sourceClosure.any { it in protectedSourceIds }) emptySet() else sourceClosure
        }
        val sources = forgottenSourceIds.mapNotNull { sourceId ->
            sourcesById[sourceId]
                ?.takeIf { it.deletedAt == null }
                ?.withDeletedAt(completedAt)
        }
        val appliedOps = mutableListOf<MemorySourceForgetOp>()
        sources.forEach { source ->
            appliedOps += MemorySourceForgetOp(
                op = "soft_delete_source",
                targetType = MemoryItemRef.Type.SOURCE,
                targetId = source.id.value,
                reason = reason,
            )
        }

        val claims = snapshot.claims.mapNotNull { claim ->
            if (claim.archivedAt != null) return@mapNotNull null
            val remainingEvidence = claim.evidenceRefs.filterNot { it.sourceId in forgottenSourceIds }
            if (remainingEvidence.size == claim.evidenceRefs.size) return@mapNotNull null
            if (remainingEvidence.isEmpty()) {
                appliedOps += MemorySourceForgetOp(
                    op = "archive_claim",
                    targetType = MemoryItemRef.Type.CLAIM,
                    targetId = claim.id.value,
                    reason = reason,
                )
                claim.copy(archivedAt = completedAt, updatedAt = completedAt)
            } else {
                appliedOps += MemorySourceForgetOp(
                    op = "prune_claim_evidence",
                    targetType = MemoryItemRef.Type.CLAIM,
                    targetId = claim.id.value,
                    reason = reason,
                )
                claim.copy(evidenceRefs = remainingEvidence, updatedAt = completedAt)
            }
        }

        val notes = snapshot.notes.mapNotNull { note ->
            if (note.archivedAt != null) return@mapNotNull null
            val remainingEvidence = note.evidenceRefs.filterNot { it.sourceId in forgottenSourceIds }
            if (remainingEvidence.size == note.evidenceRefs.size) return@mapNotNull null
            if (remainingEvidence.isEmpty()) {
                appliedOps += MemorySourceForgetOp(
                    op = "archive_note",
                    targetType = MemoryItemRef.Type.NOTE,
                    targetId = note.id.value,
                    reason = reason,
                )
                note.copy(archivedAt = completedAt, updatedAt = completedAt)
            } else {
                appliedOps += MemorySourceForgetOp(
                    op = "prune_note_evidence",
                    targetType = MemoryItemRef.Type.NOTE,
                    targetId = note.id.value,
                    reason = reason,
                )
                note.copy(
                    evidenceRefs = remainingEvidence,
                    evidenceCount = remainingEvidence.size,
                    updatedAt = completedAt,
                )
            }
        }

        val actionItems = snapshot.actionItems.mapNotNull { actionItem ->
            if (actionItem.archivedAt != null) return@mapNotNull null
            val remainingEvidence = actionItem.evidenceRefs.filterNot { it.sourceId in forgottenSourceIds }
            if (remainingEvidence.size == actionItem.evidenceRefs.size) return@mapNotNull null
            if (remainingEvidence.isEmpty()) {
                appliedOps += MemorySourceForgetOp(
                    op = "archive_action_item",
                    targetType = MemoryItemRef.Type.ACTION_ITEM,
                    targetId = actionItem.id.value,
                    reason = reason,
                )
                actionItem.copy(archivedAt = completedAt, updatedAt = completedAt)
            } else {
                appliedOps += MemorySourceForgetOp(
                    op = "prune_action_item_evidence",
                    targetType = MemoryItemRef.Type.ACTION_ITEM,
                    targetId = actionItem.id.value,
                    reason = reason,
                )
                actionItem.copy(evidenceRefs = remainingEvidence, updatedAt = completedAt)
            }
        }

        val episodes = snapshot.episodes.mapNotNull { episode ->
            if (episode.archivedAt != null) return@mapNotNull null
            val remainingEvidence = episode.evidenceRefs.filterNot { it.sourceId in forgottenSourceIds }
            if (remainingEvidence.size == episode.evidenceRefs.size) return@mapNotNull null
            if (remainingEvidence.isEmpty()) {
                appliedOps += MemorySourceForgetOp(
                    op = "archive_episode",
                    targetType = MemoryItemRef.Type.EPISODE,
                    targetId = episode.id.value,
                    reason = reason,
                )
                episode.copy(archivedAt = completedAt, updatedAt = completedAt)
            } else {
                appliedOps += MemorySourceForgetOp(
                    op = "prune_episode_evidence",
                    targetType = MemoryItemRef.Type.EPISODE,
                    targetId = episode.id.value,
                    reason = reason,
                )
                episode.copy(evidenceRefs = remainingEvidence, updatedAt = completedAt)
            }
        }

        val entities = snapshot.entities.mapNotNull { entity ->
            val aliases = entity.aliases.filterNot { it.sourceId in forgottenSourceIds }
            if (aliases.size == entity.aliases.size) return@mapNotNull null
            appliedOps += MemorySourceForgetOp(
                op = "prune_entity_aliases",
                targetType = MemoryItemRef.Type.ENTITY,
                targetId = entity.id.value,
                reason = reason,
            )
            entity.copy(aliases = aliases, updatedAt = completedAt)
        }

        return MemorySourceForgetCascadeResult(
            forgottenSourceIds = forgottenSourceIds,
            memoryBatch = MemoryUpdateBatch(
                sources = sources,
                entities = entities,
                claims = claims,
                notes = notes,
                actionItems = actionItems,
                episodes = episodes,
            ),
            appliedOps = appliedOps,
        )
    }

    fun verify(
        activeSnapshot: MemoryNamespaceSnapshot,
        forgottenSourceIds: Set<MemorySource.Id>,
    ) {
        if (forgottenSourceIds.isEmpty()) return
        val activeSourceIds = activeSnapshot.sources.mapTo(mutableSetOf()) { it.id }
        val leakedSourceIds = forgottenSourceIds.intersect(activeSourceIds)
        val leakedRefs = buildList {
            activeSnapshot.claims.forEach { claim ->
                if (claim.evidenceRefs.any { it.sourceId in forgottenSourceIds }) {
                    add("${MemoryItemRef.Type.CLAIM}:${claim.id.value}")
                }
            }
            activeSnapshot.notes.forEach { note ->
                if (note.evidenceRefs.any { it.sourceId in forgottenSourceIds }) {
                    add("${MemoryItemRef.Type.NOTE}:${note.id.value}")
                }
            }
            activeSnapshot.actionItems.forEach { actionItem ->
                if (actionItem.evidenceRefs.any { it.sourceId in forgottenSourceIds }) {
                    add("${MemoryItemRef.Type.ACTION_ITEM}:${actionItem.id.value}")
                }
            }
            activeSnapshot.episodes.forEach { episode ->
                if (episode.evidenceRefs.any { it.sourceId in forgottenSourceIds }) {
                    add("${MemoryItemRef.Type.EPISODE}:${episode.id.value}")
                }
            }
            activeSnapshot.entities.forEach { entity ->
                if (entity.aliases.any { it.sourceId in forgottenSourceIds }) {
                    add("${MemoryItemRef.Type.ENTITY}:${entity.id.value}")
                }
            }
        }
        check(leakedSourceIds.isEmpty() && leakedRefs.isEmpty()) {
            "Memory source forget postcondition failed: activeSources=${leakedSourceIds.map { it.value }.sorted()} activeRefs=${leakedRefs.sorted()}"
        }
    }

    private fun logicalSourceClosure(
        sources: List<MemorySource>,
        requestedSourceIds: Set<MemorySource.Id>,
    ): Set<MemorySource.Id> {
        val sourcesById = sources.associateBy { it.id }
        val parentIds = sources.mapNotNull { source ->
            source.parentSourceId()?.let { source.id to it }
        }.toMap()
        val closure = requestedSourceIds.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            closure.toList().forEach { sourceId ->
                val parentId = parentIds[sourceId]
                if (parentId != null && parentId in sourcesById && closure.add(parentId)) {
                    changed = true
                }
            }
            parentIds.forEach { (sourceId, parentId) ->
                if (parentId in closure && closure.add(sourceId)) {
                    changed = true
                }
            }
        } while (changed)
        return closure
    }
}

private fun MemorySource.parentSourceId(): MemorySource.Id? =
    (contentPayload as? JsonObject)
        ?.get("parentSourceId")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let(MemorySource::Id)

private fun MemorySource.withDeletedAt(deletedAt: Instant): MemorySource =
    when (this) {
        is MemorySource.ChatTurn -> copy(deletedAt = deletedAt)
        is MemorySource.ToolOutput -> copy(deletedAt = deletedAt)
        is MemorySource.ImportedNote -> copy(deletedAt = deletedAt)
        is MemorySource.ExternalRecord -> copy(deletedAt = deletedAt)
    }
