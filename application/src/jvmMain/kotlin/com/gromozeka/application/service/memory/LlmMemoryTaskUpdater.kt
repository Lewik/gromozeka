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
import com.gromozeka.domain.model.memory.MemoryTask
import com.gromozeka.domain.model.memory.MemoryTaskUpdateOp
import com.gromozeka.domain.model.memory.MemoryTaskUpdater
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

class LlmMemoryTaskUpdater(
    private val runtime: AiRuntime,
    private val timezone: String,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryTaskUpdater {
    private val log = KLoggers.logger(this)

    override suspend fun update(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryTaskUpdateOp> {
        if (!routeDecision.shouldUpdateTasks()) {
            log.info {
                "Memory task updater skipped: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "decision=${routeDecision.decision.name} types=${routeDecision.memoryTypes.joinToString { it.name }}"
            }
            return emptyList()
        }

        val stageMessages = request.toMemoryStageMessages(
            stageName = "task-updater",
            taskPrompt = buildTaskUpdaterPrompt(request, routeDecision, retrievalPlan, retrievedHits, entityOps),
        )

        log.info {
            "Memory task updater LLM call: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "decision=${routeDecision.decision.name} contentChars=${request.source.contentText.length} " +
                "entityOps=${entityOps.size} retrievedHits=${retrievedHits.size} threadContext=${request.memoryThreadContextSummaryForLog()} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size} stageMessages=${stageMessages.size}"
        }

        val response = runtime.callMemoryStageWithRetry(
            AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = 3_600,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.TaskUpdater,
                    toolContext = mapOf(
                        "memoryTaskUpdater" to true,
                        "memoryNamespace" to request.namespace.value,
                        "memorySourceId" to request.source.id.value,
                        "memoryRouteDecision" to routeDecision.decision.name,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "task-updater",
            logContext = "namespace=${request.namespace.value} source=${request.source.id.value}",
        )

        val rawText = response.messages
            .flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
            .trim()

        log.info {
            "Memory task updater raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${rawText.length} response=${rawText.oneLineForTaskMemoryLog(4_000)}"
        }

        val jsonText = rawText.extractJsonObject()
            ?: throw IllegalStateException("Memory task updater did not return JSON: ${rawText.take(500)}")

        val existingTasks = retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.TaskHit>()
            .map { it.task }
        val ops = json.decodeFromString<TaskUpdaterResponse>(jsonText)
            .operations
            .mapNotNull { it.toOp(request, existingTasks, entityOps) }

        log.info {
            "Memory task updater mapped ops: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${ops.joinToString("|") { "${it.action.name}:target=${it.targetTaskId?.value ?: "null"}:title=${it.task?.title ?: "null"}:${it.reason.oneLineForTaskMemoryLog(180)}" }.ifBlank { "none" }}"
        }

        return ops
    }

    private fun buildTaskUpdaterPrompt(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): String = """
        Memory stage: TaskUpdater v3.
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
        $TASK_UPDATER_PROMPT

        Existing tasks:
        ${retrievedHits.renderExistingTasksForUpdater()}

        Related memory context:
        ${retrievedHits.renderRelatedMemoryForTaskUpdater()}

        Resolved entities:
        ${entityOps.renderResolvedEntitiesForTaskUpdater()}

        TARGET_MESSAGE source data:
        ${request.source.renderLatestTurn()}
    """.trimIndent()

    @Serializable
    private data class TaskUpdaterResponse(
        val operations: List<Operation> = emptyList(),
    )

    @Serializable
    private data class Operation(
        val action: String,
        @SerialName("target_task_id")
        val targetTaskId: String? = null,
        val task: TaskDraft? = null,
        val reason: String = "",
    ) {
        fun toOp(
            request: DirectStructuredMemoryWriteRequest,
            existingTasks: List<MemoryTask>,
            entityOps: List<MemoryEntityCanonicalizationOp>,
        ): MemoryTaskUpdateOp? {
            val target = targetTaskId.toTaskMemoryIdTextOrNull()?.let { MemoryTask.Id(it) }
            if (target != null && existingTasks.none { it.id == target }) {
                return null
            }

            val draft = task?.toDraft(request, entityOps)
            return when (action.trim().lowercase()) {
                "insert" -> draft?.takeIf { !it.evidenceQuote.isNullOrBlank() }?.let {
                    MemoryTaskUpdateOp(
                        action = MemoryTaskUpdateOp.Action.INSERT,
                        task = it,
                        reason = reason.trim().ifBlank { "LLM task updater inserted task" },
                    )
                }

                "update" -> target?.let {
                    MemoryTaskUpdateOp(
                        action = MemoryTaskUpdateOp.Action.UPDATE,
                        targetTaskId = it,
                        task = draft,
                        reason = reason.trim().ifBlank { "LLM task updater updated task" },
                    )
                }

                "close" -> target?.let {
                    MemoryTaskUpdateOp(
                        action = MemoryTaskUpdateOp.Action.CLOSE,
                        targetTaskId = it,
                        task = draft,
                        reason = reason.trim().ifBlank { "LLM task updater closed task" },
                    )
                }

                "cancel" -> target?.let {
                    MemoryTaskUpdateOp(
                        action = MemoryTaskUpdateOp.Action.CANCEL,
                        targetTaskId = it,
                        task = draft,
                        reason = reason.trim().ifBlank { "LLM task updater cancelled task" },
                    )
                }

                "noop" -> MemoryTaskUpdateOp(
                    action = MemoryTaskUpdateOp.Action.NOOP,
                    targetTaskId = target,
                    task = draft,
                    reason = reason.trim().ifBlank { "LLM task updater selected noop" },
                )

                else -> null
            }
        }
    }

    @Serializable
    private data class TaskDraft(
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
            entityOps: List<MemoryEntityCanonicalizationOp>,
        ): MemoryTaskUpdateOp.Draft? {
            val cleanTitle = title.trim().take(180)
            if (cleanTitle.isBlank()) return null

            val quote = evidenceQuote
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" }

            if (quote != null && !request.source.contentText.contains(quote, ignoreCase = true)) {
                return null
            }

            val allowedEntityIds = entityOps.mapNotNullTo(mutableSetOf()) { it.entityId }
            val owner = ownerEntityId.toTaskMemoryIdTextOrNull()
                ?.let { MemoryEntity.Id(it) }
                ?.takeIf { it in allowedEntityIds }
            val assignee = assigneeEntityId.toTaskMemoryIdTextOrNull()
                ?.let { MemoryEntity.Id(it) }
                ?.takeIf { it in allowedEntityIds }
            val related = relatedEntityIds
                .mapNotNull { rawId -> rawId.toTaskMemoryIdTextOrNull()?.let { MemoryEntity.Id(it) } }
                .filter { it in allowedEntityIds }
                .distinct()

            return MemoryTaskUpdateOp.Draft(
                title = cleanTitle,
                scope = scopeJson.toMemoryTaskScope(request, owner ?: assignee ?: related.firstOrNull()),
                description = description?.trim()?.take(1_200)?.takeIf { it.isNotBlank() && it != "null" },
                ownerEntityId = owner,
                assigneeEntityId = assignee,
                status = status.toMemoryTaskStatus(),
                priority = priority.toMemoryTaskPriority(),
                dueAt = dueAt.toTaskInstantOrNull(),
                acceptanceCriteria = acceptanceCriteria.cleanTaskTextList(maxItems = 8, maxChars = 180),
                blockers = blockers.cleanTaskTextList(maxItems = 8, maxChars = 180),
                relatedEntityIds = related,
                confidence = confidence.coerceIn(0.0, 1.0),
                evidenceQuote = quote,
                evidenceKind = evidenceKind.toMemoryTaskEvidenceKind(),
                evidenceReason = evidenceReason.trim().take(400),
            )
        }
    }

    private companion object {
        val TASK_UPDATER_PROMPT = """
            You are TaskUpdater v3 for a long-term AI agent.

            Goal:
            Create or update operational task memory from explicit commitments, deadlines, follow-ups, blockers, or task lifecycle changes in TARGET_MESSAGE.

            Return JSON:
            {
              "operations": [
                {
                  "action": "insert | update | close | cancel | noop",
                  "target_task_id": "existing-task-id-or-null",
                  "task": {
                    "title": "short English action title",
                    "description": "English self-contained task description or null",
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
                    "evidence_reason": "why this source supports the task update"
                  },
                  "reason": "short explanation"
                }
              ]
            }

            Rules:
            - Write title, description, scope text, acceptance criteria, blockers, evidence_reason, and reason in English.
            - Create a task only when TARGET_MESSAGE explicitly creates a future action, commitment, follow-up, deadline, blocker, assignment, or lifecycle update.
            - A plain user question, request for an immediate chat answer, implementation instruction, discussion topic, preference, fact, rationale, or procedure is not automatically a task.
            - Do not create tasks from "we should think about", weak ideas, or memory mechanics chatter unless the target explicitly says to keep a follow-up/task/todo.
            - Prefer one clear task over several vague tasks.
            - Use Existing tasks to update, close, cancel, or deduplicate instead of inserting duplicates.
            - If TARGET_MESSAGE repeats an already open task without new durable details, return noop targeting the existing task.
            - If TARGET_MESSAGE changes scope, acceptance criteria, priority, blockers, assignee, deadline, or wording of an existing task, return update targeting that task.
            - If TARGET_MESSAGE says the task is done, finished, completed, shipped, resolved, or no longer open because work was completed, return close.
            - If TARGET_MESSAGE says the task is cancelled, no longer needed, abandoned, dropped, or should not be done, return cancel.
            - For close/cancel/update, target_task_id must be an existing task id.
            - Convert relative deadlines to absolute ISO-8601 when possible using Current time and Timezone.
            - Use Full thread context to resolve pronouns and omitted subjects, but TARGET_MESSAGE is the only evidence for the task update.
            - evidence_quote must be an exact short substring copied from TARGET_MESSAGE when task is not noop.
            - Use only entity IDs listed in Resolved entities.
            - If TARGET_MESSAGE only asks the assistant to do something now in this chat, return noop unless it explicitly asks to remember/save a follow-up.
            - Prefer noop over task spam.
            - Return valid JSON only.
        """.trimIndent()
    }
}

private fun MemoryRouteDecision.shouldUpdateTasks(): Boolean =
    decision != MemoryRouteDecision.Decision.NOOP &&
        MemorySemanticType.TASK in memoryTypes

private fun List<MemoryStore.SearchHit>.renderExistingTasksForUpdater(): String {
    val rendered = filterIsInstance<MemoryStore.SearchHit.TaskHit>()
        .map { it.task }
        .filter { it.archivedAt == null }
        .joinToString("\n") { task ->
            "- id=${task.id.value}; status=${task.status.name}; priority=${task.priority.name}; title=${task.title}; " +
                "description=${task.description ?: "none"}; due_at=${task.dueAt}; scope=${task.scope.text}; " +
                "acceptance=${task.acceptanceCriteria.joinToString("; ").ifBlank { "none" }}; blockers=${task.blockers.joinToString("; ").ifBlank { "none" }}"
        }

    return rendered.ifBlank { "none" }
}

private fun List<MemoryStore.SearchHit>.renderRelatedMemoryForTaskUpdater(): String {
    val rendered = mapNotNull { hit ->
        when (hit) {
            is MemoryStore.SearchHit.ClaimHit -> "- claim ${hit.claim.id.value}: ${hit.claim.normalizedText}"
            is MemoryStore.SearchHit.NoteHit -> "- note ${hit.note.id.value}: ${hit.note.title}; ${hit.note.summary}"
            is MemoryStore.SearchHit.ProfileHit -> "- profile ${hit.profile.id.value}: ${hit.profile.profileText}"
            is MemoryStore.SearchHit.SourceHit,
            is MemoryStore.SearchHit.EntityHit,
            is MemoryStore.SearchHit.TaskHit,
            is MemoryStore.SearchHit.EpisodeHit,
            is MemoryStore.SearchHit.RunHit,
            -> null
        }
    }

    return rendered.joinToString("\n").ifBlank { "none" }
}

private fun List<MemoryEntityCanonicalizationOp>.renderResolvedEntitiesForTaskUpdater(): String {
    val rendered = filter { it.action != MemoryEntityCanonicalizationOp.Action.NOOP && it.entityId != null }
        .map { op ->
            val entity = op.newEntity
            "- mention=${op.mention}; action=${op.action.name}; id=${op.entityId?.value}; type=${entity?.entityType?.name ?: "existing"}; name=${entity?.canonicalName ?: op.aliasText ?: op.mention}; reason=${op.reason}"
        }
    return rendered.joinToString("\n").ifBlank { "none" }
}

private fun JsonObject?.toMemoryTaskScope(
    request: DirectStructuredMemoryWriteRequest,
    subjectEntityId: MemoryEntity.Id?,
): MemoryScope {
    val kind = stringValueForTask("kind", "scope_kind", "type")
        ?.lowercase()
        ?.replace("-", "_")
    val text = stringValueForTask("text", "scope_text", "label")
        ?.takeIf { it.isNotBlank() }
        ?: when (kind) {
            "conversation", "chat" -> "Conversation-scoped task"
            "environment", "env" -> "Environment-scoped task"
            "document", "doc" -> "Document-scoped task"
            "entity" -> "Entity-scoped task"
            else -> "Namespace-wide task"
        }
    val basis = stringValueForTask("basis").toTaskScopeBasis()

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
            environment = stringValueForTask("environment", "environment_name", "env") ?: text,
            basis = basis,
        )

        "document", "doc" -> MemoryScope.Document(
            text = text,
            documentRef = stringValueForTask("document_ref", "document", "doc_ref") ?: text,
            basis = basis,
        )

        "entity" -> subjectEntityId?.let {
            MemoryScope.Entity(text = text, subjectEntityId = it, basis = basis)
        } ?: MemoryScope.Global(text = text, basis = basis)

        else -> MemoryScope.Global(text = text, basis = basis)
    }
}

