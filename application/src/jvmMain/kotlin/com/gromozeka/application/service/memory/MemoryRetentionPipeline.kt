package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryMaintenanceRequest
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryRetentionPlan
import com.gromozeka.domain.model.memory.MemoryRetentionPlanner
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import klog.KLoggers
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class MemoryRetentionPipelineResult(
    val candidates: List<MemoryStore.SearchHit>,
    val retentionPlan: MemoryRetentionPlan,
    val memoryBatch: MemoryUpdateBatch,
)

class MemoryRetentionPipeline(
    private val store: MemoryStore,
    private val planner: MemoryRetentionPlanner,
    private val idFactory: MemoryIdFactory,
    private val clock: MemoryClock = SystemMemoryClock,
) {
    private val log = KLoggers.logger(this)

    suspend fun run(request: MemoryMaintenanceRequest): MemoryRetentionPipelineResult {
        val startedAt = clock.now()
        val snapshot = store.loadNamespaceSnapshot(request.namespace)
        val candidates = snapshot.selectRetentionCandidates()

        log.info {
            "Memory retention selected: namespace=${request.namespace.value} conversation=${request.conversationId?.value ?: "none"} " +
                "snapshot=${snapshot.countsForRetentionLog()} candidates=${candidates.size} " +
                "items=${candidates.joinToString("|") { it.retentionHitForLog() }.ifBlank { "none" }}"
        }

        val plan = planner.plan(request, candidates, snapshot)
        val completedAt = clock.now()
        val batch = materialize(
            request = request,
            startedAt = startedAt,
            completedAt = completedAt,
            snapshot = snapshot,
            candidates = candidates,
            plan = plan,
        )

        store.apply(batch)

        log.info {
            "Memory retention completed: namespace=${request.namespace.value} candidates=${candidates.size} " +
                "actions=${plan.retentionActions.size} appliedRuns=${batch.runs.size} appliedClaims=${batch.claims.size} " +
                "appliedNotes=${batch.notes.size} appliedTasks=${batch.actionItems.size} " +
                "summary=${plan.summary.oneLineForRetentionPipelineLog(500)}"
        }

        return MemoryRetentionPipelineResult(
            candidates = candidates,
            retentionPlan = plan,
            memoryBatch = batch,
        )
    }

    private fun materialize(
        request: MemoryMaintenanceRequest,
        startedAt: Instant,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        candidates: List<MemoryStore.SearchHit>,
        plan: MemoryRetentionPlan,
    ): MemoryUpdateBatch {
        val runId = idFactory.newRunId()
        val candidateRefs = candidates.mapTo(mutableSetOf()) { it.toRetentionItemRef() }
        val retainedClaims = mutableMapOf<MemoryClaim.Id, MemoryClaim>()
        val retainedNotes = mutableMapOf<MemoryNote.Id, MemoryNote>()
        val retainedActionItems = mutableMapOf<MemoryActionItem.Id, MemoryActionItem>()

        val appliedOps = buildJsonArray {
            plan.retentionActions.forEach { action ->
                val applied = applyAction(
                    action = action,
                    completedAt = completedAt,
                    snapshot = snapshot,
                    candidateRefs = candidateRefs,
                    retainedClaims = retainedClaims,
                    retainedNotes = retainedNotes,
                    retainedActionItems = retainedActionItems,
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
            runType = MemoryRun.Type.APPLY_RETENTION,
            triggerMode = request.triggerMode,
            summary = plan.summary.ifBlank { "Memory retention completed." },
            sourceIds = candidates.sourceIdsForRetentionRun(),
            retrievedItemRefs = candidateRefs.toList(),
            output = plan.toRetentionOutputJson(),
            appliedOps = appliedOps,
            latencyMs = completedAt.toEpochMilliseconds() - startedAt.toEpochMilliseconds(),
            status = MemoryRun.Status.SUCCESS,
            createdAt = startedAt,
            startedAt = startedAt,
            completedAt = completedAt,
        )

        return MemoryUpdateBatch(
            runs = listOf(run),
            claims = retainedClaims.values.toList(),
            notes = retainedNotes.values.toList(),
            actionItems = retainedActionItems.values.toList(),
        )
    }

    private fun applyAction(
        action: MemoryRetentionPlan.Action,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        candidateRefs: Set<MemoryItemRef>,
        retainedClaims: MutableMap<MemoryClaim.Id, MemoryClaim>,
        retainedNotes: MutableMap<MemoryNote.Id, MemoryNote>,
        retainedActionItems: MutableMap<MemoryActionItem.Id, MemoryActionItem>,
    ): List<AppliedRetentionOp> {
        if (action.action == MemoryRetentionPlan.Action.Type.NOOP ||
            action.action == MemoryRetentionPlan.Action.Type.KEEP
        ) {
            return emptyList()
        }

        if (action.targetIds.any { MemoryItemRef(action.targetType, it) !in candidateRefs }) {
            log.info {
                "Memory retention skipped action outside candidate set: action=${action.action.name} targetType=${action.targetType.name} " +
                    "targetIds=${action.targetIds.joinToString("|")} reason=${action.reason.oneLineForRetentionPipelineLog(240)}"
            }
            return emptyList()
        }

        return when (action.targetType) {
            MemoryItemRef.Type.CLAIM -> archiveClaimsIfSafe(action, completedAt, snapshot, retainedClaims)
            MemoryItemRef.Type.NOTE -> archiveNotesIfSafe(action, completedAt, snapshot, retainedNotes)
            MemoryItemRef.Type.ACTION_ITEM -> archiveTasksIfSafe(action, completedAt, snapshot, retainedActionItems)
            MemoryItemRef.Type.PROFILE,
            MemoryItemRef.Type.ENTITY,
            MemoryItemRef.Type.EPISODE,
            MemoryItemRef.Type.SOURCE,
            MemoryItemRef.Type.RUN,
            -> emptyList()
        }
    }

    private fun archiveClaimsIfSafe(
        action: MemoryRetentionPlan.Action,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        retainedClaims: MutableMap<MemoryClaim.Id, MemoryClaim>,
    ): List<AppliedRetentionOp> =
        action.targetIds.mapNotNull { id -> snapshot.claims.firstOrNull { it.id.value == id } }
            .filter { it.canArchiveByRetention() }
            .map { claim ->
                retainedClaims[claim.id] = claim.copy(archivedAt = completedAt, updatedAt = completedAt)
                AppliedRetentionOp("archive_claim", MemoryItemRef.Type.CLAIM, claim.id.value, action.reason)
            }

    private fun archiveNotesIfSafe(
        action: MemoryRetentionPlan.Action,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        retainedNotes: MutableMap<MemoryNote.Id, MemoryNote>,
    ): List<AppliedRetentionOp> =
        action.targetIds.mapNotNull { id -> snapshot.notes.firstOrNull { it.id.value == id } }
            .filter { it.canArchiveByRetention() }
            .map { note ->
                retainedNotes[note.id] = note.copy(archivedAt = completedAt, updatedAt = completedAt)
                AppliedRetentionOp("archive_note", MemoryItemRef.Type.NOTE, note.id.value, action.reason)
            }

    private fun archiveTasksIfSafe(
        action: MemoryRetentionPlan.Action,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        retainedActionItems: MutableMap<MemoryActionItem.Id, MemoryActionItem>,
    ): List<AppliedRetentionOp> =
        action.targetIds.mapNotNull { id -> snapshot.actionItems.firstOrNull { it.id.value == id } }
            .filter { it.canArchiveByRetention() }
            .map { actionItem ->
                retainedActionItems[actionItem.id] = actionItem.copy(archivedAt = completedAt, updatedAt = completedAt)
                AppliedRetentionOp("archive_task", MemoryItemRef.Type.ACTION_ITEM, actionItem.id.value, action.reason)
            }
}

class PolicyMemoryRetentionPlanner : MemoryRetentionPlanner {
    override suspend fun plan(
        request: MemoryMaintenanceRequest,
        candidates: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryRetentionPlan {
        if (candidates.isEmpty()) {
            return MemoryRetentionPlan(summary = "No retention candidates found.")
        }

        val actions = candidates.mapNotNull { hit ->
            val ref = hit.toRetentionItemRef()
            when (hit) {
                is MemoryStore.SearchHit.ClaimHit -> hit.claim.takeIf { it.canArchiveByRetention() }?.let {
                    MemoryRetentionPlan.Action(
                        action = MemoryRetentionPlan.Action.Type.ARCHIVE_ITEM,
                        targetType = ref.type,
                        targetIds = listOf(ref.id),
                        reason = "Claim is no longer active truth but remains explainable as archived history.",
                    )
                }
                is MemoryStore.SearchHit.NoteHit -> hit.note.takeIf { it.canArchiveByRetention() }?.let {
                    MemoryRetentionPlan.Action(
                        action = MemoryRetentionPlan.Action.Type.ARCHIVE_ITEM,
                        targetType = ref.type,
                        targetIds = listOf(ref.id),
                        reason = "Note is resolved, stale, superseded, retracted, or already consolidated.",
                    )
                }
                is MemoryStore.SearchHit.ActionItemHit -> hit.actionItem.takeIf { it.canArchiveByRetention() }?.let {
                    MemoryRetentionPlan.Action(
                        action = MemoryRetentionPlan.Action.Type.ARCHIVE_ITEM,
                        targetType = ref.type,
                        targetIds = listOf(ref.id),
                        reason = "Action item is closed and should not participate in normal active recall.",
                    )
                }
                is MemoryStore.SearchHit.SourceHit,
                is MemoryStore.SearchHit.EntityHit,
                is MemoryStore.SearchHit.ProfileHit,
                is MemoryStore.SearchHit.EpisodeHit,
                is MemoryStore.SearchHit.RunHit,
                -> MemoryRetentionPlan.Action(
                    action = MemoryRetentionPlan.Action.Type.KEEP,
                    targetType = ref.type,
                    targetIds = listOf(ref.id),
                    reason = "Retention MVP does not mutate this memory type automatically.",
                )
            }
        }

        return MemoryRetentionPlan(
            retentionActions = actions,
            summary = "Retention policy classified ${candidates.size} candidates and proposed ${actions.count { it.action == MemoryRetentionPlan.Action.Type.ARCHIVE_ITEM }} archives.",
        )
    }
}

private data class AppliedRetentionOp(
    val op: String,
    val targetType: MemoryItemRef.Type,
    val targetId: String,
    val reason: String,
)

private fun MemoryNamespaceSnapshot.selectRetentionCandidates(): List<MemoryStore.SearchHit> =
    buildList {
        claims
            .filter { it.canArchiveByRetention() }
            .sortedBy { it.updatedAt }
            .mapTo(this) { MemoryStore.SearchHit.ClaimHit(it, score = 1.0) }

        notes
            .filter { it.canArchiveByRetention() }
            .sortedBy { it.updatedAt }
            .mapTo(this) { MemoryStore.SearchHit.NoteHit(it, score = 0.9) }

        actionItems
            .filter { it.canArchiveByRetention() }
            .sortedBy { it.updatedAt }
            .mapTo(this) { MemoryStore.SearchHit.ActionItemHit(it, score = 0.8) }
    }.take(100)

private fun MemoryClaim.canArchiveByRetention(): Boolean =
    archivedAt == null && status != MemoryClaim.Status.ACTIVE && status != MemoryClaim.Status.CANDIDATE

private fun MemoryNote.canArchiveByRetention(): Boolean =
    archivedAt == null &&
        (
            status in setOf(
                MemoryNote.Status.RESOLVED,
                MemoryNote.Status.STALE,
                MemoryNote.Status.SUPERSEDED,
                MemoryNote.Status.RETRACTED,
            ) ||
                maturity == MemoryNote.Maturity.CONSOLIDATED
            )

private fun MemoryActionItem.canArchiveByRetention(): Boolean =
    archivedAt == null && status in setOf(MemoryActionItem.Status.DONE, MemoryActionItem.Status.CANCELLED)

private fun MemoryStore.SearchHit.toRetentionItemRef(): MemoryItemRef =
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

private fun List<MemoryStore.SearchHit>.sourceIdsForRetentionRun() =
    flatMap { hit ->
        when (hit) {
            is MemoryStore.SearchHit.ClaimHit -> hit.claim.evidenceRefs.map(MemoryEvidenceRef::sourceId)
            is MemoryStore.SearchHit.NoteHit -> hit.note.evidenceRefs.map(MemoryEvidenceRef::sourceId)
            is MemoryStore.SearchHit.ActionItemHit -> hit.actionItem.evidenceRefs.map(MemoryEvidenceRef::sourceId)
            is MemoryStore.SearchHit.EpisodeHit -> hit.episode.evidenceRefs.map(MemoryEvidenceRef::sourceId)
            is MemoryStore.SearchHit.SourceHit -> listOf(hit.source.id)
            is MemoryStore.SearchHit.EntityHit,
            is MemoryStore.SearchHit.ProfileHit,
            is MemoryStore.SearchHit.RunHit,
            -> emptyList()
        }
    }.distinct()

private fun MemoryRetentionPlan.toRetentionOutputJson() =
    buildJsonObject {
        put("planner", "PolicyMemoryRetentionPlanner")
        put("planner_version", "mvp1")
        put("summary", summary)
        put("retention_actions", buildJsonArray {
            retentionActions.forEach { action ->
                add(buildJsonObject {
                    put("action", action.action.name)
                    put("target_type", action.targetType.name)
                    put("target_ids", action.targetIds.joinToString("|"))
                    put("reason", action.reason)
                })
            }
        })
    }

private fun MemoryStore.SearchHit.retentionHitForLog(): String =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> "claim:${claim.id.value}:${claim.status.name}:${claim.predicate}:${claim.normalizedText.oneLineForRetentionPipelineLog(120)}"
        is MemoryStore.SearchHit.NoteHit -> "note:${note.id.value}:${note.status.name}/${note.maturity.name}:${note.title.oneLineForRetentionPipelineLog(120)}"
        is MemoryStore.SearchHit.ActionItemHit -> "actionItem:${actionItem.id.value}:${actionItem.status.name}:${actionItem.title.oneLineForRetentionPipelineLog(120)}"
        is MemoryStore.SearchHit.ProfileHit -> "profile:${profile.id.value}:${profile.ownerEntityId.value}"
        is MemoryStore.SearchHit.EpisodeHit -> "episode:${episode.id.value}:${episode.lesson.oneLineForRetentionPipelineLog(120)}"
        is MemoryStore.SearchHit.EntityHit -> "entity:${entity.id.value}:${entity.canonicalName.oneLineForRetentionPipelineLog(80)}"
        is MemoryStore.SearchHit.SourceHit -> "source:${source.id.value}:${source.contentText.oneLineForRetentionPipelineLog(80)}"
        is MemoryStore.SearchHit.RunHit -> "run:${run.id.value}:${run.runType.name}"
    }

private fun MemoryNamespaceSnapshot.countsForRetentionLog(): String =
    "sources=${sources.size},runs=${runs.size},entities=${entities.size},claims=${claims.size},notes=${notes.size},actionItems=${actionItems.size},profiles=${profiles.size},episodes=${episodes.size}"

private fun String.oneLineForRetentionPipelineLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
