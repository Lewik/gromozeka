package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizer
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class LlmMemoryEntityCanonicalizer(
    private val runtime: AiRuntime,
    private val runtimeSystemPrompts: List<String>,
    private val runtimeTools: List<AiToolCallback>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemoryEntityCanonicalizer {
    private val log = KLoggers.logger(this)

    override suspend fun canonicalize(
        request: DirectStructuredMemoryWriteRequest,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): List<MemoryEntityCanonicalizationOp> {
        log.info {
            "Memory entity canonicalizer call: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "contentChars=${request.source.contentText.length} candidateEntityHits=${retrievedHits.filterIsInstance<MemoryStore.SearchHit.EntityHit>().size} " +
                "entityQueries=${retrievalPlan.entityQueries.joinToString("|")} " +
                "threadContext=${request.memoryThreadContextSummaryForLog()} runtimeSystemPrompts=${runtimeSystemPrompts.size} runtimeTools=${runtimeTools.size}"
        }

        val stageMessages = request.toMemoryStageMessages(
            stageName = "entity-canonicalizer",
            taskPrompt = buildCanonicalizerUserPrompt(request, retrievalPlan, retrievedHits),
        )

        val response = runtime.callMemoryStageWithRetry(
            AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxTokens = 2_000,
                    toolChoice = AiToolChoice.None,
                    responseFormat = MemoryStructuredResponseFormats.EntityCanonicalizer,
                    toolContext = mapOf(
                        "memoryEntityCanonicalizer" to true,
                        "memoryNamespace" to request.namespace.value,
                        "memorySourceId" to request.source.id.value,
                    ) + request.conversationToolContext(),
                ),
            ),
            stageName = "entity-canonicalizer",
            logContext = "namespace=${request.namespace.value} source=${request.source.id.value}",
        )

        val rawText = response.messages
            .flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
            .trim()

        log.info {
            "Memory entity canonicalizer raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${rawText.length} response=${rawText.oneLineForLlmMemoryLog(4_000)}"
        }

        val jsonText = rawText.extractJsonObject() ?: rawText.extractJsonArray()
            ?: throw IllegalStateException("Entity canonicalizer did not return JSON: ${rawText.take(500)}")

        log.info {
            "Memory entity canonicalizer parsed JSON: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "json=${jsonText.oneLineForLlmMemoryLog(4_000)}"
        }

        val responses = if (jsonText.trimStart().startsWith("{")) {
            json.decodeFromString<EntityCanonicalizerResponseEnvelope>(jsonText).operations
        } else {
            json.decodeFromString<List<EntityCanonicalizerResponse>>(jsonText)
        }

        val ops = responses
            .mapNotNull { it.toOp(request) }
            .normalizeStableUserOps(request, retrievedHits)

        log.info {
            "Memory entity canonicalizer mapped ops: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "ops=${ops.joinToString("|") { it.toDebugString() }.ifBlank { "none" }}"
        }

        return ops
    }

    private fun buildCanonicalizerUserPrompt(
        request: DirectStructuredMemoryWriteRequest,
        retrievalPlan: MemoryWriteRetrievalPlan,
        retrievedHits: List<MemoryStore.SearchHit>,
    ): String = """
        Memory stage: EntityCanonicalizer v1.
        Current time: ${Clock.System.now()}
        Namespace: ${request.namespace.value}

        Stage instructions:
        $ENTITY_CANONICALIZER_SYSTEM_PROMPT

        Detected raw mentions:
        ${request.source.renderRawMentionHint()}

        Candidate existing entities:
        ${retrievedHits.renderCandidateEntities()}

        Retrieval plan context:
        entity_queries=${retrievalPlan.entityQueries.joinToString("|")}
        text_queries=${retrievalPlan.textQueries.joinToString("|")}
        predicate_hints=${retrievalPlan.predicateHints.joinToString("|")}

        TARGET_MESSAGE source data:
        ${request.source.renderLatestTurn()}
    """.trimIndent()

    private fun MemorySource.renderRawMentionHint(): String {
        return """
            No deterministic mention detector is available in this MVP.
            Infer only concrete reusable referents from the latest turns.
            For first-person USER statements, create or link a stable USER entity when claims need a subject.
        """.trimIndent()
    }

    private fun EntityCanonicalizerResponse.toOp(
        request: DirectStructuredMemoryWriteRequest,
    ): MemoryEntityCanonicalizationOp? {
        val normalizedMention = mention.trim()
        if (normalizedMention.isBlank()) {
            return null
        }

        val mappedAction = action.toCanonicalizationAction()
        val mappedNewEntity = newEntity?.toNewEntity()
        val mappedEntityId = entityId
            .toMemoryIdTextOrNull()
            ?.let { MemoryEntity.Id(it) }
            ?: mappedAction.provisionalEntityId(request, mappedNewEntity)

        return MemoryEntityCanonicalizationOp(
            mention = normalizedMention,
            action = mappedAction,
            entityId = mappedEntityId,
            newEntity = mappedNewEntity,
            aliasText = aliasText.toMemoryIdTextOrNull(),
            confidence = confidence.coerceIn(0.0, 1.0),
            reason = reason.trim(),
        )
    }

    private fun MemoryEntityCanonicalizationOp.Action.provisionalEntityId(
        request: DirectStructuredMemoryWriteRequest,
        newEntity: MemoryEntityCanonicalizationOp.NewEntity?,
    ): MemoryEntity.Id? {
        if (this != MemoryEntityCanonicalizationOp.Action.CREATE_NEW || newEntity == null) {
            return null
        }

        val stableKey = "${request.namespace.value}|${newEntity.entityType.name}|${newEntity.canonicalName.normalizeCanonicalMemoryText()}"
        val hash = MessageDigest
            .getInstance("SHA-256")
            .digest(stableKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

        return MemoryEntity.Id("entity:$hash")
    }

    private fun NewEntityResponse.toNewEntity(): MemoryEntityCanonicalizationOp.NewEntity? {
        val name = canonicalName.trim()
        if (name.isBlank()) {
            return null
        }

        return MemoryEntityCanonicalizationOp.NewEntity(
            entityType = entityType.toMemoryEntityType(),
            canonicalName = name,
            summary = summary?.trim()?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    @Serializable
    private data class EntityCanonicalizerResponseEnvelope(
        val operations: List<EntityCanonicalizerResponse> = emptyList(),
    )

    @Serializable
    private data class EntityCanonicalizerResponse(
        val mention: String,
        val action: String,
        @SerialName("entity_id")
        val entityId: String? = null,
        @SerialName("new_entity")
        val newEntity: NewEntityResponse? = null,
        @SerialName("alias_text")
        val aliasText: String? = null,
        val confidence: Double = 0.0,
        val reason: String = "",
    )

    @Serializable
    private data class NewEntityResponse(
        @SerialName("entity_type")
        val entityType: String = "other",
        @SerialName("canonical_name")
        val canonicalName: String,
        val summary: String? = null,
    )

    private companion object {
        val ENTITY_CANONICALIZER_SYSTEM_PROMPT = """
            You are EntityCanonicalizer v1.

            Goal:
            Resolve entity mentions from the latest turns into canonical entities.

            Return JSON object:
            {
              "operations": [
                {
                  "mention": "text",
                  "action": "link_existing | create_new | add_alias | noop",
                  "entity_id": "uuid-or-null",
                  "new_entity": {
                    "entity_type": "user | person | agent | organization | project | repo | file | technology | product | location | concept | document | conversation | service | environment | other",
                    "canonical_name": "text",
                    "summary": "text-or-null"
                  },
                  "alias_text": "text-or-null",
                  "confidence": 0.0,
                  "reason": "short explanation"
                }
              ]
            }

            Rules:
            - Prefer precision over recall.
            - Do not merge distinct entities on weak evidence.
            - Create new entities only when no candidate is a safe match.
            - If a candidate safely matches the mention, use "link_existing" with that candidate id.
            - Use "add_alias" only when the mention is a new surface form for a safe existing candidate.
            - If several candidates share a name, pick by entity type and summary; do not create another same-name entity.
            - Prefer English for entity summaries; keep proper names, product names, repo names, and file names unchanged.
            - Entity summaries must describe identity only, not mutable facts, current status, preferences, ownership, formats, fields, versions, or decisions. Put those facts into claims/notes instead.
            - For USER first-person facts and preferences, resolve the user as the stable namespace-level USER entity named "User".
            - Never create chat-specific USER entities such as "User of chat:..."; a new chat is not a new user.
            - Return valid JSON only.
        """.trimIndent()
    }
}

private fun List<MemoryEntityCanonicalizationOp>.normalizeStableUserOps(
    request: DirectStructuredMemoryWriteRequest,
    retrievedHits: List<MemoryStore.SearchHit>,
): List<MemoryEntityCanonicalizationOp> {
    val existingUser = retrievedHits
        .filterIsInstance<MemoryStore.SearchHit.EntityHit>()
        .map { it.entity }
        .firstOrNull { it.entityType == MemoryEntity.Type.USER && it.status == MemoryEntity.Status.ACTIVE }

    val stableUserEntity = MemoryEntityCanonicalizationOp.NewEntity(
        entityType = MemoryEntity.Type.USER,
        canonicalName = "User",
        summary = "The user interacting with this agent.",
    )

    val stableUserId = existingUser?.id ?: stableUserEntity.provisionalEntityId(request)

    return map { op ->
        if (op.newEntity?.entityType != MemoryEntity.Type.USER) {
            op
        } else if (existingUser != null) {
            op.copy(
                action = MemoryEntityCanonicalizationOp.Action.LINK_EXISTING,
                entityId = existingUser.id,
                newEntity = null,
                aliasText = op.aliasText ?: op.mention,
                reason = op.reason.ifBlank { "First-person USER mention resolves to the existing stable namespace user." },
            )
        } else {
            op.copy(
                action = MemoryEntityCanonicalizationOp.Action.CREATE_NEW,
                entityId = stableUserId,
                newEntity = stableUserEntity,
                aliasText = op.aliasText ?: op.mention,
                reason = op.reason.ifBlank { "First-person USER mention creates the stable namespace user." },
            )
        }
    }
}

private fun MemoryEntityCanonicalizationOp.NewEntity.provisionalEntityId(
    request: DirectStructuredMemoryWriteRequest,
): MemoryEntity.Id {
    val stableKey = "${request.namespace.value}|${entityType.name}|${canonicalName.normalizeCanonicalMemoryText()}"
    val hash = MessageDigest
        .getInstance("SHA-256")
        .digest(stableKey.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)

    return MemoryEntity.Id("entity:$hash")
}

private fun String?.toMemoryIdTextOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = value.lowercase()
    if (normalized == "null" || normalized == "uuid-or-null" || normalized == "text-or-null") {
        return null
    }
    return value
}

private fun String.toCanonicalizationAction(): MemoryEntityCanonicalizationOp.Action =
    when (trim().lowercase().replace("-", "_")) {
        "link_existing" -> MemoryEntityCanonicalizationOp.Action.LINK_EXISTING
        "create_new" -> MemoryEntityCanonicalizationOp.Action.CREATE_NEW
        "add_alias" -> MemoryEntityCanonicalizationOp.Action.ADD_ALIAS
        "noop" -> MemoryEntityCanonicalizationOp.Action.NOOP
        else -> MemoryEntityCanonicalizationOp.Action.NOOP
    }

private fun String.toMemoryEntityType(): MemoryEntity.Type =
    when (trim().lowercase().replace("-", "_")) {
        "user" -> MemoryEntity.Type.USER
        "person" -> MemoryEntity.Type.PERSON
        "agent" -> MemoryEntity.Type.AGENT
        "organization", "org" -> MemoryEntity.Type.ORGANIZATION
        "project" -> MemoryEntity.Type.PROJECT
        "repo", "repository" -> MemoryEntity.Type.REPO
        "file" -> MemoryEntity.Type.FILE
        "technology", "tech" -> MemoryEntity.Type.TECHNOLOGY
        "product" -> MemoryEntity.Type.PRODUCT
        "location" -> MemoryEntity.Type.LOCATION
        "concept" -> MemoryEntity.Type.CONCEPT
        "document", "doc" -> MemoryEntity.Type.DOCUMENT
        "conversation" -> MemoryEntity.Type.CONVERSATION
        "service" -> MemoryEntity.Type.SERVICE
        "environment", "env" -> MemoryEntity.Type.ENVIRONMENT
        else -> MemoryEntity.Type.OTHER
    }

internal fun MemorySource.renderLatestTurn(): String {
    val role = when (this) {
        is MemorySource.ChatTurn -> speakerRole.name
        is MemorySource.ToolOutput -> "TOOL"
        is MemorySource.ImportedNote -> "IMPORT"
        is MemorySource.ExternalRecord -> "EXTERNAL"
    }

    return "[${id.value}] $role\n${contentText.trim()}"
}

private fun List<MemoryStore.SearchHit>.renderCandidateEntities(): String {
    val entities = filterIsInstance<MemoryStore.SearchHit.EntityHit>()
        .map { it.entity }

    if (entities.isEmpty()) {
        return "none"
    }

    return entities.joinToString("\n") { entity ->
        val aliases = entity.aliases.joinToString(", ") { it.text }.ifBlank { "none" }
        "- id=${entity.id.value}; type=${entity.entityType.name}; name=${entity.canonicalName}; aliases=$aliases; summary=${entity.summary ?: "none"}"
    }
}

internal fun String.extractJsonArray(): String? {
    val fenced = Regex("```(?:json)?\\s*(\\[.*])\\s*```", setOf(RegexOption.DOT_MATCHES_ALL))
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
    if (fenced != null) {
        return fenced.trim()
    }

    val start = indexOf('[')
    val end = lastIndexOf(']')
    if (start >= 0 && end > start) {
        return substring(start, end + 1)
    }

    return null
}

internal fun String.limitForMemoryPrompt(maxChars: Int): String {
    val trimmed = trim()
    if (trimmed.length <= maxChars) {
        return trimmed
    }
    return trimmed.take(maxChars) + "\n[truncated ${trimmed.length - maxChars} chars]"
}

private fun String.normalizeCanonicalMemoryText(): String {
    return trim().lowercase()
}

private fun String.oneLineForLlmMemoryLog(maxChars: Int): String {
    val oneLine = trim()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
    if (oneLine.length <= maxChars) {
        return oneLine
    }
    return oneLine.take(maxChars) + "...[truncated ${oneLine.length - maxChars} chars]"
}

private fun MemoryEntityCanonicalizationOp.toDebugString(): String {
    return "mention=${mention.oneLineForLlmMemoryLog(120)} action=${action.name} entityId=${entityId?.value ?: "null"} " +
        "newType=${newEntity?.entityType?.name ?: "null"} newName=${newEntity?.canonicalName?.oneLineForLlmMemoryLog(120) ?: "null"} " +
        "alias=${aliasText?.oneLineForLlmMemoryLog(120) ?: "null"} confidence=$confidence reason=${reason.oneLineForLlmMemoryLog(220)}"
}
