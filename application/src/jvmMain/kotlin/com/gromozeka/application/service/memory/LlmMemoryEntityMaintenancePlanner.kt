package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryClaim
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityMaintenanceCandidateGroup
import com.gromozeka.domain.model.memory.MemoryEntityMaintenancePlan
import com.gromozeka.domain.model.memory.MemoryEntityMaintenancePlanner
import com.gromozeka.domain.model.memory.MemoryMaintenanceRequest
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LlmMemoryEntityMaintenancePlanner(
    private val runtime: AiRuntime,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryEntityMaintenancePlanner {
    private val log = KLoggers.logger(this)

    override suspend fun plan(
        request: MemoryMaintenanceRequest,
        candidateGroups: List<MemoryEntityMaintenanceCandidateGroup>,
        snapshot: MemoryNamespaceSnapshot,
    ): MemoryEntityMaintenancePlan {
        if (candidateGroups.isEmpty()) {
            return MemoryEntityMaintenancePlan(summary = "No entity maintenance candidates found.")
        }

        val stageMessages = listOf(
            entityMaintenanceTaskMessage(
                stageName = "memory-entity-maintenance-planner",
                taskPrompt = buildPrompt(request, candidateGroups, snapshot),
            )
        )

        log.info {
            "Memory entity maintenance planner LLM call: namespace=${request.namespace.value} groups=${candidateGroups.size} " +
                "entities=${candidateGroups.sumOf { it.entities.size }} runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size}"
        }

        val candidateIds = candidateGroups.flatMapTo(mutableSetOf()) { group -> group.entities.map { it.id.value } }
        val result = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.ENTITY_MAINTENANCE_PLANNER_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.EntityMaintenancePlanner,
                    toolContext = mapOf(
                        "memoryEntityMaintenancePlanner" to true,
                        "memoryNamespace" to request.namespace.value,
                    ),
                ),
            ),
            stageName = "memory-entity-maintenance-planner",
            logContext = "namespace=${request.namespace.value}",
            parse = { jsonText ->
                val parsed = json.decodeFromString<EntityMaintenancePlannerResponse>(jsonText)
                MemoryEntityMaintenancePlan(
                    actions = parsed.actions.mapNotNull { it.toAction(candidateIds) },
                    summary = parsed.summary.trim(),
                )
            },
        )

        log.info {
            "Memory entity maintenance planner raw response: namespace=${request.namespace.value} chars=${result.rawText.length} " +
                "response=${result.rawText.oneLineForEntityMaintenanceLog(4_000)}"
        }

        val plan = result.value

        log.info {
            "Memory entity maintenance planner mapped result: namespace=${request.namespace.value} actions=${plan.actions.size} " +
                "summary=${plan.summary.oneLineForEntityMaintenanceLog(500)}"
        }

        return plan
    }

    private fun buildPrompt(
        request: MemoryMaintenanceRequest,
        candidateGroups: List<MemoryEntityMaintenanceCandidateGroup>,
        snapshot: MemoryNamespaceSnapshot,
    ): String = """
        You are MemoryEntityMaintenancePlanner v1.

        Goal:
        Review near-duplicate entity clusters and entity summaries that contain mutable facts.

        Current time: ${Clock.System.now()}
        Namespace: ${request.namespace.value}

        Candidate groups:
        ${candidateGroups.renderEntityGroupsForMaintenance(snapshot)}

        Return JSON:
        {
          "actions": [
            {
              "action": "merge | add_alias | update_summary | keep_separate | noop",
              "winner_entity_id": "entity-id-or-null",
              "loser_entity_ids": ["entity-id"],
              "target_entity_ids": ["entity-id"],
              "alias_texts": ["alias"],
              "summary_text": "new summary or null",
              "reason": "short explanation"
            }
          ],
          "summary": "short summary"
        }

        Rules:
        - Be conservative. Merge only when entities clearly name the same real-world or system referent.
        - A same-name group across entity types may indicate a prior misclassification. Merge it only when the referent is clearly identical; otherwise use keep_separate.
        - For current-user identity groups, merge USER and PERSON only when aliases, preferred_name claims, or profile/claim evidence establish that the named person is the current user.
        - When a current-user USER entity and a named PERSON are clearly the same current user, keep the USER entity as winner and preserve exact personal names as alias_texts.
        - A shared first name alone is not enough to merge people. Keep separate unless the first-name/full-name bridge is backed by current-user alias/preferred_name or matching profile facts.
        - Compatible technical types such as technology, product, concept, service, and environment may still be the same referent; choose the most concrete, best-supported entity as winner.
        - Do not merge merely related concepts, components, files, projects, or implementation steps.
        - Do not merge a project entity with a component/service/concept entity.
        - Prefer the winner with the most precise canonical name, clearest summary, most references, and active status.
        - Use add_alias when one entity is correct and only needs an extra surface form.
        - Use update_summary when a single active entity summary is obsolete, over-specific, or contains claim-like facts.
        - For update_summary, set target_entity_ids to exactly the entity being refreshed and summary_text to an identity-only description.
        - Entity summaries must not contain mutable facts: preferences, current formats, statuses, owners, feature decisions, or project state.
        - Base update_summary on ACTIVE claims only to detect stale summaries. Do not copy claim truth into the entity summary.
        - Use keep_separate when candidates are related but not identical.
        - Use noop when no safe action exists.
        - Do not invent entity ids. Use only ids from Candidate groups.
        - Preserve exact proper names and identifiers in alias_texts.
        - Return valid JSON only.
    """.trimIndent()

    @Serializable
    private data class EntityMaintenancePlannerResponse(
        val actions: List<ActionResponse> = emptyList(),
        val summary: String = "",
    )

    @Serializable
    private data class ActionResponse(
        val action: String,
        @SerialName("winner_entity_id")
        val winnerEntityId: String? = null,
        @SerialName("loser_entity_ids")
        val loserEntityIds: List<String> = emptyList(),
        @SerialName("target_entity_ids")
        val targetEntityIds: List<String> = emptyList(),
        @SerialName("alias_texts")
        val aliasTexts: List<String> = emptyList(),
        @SerialName("summary_text")
        val summaryText: String? = null,
        val reason: String = "",
    ) {
        fun toAction(candidateIds: Set<String>): MemoryEntityMaintenancePlan.Action? {
            val type = action.toEntityMaintenanceActionType() ?: return null
            val winner = winnerEntityId.toEntityMaintenanceIdOrNull()?.takeIf { it in candidateIds }
            val losers = loserEntityIds.mapNotNull { it.toEntityMaintenanceIdOrNull() }
                .filter { it in candidateIds && it != winner }
                .distinct()
            val targets = targetEntityIds.mapNotNull { it.toEntityMaintenanceIdOrNull() }
                .filter { it in candidateIds }
                .distinct()

            if (type == MemoryEntityMaintenancePlan.Action.Type.MERGE && (winner == null || losers.isEmpty())) {
                return null
            }
            if (type == MemoryEntityMaintenancePlan.Action.Type.ADD_ALIAS && targets.isEmpty()) {
                return null
            }
            val cleanedSummary = summaryText?.trim()?.take(600)?.takeIf { it.isNotBlank() }
            if (type == MemoryEntityMaintenancePlan.Action.Type.UPDATE_SUMMARY && (targets.isEmpty() || cleanedSummary == null)) {
                return null
            }

            return MemoryEntityMaintenancePlan.Action(
                action = type,
                winnerEntityId = winner,
                loserEntityIds = losers,
                targetEntityIds = targets,
                aliasTexts = aliasTexts.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20),
                summaryText = cleanedSummary,
                reason = reason.trim().take(800),
            )
        }
    }
}

