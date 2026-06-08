package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryMaintenanceRequest
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryProfile
import com.gromozeka.domain.model.memory.MemoryRepairCandidateCluster
import com.gromozeka.domain.model.memory.MemoryRepairPlan
import com.gromozeka.domain.model.memory.MemoryRepairPlanner
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import com.gromozeka.domain.model.memory.resolvePredicateDefinition
import klog.KLoggers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class MemoryRepairPipelineResult(
    val candidateClusters: List<MemoryRepairCandidateCluster>,
    val suspiciousHits: List<MemoryStore.SearchHit>,
    val repairPlan: MemoryRepairPlan,
    val memoryBatch: MemoryUpdateBatch,
)

class MemoryRepairPipeline(
    private val store: MemoryStore,
    private val planner: MemoryRepairPlanner,
    private val idFactory: MemoryIdFactory,
    private val profileUpdater: ProjectionMemoryProfileUpdater,
    private val embeddingIndexer: MemoryEmbeddingIndexer = NoOpMemoryEmbeddingIndexer,
    private val clock: MemoryClock = SystemMemoryClock,
) {
    private val log = KLoggers.logger(this)

    suspend fun run(request: MemoryMaintenanceRequest): MemoryRepairPipelineResult {
        val startedAt = clock.now()
        val snapshot = store.loadNamespaceSnapshot(request.namespace)
        val candidateClusters = snapshot.detectRepairCandidateClusters()
        val suspiciousHits = candidateClusters.flattenRepairHits()

        log.info {
            "Memory repair selected: namespace=${request.namespace.value} conversation=${request.conversationId?.value ?: "none"} " +
                "snapshot=${snapshot.countsForRepairLog()} clusters=${candidateClusters.size} suspiciousHits=${suspiciousHits.size} " +
                "clustersDetail=${candidateClusters.joinToString("|") { it.repairClusterForLog() }.ifBlank { "none" }}"
        }

        val plan = planRepair(request, candidateClusters, snapshot)
        val completedAt = clock.now()
        val structuredBatch = materialize(
            request = request,
            startedAt = startedAt,
            completedAt = completedAt,
            snapshot = snapshot,
            candidateClusters = candidateClusters,
            plan = plan,
        )

        val indexedStructuredBatch = embeddingIndexer.withEmbeddings(structuredBatch)
        store.apply(indexedStructuredBatch)

        val shouldRefreshProfiles = plan.repairActions.any { it.action == MemoryRepairPlan.Action.Type.REFRESH_PROFILE }
        val profileBatch = profileUpdater.updateNamespaceProfiles(
            namespace = request.namespace,
            logSubject = "maintenance=memory_repair",
            appliedBatch = indexedStructuredBatch,
            completedAt = completedAt,
            force = shouldRefreshProfiles,
        )
        val indexedProfileBatch = embeddingIndexer.withEmbeddings(profileBatch)

        if (indexedProfileBatch.isNotEmptyForRepair()) {
            store.apply(indexedProfileBatch)
        }

        val memoryBatch = indexedStructuredBatch + indexedProfileBatch

        log.info {
            "Memory repair completed: namespace=${request.namespace.value} suspiciousHits=${suspiciousHits.size} " +
                "actions=${plan.repairActions.size} appliedRuns=${memoryBatch.runs.size} appliedClaims=${memoryBatch.claims.size} " +
                "appliedNotes=${memoryBatch.notes.size} appliedTasks=${memoryBatch.actionItems.size} appliedProfiles=${memoryBatch.profiles.size} " +
                "summary=${plan.summary.oneLineForRepairPipelineLog(500)}"
        }

        return MemoryRepairPipelineResult(
            candidateClusters = candidateClusters,
            suspiciousHits = suspiciousHits,
            repairPlan = plan,
            memoryBatch = memoryBatch,
        )
    }

    private suspend fun planRepair(
        request: MemoryMaintenanceRequest,
        candidateClusters: List<MemoryRepairCandidateCluster>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryRepairPlan {
        if (candidateClusters.size <= REPAIR_PLANNER_CLUSTER_BATCH_SIZE) {
            return planner.plan(request, candidateClusters, snapshot)
        }

        val plans = candidateClusters
            .chunked(REPAIR_PLANNER_CLUSTER_BATCH_SIZE)
            .mapIndexed { index, batch ->
                log.info {
                    "Memory repair planner batch: namespace=${request.namespace.value} " +
                        "batch=${index + 1} clusters=${batch.size} totalClusters=${candidateClusters.size}"
                }
                planner.plan(request, batch, snapshot)
            }

        return MemoryRepairPlan(
            repairActions = plans.flatMap { it.repairActions },
            summary = plans.joinToString(" ") { it.summary }.ifBlank {
                "Memory repair planned over ${plans.size} batches."
            },
        )
    }

    private fun materialize(
        request: MemoryMaintenanceRequest,
        startedAt: kotlinx.datetime.Instant,
        completedAt: kotlinx.datetime.Instant,
        snapshot: MemoryNamespaceSnapshot,
        candidateClusters: List<MemoryRepairCandidateCluster>,
        plan: MemoryRepairPlan,
    ): MemoryUpdateBatch {
        val runId = idFactory.newRunId()
        val suspiciousRefList = candidateClusters
            .flatMap { cluster -> cluster.hits.sortedWith(repairPipelineHitComparator).map { it.toRepairPipelineItemRef() } }
            .distinctBy { "${it.type.name}:${it.id}" }
        val suspiciousRefs = suspiciousRefList.toSet()
        val repairedClaims = mutableMapOf<MemoryClaim.Id, MemoryClaim>()
        val repairedNotes = mutableMapOf<MemoryNote.Id, MemoryNote>()
        val repairedActionItems = mutableMapOf<MemoryActionItem.Id, MemoryActionItem>()
        val repairedEpisodes = mutableMapOf<MemoryEpisode.Id, MemoryEpisode>()
        val appliedOps = buildJsonArray {
            plan.repairActions.forEach { action ->
                val applied = applyAction(
                    action = action,
                    completedAt = completedAt,
                    snapshot = snapshot,
                    suspiciousRefs = suspiciousRefs,
                    repairedClaims = repairedClaims,
                    repairedNotes = repairedNotes,
                    repairedActionItems = repairedActionItems,
                    repairedEpisodes = repairedEpisodes,
                )
                applied.forEach { op ->
                    add(buildJsonObject {
                        put("op", op.op)
                        put("target_type", op.targetType.name)
                        put("target_id", op.targetId)
                        put("reason", op.reason)
                    })
                }
            }
        }

        val run = MemoryRun(
            id = runId,
            namespace = request.namespace,
            runType = MemoryRun.Type.REPAIR_MEMORY,
            triggerMode = request.triggerMode,
            summary = plan.summary.ifBlank { "Memory repair completed." },
            retrievedItemRefs = suspiciousRefList,
            promptName = "MemoryRepairPlanner",
            promptVersion = "v1",
            output = plan.toRepairOutputJson(candidateClusters),
            repairActions = plan.toRepairActionsJson(),
            appliedOps = appliedOps,
            llmCalls = currentMemoryRunLlmCalls(),
            latencyMs = completedAt.toEpochMilliseconds() - startedAt.toEpochMilliseconds(),
            status = MemoryRun.Status.SUCCESS,
            createdAt = startedAt,
            startedAt = startedAt,
            completedAt = completedAt,
        )

        return MemoryUpdateBatch(
            runs = listOf(run),
            claims = repairedClaims.values.toList(),
            notes = repairedNotes.values.toList(),
            actionItems = repairedActionItems.values.toList(),
            episodes = repairedEpisodes.values.toList(),
        )
    }

    private fun applyAction(
        action: MemoryRepairPlan.Action,
        completedAt: kotlinx.datetime.Instant,
        snapshot: MemoryNamespaceSnapshot,
        suspiciousRefs: Set<MemoryItemRef>,
        repairedClaims: MutableMap<MemoryClaim.Id, MemoryClaim>,
        repairedNotes: MutableMap<MemoryNote.Id, MemoryNote>,
        repairedActionItems: MutableMap<MemoryActionItem.Id, MemoryActionItem>,
        repairedEpisodes: MutableMap<MemoryEpisode.Id, MemoryEpisode>,
    ): List<AppliedRepairOp> {
        if (action.action == MemoryRepairPlan.Action.Type.NOOP) {
            return emptyList()
        }

        if (action.targetIds.any { MemoryItemRef(action.targetType, it) !in suspiciousRefs }) {
            log.info {
                "Memory repair skipped action outside suspicious set: action=${action.action.name} targetType=${action.targetType.name} " +
                    "targetIds=${action.targetIds.joinToString("|")} reason=${action.reason.oneLineForRepairPipelineLog(240)}"
            }
            return emptyList()
        }

        return when (action.targetType) {
            MemoryItemRef.Type.CLAIM -> applyClaimAction(action, completedAt, snapshot, repairedClaims)
            MemoryItemRef.Type.NOTE -> applyNoteAction(action, completedAt, snapshot, repairedNotes)
            MemoryItemRef.Type.ACTION_ITEM -> applyTaskAction(action, completedAt, snapshot, repairedActionItems)
            MemoryItemRef.Type.EPISODE -> applyEpisodeAction(action, completedAt, snapshot, repairedEpisodes)
            MemoryItemRef.Type.PROFILE -> applyProfileAction(action)
            MemoryItemRef.Type.ENTITY,
            MemoryItemRef.Type.SOURCE,
            MemoryItemRef.Type.RUN,
            -> emptyList()
        }
    }

    private fun applyClaimAction(
        action: MemoryRepairPlan.Action,
        completedAt: kotlinx.datetime.Instant,
        snapshot: MemoryNamespaceSnapshot,
        repairedClaims: MutableMap<MemoryClaim.Id, MemoryClaim>,
    ): List<AppliedRepairOp> {
        val claims = action.targetIds.mapNotNull { id -> snapshot.claims.firstOrNull { it.id.value == id } }
        if (claims.isEmpty()) return emptyList()

        return when (action.action) {
            MemoryRepairPlan.Action.Type.MERGE_DUPLICATES -> mergeDuplicateClaims(claims, completedAt, repairedClaims, action.reason)
            MemoryRepairPlan.Action.Type.ARCHIVE_ITEM -> archiveClaimsIfSafe(claims, snapshot, completedAt, repairedClaims, action.reason)
            MemoryRepairPlan.Action.Type.SUPERSEDE_ITEM -> supersedeClaimsIfSafe(claims, snapshot, completedAt, repairedClaims, action.reason)
            MemoryRepairPlan.Action.Type.REFRESH_PROFILE,
            MemoryRepairPlan.Action.Type.NOOP,
            -> emptyList()
        }
    }

    private fun mergeDuplicateClaims(
        claims: List<MemoryClaim>,
        completedAt: kotlinx.datetime.Instant,
        repairedClaims: MutableMap<MemoryClaim.Id, MemoryClaim>,
        reason: String,
    ): List<AppliedRepairOp> {
        val groups = claims.groupBy { it.repairDuplicateKey() }
        val applied = mutableListOf<AppliedRepairOp>()

        groups.values.filter { it.size > 1 }.forEach { duplicateClaims ->
            val keeper = duplicateClaims.maxWith(compareBy<MemoryClaim> { it.confidence }.thenBy { it.importance }.thenBy { it.updatedAt })
            val losers = duplicateClaims.filter { it.id != keeper.id }
            val mergedEvidence = (keeper.evidenceRefs + losers.flatMap { it.evidenceRefs }).distinctEvidenceRefs()
            repairedClaims[keeper.id] = keeper.copy(
                evidenceRefs = mergedEvidence,
                useCount = keeper.useCount + losers.sumOf { it.useCount },
                updatedAt = completedAt,
            )
            losers.forEach { loser ->
                repairedClaims[loser.id] = loser.copy(archivedAt = completedAt, updatedAt = completedAt)
                applied += AppliedRepairOp("merge_duplicate_claim_archive", MemoryItemRef.Type.CLAIM, loser.id.value, reason)
            }
            applied += AppliedRepairOp("merge_duplicate_claim_keep", MemoryItemRef.Type.CLAIM, keeper.id.value, reason)
        }

        return applied
    }

    private fun archiveClaimsIfSafe(
        claims: List<MemoryClaim>,
        snapshot: MemoryNamespaceSnapshot,
        completedAt: kotlinx.datetime.Instant,
        repairedClaims: MutableMap<MemoryClaim.Id, MemoryClaim>,
        reason: String,
    ): List<AppliedRepairOp> =
        claims.filter { claim -> claim.canArchiveClaim(snapshot) }
            .map { claim ->
                repairedClaims[claim.id] = claim.copy(archivedAt = completedAt, updatedAt = completedAt)
                AppliedRepairOp("archive_claim", MemoryItemRef.Type.CLAIM, claim.id.value, reason)
            }

    private fun supersedeClaimsIfSafe(
        claims: List<MemoryClaim>,
        snapshot: MemoryNamespaceSnapshot,
        completedAt: kotlinx.datetime.Instant,
        repairedClaims: MutableMap<MemoryClaim.Id, MemoryClaim>,
        reason: String,
    ): List<AppliedRepairOp> =
        claims.filter { claim -> claim.canSupersedeClaim(snapshot) }
            .map { claim ->
                repairedClaims[claim.id] = claim.copy(status = MemoryClaim.Status.SUPERSEDED, updatedAt = completedAt)
                AppliedRepairOp("supersede_claim", MemoryItemRef.Type.CLAIM, claim.id.value, reason)
            }

    private fun applyNoteAction(
        action: MemoryRepairPlan.Action,
        completedAt: kotlinx.datetime.Instant,
        snapshot: MemoryNamespaceSnapshot,
        repairedNotes: MutableMap<MemoryNote.Id, MemoryNote>,
    ): List<AppliedRepairOp> {
        val notes = action.targetIds.mapNotNull { id -> snapshot.notes.firstOrNull { it.id.value == id } }
        if (notes.isEmpty()) return emptyList()

        return when (action.action) {
            MemoryRepairPlan.Action.Type.MERGE_DUPLICATES -> mergeDuplicateNotes(notes, completedAt, repairedNotes, action.reason)
            MemoryRepairPlan.Action.Type.ARCHIVE_ITEM -> archiveNotesIfSafe(notes, snapshot, completedAt, repairedNotes, action.reason)
            MemoryRepairPlan.Action.Type.SUPERSEDE_ITEM -> supersedeNotesIfSafe(notes, snapshot, completedAt, repairedNotes, action.reason)
            MemoryRepairPlan.Action.Type.REFRESH_PROFILE,
            MemoryRepairPlan.Action.Type.NOOP,
            -> emptyList()
        }
    }

    private fun mergeDuplicateNotes(
        notes: List<MemoryNote>,
        completedAt: kotlinx.datetime.Instant,
        repairedNotes: MutableMap<MemoryNote.Id, MemoryNote>,
        reason: String,
    ): List<AppliedRepairOp> {
        val groups = notes.groupBy { it.repairDuplicateKey() }
        val applied = mutableListOf<AppliedRepairOp>()

        groups.values.filter { it.size > 1 }.forEach { duplicateNotes ->
            val keeper = duplicateNotes.maxWith(compareBy<MemoryNote> { it.confidence }.thenBy { it.importance }.thenBy { it.updatedAt })
            val losers = duplicateNotes.filter { it.id != keeper.id }
            val mergedEvidence = (keeper.evidenceRefs + losers.flatMap { it.evidenceRefs }).distinctEvidenceRefs()
            repairedNotes[keeper.id] = keeper.copy(
                evidenceRefs = mergedEvidence,
                evidenceCount = mergedEvidence.size,
                useCount = keeper.useCount + losers.sumOf { it.useCount },
                updatedAt = completedAt,
            )
            losers.forEach { loser ->
                repairedNotes[loser.id] = loser.copy(archivedAt = completedAt, updatedAt = completedAt)
                applied += AppliedRepairOp("merge_duplicate_note_archive", MemoryItemRef.Type.NOTE, loser.id.value, reason)
            }
            applied += AppliedRepairOp("merge_duplicate_note_keep", MemoryItemRef.Type.NOTE, keeper.id.value, reason)
        }

        return applied
    }

    private fun archiveNotesIfSafe(
        notes: List<MemoryNote>,
        snapshot: MemoryNamespaceSnapshot,
        completedAt: kotlinx.datetime.Instant,
        repairedNotes: MutableMap<MemoryNote.Id, MemoryNote>,
        reason: String,
    ): List<AppliedRepairOp> =
        notes.filter { note -> note.canArchiveNote(snapshot) }
            .map { note ->
                repairedNotes[note.id] = note.copy(archivedAt = completedAt, updatedAt = completedAt)
                AppliedRepairOp("archive_note", MemoryItemRef.Type.NOTE, note.id.value, reason)
            }

    private fun supersedeNotesIfSafe(
        notes: List<MemoryNote>,
        snapshot: MemoryNamespaceSnapshot,
        completedAt: kotlinx.datetime.Instant,
        repairedNotes: MutableMap<MemoryNote.Id, MemoryNote>,
        reason: String,
    ): List<AppliedRepairOp> =
        notes.filter { note -> note.canArchiveNote(snapshot) }
            .map { note ->
                repairedNotes[note.id] = note.copy(status = MemoryNote.Status.SUPERSEDED, updatedAt = completedAt)
                AppliedRepairOp("supersede_note", MemoryItemRef.Type.NOTE, note.id.value, reason)
            }

    private fun applyTaskAction(
        action: MemoryRepairPlan.Action,
        completedAt: kotlinx.datetime.Instant,
        snapshot: MemoryNamespaceSnapshot,
        repairedActionItems: MutableMap<MemoryActionItem.Id, MemoryActionItem>,
    ): List<AppliedRepairOp> {
        if (action.action != MemoryRepairPlan.Action.Type.MERGE_DUPLICATES &&
            action.action != MemoryRepairPlan.Action.Type.ARCHIVE_ITEM
        ) {
            return emptyList()
        }

        val actionItems = action.targetIds.mapNotNull { id -> snapshot.actionItems.firstOrNull { it.id.value == id } }
        val duplicateGroups = actionItems.groupBy { it.repairDuplicateKey() }.values.filter { it.size > 1 }
        return duplicateGroups.flatMap { duplicateActionItems ->
            val keeper = duplicateActionItems.maxWith(compareBy<MemoryActionItem> { it.confidence }.thenBy { it.updatedAt })
            duplicateActionItems.filter { it.id != keeper.id }.map { loser ->
                repairedActionItems[loser.id] = loser.copy(archivedAt = completedAt, updatedAt = completedAt)
                AppliedRepairOp("archive_duplicate_action_item", MemoryItemRef.Type.ACTION_ITEM, loser.id.value, action.reason)
            }
        }
    }

    private fun applyEpisodeAction(
        action: MemoryRepairPlan.Action,
        completedAt: kotlinx.datetime.Instant,
        snapshot: MemoryNamespaceSnapshot,
        repairedEpisodes: MutableMap<MemoryEpisode.Id, MemoryEpisode>,
    ): List<AppliedRepairOp> {
        if (action.action != MemoryRepairPlan.Action.Type.MERGE_DUPLICATES &&
            action.action != MemoryRepairPlan.Action.Type.ARCHIVE_ITEM
        ) {
            return emptyList()
        }

        val episodes = action.targetIds.mapNotNull { id -> snapshot.episodes.firstOrNull { it.id.value == id } }
        val duplicateGroups = episodes.groupBy { it.repairDuplicateKey() }.values.filter { it.size > 1 }
        return duplicateGroups.flatMap { duplicateEpisodes ->
            val keeper = duplicateEpisodes.maxWith(compareBy<MemoryEpisode> { it.successScore ?: 0.0 }.thenBy { it.updatedAt })
            duplicateEpisodes.filter { it.id != keeper.id }.map { loser ->
                repairedEpisodes[loser.id] = loser.copy(archivedAt = completedAt, updatedAt = completedAt)
                AppliedRepairOp("archive_duplicate_episode", MemoryItemRef.Type.EPISODE, loser.id.value, action.reason)
            }
        }
    }

    private fun applyProfileAction(action: MemoryRepairPlan.Action): List<AppliedRepairOp> {
        if (action.action != MemoryRepairPlan.Action.Type.REFRESH_PROFILE) return emptyList()
        return action.targetIds.map {
            AppliedRepairOp("refresh_profile", MemoryItemRef.Type.PROFILE, it, action.reason)
        }
    }
}

private data class AppliedRepairOp(
    val op: String,
    val targetType: MemoryItemRef.Type,
    val targetId: String,
    val reason: String,
)

private fun MemoryNamespaceSnapshot.detectRepairCandidateClusters(): List<MemoryRepairCandidateCluster> {
    val clusters = mutableListOf<MemoryRepairCandidateCluster>()

    claims
        .filter { it.archivedAt == null && it.status == MemoryClaim.Status.ACTIVE }
        .groupBy { it.repairDuplicateKey() }
        .entries
        .sortedBy { it.key }
        .map { it.value }
        .filter { it.size > 1 }
        .forEach { duplicateClaims ->
            clusters += MemoryRepairCandidateCluster(
                id = "repair-cluster-${clusters.size + 1}",
                kind = MemoryRepairCandidateCluster.Kind.DUPLICATE_CLAIMS,
                hits = duplicateClaims.sortedWith(repairClaimComparator).map { MemoryStore.SearchHit.ClaimHit(it, score = 1.0) },
                reason = "Active claims have the same subject, predicate, and normalized text.",
            )
        }

    claims
        .filter { it.archivedAt == null && it.status == MemoryClaim.Status.ACTIVE }
        .mapNotNull { claim -> claim.repairConflictKey(this)?.let { it to claim } }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key }
        .map { it.value }
        .filter { group ->
            group.size > 1 &&
                group.map { it.repairObjectKey() }.distinct().size > 1
        }
        .forEach { conflictingClaims ->
            clusters += MemoryRepairCandidateCluster(
                id = "repair-cluster-${clusters.size + 1}",
                kind = MemoryRepairCandidateCluster.Kind.CONFLICTING_CLAIMS,
                hits = conflictingClaims.sortedWith(repairClaimComparator).map { MemoryStore.SearchHit.ClaimHit(it, score = 0.95) },
                reason = "Active replacement-style claims share subject, predicate, and scope but disagree on object/value.",
            )
        }

    notes
        .filter { it.archivedAt == null && it.status == MemoryNote.Status.ACTIVE }
        .groupBy { it.repairDuplicateKey() }
        .entries
        .sortedBy { it.key }
        .map { it.value }
        .filter { it.size > 1 }
        .forEach { duplicateNotes ->
            clusters += MemoryRepairCandidateCluster(
                id = "repair-cluster-${clusters.size + 1}",
                kind = MemoryRepairCandidateCluster.Kind.DUPLICATE_NOTES,
                hits = duplicateNotes.sortedWith(repairNoteComparator).map { MemoryStore.SearchHit.NoteHit(it, score = 0.9) },
                reason = "Active notes have the same type, title, summary, and scope.",
            )
        }

    actionItems
        .filter { it.archivedAt == null && it.status in setOf(MemoryActionItem.Status.OPEN, MemoryActionItem.Status.IN_PROGRESS, MemoryActionItem.Status.BLOCKED) }
        .groupBy { it.repairDuplicateKey() }
        .entries
        .sortedBy { it.key }
        .map { it.value }
        .filter { it.size > 1 }
        .forEach { duplicateActionItems ->
            clusters += MemoryRepairCandidateCluster(
                id = "repair-cluster-${clusters.size + 1}",
                kind = MemoryRepairCandidateCluster.Kind.DUPLICATE_ACTION_ITEMS,
                hits = duplicateActionItems.sortedWith(repairTaskComparator).map { MemoryStore.SearchHit.ActionItemHit(it, score = 0.8) },
                reason = "Open actionItem-like memory items have the same title, scope, and status.",
            )
        }

    episodes
        .filter { it.archivedAt == null }
        .groupBy { it.repairDuplicateKey() }
        .entries
        .sortedBy { it.key }
        .map { it.value }
        .filter { it.size > 1 }
        .forEach { duplicateEpisodes ->
            clusters += MemoryRepairCandidateCluster(
                id = "repair-cluster-${clusters.size + 1}",
                kind = MemoryRepairCandidateCluster.Kind.DUPLICATE_EPISODES,
                hits = duplicateEpisodes.sortedWith(repairEpisodeComparator).map { MemoryStore.SearchHit.EpisodeHit(it, score = 0.75) },
                reason = "Episodes have the same owner, situation, action, result, and lesson.",
            )
        }

    profiles
        .sortedBy { it.id.value }
        .forEach { profile ->
            val relevantClaims = claims.profileRelevantClaims(profile)
            val relevantNotes = notes.profileRelevantNotes(profile)
            val relevantActionItems = actionItems.profileRelevantActionItems(profile)
            val latestRelevantUpdate = listOf(
                relevantClaims.maxOfOrNull { it.updatedAt },
                relevantNotes.maxOfOrNull { it.updatedAt },
                relevantActionItems.maxOfOrNull { it.updatedAt },
            ).filterNotNull().maxOrNull()
            if (latestRelevantUpdate != null && latestRelevantUpdate > profile.updatedAt) {
                val profileRelevantHits =
                    relevantClaims.map { MemoryStore.SearchHit.ClaimHit(it, score = 0.85) } +
                        relevantNotes.map { MemoryStore.SearchHit.NoteHit(it, score = 0.75) } +
                        relevantActionItems.map { MemoryStore.SearchHit.ActionItemHit(it, score = 0.7) }
                clusters += MemoryRepairCandidateCluster(
                    id = "repair-cluster-${clusters.size + 1}",
                    kind = MemoryRepairCandidateCluster.Kind.PROFILE_DRIFT,
                    hits = listOf(MemoryStore.SearchHit.ProfileHit(profile, score = 0.7)) +
                        profileRelevantHits.sortedWith(repairPipelineHitComparator).take(MAX_PROFILE_DRIFT_SUPPORTING_HITS),
                    reason = "Profile is older than active profile-relevant memory.",
                )
            }
        }

    return clusters
        .sortedWith(compareByDescending<MemoryRepairCandidateCluster> { it.kind.repairPriority() }.thenBy { it.id })
        .take(MAX_REPAIR_CANDIDATE_CLUSTERS)
}

