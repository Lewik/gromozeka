package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryNoteCandidate
import com.gromozeka.domain.model.memory.MemoryNoteReconciliationOp
import com.gromozeka.domain.model.memory.MemoryNoteReconciler
import com.gromozeka.domain.model.memory.MemoryReconciliationAction
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class LlmMemoryNoteReconciler(
    private val runtime: AiRuntime,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryNoteReconciler {
    private val log = KLoggers.logger(this)

    override suspend fun reconcile(
        request: DirectStructuredMemoryWriteRequest,
        noteCandidates: List<MemoryNoteCandidate>,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): List<MemoryNoteReconciliationOp> {
        if (noteCandidates.isEmpty()) return emptyList()

        val existingNotes = retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.NoteHit>()
            .map { it.note }
            .filter { it.status == MemoryNote.Status.ACTIVE }

        if (existingNotes.isEmpty()) {
            log.info {
                "Memory note reconciler direct insert: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "reason=no_existing_active_notes candidates=${noteCandidates.size}"
            }
            return noteCandidates.map {
                MemoryNoteReconciliationOp(
                    action = MemoryReconciliationAction.INSERT,
                    candidate = it,
                    reason = "No existing active notes were retrieved.",
                )
            }
        }

        val stageMessages = request.toMemoryStageMessages(
            stageName = "note-reconciler",
            taskPrompt = buildNoteReconcilerPrompt(request, noteCandidates, existingNotes),
        )

        log.info {
            "Memory note reconciler LLM call: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "candidates=${noteCandidates.size} existingNotes=${existingNotes.size} " +
                "threadContext=${request.memoryThreadContextSummaryForLog()} stageMessages=${stageMessages.size}"
        }

        val response = runtime.callMemoryStageWithRetry(
            AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxTokens = 3_000,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.NoteReconciler,
                    toolContext = mapOf(
                        "memoryNoteReconciler" to true,
                        "memoryNamespace" to request.namespace.value,
                        "memorySourceId" to request.source.id.value,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "note-reconciler",
            logContext = "namespace=${request.namespace.value} source=${request.source.id.value}",
        )

        val rawText = response.messages
            .flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
            .trim()

        log.info {
            "Memory note reconciler raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${rawText.length} response=${rawText.oneLineForNoteReconcilerLog(4_000)}"
        }

        val jsonText = rawText.extractJsonObject()
            ?: throw IllegalStateException("Memory note reconciler did not return JSON: ${rawText.take(500)}")
        val parsed = json.decodeFromString<NoteReconcilerResponse>(jsonText)
        val ops = parsed.operations.mapNotNull { it.toOp(request, noteCandidates, existingNotes) }

        log.info {
            "Memory note reconciler mapped ops: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${ops.joinToString("|") { "${it.action.name}:target=${it.targetNoteId?.value ?: "null"}:candidate=${it.candidate?.title ?: "null"}:${it.reason.oneLineForNoteReconcilerLog(180)}" }.ifBlank { "none" }}"
        }

        return ops.ifEmpty {
            noteCandidates.map {
                MemoryNoteReconciliationOp(
                    action = MemoryReconciliationAction.INSERT,
                    candidate = it,
                    reason = "Note reconciler returned no valid operations.",
                )
            }
        }
    }

    private fun buildNoteReconcilerPrompt(
        request: DirectStructuredMemoryWriteRequest,
        noteCandidates: List<MemoryNoteCandidate>,
        existingNotes: List<MemoryNote>,
    ): String = """
        Memory stage: NoteReconciler v1.
        Namespace: ${request.namespace.value}

        Decide how candidate notes should update active note memory.

        Return JSON:
        {
          "operations": [
            {
              "action": "insert | noop | supersede | retract | update",
              "candidate_index": 0,
              "target_note_id": "existing-note-id-or-null",
              "patch": null,
              "links_to_create": [
                {
                  "to_note_id": "existing-note-id",
                  "link_type": "supports | contradicts | refines | related | supersedes | derived_from",
                  "link_weight": 1.0
                }
              ],
              "reason": "short explanation"
            }
          ]
        }

        Actions:
        - insert: candidate is new reusable context.
        - noop: candidate duplicates an existing active note or is lower value.
        - supersede: candidate is a better current version of target_note_id.
        - update: patch target_note_id without inserting a new note.
        - retract: target_note_id should no longer be active because TARGET_SOURCE retracts it.

        Rules:
        - Compare semantic meaning, not wording.
        - Do not keep duplicate notes just because titles differ.
        - Prefer updating or superseding an existing note when the candidate refines the same decision, rationale, plan, or procedure.
        - Insert when the candidate captures a distinct reusable semantic fragment.
        - Use noop for summary spam, weak paraphrases, or information already captured by an existing note.
        - Use links_to_create only for strong semantic relations between notes.
        - target_note_id must be one of Existing active notes below or null.
        - candidate_index is zero-based from Candidate notes below.
        - Return one operation per candidate unless TARGET_SOURCE only retracts or updates an existing note.
        - Return valid JSON only.

        TARGET_SOURCE:
        ${request.source.contentText.trim()}

        Candidate notes:
        ${noteCandidates.mapIndexed { index, note -> note.renderForNoteReconciler(index) }.joinToString("\n")}

        Existing active notes:
        ${existingNotes.joinToString("\n") { it.renderForNoteReconciler() }}
    """.trimIndent()

    @Serializable
    private data class NoteReconcilerResponse(
        val operations: List<Operation> = emptyList(),
    )

    @Serializable
    private data class Operation(
        val action: String,
        @SerialName("candidate_index")
        val candidateIndex: Int? = null,
        @SerialName("target_note_id")
        val targetNoteId: String? = null,
        val patch: Patch? = null,
        @SerialName("links_to_create")
        val linksToCreate: List<LinkResponse> = emptyList(),
        val reason: String = "",
    ) {
        fun toOp(
            request: DirectStructuredMemoryWriteRequest,
            candidates: List<MemoryNoteCandidate>,
            existingNotes: List<MemoryNote>,
        ): MemoryNoteReconciliationOp? {
            val candidate = candidateIndex?.let { candidates.getOrNull(it) }
            val target = targetNoteId?.toNoteMemoryIdTextOrNull()?.let { MemoryNote.Id(it) }
            val targetExists = target == null || existingNotes.any { it.id == target }
            if (!targetExists) return null
            val links = linksToCreate.mapNotNull { it.toLinkDraft(existingNotes) }

            return when (action.trim().lowercase()) {
                "insert" -> candidate?.let {
                    MemoryNoteReconciliationOp(
                        action = MemoryReconciliationAction.INSERT,
                        candidate = it,
                        linksToCreate = links,
                        reason = reason.trim().ifBlank { "LLM note reconciler inserted candidate" },
                    )
                }

                "noop" -> MemoryNoteReconciliationOp(
                    action = MemoryReconciliationAction.NOOP,
                    targetNoteId = target,
                    candidate = candidate,
                    reason = reason.trim().ifBlank { "LLM note reconciler selected noop" },
                )

                "supersede" -> {
                    if (target == null || candidate == null) return null
                    MemoryNoteReconciliationOp(
                        action = MemoryReconciliationAction.SUPERSEDE,
                        targetNoteId = target,
                        candidate = candidate,
                        linksToCreate = links,
                        reason = reason.trim().ifBlank { "LLM note reconciler superseded target note" },
                    )
                }

                "retract" -> target?.let {
                    MemoryNoteReconciliationOp(
                        action = MemoryReconciliationAction.RETRACT,
                        targetNoteId = it,
                        reason = reason.trim().ifBlank { "LLM note reconciler retracted target note" },
                    )
                }

                "update" -> target?.let {
                    MemoryNoteReconciliationOp(
                        action = MemoryReconciliationAction.UPDATE,
                        targetNoteId = it,
                        updatedNote = patch?.toPatch(request, existingNotes.firstOrNull { note -> note.id == target }),
                        linksToCreate = links,
                        reason = reason.trim().ifBlank { "LLM note reconciler updated target note" },
                    )
                }

                else -> null
            }
        }
    }

    @Serializable
    private data class Patch(
        val title: String? = null,
        val summary: String? = null,
        @SerialName("scope_json")
        val scopeJson: JsonObject? = null,
        val status: String? = null,
        val maturity: String? = null,
        @SerialName("maturity_score")
        val maturityScore: Double = 0.0,
    ) {
        fun toPatch(
            request: DirectStructuredMemoryWriteRequest,
            target: MemoryNote?,
        ): MemoryNoteReconciliationOp.Patch =
            MemoryNoteReconciliationOp.Patch(
                title = title?.trim()?.take(160)?.takeIf { it.isNotBlank() && it != "null" },
                summary = summary?.trim()?.take(1_500)?.takeIf { it.isNotBlank() && it != "null" },
                scope = scopeJson?.toMemoryScopeForNotePatch(request, target?.anchorEntityId),
                status = status?.toMemoryNoteStatus(),
                maturity = maturity?.toMemoryNoteMaturity(),
                maturityScore = maturityScore.coerceIn(0.0, 1.0),
            )
    }

    @Serializable
    private data class LinkResponse(
        @SerialName("to_note_id")
        val toNoteId: String,
        @SerialName("link_type")
        val linkType: String = "related",
        @SerialName("link_weight")
        val linkWeight: Double = 1.0,
    ) {
        fun toLinkDraft(existingNotes: List<MemoryNote>): MemoryNoteReconciliationOp.LinkDraft? {
            val targetId = toNoteId.toNoteMemoryIdTextOrNull()?.let { MemoryNote.Id(it) } ?: return null
            if (existingNotes.none { it.id == targetId }) return null
            return MemoryNoteReconciliationOp.LinkDraft(
                toNoteId = targetId,
                linkType = linkType.toMemoryNoteLinkType(),
                linkWeight = linkWeight.coerceIn(0.0, 1.0),
            )
        }
    }
}

private fun MemoryNoteCandidate.renderForNoteReconciler(index: Int): String =
    "[$index] type=${noteType.name} title=${title.oneLineForNoteReconcilerLog(160)} summary=${summary.oneLineForNoteReconcilerLog(400)} " +
        "scope=${scope.text.oneLineForNoteReconcilerLog(160)} entities=${entityRefs.joinToString(",") { "${it.entityId.value}:${it.role.name}" }} " +
        "keywords=${keywords.joinToString(",")} tags=${tags.joinToString(",")} evidence=${evidenceQuote?.oneLineForNoteReconcilerLog(220) ?: "null"}"

private fun MemoryNote.renderForNoteReconciler(): String =
    "id=${id.value} type=${noteType.name} status=${status.name} maturity=${maturity.name} title=${title.oneLineForNoteReconcilerLog(160)} " +
        "summary=${summary.oneLineForNoteReconcilerLog(400)} scope=${scope.text.oneLineForNoteReconcilerLog(160)} " +
        "keywords=${keywords.joinToString(",")} tags=${tags.joinToString(",")} useCount=$useCount"

private fun JsonObject.toMemoryScopeForNotePatch(
    request: DirectStructuredMemoryWriteRequest,
    subjectEntityId: MemoryEntity.Id?,
): MemoryScope {
    val kind = stringValueForNotePatch("kind", "scope_kind", "type")
        ?.lowercase()
        ?.replace("-", "_")
    val text = stringValueForNotePatch("text", "scope_text", "label")
        ?.takeIf { it.isNotBlank() }
        ?: "Updated note scope"
    val basis = stringValueForNotePatch("basis").toScopeBasisForNotePatch()

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
            environment = stringValueForNotePatch("environment", "environment_name", "env") ?: text,
            basis = basis,
        )

        "document", "doc" -> MemoryScope.Document(
            text = text,
            documentRef = stringValueForNotePatch("document_ref", "document", "doc_ref") ?: text,
            basis = basis,
        )

        "entity" -> subjectEntityId?.let {
            MemoryScope.Entity(text = text, subjectEntityId = it, basis = basis)
        } ?: MemoryScope.Global(text = text, basis = basis)

        else -> MemoryScope.Global(text = text, basis = basis)
    }
}

