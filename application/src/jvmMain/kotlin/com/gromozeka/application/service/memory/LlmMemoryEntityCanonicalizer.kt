package com.gromozeka.application.service.memory

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
import com.gromozeka.domain.model.memory.isValidMemoryEntityId
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

        val result = runtime.callMemoryStructuredStage(
            request = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = stageMessages,
                tools = runtimeTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = MemoryLlmStageLimits.ENTITY_CANONICALIZER_OUTPUT,
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
            jsonRoot = MemoryStructuredJsonRoot.OBJECT_OR_ARRAY,
            repairAttempts = 2,
            parse = { jsonText ->
                val responses = if (jsonText.trimStart().startsWith("{")) {
                    json.decodeFromString<EntityCanonicalizerResponseEnvelope>(jsonText).operations
                } else {
                    json.decodeFromString<List<EntityCanonicalizerResponse>>(jsonText)
                }
                responses
                    .mapIndexedNotNull { index, response ->
                        response.toOp(
                            request = request,
                            retrievedHits = retrievedHits,
                            responsePath = "operations[$index]",
                        )
                    }
                    .linkSafeExistingEntityOps(retrievedHits)
                    .normalizeStableUserOps(request, retrievedHits)
                    .normalizeCurrentUserPersonOps(request, retrievedHits)
            },
        )

        log.info {
            "Memory entity canonicalizer raw response: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "chars=${result.rawText.length} response=${result.rawText.oneLineForLlmMemoryLog(4_000)}"
        }

        log.info {
            "Memory entity canonicalizer parsed JSON: namespace=${request.namespace.value} source=${request.source.id.value} " +
                "json=${result.jsonText.oneLineForLlmMemoryLog(4_000)}"
        }

        val ops = result.value

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
        ${retrievedHits.renderCandidateEntities(request, retrievalPlan)}

        Relevant retrieval context for ambiguous mention resolution:
        ${retrievedHits.renderEntityResolutionContext()}

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
        retrievedHits: List<MemoryStore.SearchHit>,
        responsePath: String,
    ): MemoryEntityCanonicalizationOp? {
        val normalizedMention = mention.trim()
        if (normalizedMention.isBlank()) {
            return null
        }

        val mappedAction = action.toCanonicalizationAction()
        val mappedNewEntity = newEntity?.toNewEntity()
        if (
            request.source.isDocumentIngestSource() &&
            mappedAction == MemoryEntityCanonicalizationOp.Action.CREATE_NEW &&
            mappedNewEntity?.entityType == MemoryEntity.Type.FILE &&
            !aboutFileAssertion
        ) {
            throw IllegalArgumentException(
                "EntityCanonicalizer cannot create a FILE entity for an imported document mention without " +
                    "about_file_assertion=true. Use action=noop for incidental code references, stack traces, " +
                    "code blocks, file extensions, or paths unless the text asserts a durable fact about the file itself."
            )
        }
        val candidateEntityIds = retrievedHits
            .filterIsInstance<MemoryStore.SearchHit.EntityHit>()
            .mapTo(mutableSetOf()) { it.entity.id }
        val mappedEntityId = when (mappedAction) {
            MemoryEntityCanonicalizationOp.Action.CREATE_NEW ->
                mappedAction.provisionalEntityId(request, mappedNewEntity)

            MemoryEntityCanonicalizationOp.Action.LINK_EXISTING,
            MemoryEntityCanonicalizationOp.Action.ADD_ALIAS ->
                entityId.toCandidateEntityIdOrNull(candidateEntityIds, "$responsePath.entity_id")
                    ?: throw IllegalArgumentException(
                        "EntityCanonicalizer returned ${mappedAction.name} without a valid existing entity id at $responsePath.entity_id"
                    )

            MemoryEntityCanonicalizationOp.Action.NOOP ->
                entityId.toCandidateEntityIdOrNull(candidateEntityIds, "$responsePath.entity_id")
        }

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

        val stableKey = request.entityIdentityStableKey(newEntity.canonicalName)
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
        @SerialName("about_file_assertion")
        val aboutFileAssertion: Boolean = false,
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
                  "about_file_assertion": false,
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
            - If a row in Candidate existing entities safely matches the mention, use "link_existing" with that exact row id.
            - Use "add_alias" only when the mention is a new surface form for a safe row in Candidate existing entities.
            - Relevant retrieval context can explain meaning, but it is not a linkable entity list. If it mentions a safe referent that is absent from Candidate existing entities, use "create_new" with the same canonical identity instead of inventing an entity_id.
            - Canonical names are identity keys inside a namespace: the same canonical_name is treated as the same entity even if the entity_type differs.
            - If distinct referents share the same surface form, disambiguate the canonical_name, for example "Java (programming language)" vs "Java (island)".
            - If several candidates share a name, pick by entity type and summary; do not create another same-name entity just because the type differs.
            - Prefer English for entity summaries; keep proper names, product names, repo names, and file names unchanged.
            - Entity summaries must describe identity only, not mutable facts, current status, preferences, ownership, formats, fields, versions, or decisions. Put those facts into claims/notes instead.
            - For USER first-person facts and preferences, resolve the user as the stable namespace-level USER entity named "User".
            - For imported document/file_path/raw_url sources, the source path, URL, title, and section heading are source metadata. Do not create DOCUMENT, FILE, or URL-like entities merely to represent the imported source itself. Create a document/file entity only when the document content discusses that document/file as a durable domain object.
            - For imported document sources, do not create FILE entities from incidental code references, stack traces, code blocks, file extensions, or paths such as "*.kt". Set action="noop" unless the text states a durable assertion about the file itself and later stages need that file as a claim subject or object.
            - Set about_file_assertion=true only for FILE operations where the target text asserts something about that file itself. Set it false for non-FILE operations and incidental file-like mentions.
            - Do not use raw labels such as "user", "assistant", "project", or "document" as entity_id.
            - Do not invent placeholder ids such as "entity:<5>", "entity:unbound-7", "new_entity_1", or ids copied from retrieval context.
            - entity_id must be an exact id from Candidate existing entities for "link_existing"/"add_alias", or null for "create_new"/"noop".
            - Use relevant retrieval context to resolve ambiguous target mentions such as pronouns, "it", "that", "first one", "second one", and "from that list".
            - When TARGET_MESSAGE selects one item from a previously retrieved ordered list, create or link only the selected concrete entity so later stages can write the target claim.
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

private fun List<MemoryEntityCanonicalizationOp>.linkSafeExistingEntityOps(
    retrievedHits: List<MemoryStore.SearchHit>,
): List<MemoryEntityCanonicalizationOp> {
    val candidates = retrievedHits
        .filterIsInstance<MemoryStore.SearchHit.EntityHit>()
        .map { it.entity }
        .filter { it.status == MemoryEntity.Status.ACTIVE }

    return map { op ->
        val newEntity = op.newEntity
        if (op.action != MemoryEntityCanonicalizationOp.Action.CREATE_NEW || newEntity == null) {
            return@map op
        }

        val existing = candidates.singleExistingEntityBySurface(op) ?: return@map op
        if (!existing.entityType.isEntityMergeCompatibleWith(newEntity.entityType)) {
            return@map op
        }

        op.copy(
            action = MemoryEntityCanonicalizationOp.Action.LINK_EXISTING,
            entityId = existing.id,
            newEntity = null,
            aliasText = op.aliasText ?: op.mention.takeIf { it.normalizeCanonicalMemoryText() != existing.normalizedName },
            reason = op.reason.ifBlank { "Candidate existing entity safely matches the emitted surface form." },
        )
    }
}

private fun List<MemoryEntityCanonicalizationOp>.normalizeCurrentUserPersonOps(
    request: DirectStructuredMemoryWriteRequest,
    retrievedHits: List<MemoryStore.SearchHit>,
): List<MemoryEntityCanonicalizationOp> {
    val candidateEntities = retrievedHits
        .filterIsInstance<MemoryStore.SearchHit.EntityHit>()
        .map { it.entity }
    val candidateEntitiesById = candidateEntities.associateBy { it.id }
    val currentUser = candidateEntities
        .firstOrNull { it.entityType == MemoryEntity.Type.USER && it.status == MemoryEntity.Status.ACTIVE }

    val stableUserEntity = MemoryEntityCanonicalizationOp.NewEntity(
        entityType = MemoryEntity.Type.USER,
        canonicalName = "User",
        summary = "The user interacting with this agent.",
    )
    val stableUserId = currentUser?.id ?: stableUserEntity.provisionalEntityId(request)
    val userIdentityNames = retrievedHits.currentUserIdentityNames(currentUser)

    return map { op ->
        val newPerson = op.newEntity?.takeIf { it.entityType == MemoryEntity.Type.PERSON }
        val existingPerson = op.entityId
            ?.let(candidateEntitiesById::get)
            ?.takeIf { it.entityType == MemoryEntity.Type.PERSON && it.status == MemoryEntity.Status.ACTIVE }
        if (newPerson == null && existingPerson == null) {
            return@map op
        }

        val personNames = op.identitySurfaceTexts() + existingPerson.identitySurfaceTextsOrEmpty()
        val hasExplicitUserIdentity = userIdentityNames.any { userName ->
            personNames.any { personName -> userName.normalizeCanonicalMemoryText() == personName.normalizeCanonicalMemoryText() }
        }
        val hasSourceSelfIdentity = request.source.containsSelfIdentityDeclaration(personNames)
        if (!hasExplicitUserIdentity && !hasSourceSelfIdentity) {
            return@map op
        }

        if (currentUser != null) {
            op.copy(
                action = MemoryEntityCanonicalizationOp.Action.ADD_ALIAS,
                entityId = currentUser.id,
                newEntity = null,
                aliasText = op.aliasText ?: newPerson?.canonicalName ?: existingPerson?.canonicalName ?: op.mention,
                reason = op.reason.ifBlank { "Named person mention is supported as the current user's identity alias." },
            )
        } else if (newPerson != null || hasSourceSelfIdentity) {
            op.copy(
                action = MemoryEntityCanonicalizationOp.Action.CREATE_NEW,
                entityId = stableUserId,
                newEntity = stableUserEntity,
                aliasText = op.aliasText ?: newPerson?.canonicalName ?: existingPerson?.canonicalName ?: op.mention,
                reason = op.reason.ifBlank { "Self-identity mention creates the stable namespace user with the personal name as alias." },
            )
        } else {
            op
        }
    }
}

private fun MemoryEntityCanonicalizationOp.NewEntity.provisionalEntityId(
    request: DirectStructuredMemoryWriteRequest,
): MemoryEntity.Id {
    val stableKey = request.entityIdentityStableKey(canonicalName)
    val hash = MessageDigest
        .getInstance("SHA-256")
        .digest(stableKey.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)

    return MemoryEntity.Id("entity:$hash")
}

private fun DirectStructuredMemoryWriteRequest.entityIdentityStableKey(canonicalName: String): String =
    "${namespace.value}|${canonicalName.normalizeCanonicalMemoryText()}"

private fun String?.toMemoryIdTextOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = value.lowercase()
    if (normalized == "null" || normalized == "uuid-or-null" || normalized == "text-or-null") {
        return null
    }
    return value
}