private fun MemoryClaim.repairDuplicateKey(): String =
    listOf(subjectEntityId.value, predicate, normalizedText.normalizedRepairKey()).joinToString("|")

private fun MemoryClaim.repairConflictKey(snapshot: MemoryNamespaceSnapshot): String? {
    val predicateDefinition = predicatePolicy ?: snapshot.predicateDefinitions.resolvePredicateDefinition(predicate, predicateFamily)
    if (predicateDefinition == null ||
        (
            predicateDefinition.conflictPolicy != com.gromozeka.domain.model.memory.MemoryPredicateDefinition.ConflictPolicy.REPLACE &&
                predicateDefinition.cardinality != com.gromozeka.domain.model.memory.MemoryPredicateDefinition.Cardinality.SINGLE
        )
    ) {
        return null
    }

    return listOf(
        subjectEntityId.value,
        predicateDefinition.predicate.normalizedRepairKey(),
        scope.repairScopeKey(),
        validFrom?.toString().orEmpty(),
        validTo?.toString().orEmpty(),
    ).joinToString("|")
}

private fun MemoryClaim.repairObjectKey(): String =
    objectEntityId?.let { "entity:${it.value}" }
        ?: objectValue?.toString()?.normalizedRepairKey()?.let { "value:$it" }
        ?: normalizedText.normalizedRepairKey()

