package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryNote
import com.gromozeka.domain.model.memory.MemoryNoteCandidate
import com.gromozeka.domain.model.memory.MemoryNoteConstructor
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class LlmMemoryNoteConstructor(
    private val runtime: AiRuntime,
    private val timezone: String,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryNoteConstructor {
    private val log = KLoggers.logger(this)

    override suspend fun construct(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): List<MemoryNoteCandidate> {
        if (!routeDecision.shouldConstructNotes()) {
            log.info {
                "Memory note constructor skipped: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "decision=${routeDecision.decision.name}"
            }
            return emptyList()
        }

        val stageMessages = request.toMemoryStageMessages(
            stageName = "note-constructor",
            taskPrompt = buildNoteConstructorPrompt(request, routeDecision, retrievalPlan, retrievedHits, entityOps),
        )

        log.info {
            "Memory note constructor LLM call: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "decision=${routeDecision.decision.name} contentChars=${request.source.contentText.length} " +
                "entityOps=${entityOps.size} retrievedHits=${retrievedHits.size} threadContext=${request.memoryThreadContextSummaryForLog()} " +
                "runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size} stageMessages=${stageMessages.size}"
        }

        val entityRefValidator = MemoryEntityRefValidator(
            stageName = "NoteConstructor",
            allowedEntityIds = entityOps.mapNotNullTo(mutableSetOf()) { it.entityId },
            entityAliases = entityOps.toEntityRefAliases(),
        )
        val result = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.NOTE_CONSTRUCTOR_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.NoteConstructor,
                    toolContext = mapOf(
                        "memoryNoteConstructor" to true,
                        "memoryNamespace" to request.namespace.value,
                        "memorySourceId" to request.source.id.value,
                        "memoryRouteDecision" to routeDecision.decision.name,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "note-constructor",
            logContext = "namespace=${request.namespace.value} source=${request.source.id.value}",
            jsonRoot = MemoryStructuredJsonRoot.OBJECT_OR_ARRAY,
            parse = { jsonText ->
                val responses = if (jsonText.trimStart().startsWith("{")) {
                    json.decodeFromString<NoteConstructorResponseEnvelope>(jsonText).notes
                } else {
                    json.decodeFromString<List<NoteConstructorResponse>>(jsonText)
                }
                responses.mapIndexedNotNull { index, response ->
                    response.toCandidate(
                        request = request,
                        entityRefValidator = entityRefValidator,
                        candidatePath = "notes[$index]",
                    )
                }
            },
        )

        log.info {
            "Memory note constructor raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${result.rawText.length} response=${result.rawText.oneLineForNoteMemoryLog(4_000)}"
        }

        val candidates = result.value

        log.info {
            "Memory note constructor mapped candidates: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "notes=${candidates.joinToString("|") { it.toDebugString() }.ifBlank { "none" }}"
        }

        return candidates
    }

    private fun buildNoteConstructorPrompt(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
    ): String = """
        Memory stage: NoteConstructor v1.
        Current time: ${Clock.System.now()}
        Timezone: $timezone
        Namespace: ${request.namespace.value}

        Router decision: ${routeDecision.decision.name}
        Router memory types: ${routeDecision.memoryTypes.joinToString { it.name }}
        Router reason: ${routeDecision.reason}

        Stage instructions:
        $NOTE_CONSTRUCTOR_PROMPT

        Relevant persisted memory for dedup/context only:
        ${retrievedHits.renderRelevantNotesAndClaims()}

        Resolved entities:
        ${entityOps.renderResolvedEntitiesForNotes()}

        Retrieval plan context:
        entity_queries=${retrievalPlan.entityQueries.joinToString("|")}
        text_queries=${retrievalPlan.textQueries.joinToString("|")}
        predicate_hints=${retrievalPlan.predicateHints.joinToString("|")}

        TARGET_MESSAGE source data:
        ${request.source.renderLatestTurn()}
    """.trimIndent()

    private fun NoteConstructorResponse.toCandidate(
        request: DirectStructuredMemoryWriteRequest,
        entityRefValidator: MemoryEntityRefValidator,
        candidatePath: String,
    ): MemoryNoteCandidate? {
        val cleanTitle = title.trim().take(160)
        val cleanSummary = summary.trim().take(1_500)
        if (cleanTitle.isBlank() || cleanSummary.isBlank()) return null

        val refs = entityRefs
            .mapIndexed { index, ref ->
                ref.toEntityRef(entityRefValidator, "$candidatePath.entity_refs[$index]")
            }
            .distinctBy { "${it.entityId.value}:${it.role.name}" }

        val quote = evidenceQuote
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null

        if (!MemoryEvidenceQuoteMatcher.matches(request.source.contentText, quote)) {
            log.info {
                "Memory note constructor dropped candidate without target evidence quote: " +
                    "namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "title=${cleanTitle.oneLineForNoteMemoryLog(120)} quote=${quote.oneLineForNoteMemoryLog(240)}"
            }
            return null
        }

        return MemoryNoteCandidate(
            title = cleanTitle,
            summary = cleanSummary,
            scope = scopeJson.toMemoryScopeForNote(request, refs.firstOrNull()?.entityId),
            noteType = noteType.toMemoryNoteType(),
            entityRefs = refs,
            keywords = keywords.cleanTextList(maxItems = 12, maxChars = 80),
            tags = tags.cleanTextList(maxItems = 8, maxChars = 60),
            candidateClaims = JsonArray(candidateClaimHints.take(8)),
            confidence = confidence.coerceIn(0.0, 1.0),
            importance = importance.coerceIn(1, 10),
            validFrom = validFrom.toMemoryInstantOrNull(timezone),
            validTo = validTo.toMemoryInstantOrNull(timezone),
            evidenceQuote = quote,
            evidenceKind = evidenceKind.toMemoryEvidenceKindForNote(),
            evidenceReason = evidenceReason.trim().take(400),
            rationale = rationale.trim().take(600),
        )
    }

    @Serializable
    private data class NoteConstructorResponseEnvelope(
        val notes: List<NoteConstructorResponse> = emptyList(),
    )

    @Serializable
    private data class NoteConstructorResponse(
        val title: String,
        val summary: String,
        @SerialName("note_type")
        val noteType: String = "context",
        @SerialName("scope_json")
        val scopeJson: JsonObject? = null,
        @SerialName("entity_refs")
        val entityRefs: List<EntityRefResponse> = emptyList(),
        val keywords: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        @SerialName("candidate_claim_hints")
        val candidateClaimHints: List<JsonElement> = emptyList(),
        val confidence: Double = 0.0,
        val importance: Int = 5,
        @SerialName("valid_from")
        val validFrom: String? = null,
        @SerialName("valid_to")
        val validTo: String? = null,
        @SerialName("evidence_quote")
        val evidenceQuote: String? = null,
        @SerialName("evidence_kind")
        val evidenceKind: String = "summarized",
        @SerialName("evidence_reason")
        val evidenceReason: String = "",
        val rationale: String = "",
    )

    @Serializable
    private data class EntityRefResponse(
        @SerialName("entity_id")
        val entityId: String,
        val role: String = "mentioned",
    )

    private fun EntityRefResponse.toEntityRef(
        entityRefs: MemoryEntityRefValidator,
        fieldPath: String,
    ): MemoryNote.EntityRef {
        val id = entityRefs.required(entityId, "$fieldPath.entity_id")
        return MemoryNote.EntityRef(
            entityId = id,
            role = role.toMemoryNoteEntityRole(),
        )
    }

    private companion object {
        val NOTE_CONSTRUCTOR_PROMPT = """
            You are NoteConstructor v1 for a long-term AI agent.

            Goal:
            Construct reusable semantic notes from TARGET_MESSAGE when the material is contextual rather than a clean atomic fact.

            A note is for decisions, rationale, design direction, plans, hypotheses, lessons, trade-offs, troubleshooting procedures, and compact document/context digests.
            A note must add reusable meaning. It must not be a generic summary of the target turn.

            Return JSON:
            {
              "notes": [
                {
                  "title": "short English title",
                  "summary": "self-contained English semantic fragment",
                  "note_type": "decision | direction | hypothesis | plan | lesson | doc_digest | context",
                  "scope_json": {
                    "kind": "global | conversation | entity | environment | document | project",
                    "text": "human-readable applicability boundary",
                    "basis": "explicit | inferred | summarized | imported"
                  },
                  "entity_refs": [
                    {"entity_id": "resolved-entity-id", "role": "primary | secondary | mentioned | owner | subject"}
                  ],
                  "keywords": ["short search keyword"],
                  "tags": ["short_tag"],
                  "candidate_claim_hints": [],
                  "confidence": 0.0,
                  "importance": 1,
                  "valid_from": "ISO-8601 or null",
                  "valid_to": "ISO-8601 or null",
                  "evidence_quote": "exact substring from TARGET_MESSAGE",
                  "evidence_kind": "direct | summarized | imported | inferred | derived_from_note",
                  "evidence_reason": "why this source supports the note",
                  "rationale": "why this should be a note, not a claim"
                }
              ]
            }

            Rules:
            - Write title, summary, keywords, tags, evidence_reason, and rationale in English.
            - Preserve proper names, product names, repo names, file names, exact commands, exact identifiers, dates, and quoted terms.
            - Use only entity IDs listed in Resolved entities.
            - Never write raw labels such as "user", "assistant", "project", or "document" into entity_refs. If a note is about the user, use the resolved USER entity id from Resolved entities.
            - For project-level notes without a more specific subject, use the resolved namespace/project subject entity.
            - Use TARGET_MESSAGE as the only evidence. Earlier messages are context only.
            - evidence_quote must be an exact short substring copied from TARGET_MESSAGE source data.
            - Do not construct notes from greetings, phatic chatter, pure questions, one-off commands with no durable rationale, or memory mechanics discussion that contains no reusable project/user decision.
            - Do not construct notes from a single weak uncertain factual observation when source recall is enough and there is no rationale, decision, plan, lesson, or reusable analysis.
            - Do not duplicate a claim candidate as a note when a precise claim is enough.
            - Do use a note when the target captures "why", "how", "we decided", "current approach", trade-offs, working agreements, troubleshooting order, or a design direction.
            - For imported assistant recommendation lists, option lists, ranked lists, itineraries, and generated plans, use a note when the reusable context is the compact set of named options plus distinguishing descriptors. Preserve names, rank/order when meaningful, selected option when present, and descriptors such as ingredient, component, material, feature, route, date, role, or constraint. Do not collapse the note to only the option the user chose.
            - If TARGET_MESSAGE is a document or document section, preserve document title/source/section names and create section-scoped notes for important definitions, component roles, workflows, policies, and lists.
            - For dense technical documents and prompt packs, prefer several strong section-scoped notes over one broad document summary. Keep only reusable notes; do not summarize every paragraph.
            - For one document section, return at most two notes. If a section defines one compact component or policy, return one note.
            - If source metadata force_memory_write=true, create a note when the content is reusable context but not a clean claim/action item/profile update.
            - For named components, include the exact component names in title, summary, keywords, or tags so later retrieval can find the component role.
            - A troubleshooting/procedure note is allowed, but do not create an action item unless the target creates a commitment.
            - Keep each note self-contained: future recall should understand it without the original conversation.
            - Keep summary concise: usually one or two sentences.
            - Prefer zero notes over broad summary spam.
            - Return valid JSON only.
        """.trimIndent()
    }
}

private fun MemoryRouteDecision.shouldConstructNotes(): Boolean =
    decision != MemoryRouteDecision.Decision.NOOP &&
        MemorySemanticType.NOTE in memoryTypes

private fun List<MemoryStore.SearchHit>.renderRelevantNotesAndClaims(): String {
    val rendered = mapNotNull { hit ->
        when (hit) {
            is MemoryStore.SearchHit.NoteHit -> "- note ${hit.note.id.value}: ${hit.note.noteType.name}; ${hit.note.title}; ${hit.note.summary}; scope=${hit.note.scope.text}"
            is MemoryStore.SearchHit.ClaimHit -> "- claim ${hit.claim.id.value}: ${hit.claim.predicate}; ${hit.claim.normalizedText}; scope=${hit.claim.scope.text}"
            is MemoryStore.SearchHit.ActionItemHit -> "- actionItem ${hit.actionItem.id.value}: ${hit.actionItem.status.name}; ${hit.actionItem.title}; ${hit.actionItem.description ?: "no description"}"
            is MemoryStore.SearchHit.ProfileHit -> "- profile ${hit.profile.id.value}: ${hit.profile.profileText}"
            is MemoryStore.SearchHit.EpisodeHit -> "- episode ${hit.episode.id.value}: ${hit.episode.situation}; lesson=${hit.episode.lesson}"
            is MemoryStore.SearchHit.SourceHit,
            is MemoryStore.SearchHit.EntityHit,
            is MemoryStore.SearchHit.RunHit,
            -> null
        }
    }
    return rendered.joinToString("\n").ifBlank { "none" }
}

private fun List<MemoryEntityCanonicalizationOp>.renderResolvedEntitiesForNotes(): String {
    val rendered = filter { it.action != MemoryEntityCanonicalizationOp.Action.NOOP && it.entityId != null }
        .map { op ->
            val entity = op.newEntity
            "- mention=${op.mention}; action=${op.action.name}; id=${op.entityId?.value}; type=${entity?.entityType?.name ?: "existing"}; name=${entity?.canonicalName ?: op.aliasText ?: op.mention}; reason=${op.reason}"
        }
    return rendered.joinToString("\n").ifBlank { "none" }
}

private fun JsonObject?.toMemoryScopeForNote(
    request: DirectStructuredMemoryWriteRequest,
    subjectEntityId: MemoryEntity.Id?,
): MemoryScope {
    val kind = stringValueForNote("kind", "scope_kind", "type")
        ?.lowercase()
        ?.replace("-", "_")
    val text = stringValueForNote("text", "scope_text", "label")
        ?.takeIf { it.isNotBlank() }
        ?: when (kind) {
            "global", "namespace", "project" -> "Namespace-wide memory"
            "conversation", "chat" -> "Conversation-scoped memory"
            "environment", "env" -> "Environment-scoped memory"
            "document", "doc" -> "Document-scoped memory"
            else -> "Entity-scoped memory"
        }
    val basis = stringValueForNote("basis").toScopeBasisForNote()

    return when (kind) {
        "conversation", "chat" -> {
            val source = request.source
            if (source is MemorySource.ChatTurn) {
                MemoryScope.Conversation(
                    text = text,
                    conversationId = source.conversationId,
                    projectId = null,
                    basis = basis,
                )
            } else {
                MemoryScope.Global(text = text, basis = basis)
            }
        }

        "environment", "env" -> MemoryScope.Environment(
            text = text,
            environment = stringValueForNote("environment", "environment_name", "env") ?: text,
            basis = basis,
        )

        "document", "doc" -> MemoryScope.Document(
            text = text,
            documentRef = stringValueForNote("document_ref", "document", "doc_ref") ?: text,
            basis = basis,
        )

        "entity" -> subjectEntityId?.let {
            MemoryScope.Entity(text = text, subjectEntityId = it, basis = basis)
        } ?: MemoryScope.Global(text = text, basis = basis)

        else -> MemoryScope.Global(text = text, basis = basis)
    }
}

private fun JsonObject?.stringValueForNote(vararg keys: String): String? {
    if (this == null) return null
    return keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
    }
}

