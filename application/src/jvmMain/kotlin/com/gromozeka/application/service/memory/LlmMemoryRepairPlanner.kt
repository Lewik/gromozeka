package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryMaintenanceRequest
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryRepairCandidateCluster
import com.gromozeka.domain.model.memory.MemoryRepairPlan
import com.gromozeka.domain.model.memory.MemoryRepairPlanner
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LlmMemoryRepairPlanner(
    private val runtime: AiRuntime,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryRepairPlanner {
    private val log = KLoggers.logger(this)

    override suspend fun plan(
        request: MemoryMaintenanceRequest,
        candidateClusters: List<MemoryRepairCandidateCluster>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryRepairPlan {
        if (candidateClusters.isEmpty()) {
            return MemoryRepairPlan(summary = "No suspicious memory clusters found.")
        }

        val stageMessages = listOf(
            repairMaintenanceTaskMessage(
                stageName = "memory-repair-planner",
                taskPrompt = buildPrompt(request, candidateClusters, snapshot),
            )
        )

        log.info {
            "Memory repair planner LLM call: namespace=${request.namespace.value} clusters=${candidateClusters.size} " +
                "suspiciousHits=${candidateClusters.sumOf { it.hits.size }} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size}"
        }

        val response = runtime.callMemoryStageWithRetry(
            AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxTokens = 3_600,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.MemoryRepairPlanner,
                    toolContext = mapOf(
                        "memoryRepairPlanner" to true,
                        "memoryNamespace" to request.namespace.value,
                    ),
                ),
            ),
            stageName = "memory-repair-planner",
            logContext = "namespace=${request.namespace.value}",
        )

        val rawText = response.messages
            .flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
            .trim()

        log.info {
            "Memory repair planner raw response: namespace=${request.namespace.value} chars=${rawText.length} " +
                "response=${rawText.oneLineForRepairPlannerLog(4_000)}"
        }

        val jsonText = rawText.extractJsonObject()
            ?: throw IllegalStateException("Memory repair planner did not return JSON: ${rawText.take(500)}")

        val parsed = json.decodeFromString<RepairPlannerResponse>(jsonText)
        val existingRefs = candidateClusters.flatMapTo(mutableSetOf()) { cluster -> cluster.hits.map { it.toRepairItemRef() } }
        val actions = parsed.repairActions.mapNotNull { it.toAction(existingRefs) }

        val plan = MemoryRepairPlan(
            repairActions = actions,
            summary = parsed.summary.trim(),
        )

        log.info {
            "Memory repair planner mapped result: namespace=${request.namespace.value} actions=${actions.size} " +
                "summary=${plan.summary.oneLineForRepairPlannerLog(500)}"
        }

        return plan
    }

    private fun buildPrompt(
        request: MemoryMaintenanceRequest,
        candidateClusters: List<MemoryRepairCandidateCluster>,
        snapshot: MemoryNamespaceSnapshot,
    ): String = """
        You are MemoryRepairPlanner v1.

        Goal:
        Review suspicious memory clusters and propose conservative repair actions that improve memory hygiene without destroying useful history.

        Current time: ${Clock.System.now()}
        Namespace: ${request.namespace.value}

        Candidate clusters:
        ${candidateClusters.renderRepairCandidateClusters()}

        Supporting evidence:
        ${snapshot.renderRepairSupportingEvidence(candidateClusters)}

        Return JSON:
        {
          "repair_actions": [
            {
              "action": "merge_duplicates | supersede_item | archive_item | refresh_profile | noop",
              "target_type": "note | claim | task | profile | entity | episode",
              "target_ids": ["id"],
              "reason": "short explanation"
            }
          ],
          "summary": "short summary"
        }

        Rules:
        - Be conservative.
        - Decide per cluster. If a cluster is not safely repairable, return noop for that cluster or omit it.
        - Prefer preserving history with status changes or archive over destructive edits.
        - Use merge_duplicates only for items that are exact or near-exact duplicates.
        - Use archive_item only when an item is stale or duplicate but still explainable.
        - Use supersede_item only when active memory is clearly replaced by a newer active item.
        - Use refresh_profile when profile text can drift from active claims, notes, or tasks.
        - For duplicate_claims, duplicate_notes, duplicate_tasks, and duplicate_episodes clusters, use merge_duplicates targeting all duplicate item ids when the cluster items really say the same thing. Do not use archive_item for duplicate clusters unless only one clearly bad item is targeted and a separate active keeper remains.
        - For conflicting_claims clusters, prefer supersede_item targeting the older claim ids that were clearly replaced by newer claims. Do not supersede the newest claim.
        - For profile_drift clusters, prefer refresh_profile targeting the profile id.
        - Do not invent target ids. Use only ids from candidate clusters.
        - Return valid JSON only.
    """.trimIndent()

    @Serializable
    private data class RepairPlannerResponse(
        @SerialName("repair_actions")
        val repairActions: List<ActionResponse> = emptyList(),
        val summary: String = "",
    )

    @Serializable
    private data class ActionResponse(
        val action: String,
        @SerialName("target_type")
        val targetType: String,
        @SerialName("target_ids")
        val targetIds: List<String> = emptyList(),
        val reason: String = "",
    ) {
        fun toAction(existingRefs: Set<MemoryItemRef>): MemoryRepairPlan.Action? {
            val type = targetType.toRepairTargetType() ?: return null
            val ids = targetIds.mapNotNull { it.toRepairIdOrNull() }
            if (action.trim().lowercase() != "noop" && ids.isEmpty()) return null
            if (type != MemoryItemRef.Type.PROFILE && action.trim().lowercase() != "noop") {
                val allowed = ids.filter { id -> MemoryItemRef(type, id) in existingRefs }
                if (allowed.isEmpty()) return null
                return MemoryRepairPlan.Action(
                    action = action.toRepairActionType() ?: return null,
                    targetType = type,
                    targetIds = allowed,
                    reason = reason.trim().take(800),
                )
            }

            return MemoryRepairPlan.Action(
                action = action.toRepairActionType() ?: return null,
                targetType = type,
                targetIds = ids,
                reason = reason.trim().take(800),
            )
        }
    }
}

