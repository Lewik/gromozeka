package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryForgetPlan
import com.gromozeka.domain.model.memory.MemoryForgetPlanner
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LlmMemoryForgetPlanner(
    private val runtime: AiRuntime,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryForgetPlanner {
    private val log = KLoggers.logger(this)

    override suspend fun plan(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        candidates: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryForgetPlan {
        if (candidates.isEmpty()) {
            return MemoryForgetPlan(summary = "No memory candidates matched the explicit forget request.")
        }

        val stageMessages = request.toMemoryStageMessages(
            stageName = "forget-planner",
            taskPrompt = buildPrompt(request, routeDecision, candidates, snapshot),
        )

        log.info {
            "Memory forget planner LLM call: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "candidates=${candidates.size} runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size}"
        }

        val candidateRefs = candidates.mapTo(mutableSetOf()) { it.toForgetItemRef() }
        val result = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.FORGET_PLANNER_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.ForgetPlanner,
                    toolContext = mapOf(
                        "memoryForgetPlanner" to true,
                        "memoryNamespace" to request.namespace.value,
                        "memorySourceId" to request.source.id.value,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "forget-planner",
            logContext = "namespace=${request.namespace.value} source=${request.source.id.value}",
            parse = { jsonText ->
                val parsed = json.decodeFromString<ForgetPlannerResponse>(jsonText)
                MemoryForgetPlan(
                    forgetActions = parsed.forgetActions.mapNotNull { it.toAction(candidateRefs) },
                    summary = parsed.summary.trim(),
                )
            },
        )

        log.info {
            "Memory forget planner raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${result.rawText.length} response=${result.rawText.oneLineForForgetPlannerLog(4_000)}"
        }

        log.info {
            "Memory forget planner mapped result: namespace=${request.namespace.value} actions=${result.value.forgetActions.size} " +
                "summary=${result.value.summary.oneLineForForgetPlannerLog(500)}"
        }

        return result.value
    }

    private fun buildPrompt(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        candidates: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): String = """
        You are MemoryForgetPlanner v1.

        Goal:
        Apply an explicit user request to forget/remove/delete remembered information from normal memory recall.

        Current time: ${Clock.System.now()}
        Namespace: ${request.namespace.value}
        Current forget request source id: ${request.source.id.value}
        Forget target search text: ${routeDecision.sourceSearchText.orEmpty()}

        Forget request:
        ${request.source.renderLatestTurn()}

        Candidate memory:
        ${candidates.renderForgetCandidates()}

        Supporting source evidence:
        ${snapshot.renderForgetSupportingSources(candidates, request.source.id)}

        Return JSON:
        {
          "forget_actions": [
            {
              "action": "archive_item | soft_delete_source | noop",
              "target_type": "source | claim | note | action_item | episode",
              "target_ids": ["id"],
              "reason": "short explanation"
            }
          ],
          "summary": "short summary"
        }

        Rules:
        - This is an explicit forget request, not a truth correction.
        - Prefer removing matching memory from normal recall, not changing truth status.
        - Archive matching claims, notes, action items, and episodes that encode the forgotten content.
        - Soft-delete source evidence that directly supports the forgotten content when the source is primarily about the forgotten content.
        - Never soft-delete the current forget request source id ${request.source.id.value}; it is audit evidence for this operation.
        - Do not delete unrelated sources that merely share a word with the forget request.
        - If the request is broad but candidates are ambiguous, no-op ambiguous candidates instead of over-deleting.
        - Do not invent target ids.
        - Return valid JSON only.
    """.trimIndent()

    @Serializable
    private data class ForgetPlannerResponse(
        @SerialName("forget_actions")
        val forgetActions: List<ActionResponse> = emptyList(),
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
        fun toAction(candidateRefs: Set<MemoryItemRef>): MemoryForgetPlan.Action? {
            val type = targetType.toForgetTargetType() ?: return null
            val actionType = action.toForgetActionType() ?: return null
            val ids = targetIds.mapNotNull { it.toForgetIdOrNull(type, candidateRefs) }
            if (actionType != MemoryForgetPlan.Action.Type.NOOP && ids.isEmpty()) return null
            val allowed = ids.filter { id -> MemoryItemRef(type, id) in candidateRefs }
            if (actionType != MemoryForgetPlan.Action.Type.NOOP && allowed.isEmpty()) return null

            return MemoryForgetPlan.Action(
                action = actionType,
                targetType = type,
                targetIds = allowed,
                reason = reason.trim().take(800),
            )
        }
    }
}

private fun List<MemoryStore.SearchHit>.renderForgetCandidates(): String =
    joinToString("\n") { hit ->
        when (hit) {
            is MemoryStore.SearchHit.SourceHit -> "- source ${hit.source.id.value}: ${hit.source.contentText.oneLineForForgetPlannerLog(MAX_FORGET_SOURCE_TEXT_CHARS)}"
            is MemoryStore.SearchHit.ClaimHit -> "- claim ${hit.claim.id.value}: status=${hit.claim.status.name}; predicate=${hit.claim.predicate}; text=${hit.claim.normalizedText.oneLineForForgetPlannerLog(MAX_FORGET_ITEM_TEXT_CHARS)}; evidence=${hit.claim.evidenceRefs.map { it.sourceId.value }.joinToString("|")}"
            is MemoryStore.SearchHit.NoteHit -> "- note ${hit.note.id.value}: status=${hit.note.status.name}; maturity=${hit.note.maturity.name}; title=${hit.note.title.oneLineForForgetPlannerLog(MAX_FORGET_ITEM_TEXT_CHARS)}; summary=${hit.note.summary.oneLineForForgetPlannerLog(MAX_FORGET_ITEM_TEXT_CHARS)}; evidence=${hit.note.evidenceRefs.map { it.sourceId.value }.joinToString("|")}"
            is MemoryStore.SearchHit.ActionItemHit -> "- action_item ${hit.actionItem.id.value}: status=${hit.actionItem.status.name}; title=${hit.actionItem.title.oneLineForForgetPlannerLog(MAX_FORGET_ITEM_TEXT_CHARS)}; description=${hit.actionItem.description.orEmpty().oneLineForForgetPlannerLog(MAX_FORGET_ITEM_TEXT_CHARS)}; evidence=${hit.actionItem.evidenceRefs.map { it.sourceId.value }.joinToString("|")}"
            is MemoryStore.SearchHit.EpisodeHit -> "- episode ${hit.episode.id.value}: situation=${hit.episode.situation.oneLineForForgetPlannerLog(MAX_FORGET_ITEM_TEXT_CHARS)}; lesson=${hit.episode.lesson.oneLineForForgetPlannerLog(MAX_FORGET_ITEM_TEXT_CHARS)}; evidence=${hit.episode.evidenceRefs.map { it.sourceId.value }.joinToString("|")}"
            is MemoryStore.SearchHit.EntityHit -> "- entity ${hit.entity.id.value}: type=${hit.entity.entityType.name}; name=${hit.entity.canonicalName.oneLineForForgetPlannerLog(MAX_FORGET_ITEM_TEXT_CHARS)}; summary=${hit.entity.summary.orEmpty().oneLineForForgetPlannerLog(MAX_FORGET_ITEM_TEXT_CHARS)}"
            is MemoryStore.SearchHit.ProfileHit -> "- profile ${hit.profile.id.value}: owner=${hit.profile.ownerEntityId.value}; text=${hit.profile.profileText.oneLineForForgetPlannerLog(500)}"
            is MemoryStore.SearchHit.RunHit -> "- run ${hit.run.id.value}: type=${hit.run.runType.name}; summary=${hit.run.summary.oneLineForForgetPlannerLog(MAX_FORGET_ITEM_TEXT_CHARS)}"
        }
    }.ifBlank { "none" }

private fun MemoryNamespaceSnapshot.renderForgetSupportingSources(
    candidates: List<MemoryStore.SearchHit>,
    currentSourceId: com.gromozeka.domain.model.memory.MemorySource.Id,
): String {
    val sourceIds = buildSet {
        candidates.forEach { hit ->
            when (hit) {
                is MemoryStore.SearchHit.SourceHit -> add(hit.source.id)
                is MemoryStore.SearchHit.ClaimHit -> addAll(hit.claim.evidenceRefs.map { it.sourceId })
                is MemoryStore.SearchHit.NoteHit -> addAll(hit.note.evidenceRefs.map { it.sourceId })
                is MemoryStore.SearchHit.ActionItemHit -> addAll(hit.actionItem.evidenceRefs.map { it.sourceId })
                is MemoryStore.SearchHit.EpisodeHit -> addAll(hit.episode.evidenceRefs.map { it.sourceId })
                is MemoryStore.SearchHit.EntityHit,
                is MemoryStore.SearchHit.ProfileHit,
                is MemoryStore.SearchHit.RunHit,
                -> Unit
            }
        }
    }

    return sources
        .filter { it.id in sourceIds && it.id != currentSourceId }
        .take(16)
        .joinToString("\n") { source ->
            "- source ${source.id.value}: ${source.contentText.oneLineForForgetPlannerLog(MAX_FORGET_SUPPORTING_SOURCE_TEXT_CHARS)}"
        }
        .ifBlank { "none" }
}

internal fun MemoryStore.SearchHit.toForgetItemRef(): MemoryItemRef =
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

private fun String.toForgetActionType(): MemoryForgetPlan.Action.Type? =
    when (trim().lowercase()) {
        "archive_item" -> MemoryForgetPlan.Action.Type.ARCHIVE_ITEM
        "soft_delete_source" -> MemoryForgetPlan.Action.Type.SOFT_DELETE_SOURCE
        "noop" -> MemoryForgetPlan.Action.Type.NOOP
        else -> null
    }

private fun String.toForgetTargetType(): MemoryItemRef.Type? =
    when (trim().lowercase()) {
        "source" -> MemoryItemRef.Type.SOURCE
        "claim" -> MemoryItemRef.Type.CLAIM
        "note" -> MemoryItemRef.Type.NOTE
        "action_item", "actionitem", "task" -> MemoryItemRef.Type.ACTION_ITEM
        "episode" -> MemoryItemRef.Type.EPISODE
        else -> null
    }

private fun String.toForgetIdOrNull(
    targetType: MemoryItemRef.Type,
    candidateRefs: Set<MemoryItemRef>,
): String? {
    val value = trim().takeIf { it.isNotBlank() } ?: return null
    val lowerValue = value.lowercase()
    if (lowerValue in setOf("uuid", "id", "null")) return null
    if (lowerValue.contains("<id>") || lowerValue.contains("{id}") || lowerValue.endsWith(":id")) {
        return candidateRefs
            .filter { it.type == targetType }
            .singleOrNull()
            ?.id
    }
    return value
}

private fun String.oneLineForForgetPlannerLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}

private const val MAX_FORGET_SOURCE_TEXT_CHARS = 1_200
private const val MAX_FORGET_SUPPORTING_SOURCE_TEXT_CHARS = 1_200
private const val MAX_FORGET_ITEM_TEXT_CHARS = 500