private fun String?.toCandidateEntityIdOrNull(
    candidateEntityIds: Set<MemoryEntity.Id>,
    fieldPath: String,
): MemoryEntity.Id? {
    val value = toMemoryIdTextOrNull() ?: return null
    val id = MemoryEntity.Id(value)
    if (value.isValidMemoryEntityId() && id in candidateEntityIds) {
        return id
    }

    value.withEntityPrefixOrNull()?.let { prefixedValue ->
        val prefixedId = MemoryEntity.Id(prefixedValue)
        if (prefixedId in candidateEntityIds) {
            return prefixedId
        }
    }

    throw IllegalArgumentException(
        "EntityCanonicalizer returned invalid entity reference at $fieldPath: value=$value. " +
            "Use an exact candidate entity id from Candidate existing entities, or use create_new with entity_id=null. " +
            "Allowed candidate entity ids: ${candidateEntityIds.renderCandidateEntityIdsForError()}"
    )
}

private fun Set<MemoryEntity.Id>.renderCandidateEntityIdsForError(): String =
    if (isEmpty()) {
        "none"
    } else {
        map { it.value }.sorted().joinToString(", ").limitForMemoryPrompt(2_000)
    }

private fun String.withEntityPrefixOrNull(): String? {
    val value = trim()
    if (':' in value || '-' in value) {
        return null
    }
    val prefixed = "entity:$value"
    return prefixed.takeIf { it.isValidMemoryEntityId() }
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

private fun List<MemoryStore.SearchHit>.currentUserIdentityNames(
    currentUser: MemoryEntity?,
): Set<String> {
    val hits = this
    return buildSet {
        currentUser?.aliases?.mapTo(this) { it.text }
        currentUser?.aliases?.mapTo(this) { it.normalizedText }
        hits.filterIsInstance<MemoryStore.SearchHit.ClaimHit>()
            .map { it.claim }
            .filter { claim -> claim.status == com.gromozeka.domain.model.memory.MemoryClaim.Status.ACTIVE }
            .filter { claim -> claim.subjectEntityId == currentUser?.id }
            .filter { claim -> claim.predicate == "preferred_name" }
            .mapNotNullTo(this) { claim ->
                (claim.objectValue as? JsonPrimitive)
                    ?.contentOrNull
            }
    }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filter { it.normalizeCanonicalMemoryText() !in setOf("user", "the user", "i", "me", "myself") }
        .toSet()
}

private fun List<MemoryEntity>.singleExistingEntityBySurface(
    op: MemoryEntityCanonicalizationOp,
): MemoryEntity? {
    val opSurfaces = op.identitySurfaceTexts().mapTo(mutableSetOf()) { it.normalizeCanonicalMemoryText() }
    if (opSurfaces.isEmpty()) return null

    return filter { entity ->
        entity.identitySurfaceTexts()
            .map { it.normalizeCanonicalMemoryText() }
            .any { it in opSurfaces }
    }
        .distinctBy { it.id }
        .singleOrNull()
}

private fun MemoryEntityCanonicalizationOp.identitySurfaceTexts(): Set<String> =
    buildSet {
        add(mention)
        aliasText?.let(::add)
        newEntity?.canonicalName?.let(::add)
    }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()

private fun MemoryEntity.identitySurfaceTexts(): Set<String> =
    buildSet {
        add(canonicalName)
        add(normalizedName)
        aliases.forEach { alias ->
            add(alias.text)
            add(alias.normalizedText)
        }
    }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()

private fun MemoryEntity?.identitySurfaceTextsOrEmpty(): Set<String> =
    this?.identitySurfaceTexts().orEmpty()

private fun MemorySource.containsSelfIdentityDeclaration(names: Set<String>): Boolean {
    val normalizedText = contentText.normalizeCanonicalMemoryText()
    if (normalizedText.isBlank()) return false

    return names
        .map { it.normalizeCanonicalMemoryText() }
        .filter { it.isNotBlank() }
        .any { name ->
            listOf(
                "my name is $name",
                "i am $name",
                "i'm $name",
                "call me $name",
                "you can call me $name",
                "i go by $name",
                "my preferred name is $name",
                "preferred name is $name",
            ).any { marker -> marker in normalizedText }
        }
}

internal fun MemorySource.renderLatestTurn(): String {
    val role = when (this) {
        is MemorySource.ChatTurn -> speakerRole.name
        is MemorySource.ToolOutput -> "TOOL"
        is MemorySource.ImportedNote -> "IMPORT"
        is MemorySource.ExternalRecord -> "EXTERNAL"
    }

    return buildString {
        appendLine("[${id.value}] $role")
        renderIngestionMetadataForPrompt()?.let { appendLine(it) }
        append(contentText.trim())
    }
}

private fun List<MemoryStore.SearchHit>.renderCandidateEntities(
    request: DirectStructuredMemoryWriteRequest,
    retrievalPlan: MemoryWriteRetrievalPlan,
): String {
    val lookupNames = retrievalPlan.entityCanonicalizerLookupNames(request.source)
    val entities = filterIsInstance<MemoryStore.SearchHit.EntityHit>()
        .sortedWith(entityCanonicalizerCandidateComparator(lookupNames))

    if (entities.isEmpty()) {
        return "none"
    }

    return entities.joinToString("\n") { hit ->
        val entity = hit.entity
        val aliases = entity.aliases
            .sortedWith(compareBy<MemoryEntity.Alias> { it.normalizedText }.thenBy { it.text })
            .joinToString(", ") { it.text }
            .ifBlank { "none" }
        "- id=${entity.id.value}; type=${entity.entityType.name}; name=${entity.canonicalName}; aliases=$aliases; summary=${entity.summary ?: "none"}"
    }
}

private fun List<MemoryStore.SearchHit>.renderEntityResolutionContext(): String {
    val rendered = mapNotNull { hit ->
        when (hit) {
            is MemoryStore.SearchHit.EntityHit -> null
            is MemoryStore.SearchHit.ClaimHit -> "- claim ${hit.claim.id.value}: ${hit.claim.normalizedText}"
            is MemoryStore.SearchHit.ProfileHit -> "- profile ${hit.profile.id.value}: ${hit.profile.profileText}"
            is MemoryStore.SearchHit.SourceHit -> "- source ${hit.source.id.value}: ${hit.source.contentText.limitForMemoryPrompt(700)}"
            is MemoryStore.SearchHit.NoteHit -> "- note ${hit.note.id.value}: ${hit.note.title}; ${hit.note.summary}"
            is MemoryStore.SearchHit.ActionItemHit -> "- actionItem ${hit.actionItem.id.value}: ${hit.actionItem.title}; ${hit.actionItem.description ?: "no description"}"
            is MemoryStore.SearchHit.EpisodeHit -> "- episode ${hit.episode.id.value}: ${hit.episode.situation}; lesson=${hit.episode.lesson}"
            is MemoryStore.SearchHit.RunHit -> null
        }
    }.take(12)

    if (rendered.isEmpty()) {
        return "none"
    }

    return rendered.joinToString("\n")
}

private fun entityCanonicalizerCandidateComparator(
    lookupNames: List<String>,
): Comparator<MemoryStore.SearchHit.EntityHit> =
    compareByDescending<MemoryStore.SearchHit.EntityHit> { it.score }
        .thenBy { it.entity.entityCanonicalizerLookupRank(lookupNames) }
        .thenBy { it.entity.entityType.name }
        .thenBy { it.entity.canonicalName.stableEntityCanonicalizerSortText() }
        .thenBy { it.entity.id.value }

private fun MemoryWriteRetrievalPlan.entityCanonicalizerLookupNames(source: MemorySource): List<String> =
    buildList {
        entityQueries
            .map { it.withoutEntityCanonicalizerRuntimeSearchIds() }
            .filter { it.isNotBlank() }
            .forEach { add(it) }
        if (source is MemorySource.ChatTurn && source.speakerRole == MemorySource.ActorRole.USER) {
            add("user")
        }
    }
        .map { it.normalizeEntityCanonicalizerLookupText() }
        .filter { it.isNotBlank() }
        .distinct()

private fun MemoryEntity.entityCanonicalizerLookupRank(lookupNames: List<String>): Int {
    val rank = lookupNames.indexOfFirst { lookupName ->
        normalizedName == lookupName || aliases.any { it.normalizedText == lookupName }
    }
    return rank.takeIf { it >= 0 } ?: Int.MAX_VALUE
}

private fun String.withoutEntityCanonicalizerRuntimeSearchIds(): String =
    replace(Regex("\\bruntime:[A-Za-z0-9_:\\-]+\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.normalizeEntityCanonicalizerLookupText(): String =
    trim().lowercase()

private fun String.stableEntityCanonicalizerSortText(): String =
    lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

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