private fun String?.toScopeBasisForNote(): MemoryScope.Basis =
    when (this?.trim()?.lowercase()) {
        "inferred" -> MemoryScope.Basis.INFERRED
        "summarized", "summary" -> MemoryScope.Basis.SUMMARIZED
        "imported" -> MemoryScope.Basis.IMPORTED
        else -> MemoryScope.Basis.EXPLICIT
    }

private fun String.toMemoryNoteType(): MemoryNote.Type =
    when (trim().lowercase().replace("-", "_")) {
        "decision" -> MemoryNote.Type.DECISION
        "direction" -> MemoryNote.Type.DIRECTION
        "hypothesis" -> MemoryNote.Type.HYPOTHESIS
        "plan" -> MemoryNote.Type.PLAN
        "lesson" -> MemoryNote.Type.LESSON
        "doc_digest" -> MemoryNote.Type.DOC_DIGEST
        else -> MemoryNote.Type.CONTEXT
    }

private fun String.toMemoryNoteEntityRole(): MemoryNote.EntityRef.Role =
    when (trim().lowercase().replace("-", "_")) {
        "primary" -> MemoryNote.EntityRef.Role.PRIMARY
        "secondary" -> MemoryNote.EntityRef.Role.SECONDARY
        "owner" -> MemoryNote.EntityRef.Role.OWNER
        "subject" -> MemoryNote.EntityRef.Role.SUBJECT
        else -> MemoryNote.EntityRef.Role.MENTIONED
    }

