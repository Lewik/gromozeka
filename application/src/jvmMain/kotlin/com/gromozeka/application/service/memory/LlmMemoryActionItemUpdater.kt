package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.memory.MemoryActionItemUpdateOp
import com.gromozeka.domain.model.memory.MemoryActionItemUpdater
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class LlmMemoryActionItemUpdater(
    private val runtime: AiRuntime,
    private val timezone: String,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryActionItemUpdater {
    private val log = KLoggers.logger(this)

    override suspend fun update(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryActionItemUpdateOp> {
        if (!routeDecision.shouldUpdateActionItems()) {
            log.info {
                "Memory action item updater skipped: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "decision=${routeDecision.decision.name} types=${routeDecision.memoryTypes.joinToString { it.name }}"
            }
            return emptyList()
        }

        val stageMessages = request.toMemoryStageMessages(
            stageName = "action-item-updater",
            taskPrompt = buildActionItemUpdaterPrompt(request, routeDecision, retrievalPlan, retrievedHits, entityOps),
        )

        log.info {
            "Memory action item updater LLM call: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "decision=${routeDecision.decision.name} contentChars=${request.source.contentText.length} " +
                "entityOps=${entityOps.size} retrievedHits=${retrievedHits.size} threadContext=${request.memoryThreadContextSummaryForLog()} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size} stageMessages=${stageMessages.size}"
        }

        val existingActionItems = retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.ActionItemHit>()
            .map { it.actionItem }
        val entityRefValidator = MemoryEntityRefValidator(
            stageName = "ActionItemUpdater",
            allowedEntityIds = entityOps.mapNotNullTo(mutableSetOf()) { it.entityId },
            entityAliases = entityOps.toEntityRefAliases(),
        )
        val result = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.ACTION_ITEM_UPDATER_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.ActionItemUpdater,
                    toolContext = mapOf(
                        "memoryActionItemUpdater" to true,
                        "memoryNamespace" to request.namespace.value,
                        "memorySourceId" to request.source.id.value,
                        "memoryRouteDecision" to routeDecision.decision.name,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "action-item-updater",
            logContext = "namespace=${request.namespace.value} source=${request.source.id.value}",
            parse = { jsonText ->
                json.decodeFromString<ActionItemUpdaterResponse>(jsonText)
                    .operations
                    .mapIndexedNotNull { index, operation ->
                        operation.toOp(
                            request = request,
                            existingActionItems = existingActionItems,
                            entityRefs = entityRefValidator,
                            operationPath = "operations[$index]",
                        )
                    }
            },
        )

        log.info {
            "Memory action item updater raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${result.rawText.length} response=${result.rawText.oneLineForActionItemMemoryLog(4_000)}"
        }

        val ops = result.value

        log.info {
            "Memory action item updater mapped ops: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${ops.joinToString("|") { "${it.action.name}:target=${it.targetActionItemId?.value ?: "null"}:title=${it.actionItem?.title ?: "null"}:${it.reason.oneLineForActionItemMemoryLog(180)}" }.ifBlank { "none" }}"
        }

        return ops
    }

    private fun buildActionItemUpdaterPrompt(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): String = """
        Memory stage: ActionItemUpdater v3.
        Current time: ${Clock.System.now()}
        Timezone: $timezone
        Namespace: ${request.namespace.value}

        Router decision: ${routeDecision.decision.name}
        Router memory types: ${routeDecision.memoryTypes.joinToString { it.name }}
        Router reason: ${routeDecision.reason}

        Retrieval plan context:
        entity_queries=${retrievalPlan.entityQueries.joinToString("|")}
        text_queries=${retrievalPlan.textQueries.joinToString("|")}
        memory_types=${retrievalPlan.memoryTypes.joinToString { it.name }}

        Stage instructions:
        $ACTION_ITEM_UPDATER_PROMPT

        Existing action items:
        ${retrievedHits.renderExistingActionItemsForUpdater()}

        Related memory context:
        ${retrievedHits.renderRelatedMemoryForActionItemUpdater()}

        Resolved entities:
        ${entityOps.renderResolvedEntitiesForActionItemUpdater()}

        TARGET_MESSAGE source data:
        ${request.source.renderLatestTurn()}
    """.trimIndent()

    @Serializable
    private data class ActionItemUpdaterResponse(
        val operations: List<Operation> = emptyList(),
    )

    @Serializable
    private data class Operation(
        val action: String,
        @SerialName("target_action_item_id")
        val targetActionItemId: String? = null,
        @SerialName("action_item")
        val actionItem: ActionItemDraft? = null,
        val reason: String = "",
    ) {
        fun toOp(
            request: DirectStructuredMemoryWriteRequest,
            existingActionItems: List<MemoryActionItem>,
            entityRefs: MemoryEntityRefValidator,
            operationPath: String,
        ): MemoryActionItemUpdateOp? {
            val target = targetActionItemId.toActionItemMemoryIdTextOrNull()?.let { MemoryActionItem.Id(it) }
            if (target != null && existingActionItems.none { it.id == target }) {
                return null
            }

            val draft = actionItem?.toDraft(request, entityRefs, "$operationPath.action_item")
            return when (action.trim().lowercase()) {
                "insert" -> draft?.takeIf { !it.evidenceQuote.isNullOrBlank() }?.let {
                    MemoryActionItemUpdateOp(
                        action = MemoryActionItemUpdateOp.Action.INSERT,
                        actionItem = it,
                        reason = reason.trim().ifBlank { "LLM action item updater inserted action item" },
                    )
                }

                "update" -> target?.let {
                    MemoryActionItemUpdateOp(
                        action = MemoryActionItemUpdateOp.Action.UPDATE,
                        targetActionItemId = it,
                        actionItem = draft,
                        reason = reason.trim().ifBlank { "LLM action item updater updated action item" },
                    )
                }

                "close" -> target?.let {
                    MemoryActionItemUpdateOp(
                        action = MemoryActionItemUpdateOp.Action.CLOSE,
                        targetActionItemId = it,
                        actionItem = draft,
                        reason = reason.trim().ifBlank { "LLM action item updater closed action item" },
                    )
                }

                "cancel" -> target?.let {
                    MemoryActionItemUpdateOp(
                        action = MemoryActionItemUpdateOp.Action.CANCEL,
                        targetActionItemId = it,
                        actionItem = draft,
                        reason = reason.trim().ifBlank { "LLM action item updater cancelled action item" },
                    )
                }

                "noop" -> MemoryActionItemUpdateOp(
                    action = MemoryActionItemUpdateOp.Action.NOOP,
                    targetActionItemId = target,
                    actionItem = draft,
                    reason = reason.trim().ifBlank { "LLM action item updater selected noop" },
                )

                else -> null
            }
        }
    }

    @Serializable
    private data class ActionItemDraft(
        val title: String,
        val description: String? = null,
        val status: String = "open",
        val priority: String = "normal",
        @SerialName("due_at")
        val dueAt: String? = null,
        @SerialName("scope_json")
        val scopeJson: JsonObject? = null,
        @SerialName("owner_entity_id")
        val ownerEntityId: String? = null,
        @SerialName("assignee_entity_id")
        val assigneeEntityId: String? = null,
        @SerialName("acceptance_criteria")
        val acceptanceCriteria: List<String> = emptyList(),
        val blockers: List<String> = emptyList(),
        @SerialName("related_entity_ids")
        val relatedEntityIds: List<String> = emptyList(),
        val confidence: Double = 0.0,
        @SerialName("evidence_quote")
        val evidenceQuote: String? = null,
        @SerialName("evidence_kind")
        val evidenceKind: String = "direct",
        @SerialName("evidence_reason")
        val evidenceReason: String = "",
    ) {
        fun toDraft(
            request: DirectStructuredMemoryWriteRequest,
            entityRefs: MemoryEntityRefValidator,
            actionItemPath: String,
        ): MemoryActionItemUpdateOp.Draft? {
            val cleanTitle = title.trim().take(180)
            if (cleanTitle.isBlank()) return null

            val quote = evidenceQuote
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" }

            if (quote != null && !MemoryEvidenceQuoteMatcher.matches(request.source.contentText, quote)) {
                return null
            }

            val owner = entityRefs.optional(ownerEntityId, "$actionItemPath.owner_entity_id")
            val assignee = entityRefs.optional(assigneeEntityId, "$actionItemPath.assignee_entity_id")
            val related = entityRefs.optionalList(relatedEntityIds, "$actionItemPath.related_entity_ids")

            return MemoryActionItemUpdateOp.Draft(
                title = cleanTitle,
                scope = scopeJson.toMemoryActionItemScope(request, owner ?: assignee ?: related.firstOrNull()),
                description = description?.trim()?.take(1_200)?.takeIf { it.isNotBlank() && it != "null" },
                ownerEntityId = owner,
                assigneeEntityId = assignee,
                status = status.toMemoryActionItemStatus(),
                priority = priority.toMemoryActionItemPriority(),
                dueAt = dueAt.toActionItemInstantOrNull(),
                acceptanceCriteria = acceptanceCriteria.cleanActionItemTextList(maxItems = 8, maxChars = 180),
                blockers = blockers.cleanActionItemTextList(maxItems = 8, maxChars = 180),
                relatedEntityIds = related,
                confidence = confidence.coerceIn(0.0, 1.0),
                evidenceQuote = quote,
                evidenceKind = evidenceKind.toMemoryActionItemEvidenceKind(),
                evidenceReason = evidenceReason.trim().take(400),
            )
        }
    }

    private companion object {
        val ACTION_ITEM_UPDATER_PROMPT = """
            You are ActionItemUpdater v3 for a long-term AI agent.

            Goal:
            Create or update internal memory action items from explicit user commitments, deadlines, follow-ups, blockers, or action item lifecycle changes in TARGET_MESSAGE.

            Definition:
            An action item is Gromozeka's internal reminder/work-to-do memory for something the assistant or user should track later.
            It is not the same thing as an external issue tracker item such as Jira Story, GitHub Issue, ticket, backlog item, or assignee field unless TARGET_MESSAGE explicitly asks Gromozeka to remember or track it as a follow-up.

            Return JSON:
            {
              "operations": [
                {
                  "action": "insert | update | close | cancel | noop",
                  "target_action_item_id": "existing-action-item-id-or-null",
                  "action_item": {
                    "title": "short English action title",
                    "description": "English self-contained action item description or null",
                    "status": "open | in_progress | blocked | done | cancelled",
                    "priority": "low | normal | high",
                    "due_at": "ISO-8601 or null",
                    "scope_json": {
                      "kind": "global | conversation | entity | environment | document | project",
                      "text": "human-readable applicability boundary",
                      "basis": "explicit | inferred | summarized | imported"
                    },
                    "owner_entity_id": "resolved-entity-id-or-null",
                    "assignee_entity_id": "resolved-entity-id-or-null",
                    "acceptance_criteria": ["..."],
                    "blockers": ["..."],
                    "related_entity_ids": ["resolved-entity-id"],
                    "confidence": 0.0,
                    "evidence_quote": "exact substring from TARGET_MESSAGE or null",
                    "evidence_kind": "direct | summarized | imported | inferred | derived_from_note",
                    "evidence_reason": "why this source supports the action item update"
                  },
                  "reason": "short explanation"
                }
              ]
            }

            Rules:
            - Write title, description, scope text, acceptance criteria, blockers, evidence_reason, and reason in English.
            - Create an action item only when TARGET_MESSAGE explicitly creates a future action, commitment, follow-up, deadline, blocker, assignment, or lifecycle update for Gromozeka memory.
            - Do not convert external work records into action items just because they have Type=Story, Assignee, Status, Priority, or similar fields.
            - A Jira Story, GitHub Issue, ticket, backlog item, or project-management row should usually become a source/note/claim about that external record, not an action item.
            - A plain user question, request for an immediate chat answer, implementation instruction, discussion topic, preference, fact, rationale, or procedure is not automatically an action item.
            - Do not create action items from "we should think about", weak ideas, or memory mechanics chatter unless the target explicitly says to keep a follow-up/action item/todo.
            - Prefer one clear action item over several vague action items.
            - Use Existing action items to update, close, cancel, or deduplicate instead of inserting duplicates.
            - If TARGET_MESSAGE repeats an already open action item without new durable details, return noop targeting the existing action item.
            - If TARGET_MESSAGE changes scope, acceptance criteria, priority, blockers, assignee, deadline, or wording of an existing action item, return update targeting that action item.
            - If TARGET_MESSAGE says the action item is done, finished, completed, shipped, resolved, or no longer open because work was completed, return close.
            - If TARGET_MESSAGE says the action item is cancelled, no longer needed, abandoned, dropped, or should not be done, return cancel.
            - For close/cancel/update, target_action_item_id must be an existing action item id.
            - Convert relative deadlines to absolute ISO-8601 when possible using Current time and Timezone.
            - Use Full thread context to resolve pronouns and omitted subjects, but TARGET_MESSAGE is the only evidence for the action item update.
            - evidence_quote must be an exact short substring copied from TARGET_MESSAGE when action item is not noop.
            - Use only entity IDs listed in Resolved entities.
            - Never write raw labels such as "user", "assistant", "project", or "document" into owner_entity_id, assignee_entity_id, or related_entity_ids. If an action item is owned by the user, use the resolved USER entity id from Resolved entities.
            - If TARGET_MESSAGE only asks the assistant to do something now in this chat, return noop unless it explicitly asks to remember/save a follow-up.
            - Prefer noop over action item spam.
            - Return valid JSON only.
        """.trimIndent()
    }
}

private fun MemoryRouteDecision.shouldUpdateActionItems(): Boolean =
    decision != MemoryRouteDecision.Decision.NOOP &&
        MemorySemanticType.ACTION_ITEM in memoryTypes

private fun List<MemoryStore.SearchHit>.renderExistingActionItemsForUpdater(): String {
    val rendered = filterIsInstance<MemoryStore.SearchHit.ActionItemHit>()
        .map { it.actionItem }
        .filter { it.archivedAt == null }
        .joinToString("\n") { actionItem ->
            "- id=${actionItem.id.value}; status=${actionItem.status.name}; priority=${actionItem.priority.name}; title=${actionItem.title}; " +
                "description=${actionItem.description ?: "none"}; due_at=${actionItem.dueAt}; scope=${actionItem.scope.text}; " +
                "acceptance=${actionItem.acceptanceCriteria.joinToString("; ").ifBlank { "none" }}; blockers=${actionItem.blockers.joinToString("; ").ifBlank { "none" }}"
        }

    return rendered.ifBlank { "none" }
}

private fun List<MemoryStore.SearchHit>.renderRelatedMemoryForActionItemUpdater(): String {
    val rendered = mapNotNull { hit ->
        when (hit) {
            is MemoryStore.SearchHit.ClaimHit -> "- claim ${hit.claim.id.value}: ${hit.claim.normalizedText}"
            is MemoryStore.SearchHit.NoteHit -> "- note ${hit.note.id.value}: ${hit.note.title}; ${hit.note.summary}"
            is MemoryStore.SearchHit.ProfileHit -> "- profile ${hit.profile.id.value}: ${hit.profile.profileText}"
            is MemoryStore.SearchHit.SourceHit,
            is MemoryStore.SearchHit.EntityHit,
            is MemoryStore.SearchHit.ActionItemHit,
            is MemoryStore.SearchHit.EpisodeHit,
            is MemoryStore.SearchHit.RunHit,
            -> null
        }
    }

    return rendered.joinToString("\n").ifBlank { "none" }
}

private fun List<MemoryEntityCanonicalizationOp>.renderResolvedEntitiesForActionItemUpdater(): String {
    val rendered = filter { it.action != MemoryEntityCanonicalizationOp.Action.NOOP && it.entityId != null }
        .map { op ->
            val entity = op.newEntity
            "- mention=${op.mention}; action=${op.action.name}; id=${op.entityId?.value}; type=${entity?.entityType?.name ?: "existing"}; name=${entity?.canonicalName ?: op.aliasText ?: op.mention}; reason=${op.reason}"
        }
    return rendered.joinToString("\n").ifBlank { "none" }
}

private fun JsonObject?.toMemoryActionItemScope(
    request: DirectStructuredMemoryWriteRequest,
    subjectEntityId: MemoryEntity.Id?,
): MemoryScope {
    val kind = stringValueForActionItem("kind", "scope_kind", "type")
        ?.lowercase()
        ?.replace("-", "_")
    val text = stringValueForActionItem("text", "scope_text", "label")
        ?.takeIf { it.isNotBlank() }
        ?: when (kind) {
            "conversation", "chat" -> "Conversation-scoped action item"
            "environment", "env" -> "Environment-scoped action item"
            "document", "doc" -> "Document-scoped action item"
            "entity" -> "Entity-scoped action item"
            else -> "Namespace-wide action item"
        }
    val basis = stringValueForActionItem("basis").toActionItemScopeBasis()

    return when (kind) {
        "conversation", "chat" -> {
            val source = request.source
            if (source is MemorySource.ChatTurn) {
                MemoryScope.Conversation(text = text, conversationId = source.conversationId, basis = basis)
            } else {
                MemoryScope.Global(text = text, basis = basis)
            }
        }

        "environment", "env" -> MemoryScope.Environment(
            text = text,
            environment = stringValueForActionItem("environment", "environment_name", "env") ?: text,
            basis = basis,
        )

        "document", "doc" -> MemoryScope.Document(
            text = text,
            documentRef = stringValueForActionItem("document_ref", "document", "doc_ref") ?: text,
            basis = basis,
        )

        "entity" -> subjectEntityId?.let {
            MemoryScope.Entity(text = text, subjectEntityId = it, basis = basis)
        } ?: MemoryScope.Global(text = text, basis = basis)

        else -> MemoryScope.Global(text = text, basis = basis)
    }
}

private fun JsonObject?.stringValueForActionItem(vararg keys: String): String? {
    if (this == null) return null
    return keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
    }
}

private fun String?.toActionItemScopeBasis(): MemoryScope.Basis =
    when (this?.trim()?.lowercase()) {
        "inferred" -> MemoryScope.Basis.INFERRED
        "summarized", "summary" -> MemoryScope.Basis.SUMMARIZED
        "imported" -> MemoryScope.Basis.IMPORTED
        else -> MemoryScope.Basis.EXPLICIT
    }

private fun String.toMemoryActionItemStatus(): MemoryActionItem.Status =
    when (trim().lowercase().replace("-", "_")) {
        "in_progress", "started" -> MemoryActionItem.Status.IN_PROGRESS
        "blocked" -> MemoryActionItem.Status.BLOCKED
        "done", "closed", "complete", "completed" -> MemoryActionItem.Status.DONE
        "cancelled", "canceled" -> MemoryActionItem.Status.CANCELLED
        else -> MemoryActionItem.Status.OPEN
    }

private fun String.toMemoryActionItemPriority(): MemoryActionItem.Priority =
    when (trim().lowercase()) {
        "high" -> MemoryActionItem.Priority.HIGH
        "low" -> MemoryActionItem.Priority.LOW
        else -> MemoryActionItem.Priority.NORMAL
    }

private fun String.toMemoryActionItemEvidenceKind(): MemoryEvidenceRef.Kind =
    when (trim().lowercase().replace("-", "_")) {
        "summarized" -> MemoryEvidenceRef.Kind.SUMMARIZED
        "imported" -> MemoryEvidenceRef.Kind.IMPORTED
        "inferred" -> MemoryEvidenceRef.Kind.INFERRED
        "derived_from_note" -> MemoryEvidenceRef.Kind.DERIVED_FROM_NOTE
        else -> MemoryEvidenceRef.Kind.DIRECT
    }

private fun String?.toActionItemInstantOrNull(): Instant? {
    val value = this?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    return runCatching { Instant.parse(value) }.getOrNull()
}

private fun String?.toActionItemMemoryIdTextOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = value.lowercase()
    if (
        normalized == "null" ||
        normalized == "uuid-or-null" ||
        normalized == "existing-actionitem-id-or-null" ||
        normalized == "existing-action-item-id-or-null" ||
        normalized == "resolved-entity-id-or-null" ||
        normalized == "resolved-entity-id"
    ) {
        return null
    }
    return value
}

private fun List<String>.cleanActionItemTextList(
    maxItems: Int,
    maxChars: Int,
): List<String> =
    map { it.trim().take(maxChars) }
        .filter { it.isNotBlank() && it != "null" }
        .distinct()
        .take(maxItems)

private fun String.oneLineForActionItemMemoryLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) return oneLine
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