private fun MemoryNote.repairDuplicateKey(): String =
    listOf(noteType.name, title.normalizedRepairKey(), summary.normalizedRepairKey(), scope.text.normalizedRepairKey()).joinToString("|")

private fun MemoryActionItem.repairDuplicateKey(): String =
    listOf(title.normalizedRepairKey(), scope.text.normalizedRepairKey(), status.name).joinToString("|")

private fun MemoryEpisode.repairDuplicateKey(): String =
    listOf(
        ownerEntityId?.value.orEmpty(),
        situation.normalizedRepairKey(),
        action.normalizedRepairKey(),
        result.normalizedRepairKey(),
        lesson.normalizedRepairKey(),
    ).joinToString("|")

private fun List<MemoryClaim>.profileRelevantClaims(profile: MemoryProfile): List<MemoryClaim> =
    filter {
        it.archivedAt == null &&
            it.status == MemoryClaim.Status.ACTIVE &&
            it.subjectEntityId == profile.ownerEntityId
    }.sortedWith(repairClaimComparator)

private fun List<MemoryNote>.profileRelevantNotes(profile: MemoryProfile): List<MemoryNote> =
    filter {
        it.archivedAt == null &&
            it.status == MemoryNote.Status.ACTIVE &&
            (it.anchorEntityId == profile.ownerEntityId || it.entityRefs.any { ref -> ref.entityId == profile.ownerEntityId })
    }.sortedWith(repairNoteComparator)