private fun entityMaintenanceTaskMessage(
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
                MEMORY-ONLY MAINTENANCE INSTRUCTION

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

private fun List<MemoryEntityMaintenanceCandidateGroup>.renderEntityGroupsForMaintenance(
    snapshot: MemoryNamespaceSnapshot,
): String =
    joinToString("\n\n") { group ->
        val usage = snapshot.entityUsageCounts()
        buildString {
            appendLine("Group ${group.id}: ${group.reason}")
            group.entities.forEach { entity ->
                val aliases = entity.aliases.joinToString("|") { it.text }.ifBlank { "none" }
                val observedTypes = entity.observedTypes.joinToString("|") { it.name }
                appendLine(
                    "- entity ${entity.id.value}: type=${entity.entityType.name}; observed_types=$observedTypes; status=${entity.status.name}; " +
                        "name=${entity.canonicalName}; normalized=${entity.normalizedName}; aliases=$aliases; " +
                        "refs=${usage[entity.id] ?: 0}; summary=${entity.summary ?: "none"}"
                )
                snapshot.renderClaimsForEntityMaintenance(entity.id).forEach { appendLine("  $it") }
            }
        }.trim()
    }.ifBlank { "none" }

private fun MemoryNamespaceSnapshot.entityUsageCounts(): Map<MemoryEntity.Id, Int> =
    buildMap {
        fun add(id: MemoryEntity.Id?) {
            if (id != null) put(id, (get(id) ?: 0) + 1)
        }

        claims.forEach {
            add(it.subjectEntityId)
            add(it.objectEntityId)
        }
        notes.forEach { note ->
            add(note.anchorEntityId)
            note.entityRefs.forEach { add(it.entityId) }
        }
        actionItems.forEach { actionItem ->
            add(actionItem.ownerEntityId)
            add(actionItem.assigneeEntityId)
            actionItem.relatedEntityIds.forEach(::add)
        }
        profiles.forEach { add(it.ownerEntityId) }
        episodes.forEach { add(it.ownerEntityId) }
    }

private fun String.toEntityMaintenanceActionType(): MemoryEntityMaintenancePlan.Action.Type? =
    when (trim().lowercase().replace("-", "_")) {
        "merge" -> MemoryEntityMaintenancePlan.Action.Type.MERGE
        "add_alias" -> MemoryEntityMaintenancePlan.Action.Type.ADD_ALIAS
        "update_summary" -> MemoryEntityMaintenancePlan.Action.Type.UPDATE_SUMMARY
        "keep_separate" -> MemoryEntityMaintenancePlan.Action.Type.KEEP_SEPARATE
        "noop" -> MemoryEntityMaintenancePlan.Action.Type.NOOP
        else -> null
    }

private fun MemoryNamespaceSnapshot.renderClaimsForEntityMaintenance(entityId: MemoryEntity.Id): List<String> {
    val active = claims
        .filter { it.status == MemoryClaim.Status.ACTIVE && it.subjectEntityId == entityId }
        .sortedByDescending { it.importance }
        .take(6)
        .map { claim -> "ACTIVE claim ${claim.id.value}: ${claim.predicate}; ${claim.normalizedText}" }
    val superseded = claims
        .filter { it.status == MemoryClaim.Status.SUPERSEDED && it.subjectEntityId == entityId }
        .sortedByDescending { it.updatedAt }
        .take(4)
        .map { claim -> "SUPERSEDED claim ${claim.id.value}: ${claim.predicate}; ${claim.normalizedText}" }

    return active + superseded
}

private fun String?.toEntityMaintenanceIdOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (value.lowercase() in setOf("entity-id-or-null", "entity-id", "id", "null")) return null
    return value
}

private fun String.oneLineForEntityMaintenanceLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
