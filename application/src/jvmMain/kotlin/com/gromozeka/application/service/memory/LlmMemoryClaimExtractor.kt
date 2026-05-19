package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryClaimExtractor
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryEvidenceRef
import com.gromozeka.domain.model.memory.MemoryPredicateCatalog
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition
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

class LlmMemoryClaimExtractor(
    private val runtime: AiRuntime,
    private val timezone: String,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryClaimExtractor {
    private val log = KLoggers.logger(this)

    override suspend fun extract(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
        predicateCatalog: MemoryPredicateCatalog,
    ): List<MemoryClaimCandidate> {
        if (!routeDecision.shouldExtractClaims()) {
            log.info {
                "Memory claim extractor skipped: namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "decision=${routeDecision.decision.name}"
            }
            return emptyList()
        }

        log.info {
            "Memory claim extractor call: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "decision=${routeDecision.decision.name} contentChars=${request.source.contentText.length} " +
                "entityOps=${entityOps.size} retrievedHits=${retrievedHits.size} predicateHints=${retrievalPlan.predicateHints.joinToString("|")} " +
                "threadContext=${request.memoryThreadContextSummaryForLog()} runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size}"
        }

        val stageMessages = request.toMemoryStageMessages(
            stageName = "claim-extractor",
            taskPrompt = buildClaimExtractorUserPrompt(
                request = request,
                routeDecision = routeDecision,
                retrievalPlan = retrievalPlan,
                retrievedHits = retrievedHits,
                entityOps = entityOps,
                predicateCatalog = predicateCatalog,
            ),
        )

        val response = runtime.callMemoryStageWithRetry(
            AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.CLAIM_EXTRACTOR_OUTPUT,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.ClaimExtractor,
                    toolContext = mapOf(
                        "memoryClaimExtractor" to true,
                        "memoryNamespace" to request.namespace.value,
                        "memorySourceId" to request.source.id.value,
                        "memoryRouteDecision" to routeDecision.decision.name,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "claim-extractor",
            logContext = "namespace=${request.namespace.value} source=${request.source.id.value}",
        )

        val rawText = response.messages
            .flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
            .trim()

        log.info {
            "Memory claim extractor raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${rawText.length} response=${rawText.oneLineForClaimMemoryLog(4_000)}"
        }

        val jsonText = rawText.extractJsonObject() ?: rawText.extractJsonArray()
            ?: throw IllegalStateException("Claim extractor did not return JSON: ${rawText.take(500)}")

        log.info {
            "Memory claim extractor parsed JSON: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "json=${jsonText.oneLineForClaimMemoryLog(4_000)}"
        }

        val responses = if (jsonText.trimStart().startsWith("{")) {
            json.decodeFromString<ClaimExtractorResponseEnvelope>(jsonText).claims
        } else {
            json.decodeFromString<List<ClaimExtractorResponse>>(jsonText)
        }

        val candidates = responses
            .mapNotNull { it.toClaimCandidate(request, entityOps, predicateCatalog) }

        log.info {
            "Memory claim extractor mapped candidates: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "claims=${candidates.joinToString("|") { it.toDebugString() }.ifBlank { "none" }}"
        }

        return candidates
    }

    private fun buildClaimExtractorUserPrompt(
        request: DirectStructuredMemoryWriteRequest,
        routeDecision: MemoryRouteDecision,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
        entityOps: List<MemoryEntityCanonicalizationOp>,
        predicateCatalog: MemoryPredicateCatalog,
    ): String = """
        Memory stage: ClaimExtractor v3.
        Current time: ${Clock.System.now()}
        Timezone: $timezone
        Namespace: ${request.namespace.value}

        Router decision: ${routeDecision.decision.name}
        Router memory types: ${routeDecision.memoryTypes.joinToString { it.name }}
        Router reason: ${routeDecision.reason}

        Stage instructions:
        $CLAIM_EXTRACTOR_SYSTEM_PROMPT

        Relevant persisted memory for dedup/context only:
        ${retrievedHits.renderRelevantMemory()}

        Resolved entities:
        ${entityOps.renderResolvedEntities()}

        Predicate catalog excerpt:
        ${predicateCatalog.renderForMemoryPrompt(retrievalPlan)}

        TARGET_MESSAGE source data:
        ${request.source.renderLatestTurn()}
    """.trimIndent()

    private fun ClaimExtractorResponse.toClaimCandidate(
        request: DirectStructuredMemoryWriteRequest,
        entityOps: List<MemoryEntityCanonicalizationOp>,
        predicateCatalog: MemoryPredicateCatalog,
    ): MemoryClaimCandidate? {
        val subjectId = subjectEntityId
            .toMemoryRefTextOrNull()
            ?.let { MemoryEntity.Id(it) }
            ?: return null

        if (entityOps.none { it.entityId == subjectId }) {
            return null
        }

        val mappedPredicate = predicate.trim()
        val mappedText = normalizedText.trim()
        if (mappedPredicate.isBlank() || mappedText.isBlank()) {
            return null
        }

        val mappedObjectEntityId = objectEntityId
            .toMemoryRefTextOrNull()
            ?.let { MemoryEntity.Id(it) }

        val mappedObjectValue = objectValueJson
            ?.takeUnless { it is JsonNull }
            ?.takeIf { mappedObjectEntityId == null }

        if (mappedObjectEntityId == null && mappedObjectValue == null) {
            return null
        }

        val predicateDefinition = predicateCatalog.firstOrNull { it.predicate == mappedPredicate }
        if (predicateDefinition != null && !predicateDefinition.acceptsExtractedObject(mappedObjectEntityId, mappedObjectValue)) {
            log.info {
                "Memory claim extractor dropped candidate with object kind mismatch: " +
                    "namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "predicate=${mappedPredicate.oneLineForClaimMemoryLog(120)} " +
                    "expected=${predicateDefinition.objectKind.name} objectEntity=${mappedObjectEntityId?.value ?: "null"} " +
                    "objectValue=${mappedObjectValue?.toString()?.oneLineForClaimMemoryLog(240) ?: "null"}"
            }
            return null
        }

        val mappedEvidenceQuote = evidenceQuote
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null

        if (!request.source.contentText.contains(mappedEvidenceQuote, ignoreCase = true)) {
            log.info {
                "Memory claim extractor dropped candidate without target evidence quote: " +
                    "namespace=${request.namespace.value} source=${request.source.id.value} " +
                    "predicate=${mappedPredicate.oneLineForClaimMemoryLog(120)} " +
                    "quote=${mappedEvidenceQuote.oneLineForClaimMemoryLog(240)}"
            }
            return null
        }

        return MemoryClaimCandidate(
            subjectEntityId = subjectId,
            predicate = mappedPredicate,
            objectEntityId = mappedObjectEntityId,
            objectValue = mappedObjectValue,
            normalizedText = mappedText,
            scope = scopeJson.toMemoryScope(request, subjectId),
            contextText = contextText?.trim()?.takeIf { it.isNotBlank() && it != "null" },
            qualifiers = qualifiersJson ?: JsonObject(emptyMap()),
            confidence = confidence.coerceIn(0.0, 1.0),
            importance = importance.coerceIn(1, 10),
            validFrom = validFrom.toInstantOrNull(),
            validTo = validTo.toInstantOrNull(),
            evidenceQuote = mappedEvidenceQuote,
            evidenceKind = evidenceKind.toMemoryEvidenceKind(),
            evidenceReason = evidenceReason.trim(),
            reason = reason.trim(),
        )
    }

    @Serializable
    private data class ClaimExtractorResponseEnvelope(
        val claims: List<ClaimExtractorResponse> = emptyList(),
    )

    @Serializable
    private data class ClaimExtractorResponse(
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
        val evidenceKind: String = "direct",
        @SerialName("evidence_reason")
        val evidenceReason: String = "",
        val reason: String = "",
    )

    private companion object {
        val CLAIM_EXTRACTOR_SYSTEM_PROMPT = """
            You are ClaimExtractor v3 for a long-term AI agent.

            Goal:
            Extract atomic semantic claims from the latest turns.
            A claim must be precise, reusable, and understandable without the original chat.

            Return a JSON object:
            {
              "claims": [
                {
                  "subject_entity_id": "uuid",
                  "predicate": "snake_case_relation",
                  "object_entity_id": "uuid-or-null",
                  "object_value_json": null,
                  "normalized_text": "stand-alone sentence",
                  "context_text": "string or null",
                  "scope_json": {
                    "kind": "global | conversation | entity | environment | document",
                    "text": "human-readable applicability boundary",
                    "basis": "explicit | inferred | summarized | imported"
                  },
                  "qualifiers_json": {},
                  "confidence": 0.0,
                  "importance": 1,
                  "valid_from": "ISO-8601 or null",
                  "valid_to": "ISO-8601 or null",
                  "evidence_quote": "exact substring from TARGET_MESSAGE or null",
                  "evidence_kind": "direct | summarized | imported | inferred | derived_from_note",
                  "evidence_reason": "why this quote supports this claim",
                  "reason": "short explanation"
                }
              ]
            }

            Rules:
            - One claim = one predicate application.
            - Write predicate, normalized_text, context_text, and reason in English.
            - Use a predicate from Predicate catalog excerpt whenever it fits the claim meaning.
            - Invent a new predicate only when no active catalog predicate captures the same semantic relation.
            - New predicates must be stable snake_case relations, not wording-specific paraphrases of an existing catalog predicate.
            - Do not collapse a named current/default/primary/chosen/backend slot into a generic preference, affinity, or usage predicate; use or create the specific slot predicate.
            - Prefer the most specific durable claim that captures the target message's intended memory. Do not also emit a broader paraphrase that is merely entailed by that specific claim.
            - If a message states a current/default/status value, emit that precise state claim instead of an additional generic preference/affinity claim unless the message independently asserts both.
            - For positive preference or affinity claims, prefer predicate "prefers"; keep category and strength in qualifiers_json, for example category="technology|brand|food|style|tool" and intensity="weak|normal|strong".
            - Do not invent category-specific variants such as "prefers_car_brand" for ordinary preferences; use predicate "prefers" and put category details in qualifiers_json or context_text.
            - A shortlist, candidate set, comparison set, or alternatives under consideration is not a positive preference for each option or for the whole set. Extract a preference only when the target selects, endorses, defaults to, or explicitly prefers one option.
            - "I am choosing between X, Y, and Z", "I am considering X/Y/Z", "my shortlist is X/Y/Z", or "I am comparing X and Y" are not "prefers" claims. If durable, use a constraint/comparison-set claim; otherwise return zero claims.
            - For negative preferences, exclusions, dislikes, "do not recommend", or "avoid" instructions, use predicate "avoids"; include category and polarity="negative" in qualifiers_json when useful.
            - Do not convert negated negative preference into either positive preference or avoidance. "I do not dislike X", "do not treat X as something to avoid", or "X is not an anti-preference" are neutral unless the target message also states an explicit positive or negative preference.
            - For attributed viewpoints such as "Alice thinks X", "Bob believes Y", or "Carol argues Z", prefer predicate "believes" with the person as subject and the viewpoint as a string object.
            - Do not map third-party viewpoints to "prefers" or "avoids" unless the source explicitly says the person prefers, dislikes, wants, or avoids something.
            - For ownership or responsibility assignments such as "Alice owns X", "Bob is responsible for Y", or "Mira owns token rotation", prefer predicate "responsible_for" with the owner as subject and the owned responsibility as object.
            - Keep project/account/service context for responsibility claims in normalized_text, context_text, qualifiers_json, or scope_json. Do not weaken responsibility ownership into "works_on_project".
            - Use "works_on_project" only for plain project association when no more specific responsibility, role, ownership, or project-state claim is available.
            - When the target explicitly replaces the user's current preference/status/decision with a new current value, set qualifiers_json.replaces_previous=true on the new current claim.
            - Do not set replaces_previous=true for historical dated facts where both old and new facts remain valid in their own time windows.
            - Preserve proper names, product names, repo names, file names, and exact quoted values.
            - Use only entity IDs listed in Resolved entities.
            - Follow Predicate catalog object kind. If catalog says object=ENTITY, set object_entity_id and leave object_value_json null. If catalog says object=STRING, leave object_entity_id null and set object_value_json to a JSON string.
            - For project-level facts without a more specific subject entity, use the resolved current project subject entity.
            - Use scope_json to prevent over-generalization.
            - Use entity scope when the fact is about one subject; use global scope for namespace-wide preferences or policies.
            - Use Full thread context and Relevant persisted memory to disambiguate pronouns, corrections, replacement targets, and semantic slot names, but never as evidence for a new claim.
            - If TARGET_MESSAGE updates, replaces, or says "instead of" an older value, normalize the new claim to the same semantic slot/family as the replaced claim when context identifies it.
            - Every returned claim must be supported by TARGET_MESSAGE itself.
            - evidence_quote must be an exact short substring copied from TARGET_MESSAGE source data.
            - Do not use Relevant persisted memory as evidence for a new claim; it is only dedup/context.
            - Do not emit lifecycle/reconciliation predicates such as "supersedes", "replaces", "updates", "retracts", or "corrects" as semantic claims. Emit the current semantic fact only; reconciliation handles old claim status.
            - If TARGET_MESSAGE only asks a question, asks for provenance, doubts prior memory, or discusses memory mechanics without asserting the fact, return zero claims.
            - A current-turn execution command such as "edit it", "clean it up", "run tests", "commit", "push", "do it now", or "finish it" is not a claim about user/project preferences or goals.
            - Do not create "has_goal", "has_constraint", or similar broad claims from a one-off instruction to the assistant. Create a claim only when the target states a reusable rule, stable constraint, preference, current project fact, or durable workflow.
            - If TARGET_MESSAGE explicitly creates, updates, closes, cancels, or asks to keep a follow-up/task/todo, do not duplicate that task as a generic "has_goal" or commitment claim; TaskUpdater owns task lifecycle memory.
            - Emit a claim near a task only when TARGET_MESSAGE also asserts a durable fact independent of the task lifecycle.
            - Example zero-claim targets: "Сотредактируй, я потом посмотрю diff", "Дочисти", "Run the tests", "Commit and push".
            - Example claim target: "For this project, normally edit only gromozeko.dev and update gromozeko.beta by pulling changes into beta."
            - If you cannot quote target text that supports the claim, return zero claims.
            - Do not force soft rationale into claims.
            - Prefer zero claims over low-quality claims.
            - Return valid JSON only.
        """.trimIndent()
    }
}

private fun MemoryRouteDecision.shouldExtractClaims(): Boolean {
    return decision != MemoryRouteDecision.Decision.NOOP &&
        (MemorySemanticType.CLAIM in memoryTypes || MemorySemanticType.PROFILE in memoryTypes)
}

private fun List<MemoryStore.SearchHit>.renderRelevantMemory(): String {
    val policyFilteredHits = MemorySourceRetrievalPolicy.apply(
        hits = this,
        useCase = MemorySourceRetrievalUseCase.WRITE_GROUNDING,
    ).hits

    val rendered = policyFilteredHits.mapNotNull { hit ->
        when (hit) {
            is MemoryStore.SearchHit.NoteHit -> "- note ${hit.note.id.value}: ${hit.note.title}; ${hit.note.summary}"
            is MemoryStore.SearchHit.ClaimHit -> "- claim ${hit.claim.id.value}: ${hit.claim.normalizedText}"
            is MemoryStore.SearchHit.ProfileHit -> "- profile ${hit.profile.id.value}: ${hit.profile.profileText}"
            is MemoryStore.SearchHit.TaskHit -> "- task ${hit.task.id.value}: ${hit.task.title}; ${hit.task.description ?: "no description"}"
            is MemoryStore.SearchHit.EpisodeHit -> "- episode ${hit.episode.id.value}: ${hit.episode.situation}; lesson=${hit.episode.lesson}"
            is MemoryStore.SearchHit.SourceHit -> null
            is MemoryStore.SearchHit.EntityHit -> null
            is MemoryStore.SearchHit.RunHit -> null
        }
    }

    if (rendered.isEmpty()) {
        return "none"
    }

    return rendered.joinToString("\n")
}

private fun List<MemoryEntityCanonicalizationOp>.renderResolvedEntities(): String {
    val rendered = filter { it.action != MemoryEntityCanonicalizationOp.Action.NOOP && it.entityId != null }
        .map { op ->
            val entity = op.newEntity
            "- mention=${op.mention}; action=${op.action.name}; id=${op.entityId?.value}; type=${entity?.entityType?.name ?: "existing"}; name=${entity?.canonicalName ?: op.aliasText ?: op.mention}; reason=${op.reason}"
        }

    if (rendered.isEmpty()) {
        return "none"
    }

    return rendered.joinToString("\n")
}

private fun JsonObject?.toMemoryScope(
    request: DirectStructuredMemoryWriteRequest,
    subjectEntityId: MemoryEntity.Id,
): MemoryScope {
    val kind = stringValue("kind", "scope_kind", "type")
        ?.lowercase()
        ?.replace("-", "_")
    val text = stringValue("text", "scope_text", "label")
        ?.takeIf { it.isNotBlank() }
        ?: when (kind) {
            "global", "namespace", "project" -> "Namespace-wide memory"
            "conversation", "chat" -> "Conversation-scoped memory"
            "environment", "env" -> "Environment-scoped memory"
            "document", "doc" -> "Document-scoped memory"
            else -> "Entity-scoped memory"
        }
    val basis = stringValue("basis").toScopeBasis()

    return when (kind) {
        "global", "namespace", "project" -> MemoryScope.Global(
            text = text,
            basis = basis,
        )

        "conversation", "chat" -> {
            val source = request.source
            if (source is MemorySource.ChatTurn) {
                MemoryScope.Conversation(
                    text = text,
                    conversationId = source.conversationId,
                    basis = basis,
                )
            } else {
                MemoryScope.Global(text = text, basis = basis)
            }
        }

        "environment", "env" -> MemoryScope.Environment(
            text = text,
            environment = stringValue("environment", "environment_name", "env") ?: text,
            basis = basis,
        )

        "document", "doc" -> MemoryScope.Document(
            text = text,
            documentRef = stringValue("document_ref", "document", "doc_ref") ?: text,
            basis = basis,
        )

        else -> MemoryScope.Entity(
            text = text,
            subjectEntityId = subjectEntityId,
            basis = basis,
        )
    }
}

private fun JsonObject?.stringValue(vararg keys: String): String? {
    if (this == null) {
        return null
    }

    return keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
    }
}

private fun String?.toScopeBasis(): MemoryScope.Basis =
    when (this?.trim()?.lowercase()) {
        "inferred" -> MemoryScope.Basis.INFERRED
        "summarized", "summary" -> MemoryScope.Basis.SUMMARIZED
        "imported" -> MemoryScope.Basis.IMPORTED
        else -> MemoryScope.Basis.EXPLICIT
    }

private fun String?.toInstantOrNull(): Instant? {
    val value = this?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    return runCatching { Instant.parse(value) }.getOrNull()
}

private fun String.toMemoryEvidenceKind(): MemoryEvidenceRef.Kind =
    when (trim().lowercase().replace("-", "_")) {
        "summarized" -> MemoryEvidenceRef.Kind.SUMMARIZED
        "imported" -> MemoryEvidenceRef.Kind.IMPORTED
        "inferred" -> MemoryEvidenceRef.Kind.INFERRED
        "derived_from_note" -> MemoryEvidenceRef.Kind.DERIVED_FROM_NOTE
        else -> MemoryEvidenceRef.Kind.DIRECT
    }

private fun String?.toMemoryRefTextOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = value.lowercase()
    if (normalized == "null" || normalized == "uuid-or-null") {
        return null
    }
    return value
}

private fun String.oneLineForClaimMemoryLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) {
        return oneLine
    }
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}

private fun MemoryPredicateDefinition.acceptsExtractedObject(
    objectEntityId: MemoryEntity.Id?,
    objectValue: JsonElement?,
): Boolean =
    when (objectKind) {
        MemoryPredicateDefinition.ObjectValueKind.ENTITY -> objectEntityId != null && objectValue == null
        MemoryPredicateDefinition.ObjectValueKind.STRING ->
            objectEntityId == null && objectValue is JsonPrimitive && objectValue.isString

        MemoryPredicateDefinition.ObjectValueKind.NUMBER ->
            objectEntityId == null &&
                objectValue is JsonPrimitive &&
                !objectValue.isString &&
                objectValue.contentOrNull?.toDoubleOrNull() != null

        MemoryPredicateDefinition.ObjectValueKind.BOOLEAN ->
            objectEntityId == null &&
                objectValue is JsonPrimitive &&
                !objectValue.isString &&
                objectValue.contentOrNull in setOf("true", "false")

        MemoryPredicateDefinition.ObjectValueKind.JSON -> objectEntityId != null || objectValue != null
    }

private fun MemoryClaimCandidate.toDebugString(): String {
    return "subject=${subjectEntityId.value} predicate=$predicate objectEntity=${objectEntityId?.value ?: "null"} " +
        "objectValue=${objectValue?.compactClaimValueForLog() ?: "null"} importance=$importance confidence=$confidence " +
        "validFrom=$validFrom validTo=$validTo scope=${scope.toDebugString()} normalized=${normalizedText.oneLineForClaimMemoryLog(240)} " +
        "context=${contextText?.oneLineForClaimMemoryLog(180) ?: "null"} qualifiers=${qualifiers.compactClaimValueForLog()} " +
        "evidenceKind=${evidenceKind.name} evidenceQuote=${evidenceQuote?.oneLineForClaimMemoryLog(240) ?: "null"} " +
        "evidenceReason=${evidenceReason.oneLineForClaimMemoryLog(220)} " +
        "reason=${reason.oneLineForClaimMemoryLog(220)}"
}