private fun List<MemoryActionItem>.profileRelevantActionItems(profile: MemoryProfile): List<MemoryActionItem> =
    filter {
        it.archivedAt == null &&
            it.status in setOf(MemoryActionItem.Status.OPEN, MemoryActionItem.Status.IN_PROGRESS, MemoryActionItem.Status.BLOCKED) &&
            (it.ownerEntityId == profile.ownerEntityId || it.relatedEntityIds.contains(profile.ownerEntityId))
    }.sortedWith(repairTaskComparator)

private fun MemoryClaim.canArchiveClaim(snapshot: MemoryNamespaceSnapshot): Boolean {
    if (status != MemoryClaim.Status.ACTIVE) return true
    return snapshot.claims.any {
        it.id != id &&
            it.archivedAt == null &&
            it.status == MemoryClaim.Status.ACTIVE &&
            it.repairDuplicateKey() == repairDuplicateKey()
    }
}

private fun MemoryClaim.canSupersedeClaim(snapshot: MemoryNamespaceSnapshot): Boolean {
    if (status != MemoryClaim.Status.ACTIVE) return true
    val key = repairConflictKey(snapshot) ?: return canArchiveClaim(snapshot)
    return snapshot.claims.any {
        it.id != id &&
            it.archivedAt == null &&
            it.status == MemoryClaim.Status.ACTIVE &&
            it.repairConflictKey(snapshot) == key &&
            it.updatedAt >= updatedAt
    }
}

