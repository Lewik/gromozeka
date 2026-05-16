package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEpisodeCandidate
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryMaintenanceRequest
import com.gromozeka.domain.model.memory.MemoryNamespaceSnapshot
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryNoteConsolidator
import com.gromozeka.domain.model.memory.MemoryNoteLifecycleOp
import com.gromozeka.domain.model.memory.MemoryProfileProjection
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryTask
import com.gromozeka.domain.model.memory.MemoryTaskUpdateOp
import com.gromozeka.domain.model.memory.NoteConsolidationResult
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class LlmMemoryNoteConsolidator(
    private val runtime: AiRuntime,
    private val timezone: String,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryNoteConsolidator {
    private val log = KLoggers.logger(this)

    override suspend fun consolidate(
        request: MemoryMaintenanceRequest,
        selectedNotes: List<MemoryNote>,
        relatedHits: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): NoteConsolidationResult {
        if (selectedNotes.isEmpty()) {
            return NoteConsolidationResult(summary = "No notes selected for consolidation.")
        }

        val stageMessages = listOf(
            maintenanceTaskMessage(
                stageName = "note-consolidator",
                taskPrompt = buildPrompt(request, selectedNotes, relatedHits, snapshot),
            )
        )

        log.info {
            "Memory note consolidator LLM call: namespace=${request.namespace.value} conversation=${request.conversationId?.value ?: "none"} " +
                "selectedNotes=${selectedNotes.size} relatedHits=${relatedHits.size} runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size}"
        }

        val response = runtime.callMemoryStageWithRetry(
            AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = 6_400,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.NoteConsolidator,
                    toolContext = mapOf(
                        "memoryNoteConsolidator" to true,
                        "memoryNamespace" to request.namespace.value,
                    ),
                ),
            ),
            stageName = "note-consolidator",
            logContext = "namespace=${request.namespace.value}",
        )

        val rawText = response.messages
            .flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
            .trim()

        log.info {
            "Memory note consolidator raw response: namespace=${request.namespace.value} chars=${rawText.length} " +
                "response=${rawText.oneLineForMaintenanceLog(5_000)}"
        }

        val jsonText = rawText.extractJsonObject()
            ?: throw IllegalStateException("Note consolidator did not return JSON: ${rawText.take(500)}")

        val parsed = json.decodeFromString<NoteConsolidatorResponse>(jsonText)
        val selectedById = selectedNotes.associateBy { it.id }
        val allowedEntityIds = snapshot.entities.mapTo(mutableSetOf()) { it.id } +
            selectedNotes.flatMap { note -> note.entityRefs.map { it.entityId } + listOfNotNull(note.anchorEntityId) }
        val existingTaskIds = snapshot.tasks.mapTo(mutableSetOf()) { it.id }

        val claimCandidates = parsed.claimCandidates.mapNotNull {
            it.toClaimCandidate(request, selectedById, allowedEntityIds)
        }
        val taskActions = parsed.taskActions.mapNotNull {
            it.toTaskAction(request, selectedById, existingTaskIds, allowedEntityIds)
        }
        val noteActions = parsed.noteActions.mapNotNull {
            it.toLifecycleOp(selectedById)
        }
        val episodeCandidates = parsed.episodeCandidates.mapNotNull {
            it.toEpisodeCandidate(selectedById, allowedEntityIds)
        }

        val result = NoteConsolidationResult(
            claimCandidates = claimCandidates,
            taskActions = taskActions,
            profileProjection = parsed.profilePatch?.toProfileProjection(),
            episodeCandidates = episodeCandidates,
            noteActions = noteActions,
            summary = parsed.summary.trim(),
        )

        log.info {
            "Memory note consolidator mapped result: namespace=${request.namespace.value} " +
                "claims=${claimCandidates.size} taskActions=${taskActions.size} episodes=${episodeCandidates.size} " +
                "noteActions=${noteActions.size} summary=${result.summary.oneLineForMaintenanceLog(500)}"
        }

        return result
    }

    private fun buildPrompt(
        request: MemoryMaintenanceRequest,
        selectedNotes: List<MemoryNote>,
        relatedHits: List<MemoryStore.SearchHit>,
        snapshot: MemoryNamespaceSnapshot,
    ): String = """
        You are NoteConsolidator v2 for a long-term AI agent memory system.

        Goal:
        Review selected notes and decide whether any of them should now produce durable structured memory.

        Current time: ${Clock.System.now()}
        Timezone: $timezone
        Namespace: ${request.namespace.value}

        Selected notes:
        ${selectedNotes.renderNotesForConsolidation()}

        Entity catalog:
        ${snapshot.entities.renderEntitiesForConsolidation(selectedNotes)}

        Related active claims:
        ${relatedHits.renderRelatedClaimsForConsolidation()}

        Related active notes:
        ${relatedHits.renderRelatedNotesForConsolidation(selectedNotes.mapTo(mutableSetOf()) { it.id })}

        Related tasks:
        ${relatedHits.renderRelatedTasksForConsolidation()}

        Existing profile:
        ${relatedHits.renderRelatedProfilesForConsolidation()}

        Return JSON:
        {
          "claim_candidates": [],
          "task_actions": [],
          "profile_patch": null,
          "episode_candidates": [],
          "note_actions": [
            {
              "note_id": "note-id",
              "action": "keep_active | mark_resolved | mark_stale | supersede | mark_consolidated",
              "reason": "short explanation"
            }
          ],
          "summary": "short summary"
        }

        Rules:
        - Consolidate only when the signal is strong enough.
        - Do not force every note into a claim.
        - Promote to claim when the meaning is stable, queryable, and precise enough.
        - Promote to task only when future action is clearly required and already represented by the selected note.
        - Return one episode_candidate for each selected LESSON note that clearly contains a reusable situation-action-result-lesson pattern.
        - Do not collapse several unrelated selected lessons into one episode_candidate.
        - For episode_candidates, owner_entity_id should be the most specific component/service/person/concept the lesson applies to. Use the project entity only when no more specific owner exists.
        - If a selected note is a useful stable rule but not reusable experience, promote it to claim without creating an episode_candidate.
        - Write claim text, task text, profile text, episode text, and reasons in English.
        - Preserve proper names, component names, repo names, exact identifiers, dates, commands, and quoted terms.
        - Every claim candidate must include origin_note_id from Selected notes.
        - Use only entity ids from Entity catalog.
        - If no entity is better, use the selected note anchor entity.
        - Use object_entity_id for entity objects and object_value_json for scalar/string objects, never both.
        - evidence_quote may be null for derived memory; provenance will use the origin note evidence refs.
        - Existing claims and tasks are dedup/context only.
        - If a selected note already has a matching active claim or task, prefer note_actions with mark_consolidated or keep_active instead of duplicating memory.
        - Use mark_consolidated only when durable memory was created or already exists.
        - Use keep_active for unresolved rationale, hypotheses, weak ideas, or notes that remain useful as notes.
        - Return valid JSON only.
    """.trimIndent()

    @Serializable
    private data class NoteConsolidatorResponse(
        @SerialName("claim_candidates")
        val claimCandidates: List<ClaimCandidateResponse> = emptyList(),
        @SerialName("task_actions")
        val taskActions: List<TaskActionResponse> = emptyList(),
        @SerialName("profile_patch")
        val profilePatch: ProfilePatchResponse? = null,
        @SerialName("episode_candidates")
        val episodeCandidates: List<EpisodeCandidateResponse> = emptyList(),
        @SerialName("note_actions")
        val noteActions: List<NoteActionResponse> = emptyList(),
        val summary: String = "",
    )

    @Serializable
    private data class ClaimCandidateResponse(
        @SerialName("origin_note_id")
        val originNoteId: String,
        @SerialName("subject_entity_id")
        val subjectEntityId: String,
        val predicate: String,
        @SerialName("object_entity_id")
        val objectEntityId: String? = null,
        @SerialName("object_value_json")
        val objectValueJson: JsonElement? = null,
        @SerialName("normalized_text")
        val normalizedText: String,
        @SerialName("context_text")
        val contextText: String? = null,
        @SerialName("scope_json")
        val scopeJson: JsonObject? = null,
        @SerialName("qualifiers_json")
        val qualifiersJson: JsonObject? = null,
        val confidence: Double = 0.0,
        val importance: Int = 5,
        @SerialName("valid_from")
        val validFrom: String? = null,
        @SerialName("valid_to")
        val validTo: String? = null,
        @SerialName("evidence_quote")
        val evidenceQuote: String? = null,
        @SerialName("evidence_kind")
        val evidenceKind: String = "derived_from_note",
        @SerialName("evidence_reason")
        val evidenceReason: String = "",
        val reason: String = "",
    ) {
        fun toClaimCandidate(
            request: MemoryMaintenanceRequest,
            selectedById: Map<MemoryNote.Id, MemoryNote>,
            allowedEntityIds: Set<MemoryEntity.Id>,
        ): MemoryClaimCandidate? {
            val originNote = originNoteId.toNoteIdOrNull()?.let(selectedById::get) ?: return null
            val subjectId = subjectEntityId.toMemoryIdOrNull()?.let { MemoryEntity.Id(it) }
                ?: originNote.anchorEntityId
                ?: originNote.entityRefs.firstOrNull()?.entityId
                ?: return null
            if (subjectId !in allowedEntityIds) return null

            val objectEntity = objectEntityId.toMemoryIdOrNull()
                ?.let { MemoryEntity.Id(it) }
                ?.takeIf { it in allowedEntityIds }
            val objectValue = objectValueJson
                ?.takeUnless { it is JsonNull }
                ?.takeIf { objectEntity == null }

            if ((objectEntity == null) == (objectValue == null)) return null

            val mappedPredicate = predicate.trim()
            val mappedText = normalizedText.trim()
            if (mappedPredicate.isBlank() || mappedText.isBlank()) return null

            return MemoryClaimCandidate(
                subjectEntityId = subjectId,
                predicate = mappedPredicate,
                objectEntityId = objectEntity,
                objectValue = objectValue,
                normalizedText = mappedText,
                scope = scopeJson.toMaintenanceScope(request, subjectId, originNote.scope),
                contextText = contextText.cleanNullableText(1_000),
                qualifiers = qualifiersJson ?: JsonObject(emptyMap()),
                confidence = confidence.coerceIn(0.0, 1.0),
                importance = importance.coerceIn(1, 10),
                validFrom = validFrom.toMaintenanceInstantOrNull(),
                validTo = validTo.toMaintenanceInstantOrNull(),
                evidenceQuote = evidenceQuote.cleanNullableText(500),
                evidenceKind = evidenceKind.toMaintenanceEvidenceKind(),
                evidenceReason = evidenceReason.trim().take(500),
                originNoteId = originNote.id,
                reason = reason.trim().take(800),
            )
        }
    }

    @Serializable
    private data class TaskActionResponse(
        val action: String,
        @SerialName("target_task_id")
        val targetTaskId: String? = null,
        @SerialName("origin_note_id")
        val originNoteId: String? = null,
        val task: TaskDraftResponse? = null,
        val reason: String = "",
    ) {
        fun toTaskAction(
            request: MemoryMaintenanceRequest,
            selectedById: Map<MemoryNote.Id, MemoryNote>,
            existingTaskIds: Set<MemoryTask.Id>,
            allowedEntityIds: Set<MemoryEntity.Id>,
        ): MemoryTaskUpdateOp? {
            val target = targetTaskId.toMemoryIdOrNull()?.let { MemoryTask.Id(it) }
            if (target != null && target !in existingTaskIds) return null

            val originNote = originNoteId.toNoteIdOrNull()?.let(selectedById::get)
            val draft = task?.toDraft(request, originNote, allowedEntityIds)

            return when (action.trim().lowercase()) {
                "insert" -> draft?.let {
                    MemoryTaskUpdateOp(
                        action = MemoryTaskUpdateOp.Action.INSERT,
                        task = it,
                        reason = reason.trim().ifBlank { "Note consolidation inserted task" },
                    )
                }

                "update" -> target?.let {
                    MemoryTaskUpdateOp(
                        action = MemoryTaskUpdateOp.Action.UPDATE,
                        targetTaskId = it,
                        task = draft,
                        reason = reason.trim().ifBlank { "Note consolidation updated task" },
                    )
                }

                "close" -> target?.let {
                    MemoryTaskUpdateOp(
                        action = MemoryTaskUpdateOp.Action.CLOSE,
                        targetTaskId = it,
                        task = draft,
                        reason = reason.trim().ifBlank { "Note consolidation closed task" },
                    )
                }

                "cancel" -> target?.let {
                    MemoryTaskUpdateOp(
                        action = MemoryTaskUpdateOp.Action.CANCEL,
                        targetTaskId = it,
                        task = draft,
                        reason = reason.trim().ifBlank { "Note consolidation cancelled task" },
                    )
                }

                "noop" -> MemoryTaskUpdateOp(
                    action = MemoryTaskUpdateOp.Action.NOOP,
                    targetTaskId = target,
                    task = draft,
                    reason = reason.trim().ifBlank { "Note consolidation selected noop" },
                )

                else -> null
            }
        }
    }

    @Serializable
    private data class TaskDraftResponse(
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
        val evidenceKind: String = "derived_from_note",
        @SerialName("evidence_reason")
        val evidenceReason: String = "",
    ) {
        fun toDraft(
            request: MemoryMaintenanceRequest,
            originNote: MemoryNote?,
            allowedEntityIds: Set<MemoryEntity.Id>,
        ): MemoryTaskUpdateOp.Draft? {
            val cleanTitle = title.trim().take(180)
            if (cleanTitle.isBlank()) return null

            val owner = ownerEntityId.toMemoryIdOrNull()
                ?.let { MemoryEntity.Id(it) }
                ?.takeIf { it in allowedEntityIds }
            val assignee = assigneeEntityId.toMemoryIdOrNull()
                ?.let { MemoryEntity.Id(it) }
                ?.takeIf { it in allowedEntityIds }
            val related = relatedEntityIds
                .mapNotNull { raw -> raw.toMemoryIdOrNull()?.let { MemoryEntity.Id(it) } }
                .filter { it in allowedEntityIds }
                .distinct()
            val scopeSubject = owner ?: assignee ?: related.firstOrNull() ?: originNote?.anchorEntityId

            return MemoryTaskUpdateOp.Draft(
                title = cleanTitle,
                scope = scopeJson.toMaintenanceScope(request, scopeSubject, originNote?.scope ?: MemoryScope.Global("Namespace-wide task")),
                description = description.cleanNullableText(1_200),
                ownerEntityId = owner,
                assigneeEntityId = assignee,
                status = status.toMaintenanceTaskStatus(),
                priority = priority.toMaintenanceTaskPriority(),
                dueAt = dueAt.toMaintenanceInstantOrNull(),
                acceptanceCriteria = acceptanceCriteria.cleanTextList(8, 180),
                blockers = blockers.cleanTextList(8, 180),
                relatedEntityIds = related,
                confidence = confidence.coerceIn(0.0, 1.0),
                evidenceQuote = evidenceQuote.cleanNullableText(500),
                evidenceKind = evidenceKind.toMaintenanceEvidenceKind(),
                evidenceReason = evidenceReason.trim().take(500),
                originNoteId = originNote?.id,
            )
        }
    }

    @Serializable
    private data class ProfilePatchResponse(
        @SerialName("profile_text")
        val profileText: String,
        val reason: String = "",
    ) {
        fun toProfileProjection(): MemoryProfileProjection? {
            val text = profileText.trim().take(4_000)
            if (text.isBlank()) return null
            return MemoryProfileProjection(
                profileText = text,
                reason = reason.trim().take(800),
            )
        }
    }

    @Serializable
    private data class EpisodeCandidateResponse(
        @SerialName("origin_note_id")
        val originNoteId: String,
        @SerialName("owner_entity_id")
        val ownerEntityId: String? = null,
        val situation: String,
        val action: String,
        val result: String,
        val lesson: String,
        val tags: List<String> = emptyList(),
        @SerialName("success_score")
        val successScore: Double = 0.5,
        val reason: String = "",
    ) {
        fun toEpisodeCandidate(
            selectedById: Map<MemoryNote.Id, MemoryNote>,
            allowedEntityIds: Set<MemoryEntity.Id>,
        ): MemoryEpisodeCandidate? {
            val originNote = originNoteId.toNoteIdOrNull()?.let(selectedById::get) ?: return null
            val cleanLesson = lesson.trim().take(1_200)
            if (cleanLesson.isBlank()) return null

            return MemoryEpisodeCandidate(
                ownerEntityId = ownerEntityId.toMemoryIdOrNull()
                    ?.let { MemoryEntity.Id(it) }
                    ?.takeIf { it in allowedEntityIds },
                originNoteId = originNote.id,
                situation = situation.trim().take(1_200),
                action = action.trim().take(1_200),
                result = result.trim().take(1_200),
                lesson = cleanLesson,
                tags = tags.cleanTextList(12, 80),
                successScore = successScore.coerceIn(0.0, 1.0),
                reason = reason.trim().take(800),
            )
        }
    }

    @Serializable
    private data class NoteActionResponse(
        @SerialName("note_id")
        val noteId: String,
        val action: String,
        val reason: String = "",
    ) {
        fun toLifecycleOp(selectedById: Map<MemoryNote.Id, MemoryNote>): MemoryNoteLifecycleOp? {
            val id = noteId.toNoteIdOrNull() ?: return null
            if (id !in selectedById) return null

            val mappedAction = when (action.trim().lowercase()) {
                "keep_active" -> MemoryNoteLifecycleOp.Action.KEEP_ACTIVE
                "mark_resolved" -> MemoryNoteLifecycleOp.Action.MARK_RESOLVED
                "mark_stale" -> MemoryNoteLifecycleOp.Action.MARK_STALE
                "supersede" -> MemoryNoteLifecycleOp.Action.SUPERSEDE
                "mark_consolidated" -> MemoryNoteLifecycleOp.Action.MARK_CONSOLIDATED
                else -> return null
            }

            return MemoryNoteLifecycleOp(
                noteId = id,
                action = mappedAction,
                reason = reason.trim().take(800),
            )
        }
    }
}