private fun MemoryScope.toDebugString(): String =
    when (this) {
        is MemoryScope.Global -> "global(text=${text.oneLineForClaimMemoryLog(120)},basis=${basis.name})"
        is MemoryScope.Project -> "project(projectId=${projectId.value},text=${text.oneLineForClaimMemoryLog(120)},basis=${basis.name})"
        is MemoryScope.Conversation -> "conversation(conversationId=${conversationId.value},projectId=${projectId?.value ?: "null"},text=${text.oneLineForClaimMemoryLog(120)},basis=${basis.name})"
        is MemoryScope.Entity -> "entity(subject=${subjectEntityId.value},text=${text.oneLineForClaimMemoryLog(120)},basis=${basis.name})"
        is MemoryScope.Environment -> "environment(environment=${environment.oneLineForClaimMemoryLog(120)},text=${text.oneLineForClaimMemoryLog(120)},basis=${basis.name})"
        is MemoryScope.Document -> "document(documentRef=${documentRef.oneLineForClaimMemoryLog(120)},text=${text.oneLineForClaimMemoryLog(120)},basis=${basis.name})"
    }

private fun JsonElement.compactClaimValueForLog(maxChars: Int = 220): String {
    val value = if (this is JsonPrimitive && isString) {
        content
    } else {
        toString()
    }
    return value.oneLineForClaimMemoryLog(maxChars)
}
