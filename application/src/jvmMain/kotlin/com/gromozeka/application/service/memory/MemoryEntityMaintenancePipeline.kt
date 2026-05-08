package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityMaintenanceCandidateGroup
import com.gromozeka.domain.model.memory.MemoryEntityMaintenancePlan
import com.gromozeka.domain.model.memory.MemoryEntityMaintenancePlanner
import com.gromozeka.domain.model.memory.MemoryEpisode
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryMaintenanceRequest
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTask
import com.gromozeka.domain.model.memory.MemoryUpdateBatch
import klog.KLoggers
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class MemoryEntityMaintenancePipelineResult(
    val candidateGroups: List<MemoryEntityMaintenanceCandidateGroup>,
    val maintenancePlan: MemoryEntityMaintenancePlan,
    val memoryBatch: MemoryUpdateBatch,
)

class MemoryEntityMaintenancePipeline(
    private val store: MemoryStore,
    private val planner: MemoryEntityMaintenancePlanner,
    private val idFactory: MemoryIdFactory,
    private val profileUpdater: ProjectionMemoryProfileUpdater,
    private val clock: MemoryClock = SystemMemoryClock,
) {
    private val log = KLoggers.logger(this)

    suspend fun run(request: MemoryMaintenanceRequest): MemoryEntityMaintenancePipelineResult {
        val startedAt = clock.now()
        val snapshot = store.loadNamespaceSnapshot(request.namespace)
        val candidateGroups = snapshot.detectEntityMaintenanceCandidateGroups()

        log.info {
            "Memory entity maintenance selected: namespace=${request.namespace.value} conversation=${request.conversationId?.value ?: "none"} " +
                "snapshot=${snapshot.countsForEntityMaintenanceLog()} groups=${candidateGroups.size} " +
                "groupsDetail=${candidateGroups.joinToString("|") { it.entityMaintenanceGroupForLog() }.ifBlank { "none" }}"
        }

        val plan = planner.plan(request, candidateGroups, snapshot)
        val completedAt = clock.now()
        val structuredBatch = materialize(
            request = request,
            startedAt = startedAt,
            completedAt = completedAt,
            snapshot = snapshot,
            candidateGroups = candidateGroups,
            plan = plan,
        )

        store.apply(structuredBatch)

        val profileBatch = profileUpdater.updateNamespaceProfiles(
            namespace = request.namespace,
            logSubject = "maintenance=entity_maintenance",
            appliedBatch = structuredBatch,
            completedAt = completedAt,
            force = structuredBatch.entities.any { it.status == MemoryEntity.Status.MERGED },
        )

        if (profileBatch.isNotEmptyForEntityMaintenance()) {
            store.apply(profileBatch)
        }

        val memoryBatch = structuredBatch + profileBatch

        log.info {
            "Memory entity maintenance completed: namespace=${request.namespace.value} groups=${candidateGroups.size} " +
                "actions=${plan.actions.size} appliedRuns=${memoryBatch.runs.size} appliedEntities=${memoryBatch.entities.size} " +
                "appliedClaims=${memoryBatch.claims.size} appliedNotes=${memoryBatch.notes.size} appliedTasks=${memoryBatch.tasks.size} " +
                "appliedProfiles=${memoryBatch.profiles.size} appliedEpisodes=${memoryBatch.episodes.size} " +
                "summary=${plan.summary.oneLineForEntityMaintenancePipelineLog(500)}"
        }

        return MemoryEntityMaintenancePipelineResult(
            candidateGroups = candidateGroups,
            maintenancePlan = plan,
            memoryBatch = memoryBatch,
        )
    }

    private fun materialize(
        request: MemoryMaintenanceRequest,
        startedAt: Instant,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        candidateGroups: List<MemoryEntityMaintenanceCandidateGroup>,
        plan: MemoryEntityMaintenancePlan,
    ): MemoryUpdateBatch {
        val runId = idFactory.newRunId()
        val candidateEntities = candidateGroups
            .flatMap { it.entities }
            .associateBy { it.id }
        val normalizedSummaryEntityIds = snapshot.entities
            .filter { it.status == MemoryEntity.Status.ACTIVE }
            .filter { it.summary != it.identityOnlySummary() }
            .mapTo(mutableSetOf()) { it.id }
        val candidateRefs = candidateEntities.keys
            .plus(normalizedSummaryEntityIds)
            .mapTo(mutableSetOf()) { MemoryItemRef(MemoryItemRef.Type.ENTITY, it.value) }

        val entityUpdates = mutableMapOf<MemoryEntity.Id, MemoryEntity>()
        val claimUpdates = mutableMapOf<MemoryClaim.Id, MemoryClaim>()
        val noteUpdates = mutableMapOf<MemoryNote.Id, MemoryNote>()
        val taskUpdates = mutableMapOf<MemoryTask.Id, MemoryTask>()
        val episodeUpdates = mutableMapOf<MemoryEpisode.Id, MemoryEpisode>()
        val mergeMap = mutableMapOf<MemoryEntity.Id, MemoryEntity.Id>()

        val appliedOps = buildJsonArray {
            normalizeIdentitySummaries(
                snapshot = snapshot,
                completedAt = completedAt,
                entityUpdates = entityUpdates,
            ).forEach { op ->
                add(buildJsonObject {
                    put("op", op.op)
                    put("target_type", op.targetType.name)
                    put("target_id", op.targetId)
                    put("reason", op.reason)
                })
            }
            plan.actions.forEach { action ->
                val applied = applyAction(
                    action = action,
                    completedAt = completedAt,
                    snapshot = snapshot,
                    candidateEntities = candidateEntities,
                    entityUpdates = entityUpdates,
                    claimUpdates = claimUpdates,
                    noteUpdates = noteUpdates,
                    taskUpdates = taskUpdates,
                    episodeUpdates = episodeUpdates,
                    mergeMap = mergeMap,
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
            runType = MemoryRun.Type.MAINTAIN_ENTITIES,
            triggerMode = request.triggerMode,
            summary = plan.summary.ifBlank { "Memory entity maintenance completed." },
            retrievedItemRefs = candidateRefs.toList(),
            promptName = "MemoryEntityMaintenancePlanner",
            promptVersion = "v1",
            output = plan.toEntityMaintenanceOutputJson(),
            repairActions = plan.toEntityMaintenanceActionsJson(),
            appliedOps = appliedOps,
            status = MemoryRun.Status.SUCCESS,
            createdAt = startedAt,
            completedAt = completedAt,
        )

        return MemoryUpdateBatch(
            runs = listOf(run),
            entities = entityUpdates.values.toList(),
            claims = claimUpdates.values.toList(),
            notes = noteUpdates.values.toList(),
            tasks = taskUpdates.values.toList(),
            episodes = episodeUpdates.values.toList(),
        )
    }

    private fun applyAction(
        action: MemoryEntityMaintenancePlan.Action,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        candidateEntities: Map<MemoryEntity.Id, MemoryEntity>,
        entityUpdates: MutableMap<MemoryEntity.Id, MemoryEntity>,
        claimUpdates: MutableMap<MemoryClaim.Id, MemoryClaim>,
        noteUpdates: MutableMap<MemoryNote.Id, MemoryNote>,
        taskUpdates: MutableMap<MemoryTask.Id, MemoryTask>,
        episodeUpdates: MutableMap<MemoryEpisode.Id, MemoryEpisode>,
        mergeMap: MutableMap<MemoryEntity.Id, MemoryEntity.Id>,
    ): List<AppliedEntityMaintenanceOp> =
        when (action.action) {
            MemoryEntityMaintenancePlan.Action.Type.MERGE -> applyMerge(
                action = action,
                completedAt = completedAt,
                snapshot = snapshot,
                candidateEntities = candidateEntities,
                entityUpdates = entityUpdates,
                claimUpdates = claimUpdates,
                noteUpdates = noteUpdates,
                taskUpdates = taskUpdates,
                episodeUpdates = episodeUpdates,
                mergeMap = mergeMap,
            )

            MemoryEntityMaintenancePlan.Action.Type.ADD_ALIAS -> applyAddAlias(
                action = action,
                completedAt = completedAt,
                candidateEntities = candidateEntities,
                entityUpdates = entityUpdates,
                mergeMap = mergeMap,
            )

            MemoryEntityMaintenancePlan.Action.Type.UPDATE_SUMMARY -> applyUpdateSummary(
                action = action,
                completedAt = completedAt,
                candidateEntities = candidateEntities,
                entityUpdates = entityUpdates,
                mergeMap = mergeMap,
            )

            MemoryEntityMaintenancePlan.Action.Type.KEEP_SEPARATE,
            MemoryEntityMaintenancePlan.Action.Type.NOOP,
            -> emptyList()
        }

    private fun normalizeIdentitySummaries(
        snapshot: MemoryNamespaceSnapshot,
        completedAt: Instant,
        entityUpdates: MutableMap<MemoryEntity.Id, MemoryEntity>,
    ): List<AppliedEntityMaintenanceOp> =
        snapshot.entities
            .filter { it.status == MemoryEntity.Status.ACTIVE }
            .mapNotNull { entity ->
                val current = entityUpdates[entity.id] ?: entity
                val summaryText = current.identityOnlySummary()
                if (current.status != MemoryEntity.Status.ACTIVE || current.summary == summaryText) {
                    return@mapNotNull null
                }
                entityUpdates[entity.id] = current.copy(summary = summaryText, updatedAt = completedAt)
                AppliedEntityMaintenanceOp(
                    "normalize_entity_identity_summary",
                    MemoryItemRef.Type.ENTITY,
                    entity.id.value,
                    "Entity summary stores identity only; mutable facts stay in typed memory.",
                )
            }

    private fun applyMerge(
        action: MemoryEntityMaintenancePlan.Action,
        completedAt: Instant,
        snapshot: MemoryNamespaceSnapshot,
        candidateEntities: Map<MemoryEntity.Id, MemoryEntity>,
        entityUpdates: MutableMap<MemoryEntity.Id, MemoryEntity>,
        claimUpdates: MutableMap<MemoryClaim.Id, MemoryClaim>,
        noteUpdates: MutableMap<MemoryNote.Id, MemoryNote>,
        taskUpdates: MutableMap<MemoryTask.Id, MemoryTask>,
        episodeUpdates: MutableMap<MemoryEpisode.Id, MemoryEpisode>,
        mergeMap: MutableMap<MemoryEntity.Id, MemoryEntity.Id>,
    ): List<AppliedEntityMaintenanceOp> {
        val rawWinnerId = action.winnerEntityId?.let { MemoryEntity.Id(it) } ?: return emptyList()
        val winnerId = rawWinnerId.resolveEntityMerge(mergeMap)
        val loserIds = action.loserEntityIds
            .map { MemoryEntity.Id(it) }
            .map { it.resolveEntityMerge(mergeMap) }
            .filter { it != winnerId }
            .distinct()

        val winner = entityUpdates[winnerId] ?: candidateEntities[winnerId] ?: return emptyList()
        val losers = loserIds.mapNotNull { id -> entityUpdates[id] ?: candidateEntities[id] }
            .filter { it.status == MemoryEntity.Status.ACTIVE }
            .filter { it.entityType == winner.entityType }
        if (winner.status != MemoryEntity.Status.ACTIVE || losers.isEmpty()) {
            return emptyList()
        }

        val allEntities = listOf(winner) + losers
        val aliasTexts = losers.flatMap { loser ->
            listOf(loser.canonicalName) + loser.aliases.map { it.text }
        } + action.aliasTexts
        val updatedWinner = winner.copy(
            summary = winner.summary ?: losers.firstNotNullOfOrNull { it.summary },
            aliases = winner.aliases.plusAliases(aliasTexts, completedAt),
            firstSeenAt = allEntities.minOf { it.firstSeenAt },
            lastSeenAt = allEntities.maxOf { it.lastSeenAt },
            updatedAt = completedAt,
        )

        entityUpdates[winnerId] = updatedWinner
        losers.forEach { loser ->
            mergeMap[loser.id] = winnerId
            entityUpdates[loser.id] = loser.copy(
                status = MemoryEntity.Status.MERGED,
                mergedIntoEntityId = winnerId,
                updatedAt = completedAt,
            )
        }

        val applied = mutableListOf(
            AppliedEntityMaintenanceOp("merge_entity_keep", MemoryItemRef.Type.ENTITY, winnerId.value, action.reason)
        )
        losers.forEach { loser ->
            applied += AppliedEntityMaintenanceOp("merge_entity_loser", MemoryItemRef.Type.ENTITY, loser.id.value, action.reason)
        }

        relinkClaims(snapshot, loserIds.toSet(), winnerId, completedAt, claimUpdates).forEach { applied += it }
        relinkNotes(snapshot, loserIds.toSet(), winnerId, completedAt, noteUpdates).forEach { applied += it }
        relinkTasks(snapshot, loserIds.toSet(), winnerId, completedAt, taskUpdates).forEach { applied += it }
        relinkEpisodes(snapshot, loserIds.toSet(), winnerId, completedAt, episodeUpdates).forEach { applied += it }

        return applied
    }

    private fun applyAddAlias(
        action: MemoryEntityMaintenancePlan.Action,
        completedAt: Instant,
        candidateEntities: Map<MemoryEntity.Id, MemoryEntity>,
        entityUpdates: MutableMap<MemoryEntity.Id, MemoryEntity>,
        mergeMap: Map<MemoryEntity.Id, MemoryEntity.Id>,
    ): List<AppliedEntityMaintenanceOp> =
        action.targetEntityIds
            .map { MemoryEntity.Id(it) }
            .map { it.resolveEntityMerge(mergeMap) }
            .distinct()
            .mapNotNull { entityId ->
                val entity = entityUpdates[entityId] ?: candidateEntities[entityId] ?: return@mapNotNull null
                val aliases = entity.aliases.plusAliases(action.aliasTexts, completedAt)
                if (aliases == entity.aliases) return@mapNotNull null
                entityUpdates[entityId] = entity.copy(aliases = aliases, updatedAt = completedAt)
                AppliedEntityMaintenanceOp("add_entity_alias", MemoryItemRef.Type.ENTITY, entityId.value, action.reason)
            }

    private fun applyUpdateSummary(
        action: MemoryEntityMaintenancePlan.Action,
        completedAt: Instant,
        candidateEntities: Map<MemoryEntity.Id, MemoryEntity>,
        entityUpdates: MutableMap<MemoryEntity.Id, MemoryEntity>,
        mergeMap: Map<MemoryEntity.Id, MemoryEntity.Id>,
    ): List<AppliedEntityMaintenanceOp> {
        action.summaryText?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return action.targetEntityIds
            .map { MemoryEntity.Id(it) }
            .map { it.resolveEntityMerge(mergeMap) }
            .distinct()
            .mapNotNull { entityId ->
                val entity = entityUpdates[entityId] ?: candidateEntities[entityId] ?: return@mapNotNull null
                val summaryText = entity.identityOnlySummary()
                if (entity.status != MemoryEntity.Status.ACTIVE || entity.summary == summaryText) return@mapNotNull null
                entityUpdates[entityId] = entity.copy(summary = summaryText, updatedAt = completedAt)
                AppliedEntityMaintenanceOp("update_entity_summary", MemoryItemRef.Type.ENTITY, entityId.value, action.reason)
            }
    }
}

private fun relinkClaims(
    snapshot: MemoryNamespaceSnapshot,
    loserIds: Set<MemoryEntity.Id>,
    winnerId: MemoryEntity.Id,
    completedAt: Instant,
    claimUpdates: MutableMap<MemoryClaim.Id, MemoryClaim>,
): List<AppliedEntityMaintenanceOp> =
    snapshot.claims.mapNotNull { original ->
        val claim = claimUpdates[original.id] ?: original
        val subject = claim.subjectEntityId.replaceEntity(loserIds, winnerId)
        val obj = claim.objectEntityId?.replaceEntity(loserIds, winnerId)
        if (subject == claim.subjectEntityId && obj == claim.objectEntityId) return@mapNotNull null
        claimUpdates[claim.id] = claim.copy(subjectEntityId = subject, objectEntityId = obj, updatedAt = completedAt)
        AppliedEntityMaintenanceOp("relink_claim_entity", MemoryItemRef.Type.CLAIM, claim.id.value, "entity merge")
    }

private fun relinkNotes(
    snapshot: MemoryNamespaceSnapshot,
    loserIds: Set<MemoryEntity.Id>,
    winnerId: MemoryEntity.Id,
    completedAt: Instant,
    noteUpdates: MutableMap<MemoryNote.Id, MemoryNote>,
): List<AppliedEntityMaintenanceOp> =
    snapshot.notes.mapNotNull { original ->
        val note = noteUpdates[original.id] ?: original
        val anchor = note.anchorEntityId?.replaceEntity(loserIds, winnerId)
        val refs = note.entityRefs
            .map { ref -> ref.copy(entityId = ref.entityId.replaceEntity(loserIds, winnerId)) }
            .distinctBy { "${it.entityId.value}:${it.role.name}" }
        if (anchor == note.anchorEntityId && refs == note.entityRefs) return@mapNotNull null
        noteUpdates[note.id] = note.copy(anchorEntityId = anchor, entityRefs = refs, updatedAt = completedAt)
        AppliedEntityMaintenanceOp("relink_note_entity", MemoryItemRef.Type.NOTE, note.id.value, "entity merge")
    }

private fun relinkTasks(
    snapshot: MemoryNamespaceSnapshot,
    loserIds: Set<MemoryEntity.Id>,
    winnerId: MemoryEntity.Id,
    completedAt: Instant,
    taskUpdates: MutableMap<MemoryTask.Id, MemoryTask>,
): List<AppliedEntityMaintenanceOp> =
    snapshot.tasks.mapNotNull { original ->
        val task = taskUpdates[original.id] ?: original
        val owner = task.ownerEntityId?.replaceEntity(loserIds, winnerId)
        val assignee = task.assigneeEntityId?.replaceEntity(loserIds, winnerId)
        val related = task.relatedEntityIds
            .map { it.replaceEntity(loserIds, winnerId) }
            .distinct()
        if (owner == task.ownerEntityId && assignee == task.assigneeEntityId && related == task.relatedEntityIds) {
            return@mapNotNull null
        }
        taskUpdates[task.id] = task.copy(
            ownerEntityId = owner,
            assigneeEntityId = assignee,
            relatedEntityIds = related,
            updatedAt = completedAt,
        )
        AppliedEntityMaintenanceOp("relink_task_entity", MemoryItemRef.Type.TASK, task.id.value, "entity merge")
    }

private fun relinkEpisodes(
    snapshot: MemoryNamespaceSnapshot,
    loserIds: Set<MemoryEntity.Id>,
    winnerId: MemoryEntity.Id,
    completedAt: Instant,
    episodeUpdates: MutableMap<MemoryEpisode.Id, MemoryEpisode>,
): List<AppliedEntityMaintenanceOp> =
    snapshot.episodes.mapNotNull { original ->
        val episode = episodeUpdates[original.id] ?: original
        val owner = episode.ownerEntityId?.replaceEntity(loserIds, winnerId)
        if (owner == episode.ownerEntityId) return@mapNotNull null
        episodeUpdates[episode.id] = episode.copy(ownerEntityId = owner, updatedAt = completedAt)
        AppliedEntityMaintenanceOp("relink_episode_entity", MemoryItemRef.Type.EPISODE, episode.id.value, "entity merge")
    }

private fun MemoryNamespaceSnapshot.detectEntityMaintenanceCandidateGroups(): List<MemoryEntityMaintenanceCandidateGroup> {
    val activeEntities = entities
        .filter { it.status == MemoryEntity.Status.ACTIVE }
        .sortedBy { it.id.value }
    if (activeEntities.isEmpty()) return emptyList()

    val adjacency = activeEntities.associate { it.id to mutableSetOf<MemoryEntity.Id>() }.toMutableMap()
    val reasons = mutableMapOf<String, MutableList<String>>()

    fun link(a: MemoryEntity, b: MemoryEntity, reason: String) {
        adjacency.getValue(a.id).add(b.id)
        adjacency.getValue(b.id).add(a.id)
        reasons.getOrPut(listOf(a.id.value, b.id.value).sorted().joinToString("|")) { mutableListOf() }.add(reason)
    }

    activeEntities
        .flatMap { entity -> entity.entityMaintenanceCompactKeys().map { key -> "${entity.entityType.name}:$key" to entity } }
        .groupBy({ it.first }, { it.second })
        .values
        .filter { it.map { entity -> entity.id }.distinct().size > 1 }
        .forEach { group ->
            val distinct = group.distinctBy { it.id }
            distinct.forEachIndexed { index, first ->
                distinct.drop(index + 1).forEach { second -> link(first, second, "same normalized surface form") }
            }
        }

    activeEntities.forEachIndexed { index, first ->
        activeEntities.drop(index + 1)
            .filter { it.entityType == first.entityType }
            .forEach { second ->
                val reason = first.nearDuplicateEntityReason(second)
                if (reason != null) link(first, second, reason)
            }
    }

    val byId = activeEntities.associateBy { it.id }
    val visited = mutableSetOf<MemoryEntity.Id>()
    val groups = mutableListOf<MemoryEntityMaintenanceCandidateGroup>()

    activeEntities.forEach { entity ->
        if (!visited.add(entity.id)) return@forEach
        val queue = ArrayDeque<MemoryEntity.Id>()
        val component = mutableListOf<MemoryEntity.Id>()
        queue += entity.id
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            component += current
            adjacency[current].orEmpty().forEach { next ->
                if (visited.add(next)) queue += next
            }
        }
        if (component.size > 1) {
            val componentEntities = component.mapNotNull(byId::get).sortedBy { it.canonicalName.lowercase() }
            val componentReason = component
                .flatMapIndexed { i, a -> component.drop(i + 1).map { b -> listOf(a.value, b.value).sorted().joinToString("|") } }
                .flatMap { reasons[it].orEmpty() }
                .distinct()
                .take(4)
                .joinToString("; ")
                .ifBlank { "near duplicate entity names" }
            groups += MemoryEntityMaintenanceCandidateGroup(
                id = "entity-maintenance-group-${groups.size + 1}",
                entities = componentEntities,
                reason = componentReason,
            )
        }
    }

    val staleSummaryGroups = detectEntitySummaryRefreshCandidateGroups(activeEntities)

    return (groups + staleSummaryGroups)
        .sortedWith(compareByDescending<MemoryEntityMaintenanceCandidateGroup> { it.entities.size }.thenBy { it.id })
        .take(MAX_ENTITY_MAINTENANCE_GROUPS)
}

private fun MemoryNamespaceSnapshot.detectEntitySummaryRefreshCandidateGroups(
    activeEntities: List<MemoryEntity>,
): List<MemoryEntityMaintenanceCandidateGroup> {
    val activeSupersedingClaimsBySubject = claims
        .filter { it.status == MemoryClaim.Status.ACTIVE }
        .filter { it.supersedesClaimId != null }
        .groupBy { it.subjectEntityId }
    if (activeSupersedingClaimsBySubject.isEmpty()) return emptyList()

    return activeEntities
        .filter { !it.summary.isNullOrBlank() }
        .filter { it.id in activeSupersedingClaimsBySubject }
        .mapIndexed { index, entity ->
            val claimPreview = activeSupersedingClaimsBySubject.getValue(entity.id)
                .joinToString("; ") { "${it.predicate}: ${it.normalizedText}" }
                .oneLineForEntityMaintenancePipelineLog(260)
            MemoryEntityMaintenanceCandidateGroup(
                id = "entity-summary-refresh-group-${index + 1}",
                entities = listOf(entity),
                reason = "entity summary may be stale after active replacement claims: $claimPreview",
            )
        }
}

private fun MemoryEntity.nearDuplicateEntityReason(other: MemoryEntity): String? {
    val firstCompact = entityMaintenanceCompactKeys()
    val secondCompact = other.entityMaintenanceCompactKeys()
    if (firstCompact.any { it in secondCompact }) return "same normalized surface form"

    val hasContainedName = firstCompact.any { first ->
        secondCompact.any { second ->
            val minLength = minOf(first.length, second.length)
            minLength >= 5 && kotlin.math.abs(first.length - second.length) <= 12 && (first in second || second in first)
        }
    }
    if (hasContainedName) return "one normalized surface contains the other"

    val firstTokens = entityMaintenanceTokens()
    val secondTokens = other.entityMaintenanceTokens()
    val common = firstTokens.intersect(secondTokens).size
    val union = firstTokens.union(secondTokens).size
    if (common >= 2 && union > 0 && common.toDouble() / union.toDouble() >= 0.67) {
        return "high token overlap"
    }

    return null
}

private fun MemoryEntity.entityMaintenanceCompactKeys(): Set<String> =
    buildSet {
        add(canonicalName.compactEntityMaintenanceKey())
        add(normalizedName.compactEntityMaintenanceKey())
        aliases.forEach { alias ->
            add(alias.text.compactEntityMaintenanceKey())
            add(alias.normalizedText.compactEntityMaintenanceKey())
        }
    }.filterTo(mutableSetOf()) { it.length >= 3 }

private fun MemoryEntity.entityMaintenanceTokens(): Set<String> =
    buildSet {
        addAll(canonicalName.entityMaintenanceTokens())
        addAll(normalizedName.entityMaintenanceTokens())
        aliases.forEach { alias ->
            addAll(alias.text.entityMaintenanceTokens())
            addAll(alias.normalizedText.entityMaintenanceTokens())
        }
    }

private fun List<MemoryEntity.Alias>.plusAliases(
    texts: List<String>,
    createdAt: Instant,
): List<MemoryEntity.Alias> {
    val existing = mapTo(mutableSetOf()) { it.normalizedText.compactEntityMaintenanceKey() }
    val added = texts
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { text -> text to text.normalizeEntityMaintenanceText() }
        .filter { (_, normalized) -> normalized.isNotBlank() }
        .distinctBy { (_, normalized) -> normalized.compactEntityMaintenanceKey() }
        .filter { (_, normalized) -> existing.add(normalized.compactEntityMaintenanceKey()) }
        .map { (text, normalized) ->
            MemoryEntity.Alias(
                text = text,
                normalizedText = normalized,
                confidence = 1.0,
                createdAt = createdAt,
            )
        }

    return this + added
}

private fun MemoryEntity.Id.replaceEntity(
    loserIds: Set<MemoryEntity.Id>,
    winnerId: MemoryEntity.Id,
): MemoryEntity.Id =
    if (this in loserIds) winnerId else this

private fun MemoryEntity.Id.resolveEntityMerge(
    mergeMap: Map<MemoryEntity.Id, MemoryEntity.Id>,
): MemoryEntity.Id {
    var current = this
    val visited = mutableSetOf<MemoryEntity.Id>()
    while (current in mergeMap && visited.add(current)) {
        current = mergeMap.getValue(current)
    }
    return current
}

private fun MemoryEntityMaintenanceCandidateGroup.entityMaintenanceGroupForLog(): String =
    "$id:${reason.oneLineForEntityMaintenancePipelineLog(160)}:" +
        entities.joinToString(",") { "${it.id.value}/${it.entityType.name}/${it.canonicalName.oneLineForEntityMaintenancePipelineLog(80)}" }

private fun MemoryEntityMaintenancePlan.toEntityMaintenanceOutputJson() =
    buildJsonObject {
        put("summary", summary)
        put("actions", toEntityMaintenanceActionsJson())
    }

private fun MemoryEntityMaintenancePlan.toEntityMaintenanceActionsJson(): JsonArray =
    buildJsonArray {
        actions.forEach { action ->
            add(buildJsonObject {
                put("action", action.action.name)
                put("winner_entity_id", action.winnerEntityId ?: "")
                putJsonArray("loser_entity_ids") { action.loserEntityIds.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("target_entity_ids") { action.targetEntityIds.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("alias_texts") { action.aliasTexts.forEach { add(JsonPrimitive(it)) } }
                put("summary_text", action.summaryText ?: "")
                put("reason", action.reason)
            })
        }
    }

private fun MemoryUpdateBatch.isNotEmptyForEntityMaintenance(): Boolean =
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

private fun MemoryNamespaceSnapshot.countsForEntityMaintenanceLog(): String =
    "sources=${sources.size},runs=${runs.size},entities=${entities.size},claims=${claims.size},notes=${notes.size},tasks=${tasks.size},profiles=${profiles.size},episodes=${episodes.size}"

private fun String.normalizeEntityMaintenanceText(): String =
    splitCamelCaseForEntityMaintenance()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.compactEntityMaintenanceKey(): String =
    normalizeEntityMaintenanceText().replace(Regex("[^\\p{L}\\p{N}]+"), "")

private fun String.entityMaintenanceTokens(): Set<String> =
    normalizeEntityMaintenanceText()
        .split(" ")
        .filter { it.length >= 2 }
        .filter { it !in ENTITY_MAINTENANCE_STOP_WORDS }
        .toSet()

private fun String.splitCamelCaseForEntityMaintenance(): String =
    replace(Regex("([\\p{Ll}\\p{Nd}])([\\p{Lu}])"), "$1 $2")

private fun String.oneLineForEntityMaintenancePipelineLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}

private data class AppliedEntityMaintenanceOp(
    val op: String,
    val targetType: MemoryItemRef.Type,
    val targetId: String,
    val reason: String,
)

private const val MAX_ENTITY_MAINTENANCE_GROUPS = 40

private val ENTITY_MAINTENANCE_STOP_WORDS = setOf(
    "the",
    "a",
    "an",
    "and",
    "or",
    "of",
    "for",
    "to",
)