private fun maintenanceTaskMessage(
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

private fun List<MemoryNote>.renderNotesForConsolidation(): String =
    joinToString("\n") { note ->
        "- id=${note.id.value}; type=${note.noteType.name}; status=${note.status.name}; maturity=${note.maturity.name}; " +
            "importance=${note.importance}; confidence=${note.confidence}; title=${note.title}; summary=${note.summary}; " +
            "scope=${note.scope.text}; anchor=${note.anchorEntityId?.value ?: "none"}; " +
            "entities=${note.entityRefs.joinToString(";") { "${it.entityId.value}:${it.role.name}" }.ifBlank { "none" }}; " +
            "evidence=${note.evidenceRefs.joinToString(";") { "${it.sourceId.value}:${it.kind.name}:${it.cachedQuote.orEmpty().oneLineForMaintenanceLog(220)}" }.ifBlank { "none" }}; " +
            "candidate_claims=${note.candidateClaimHints}"
    }.ifBlank { "none" }

private fun List<MemoryEntity>.renderEntitiesForConsolidation(selectedNotes: List<MemoryNote>): String {
    val selectedEntityIds = selectedNotes
        .flatMap { note -> note.entityRefs.map { it.entityId } + listOfNotNull(note.anchorEntityId) }
        .toSet()
    val preferred = sortedWith(
        compareByDescending<MemoryEntity> { it.id in selectedEntityIds }
            .thenBy { it.entityType.name }
            .thenBy { it.canonicalName }
    ).take(120)

    return preferred.joinToString("\n") { entity ->
        "- id=${entity.id.value}; type=${entity.entityType.name}; name=${entity.canonicalName}; summary=${entity.summary ?: "none"}"
    }.ifBlank { "none" }
}

private fun List<MemoryStore.SearchHit>.renderRelatedClaimsForConsolidation(): String =
    filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
        .map { it.claim }
        .filter { it.archivedAt == null && it.status == com.gromozeka.domain.model.memory.MemoryClaim.Status.ACTIVE }
        .take(40)
        .joinToString("\n") { claim ->
            "- id=${claim.id.value}; subject=${claim.subjectEntityId.value}; predicate=${claim.predicate}; text=${claim.normalizedText}; " +
                "confidence=${claim.confidence}; importance=${claim.importance}; origin_note=${claim.originNoteId?.value ?: "none"}"
        }.ifBlank { "none" }

private fun List<MemoryStore.SearchHit>.renderRelatedNotesForConsolidation(selectedIds: Set<MemoryNote.Id>): String =
    filterIsInstance<MemoryStore.SearchHit.NoteHit>()
        .map { it.note }
        .filter { it.id !in selectedIds && it.archivedAt == null && it.status == MemoryNote.Status.ACTIVE }
        .take(24)
        .joinToString("\n") { note ->
            "- id=${note.id.value}; type=${note.noteType.name}; maturity=${note.maturity.name}; title=${note.title}; summary=${note.summary}"
        }.ifBlank { "none" }

private fun List<MemoryStore.SearchHit>.renderRelatedTasksForConsolidation(): String =
    filterIsInstance<MemoryStore.SearchHit.TaskHit>()
        .map { it.task }
        .filter { it.archivedAt == null }
        .take(24)
        .joinToString("\n") { task ->
            "- id=${task.id.value}; status=${task.status.name}; priority=${task.priority.name}; title=${task.title}; description=${task.description ?: "none"}"
        }.ifBlank { "none" }

private fun List<MemoryStore.SearchHit>.renderRelatedProfilesForConsolidation(): String =
    filterIsInstance<MemoryStore.SearchHit.ProfileHit>()
        .map { it.profile }
        .take(12)
        .joinToString("\n") { profile ->
            "- id=${profile.id.value}; owner=${profile.ownerEntityId.value}; text=${profile.profileText.oneLineForMaintenanceLog(1_000)}"
        }.ifBlank { "none" }

private fun JsonObject?.toMaintenanceScope(
    request: MemoryMaintenanceRequest,
    subjectEntityId: MemoryEntity.Id?,
    fallback: MemoryScope,
): MemoryScope {
    val kind = stringValueForMaintenance("kind", "scope_kind", "type")
        ?.lowercase()
        ?.replace("-", "_")
    val text = stringValueForMaintenance("text", "scope_text", "label")
        ?.takeIf { it.isNotBlank() }
        ?: fallback.text
    val basis = stringValueForMaintenance("basis").toMaintenanceScopeBasis()

    return when (kind) {
        "global", "namespace", "project" -> MemoryScope.Global(text = text, basis = basis)
        "conversation", "chat" -> request.conversationId?.let {
            MemoryScope.Conversation(text = text, conversationId = it, basis = basis)
        } ?: fallback
        "environment", "env" -> MemoryScope.Environment(
            text = text,
            environment = stringValueForMaintenance("environment", "environment_name", "env") ?: text,
            basis = basis,
        )
        "document", "doc" -> MemoryScope.Document(
            text = text,
            documentRef = stringValueForMaintenance("document_ref", "document", "doc_ref") ?: text,
            basis = basis,
        )
        "entity" -> subjectEntityId?.let {
            MemoryScope.Entity(text = text, subjectEntityId = it, basis = basis)
        } ?: fallback
        else -> fallback
    }
}

private fun JsonObject?.stringValueForMaintenance(vararg keys: String): String? {
    if (this == null) return null
    return keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
    }
}