private fun MemoryNote.canArchiveNote(snapshot: MemoryNamespaceSnapshot): Boolean {
    if (status != MemoryNote.Status.ACTIVE) return true
    return snapshot.notes.any {
        it.id != id &&
            it.archivedAt == null &&
            it.status == MemoryNote.Status.ACTIVE &&
            it.repairDuplicateKey() == repairDuplicateKey()
    }
}

private fun List<MemoryEvidenceRef>.distinctEvidenceRefs(): List<MemoryEvidenceRef> =
    distinctBy { "${it.sourceId.value}:${it.kind.name}:${it.cachedQuote ?: ""}:${it.span}" }

private fun List<MemoryRepairCandidateCluster>.flattenRepairHits(): List<MemoryStore.SearchHit> =
    flatMap { it.hits.sortedWith(repairPipelineHitComparator) }
        .distinctBy { "${it.toRepairPipelineItemRef().type.name}:${it.toRepairPipelineItemRef().id}" }
        .take(MAX_REPAIR_SUSPICIOUS_HITS)

private fun MemoryStore.SearchHit.toRepairPipelineItemRef(): MemoryItemRef =
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

private fun MemoryRepairCandidateCluster.repairClusterForLog(): String =
    "$id:${kind.name}:${reason.oneLineForRepairPipelineLog(160)}:" +
        hits.joinToString(",") { it.repairHitForLog() }

