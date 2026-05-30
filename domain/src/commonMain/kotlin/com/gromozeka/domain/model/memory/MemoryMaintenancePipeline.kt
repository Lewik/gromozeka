package com.gromozeka.domain.model.memory

import com.gromozeka.domain.model.Conversation

/**
 * Manual or background maintenance pass over one memory namespace.
 */
data class MemoryMaintenanceRequest(
    val namespace: MemoryNamespace,
    val conversationId: Conversation.Id? = null,
    val triggerMode: MemoryRun.TriggerMode = MemoryRun.TriggerMode.MANUAL,
)

/**
 * Reviews existing notes and decides whether any should become stronger memory.
 */
interface MemoryNoteConsolidator {
    suspend fun consolidate(
        request: MemoryMaintenanceRequest,
        selectedNotes: List<MemoryNote>,
        relatedHits: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): NoteConsolidationResult
}

/**
 * Reviews suspicious memory clusters and proposes explicit repair operations.
 */
interface MemoryRepairPlanner {
    suspend fun plan(
        request: MemoryMaintenanceRequest,
        candidateClusters: List<MemoryRepairCandidateCluster>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryRepairPlan
}

data class MemoryRepairCandidateCluster(
    val id: String,
    val kind: Kind,
    val hits: List<MemoryStore.SearchHit>,
    val reason: String,
) {
    enum class Kind {
        DUPLICATE_CLAIMS,
        CONFLICTING_CLAIMS,
        DUPLICATE_NOTES,
        DUPLICATE_ACTION_ITEMS,
        DUPLICATE_EPISODES,
        PROFILE_DRIFT,
    }
}

/**
 * Classifies memory candidates for retention or archival.
 */
interface MemoryRetentionPlanner {
    suspend fun plan(
        request: MemoryMaintenanceRequest,
        candidates: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryRetentionPlan
}

/**
 * Candidate cluster of entities that may describe the same referent.
 */
data class MemoryEntityMaintenanceCandidateGroup(
    val id: String,
    val entities: List<MemoryEntity>,
    val reason: String,
)

/**
 * Reviews near-duplicate entity clusters and proposes merge/alias decisions.
 */
interface MemoryEntityMaintenancePlanner {
    suspend fun plan(
        request: MemoryMaintenanceRequest,
        candidateGroups: List<MemoryEntityMaintenanceCandidateGroup>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryEntityMaintenancePlan
}