private fun String?.toMaintenanceScopeBasis(): MemoryScope.Basis =
    when (this?.trim()?.lowercase()) {
        "inferred" -> MemoryScope.Basis.INFERRED
        "summarized", "summary" -> MemoryScope.Basis.SUMMARIZED
        "imported" -> MemoryScope.Basis.IMPORTED
        else -> MemoryScope.Basis.EXPLICIT
    }

private fun String.toMaintenanceEvidenceKind(): MemoryEvidenceRef.Kind =
    when (trim().lowercase().replace("-", "_")) {
        "summarized" -> MemoryEvidenceRef.Kind.SUMMARIZED
        "imported" -> MemoryEvidenceRef.Kind.IMPORTED
        "inferred" -> MemoryEvidenceRef.Kind.INFERRED
        "derived_from_note" -> MemoryEvidenceRef.Kind.DERIVED_FROM_NOTE
        else -> MemoryEvidenceRef.Kind.DIRECT
    }

private fun String.toMaintenanceTaskStatus(): MemoryTask.Status =
    when (trim().lowercase().replace("-", "_")) {
        "in_progress", "started" -> MemoryTask.Status.IN_PROGRESS
        "blocked" -> MemoryTask.Status.BLOCKED
        "done", "closed", "complete", "completed" -> MemoryTask.Status.DONE
        "cancelled", "canceled" -> MemoryTask.Status.CANCELLED
        else -> MemoryTask.Status.OPEN
    }