private fun String.toMemoryEvidenceKindForNote(): MemoryEvidenceRef.Kind =
    when (trim().lowercase().replace("-", "_")) {
        "direct" -> MemoryEvidenceRef.Kind.DIRECT
        "imported" -> MemoryEvidenceRef.Kind.IMPORTED
        "inferred" -> MemoryEvidenceRef.Kind.INFERRED
        "derived_from_note" -> MemoryEvidenceRef.Kind.DERIVED_FROM_NOTE
        else -> MemoryEvidenceRef.Kind.SUMMARIZED
    }

private fun List<String>.cleanTextList(
    maxItems: Int,
    maxChars: Int,
): List<String> =
    map { it.trim().take(maxChars) }
        .filter { it.isNotBlank() && it != "null" }
        .distinct()
        .take(maxItems)

private fun MemoryNoteCandidate.toDebugString(): String =
    "type=${noteType.name} title=${title.oneLineForNoteMemoryLog(140)} summary=${summary.oneLineForNoteMemoryLog(240)} " +
        "scope=${scope.text.oneLineForNoteMemoryLog(120)} entities=${entityRefs.joinToString(",") { "${it.entityId.value}:${it.role.name}" }} " +
        "importance=$importance confidence=$confidence evidence=${evidenceQuote?.oneLineForNoteMemoryLog(180)} rationale=${rationale.oneLineForNoteMemoryLog(180)}"

private fun String.oneLineForNoteMemoryLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) return oneLine
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}