private fun JsonObject?.stringValueForTask(vararg keys: String): String? {
    if (this == null) return null
    return keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
    }
}

private fun String?.toTaskScopeBasis(): MemoryScope.Basis =
    when (this?.trim()?.lowercase()) {
        "inferred" -> MemoryScope.Basis.INFERRED
        "summarized", "summary" -> MemoryScope.Basis.SUMMARIZED
        "imported" -> MemoryScope.Basis.IMPORTED
        else -> MemoryScope.Basis.EXPLICIT
    }

private fun String.toMemoryTaskStatus(): MemoryTask.Status =
    when (trim().lowercase().replace("-", "_")) {
        "in_progress", "started" -> MemoryTask.Status.IN_PROGRESS
        "blocked" -> MemoryTask.Status.BLOCKED
        "done", "closed", "complete", "completed" -> MemoryTask.Status.DONE
        "cancelled", "canceled" -> MemoryTask.Status.CANCELLED
        else -> MemoryTask.Status.OPEN
    }

private fun String.toMemoryTaskPriority(): MemoryTask.Priority =
    when (trim().lowercase()) {
        "high" -> MemoryTask.Priority.HIGH
        "low" -> MemoryTask.Priority.LOW
        else -> MemoryTask.Priority.NORMAL
    }