private fun repairMaintenanceTaskMessage(
    stageName: String,
    taskPrompt: String,
): Conversation.Message =
    Conversation.Message(
        id = Conversation.Message.Id("memory-maintenance:$stageName:${Clock.System.now()}"),
        conversationId = Conversation.Id("memory-maintenance:$stageName"),
        role = Conversation.Message.Role.USER,
        content = listOf(
            Conversation.Message.ContentItem.UserMessage(
                """
                MEMORY-ONLY MAINTENANCE TASK

                This is a private memory pipeline call, not a normal assistant reply.
                Do not answer as a chat assistant.
                Do not call tools.
                Execute only the maintenance stage below.

                Output contract:
                Return exactly one valid JSON object matching the response schema.
                No prose, no markdown fences, no explanation.

                $taskPrompt
                """.trimIndent()
            )
        ),
        createdAt = Clock.System.now(),
    )

private fun List<MemoryRepairCandidateCluster>.renderRepairCandidateClusters(): String =
    joinToString("\n\n") { cluster ->
        buildString {
            appendLine("Cluster ${cluster.id}: kind=${cluster.kind.name}; reason=${cluster.reason}")
            cluster.hits.forEach { hit ->
                appendLine(hit.renderSuspiciousItemForRepair())
            }
        }.trim()
    }.ifBlank { "none" }