private fun MemoryStore.SearchHit.repairHitForLog(): String =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> "claim:${claim.id.value}:${claim.predicate}:${claim.normalizedText.oneLineForRepairPipelineLog(120)}"
        is MemoryStore.SearchHit.NoteHit -> "note:${note.id.value}:${note.noteType.name}:${note.title.oneLineForRepairPipelineLog(120)}"
        is MemoryStore.SearchHit.ActionItemHit -> "actionItem:${actionItem.id.value}:${actionItem.status.name}:${actionItem.title.oneLineForRepairPipelineLog(120)}"
        is MemoryStore.SearchHit.ProfileHit -> "profile:${profile.id.value}:${profile.ownerEntityId.value}"
        is MemoryStore.SearchHit.EpisodeHit -> "episode:${episode.id.value}:${episode.lesson.oneLineForRepairPipelineLog(120)}"
        is MemoryStore.SearchHit.EntityHit -> "entity:${entity.id.value}:${entity.canonicalName.oneLineForRepairPipelineLog(80)}"
        is MemoryStore.SearchHit.SourceHit -> "source:${source.id.value}:${source.contentText.oneLineForRepairPipelineLog(80)}"
        is MemoryStore.SearchHit.RunHit -> "run:${run.id.value}:${run.runType.name}"
    }

