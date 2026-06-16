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
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.domain.model.memory.MemoryActionItemUpdateOp
import com.gromozeka.domain.model.memory.NoteConsolidationResult
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
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

        val selectedById = selectedNotes.associateBy { it.id }
        val allowedEntityIds = snapshot.entities.mapTo(mutableSetOf()) { it.id } +
            selectedNotes.flatMap { note -> note.entityRefs.map { it.entityId } + listOfNotNull(note.anchorEntityId) }
        val existingTaskIds = snapshot.actionItems.mapTo(mutableSetOf()) { it.id }
        val entityRefValidator = MemoryEntityRefValidator(
            stageName = "NoteConsolidator",
            allowedEntityIds = allowedEntityIds,
        )

        val structuredResult = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.NOTE_CONSOLIDATOR_OUTPUT,
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
            parse = { jsonText ->
                val parsed = json.decodeFromString<NoteConsolidatorResponse>(jsonText)
                parsed.toMappedResult(
                    request = request,
                    selectedById = selectedById,
                    existingTaskIds = existingTaskIds,
                    entityRefs = entityRefValidator,
                    timezone = timezone,
                )
            },
        )

        log.info {
            "Memory note consolidator raw response: namespace=${request.namespace.value} chars=${structuredResult.rawText.length} " +
                "response=${structuredResult.rawText.oneLineForMaintenanceLog(5_000)}"
        }

        val mapped = structuredResult.value
        val result = NoteConsolidationResult(
            claimCandidates = mapped.claimCandidates,
            actionItemActions = mapped.actionItemActions,
            profileProjection = mapped.profileProjection,
            episodeCandidates = mapped.episodeCandidates,
            noteActions = mapped.noteActions,
            summary = mapped.summary,
        )

        log.info {
            "Memory note consolidator mapped result: namespace=${request.namespace.value} " +
                "claims=${mapped.claimCandidates.size} actionItemActions=${mapped.actionItemActions.size} episodes=${mapped.episodeCandidates.size} " +
                "noteActions=${mapped.noteActions.size} summary=${result.summary.oneLineForMaintenanceLog(500)}"
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

        Related action items:
        ${relatedHits.renderRelatedTasksForConsolidation()}

        Existing profile:
        ${relatedHits.renderRelatedProfilesForConsolidation()}

        Return JSON:
        {
          "claim_candidates": [],
          "action_item_actions": [],
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
        - Promote to action_item only when future action is clearly required and already represented by the selected note.
        - Return one episode_candidate for each selected LESSON note that clearly contains a reusable situation-action-result-lesson pattern.
        - Do not collapse several unrelated selected lessons into one episode_candidate.
        - For episode_candidates, owner_entity_id should be the most specific component/service/person/concept the lesson applies to. Use the project entity only when no more specific owner exists.
        - If a selected note is a useful stable rule but not reusable experience, promote it to claim without creating an episode_candidate.
        - Write claim text, action item text, profile text, episode text, and reasons in English.
        - Preserve proper names, component names, repo names, exact identifiers, dates, commands, and quoted terms.
        - Every claim candidate must include origin_note_id from Selected notes.
        - Use only entity ids from Entity catalog.
        - If no entity is better, use the selected note anchor entity.
        - Use object_entity_id for entity objects and object_value_json for scalar/string objects, never both.
        - evidence_quote may be null for derived memory; provenance will use the origin note evidence refs.
        - Existing claims and action items are dedup/context only.
        - If a selected note already has a matching active claim or action item, prefer note_actions with mark_consolidated or keep_active instead of duplicating memory.
        - Use mark_consolidated only when durable memory was created or already exists.
        - Use keep_active for unresolved rationale, hypotheses, weak ideas, or notes that remain useful as notes.
        - Return valid JSON only.
    """.trimIndent()

    @Serializable
    private data class NoteConsolidatorResponse(
        @SerialName("claim_candidates")
        val claimCandidates: List<ClaimCandidateResponse> = emptyList(),
        @SerialName("action_item_actions")
        val actionItemActions: List<TaskActionResponse> = emptyList(),
        @SerialName("profile_patch")
        val profilePatch: ProfilePatchResponse? = null,
        @SerialName("episode_candidates")
        val episodeCandidates: List<EpisodeCandidateResponse> = emptyList(),
        @SerialName("note_actions")
        val noteActions: List<NoteActionResponse> = emptyList(),
        val summary: String = "",
    ) {
        fun toMappedResult(
            request: MemoryMaintenanceRequest,
            selectedById: Map<MemoryNote.Id, MemoryNote>,
            existingTaskIds: Set<MemoryActionItem.Id>,
            entityRefs: MemoryEntityRefValidator,
            timezone: String,
        ): MappedNoteConsolidationResult =
            MappedNoteConsolidationResult(
                claimCandidates = claimCandidates.mapIndexedNotNull { index, candidate ->
                    candidate.toClaimCandidate(
                        request = request,
                        selectedById = selectedById,
                        entityRefs = entityRefs,
                        candidatePath = "claim_candidates[$index]",
                        timezone = timezone,
                    )
                },
                actionItemActions = actionItemActions.mapIndexedNotNull { index, action ->
                    action.toTaskAction(
                        request = request,
                        selectedById = selectedById,
                        existingTaskIds = existingTaskIds,
                        entityRefs = entityRefs,
                        actionPath = "action_item_actions[$index]",
                        timezone = timezone,
                    )
                },
                profileProjection = profilePatch?.toProfileProjection(),
                episodeCandidates = episodeCandidates.mapIndexedNotNull { index, candidate ->
                    candidate.toEpisodeCandidate(
                        selectedById = selectedById,
                        entityRefs = entityRefs,
                        candidatePath = "episode_candidates[$index]",
                    )
                },
                noteActions = noteActions.mapNotNull { it.toLifecycleOp(selectedById) },
                summary = summary.trim(),
            )
    }

    private data class MappedNoteConsolidationResult(
        val claimCandidates: List<MemoryClaimCandidate>,
        val actionItemActions: List<MemoryActionItemUpdateOp>,
        val profileProjection: MemoryProfileProjection?,
        val episodeCandidates: List<MemoryEpisodeCandidate>,
        val noteActions: List<MemoryNoteLifecycleOp>,
        val summary: String,
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
            entityRefs: MemoryEntityRefValidator,
            candidatePath: String,
            timezone: String,
        ): MemoryClaimCandidate? {
            val originNote = originNoteId.toNoteIdOrNull()?.let(selectedById::get) ?: return null
            val subjectId = entityRefs.optional(subjectEntityId, "$candidatePath.subject_entity_id")
                ?: originNote.anchorEntityId
                ?: originNote.entityRefs.firstOrNull()?.entityId
                ?: return null

            val objectEntity = entityRefs.optional(objectEntityId, "$candidatePath.object_entity_id")
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
                validFrom = validFrom.toMemoryInstantOrNull(timezone),
                validTo = validTo.toMemoryInstantOrNull(timezone),
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
        @SerialName("target_action_item_id")
        val targetActionItemId: String? = null,
        @SerialName("origin_note_id")
        val originNoteId: String? = null,
        @SerialName("action_item")
        val actionItem: ActionItemDraftResponse? = null,
        val reason: String = "",
    ) {
        fun toTaskAction(
            request: MemoryMaintenanceRequest,
            selectedById: Map<MemoryNote.Id, MemoryNote>,
            existingTaskIds: Set<MemoryActionItem.Id>,
            entityRefs: MemoryEntityRefValidator,
            actionPath: String,
            timezone: String,
        ): MemoryActionItemUpdateOp? {
            val target = targetActionItemId.toMemoryIdOrNull()?.let { MemoryActionItem.Id(it) }
            if (target != null && target !in existingTaskIds) return null

            val originNote = originNoteId.toNoteIdOrNull()?.let(selectedById::get)
            val draft = actionItem?.toDraft(request, originNote, entityRefs, "$actionPath.action_item", timezone)

            return when (action.trim().lowercase()) {
                "insert" -> draft?.let {
                    MemoryActionItemUpdateOp(
                        action = MemoryActionItemUpdateOp.Action.INSERT,
                        actionItem = it,
                        reason = reason.trim().ifBlank { "Note consolidation inserted action item" },
                    )
                }

                "update" -> target?.let {
                    MemoryActionItemUpdateOp(
                        action = MemoryActionItemUpdateOp.Action.UPDATE,
                        targetActionItemId = it,
                        actionItem = draft,
                        reason = reason.trim().ifBlank { "Note consolidation updated action item" },
                    )
                }

                "close" -> target?.let {
                    MemoryActionItemUpdateOp(
                        action = MemoryActionItemUpdateOp.Action.CLOSE,
                        targetActionItemId = it,
                        actionItem = draft,
                        reason = reason.trim().ifBlank { "Note consolidation closed action item" },
                    )
                }

                "cancel" -> target?.let {
                    MemoryActionItemUpdateOp(
                        action = MemoryActionItemUpdateOp.Action.CANCEL,
                        targetActionItemId = it,
                        actionItem = draft,
                        reason = reason.trim().ifBlank { "Note consolidation cancelled action item" },
                    )
                }

                "noop" -> MemoryActionItemUpdateOp(
                    action = MemoryActionItemUpdateOp.Action.NOOP,
                    targetActionItemId = target,
                    actionItem = draft,
                    reason = reason.trim().ifBlank { "Note consolidation selected noop" },
                )

                else -> null
            }
        }
    }

    @Serializable
    private data class ActionItemDraftResponse(
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
            entityRefs: MemoryEntityRefValidator,
            actionItemPath: String,
            timezone: String,
        ): MemoryActionItemUpdateOp.Draft? {
            val cleanTitle = title.trim().take(180)
            if (cleanTitle.isBlank()) return null

            val owner = entityRefs.optional(ownerEntityId, "$actionItemPath.owner_entity_id")
            val assignee = entityRefs.optional(assigneeEntityId, "$actionItemPath.assignee_entity_id")
            val related = entityRefs.optionalList(relatedEntityIds, "$actionItemPath.related_entity_ids")
            val scopeSubject = owner ?: assignee ?: related.firstOrNull() ?: originNote?.anchorEntityId

            return MemoryActionItemUpdateOp.Draft(
                title = cleanTitle,
                scope = scopeJson.toMaintenanceScope(request, scopeSubject, originNote?.scope ?: MemoryScope.Global("Namespace-wide action item")),
                description = description.cleanNullableText(1_200),
                ownerEntityId = owner,
                assigneeEntityId = assignee,
                status = status.toMaintenanceTaskStatus(),
                priority = priority.toMaintenanceTaskPriority(),
                dueAt = dueAt.toMemoryInstantOrNull(timezone),
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
            entityRefs: MemoryEntityRefValidator,
            candidatePath: String,
        ): MemoryEpisodeCandidate? {
            val originNote = originNoteId.toNoteIdOrNull()?.let(selectedById::get) ?: return null
            val cleanLesson = lesson.trim().take(1_200)
            if (cleanLesson.isBlank()) return null

            return MemoryEpisodeCandidate(
                ownerEntityId = entityRefs.optional(ownerEntityId, "$candidatePath.owner_entity_id"),
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
    filterIsInstance<MemoryStore.SearchHit.ActionItemHit>()
        .map { it.actionItem }
        .filter { it.archivedAt == null }
        .take(24)
        .joinToString("\n") { actionItem ->
            "- id=${actionItem.id.value}; status=${actionItem.status.name}; priority=${actionItem.priority.name}; title=${actionItem.title}; description=${actionItem.description ?: "none"}"
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

private fun String.toMaintenanceTaskStatus(): MemoryActionItem.Status =
    when (trim().lowercase().replace("-", "_")) {
        "in_progress", "started" -> MemoryActionItem.Status.IN_PROGRESS
        "blocked" -> MemoryActionItem.Status.BLOCKED
        "done", "closed", "complete", "completed" -> MemoryActionItem.Status.DONE
        "cancelled", "canceled" -> MemoryActionItem.Status.CANCELLED
        else -> MemoryActionItem.Status.OPEN
    }

private fun String.toMaintenanceTaskPriority(): MemoryActionItem.Priority =
    when (trim().lowercase()) {
        "high" -> MemoryActionItem.Priority.HIGH
        "low" -> MemoryActionItem.Priority.LOW
        else -> MemoryActionItem.Priority.NORMAL
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
        normalized == "existing-actionitem-id-or-null" ||
        normalized == "existing-action-item-id-or-null"
    ) {
        return null
    }
    return value
}

private fun String?.toNoteIdOrNull(): MemoryNote.Id? =
    toMemoryIdOrNull()?.let { MemoryNote.Id(it) }

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