private fun String.toMaintenanceTaskPriority(): MemoryTask.Priority =
    when (trim().lowercase()) {
        "high" -> MemoryTask.Priority.HIGH
        "low" -> MemoryTask.Priority.LOW
        else -> MemoryTask.Priority.NORMAL
    }

private fun String?.toMemoryIdOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = value.lowercase()
    if (
        normalized == "null" ||
        normalized == "uuid" ||
        normalized == "uuid-or-null" ||
        normalized == "note-id" ||
        normalized == "resolved-entity-id-or-null" ||
        normalized == "existing-task-id-or-null"
    ) {
        return null
    }
    return value
}

private fun String?.toNoteIdOrNull(): MemoryNote.Id? =
    toMemoryIdOrNull()?.let { MemoryNote.Id(it) }

private fun String?.toMaintenanceInstantOrNull(): Instant? {
    val value = cleanNullableText(80) ?: return null
    return runCatching { Instant.parse(value) }.getOrNull()
}

private fun String?.cleanNullableText(maxChars: Int): String? =
    this?.trim()
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?.take(maxChars)

private fun List<String>.cleanTextList(maxItems: Int, maxChars: Int): List<String> =
    map { it.trim().take(maxChars) }
        .filter { it.isNotBlank() && it != "null" }
        .distinct()
        .take(maxItems)

private fun String.oneLineForMaintenanceLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