private fun MemoryRepairPlan.toRepairOutputJson(candidateClusters: List<MemoryRepairCandidateCluster>) =
    buildJsonObject {
        put("summary", summary)
        put("clusters", candidateClusters.toRepairClustersJson())
        put("repair_actions", toRepairActionsJson())
    }

private fun List<MemoryRepairCandidateCluster>.toRepairClustersJson(): JsonArray =
    buildJsonArray {
        forEach { cluster ->
            add(buildJsonObject {
                put("id", cluster.id)
                put("kind", cluster.kind.name)
                put("reason", cluster.reason)
                putJsonArray("item_refs") {
                    cluster.hits
                        .map { it.toRepairPipelineItemRef() }
                        .forEach { ref -> add(JsonPrimitive("${ref.type.name}:${ref.id}")) }
                }
            })
        }
    }

private fun MemoryRepairPlan.toRepairActionsJson() =
    buildJsonArray {
        repairActions.forEach { action ->
            add(buildJsonObject {
                put("action", action.action.name)
                put("target_type", action.targetType.name)
                put("target_ids", action.targetIds.joinToString("|"))
                put("reason", action.reason)
            })
        }
    }

private fun MemoryUpdateBatch.isNotEmptyForRepair(): Boolean =
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

private fun MemoryNamespaceSnapshot.countsForRepairLog(): String =
    "sources=${sources.size},runs=${runs.size},entities=${entities.size},claims=${claims.size},notes=${notes.size},actionItems=${actionItems.size},profiles=${profiles.size},episodes=${episodes.size}"