private fun JsonObject.stringValueForNotePatch(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
    }

private fun String?.toScopeBasisForNotePatch(): MemoryScope.Basis =
    when (this?.trim()?.lowercase()) {
        "inferred" -> MemoryScope.Basis.INFERRED
        "summarized", "summary" -> MemoryScope.Basis.SUMMARIZED
        "imported" -> MemoryScope.Basis.IMPORTED
        else -> MemoryScope.Basis.EXPLICIT
    }

private fun String.toMemoryNoteStatus(): MemoryNote.Status? =
    when (trim().lowercase().replace("-", "_")) {
        "active" -> MemoryNote.Status.ACTIVE
        "superseded" -> MemoryNote.Status.SUPERSEDED
        "retracted" -> MemoryNote.Status.RETRACTED
        "resolved" -> MemoryNote.Status.RESOLVED
        "stale" -> MemoryNote.Status.STALE
        "candidate" -> MemoryNote.Status.CANDIDATE
        else -> null
    }

private fun String.toMemoryNoteMaturity(): MemoryNote.Maturity? =
    when (trim().lowercase().replace("-", "_")) {
        "fresh" -> MemoryNote.Maturity.FRESH
        "stabilizing" -> MemoryNote.Maturity.STABILIZING
        "mature" -> MemoryNote.Maturity.MATURE
        "consolidated" -> MemoryNote.Maturity.CONSOLIDATED
        else -> null
    }

private fun String.toMemoryNoteLinkType(): MemoryNote.Link.Type =
    when (trim().lowercase().replace("-", "_")) {
        "supports" -> MemoryNote.Link.Type.SUPPORTS
        "contradicts" -> MemoryNote.Link.Type.CONTRADICTS
        "refines" -> MemoryNote.Link.Type.REFINES
        "supersedes" -> MemoryNote.Link.Type.SUPERSEDES
        "derived_from" -> MemoryNote.Link.Type.DERIVED_FROM
        else -> MemoryNote.Link.Type.RELATED
    }

private fun String?.toNoteMemoryIdTextOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = value.lowercase()
    if (normalized == "null" || normalized == "existing-note-id-or-null" || normalized == "existing-note-id") return null
    return value
}

private fun String.oneLineForNoteReconcilerLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) return oneLine
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