private fun String.toMemoryTaskEvidenceKind(): MemoryEvidenceRef.Kind =
    when (trim().lowercase().replace("-", "_")) {
        "summarized" -> MemoryEvidenceRef.Kind.SUMMARIZED
        "imported" -> MemoryEvidenceRef.Kind.IMPORTED
        "inferred" -> MemoryEvidenceRef.Kind.INFERRED
        "derived_from_note" -> MemoryEvidenceRef.Kind.DERIVED_FROM_NOTE
        else -> MemoryEvidenceRef.Kind.DIRECT
    }

private fun String?.toTaskInstantOrNull(): Instant? {
    val value = this?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    return runCatching { Instant.parse(value) }.getOrNull()
}

private fun String?.toTaskMemoryIdTextOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = value.lowercase()
    if (
        normalized == "null" ||
        normalized == "uuid-or-null" ||
        normalized == "existing-task-id-or-null" ||
        normalized == "resolved-entity-id-or-null" ||
        normalized == "resolved-entity-id"
    ) {
        return null
    }
    return value
}

private fun List<String>.cleanTaskTextList(
    maxItems: Int,
    maxChars: Int,
): List<String> =
    map { it.trim().take(maxChars) }
        .filter { it.isNotBlank() && it != "null" }
        .distinct()
        .take(maxItems)

private fun String.oneLineForTaskMemoryLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) return oneLine
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