private fun MemoryStore.SearchHit.renderSuspiciousItemForRepair(): String =
    when (this) {
        is MemoryStore.SearchHit.ClaimHit -> "- claim ${claim.id.value}: status=${claim.status.name}; predicate=${claim.predicate}; family=${claim.predicateFamily ?: "null"}; subject=${claim.subjectEntityId.value}; object=${claim.objectEntityId?.value ?: claim.objectValue?.toString() ?: "null"}; text=${claim.normalizedText}; updated=${claim.updatedAt}"
        is MemoryStore.SearchHit.NoteHit -> "- note ${note.id.value}: status=${note.status.name}; maturity=${note.maturity.name}; type=${note.noteType.name}; title=${note.title}; summary=${note.summary}; updated=${note.updatedAt}"
        is MemoryStore.SearchHit.TaskHit -> "- task ${task.id.value}: status=${task.status.name}; title=${task.title}; updated=${task.updatedAt}"
        is MemoryStore.SearchHit.ProfileHit -> "- profile ${profile.id.value}: owner=${profile.ownerEntityId.value}; version=${profile.version}; updated=${profile.updatedAt}; text=${profile.profileText.oneLineForRepairPlannerLog(500)}"
        is MemoryStore.SearchHit.EpisodeHit -> "- episode ${episode.id.value}: situation=${episode.situation}; lesson=${episode.lesson}; updated=${episode.updatedAt}"
        is MemoryStore.SearchHit.EntityHit -> "- entity ${entity.id.value}: type=${entity.entityType.name}; name=${entity.canonicalName}; updated=${entity.updatedAt}"
        is MemoryStore.SearchHit.SourceHit -> "- source ${source.id.value}: text=${source.contentText.trim()}"
        is MemoryStore.SearchHit.RunHit -> "- run ${run.id.value}: type=${run.runType.name}; summary=${run.summary}"
    }

private fun MemoryNamespaceSnapshot.renderRepairSupportingEvidence(candidateClusters: List<MemoryRepairCandidateCluster>): String {
    val suspiciousRefs = candidateClusters.flatMapTo(mutableSetOf()) { cluster -> cluster.hits.map { it.toRepairItemRef() } }
    val sourceIds = buildSet {
        claims.filter { MemoryItemRef(MemoryItemRef.Type.CLAIM, it.id.value) in suspiciousRefs }
            .flatMapTo(this) { claim -> claim.evidenceRefs.map { it.sourceId } }
        notes.filter { MemoryItemRef(MemoryItemRef.Type.NOTE, it.id.value) in suspiciousRefs }
            .flatMapTo(this) { note -> note.evidenceRefs.map { it.sourceId } }
        tasks.filter { MemoryItemRef(MemoryItemRef.Type.TASK, it.id.value) in suspiciousRefs }
            .flatMapTo(this) { task -> task.evidenceRefs.map { it.sourceId } }
        episodes.filter { MemoryItemRef(MemoryItemRef.Type.EPISODE, it.id.value) in suspiciousRefs }
            .flatMapTo(this) { episode -> episode.evidenceRefs.map { it.sourceId } }
    }

    val renderedSources = sources
        .filter { it.id in sourceIds }
        .take(12)
        .joinToString("\n") { source ->
            "- source ${source.id.value}: ${source.contentText.trim()}"
        }

    val renderedProfiles = profiles
        .take(8)
        .joinToString("\n") { profile ->
            "- profile ${profile.id.value}: ${profile.profileText.oneLineForRepairPlannerLog(500)}"
        }

    return listOf(renderedSources, renderedProfiles)
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .ifBlank { "none" }
}

private fun MemoryStore.SearchHit.toRepairItemRef(): MemoryItemRef =
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

private fun String.toRepairActionType(): MemoryRepairPlan.Action.Type? =
    when (trim().lowercase()) {
        "merge_duplicates" -> MemoryRepairPlan.Action.Type.MERGE_DUPLICATES
        "supersede_item" -> MemoryRepairPlan.Action.Type.SUPERSEDE_ITEM
        "archive_item" -> MemoryRepairPlan.Action.Type.ARCHIVE_ITEM
        "refresh_profile" -> MemoryRepairPlan.Action.Type.REFRESH_PROFILE
        "noop" -> MemoryRepairPlan.Action.Type.NOOP
        else -> null
    }

private fun String.toRepairTargetType(): MemoryItemRef.Type? =
    when (trim().lowercase()) {
        "note" -> MemoryItemRef.Type.NOTE
        "claim" -> MemoryItemRef.Type.CLAIM
        "task" -> MemoryItemRef.Type.TASK
        "profile" -> MemoryItemRef.Type.PROFILE
        "entity" -> MemoryItemRef.Type.ENTITY
        "episode" -> MemoryItemRef.Type.EPISODE
        else -> null
    }

private fun String.toRepairIdOrNull(): String? {
    val value = trim().takeIf { it.isNotBlank() } ?: return null
    if (value.lowercase() in setOf("uuid", "id", "null")) return null
    return value
}

private fun String.oneLineForRepairPlannerLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