private fun MemoryRepairCandidateCluster.Kind.repairPriority(): Int =
    when (this) {
        MemoryRepairCandidateCluster.Kind.CONFLICTING_CLAIMS -> 100
        MemoryRepairCandidateCluster.Kind.DUPLICATE_CLAIMS -> 90
        MemoryRepairCandidateCluster.Kind.DUPLICATE_NOTES -> 80
        MemoryRepairCandidateCluster.Kind.DUPLICATE_ACTION_ITEMS -> 70
        MemoryRepairCandidateCluster.Kind.DUPLICATE_EPISODES -> 60
        MemoryRepairCandidateCluster.Kind.PROFILE_DRIFT -> 50
    }

private val repairClaimComparator: Comparator<MemoryClaim> =
    compareBy<MemoryClaim> { it.updatedAt }.thenBy { it.id.value }

private val repairNoteComparator: Comparator<MemoryNote> =
    compareBy<MemoryNote> { it.updatedAt }.thenBy { it.id.value }

private val repairTaskComparator: Comparator<MemoryActionItem> =
    compareBy<MemoryActionItem> { it.updatedAt }.thenBy { it.id.value }

private val repairEpisodeComparator: Comparator<MemoryEpisode> =
    compareBy<MemoryEpisode> { it.updatedAt }.thenBy { it.id.value }

private val repairPipelineHitComparator: Comparator<MemoryStore.SearchHit> =
    compareBy<MemoryStore.SearchHit> { it.repairPipelineSortTime()?.toString().orEmpty() }
        .thenBy { it.toRepairPipelineItemRef().type.name }
        .thenBy { it.toRepairPipelineItemRef().id }

private fun MemoryStore.SearchHit.repairPipelineSortTime(): kotlinx.datetime.Instant? =
    when (this) {
        is MemoryStore.SearchHit.SourceHit -> source.observedAt
        is MemoryStore.SearchHit.EntityHit -> entity.updatedAt
        is MemoryStore.SearchHit.ClaimHit -> claim.updatedAt
        is MemoryStore.SearchHit.NoteHit -> note.updatedAt
        is MemoryStore.SearchHit.ActionItemHit -> actionItem.updatedAt
        is MemoryStore.SearchHit.ProfileHit -> profile.updatedAt
        is MemoryStore.SearchHit.EpisodeHit -> episode.updatedAt
        is MemoryStore.SearchHit.RunHit -> run.completedAt ?: run.createdAt
    }

private fun com.gromozeka.domain.model.memory.MemoryScope.repairScopeKey(): String =
    when (this) {
        is com.gromozeka.domain.model.memory.MemoryScope.Global -> "global"
        is com.gromozeka.domain.model.memory.MemoryScope.Project -> "project:${projectId.value}"
        is com.gromozeka.domain.model.memory.MemoryScope.Conversation -> "conversation:${conversationId.value}:${projectId?.value ?: "null"}"
        is com.gromozeka.domain.model.memory.MemoryScope.Entity -> "entity:${subjectEntityId.value}"
        is com.gromozeka.domain.model.memory.MemoryScope.Environment -> "environment:${environment.normalizedRepairKey()}"
        is com.gromozeka.domain.model.memory.MemoryScope.Document -> "document:${documentRef.normalizedRepairKey()}"
    }

private fun String.normalizedRepairKey(): String =
    lowercase().replace(Regex("\\s+"), " ").trim()

private fun String.oneLineForRepairPipelineLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}

private const val MAX_REPAIR_CANDIDATE_CLUSTERS = 40
private const val MAX_REPAIR_SUSPICIOUS_HITS = 80
private const val MAX_PROFILE_DRIFT_SUPPORTING_HITS = 8
private const val REPAIR_PLANNER_CLUSTER_BATCH_SIZE = 8
