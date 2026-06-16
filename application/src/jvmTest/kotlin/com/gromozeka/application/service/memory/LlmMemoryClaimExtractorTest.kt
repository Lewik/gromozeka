package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryEntityCanonicalizationOp
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import com.gromozeka.domain.model.memory.MemoryPredicateDefinition
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonPrimitive

class LlmMemoryClaimExtractorTest {

    @Test
    fun dropsClaimWhenPredicateObjectKindDoesNotMatchCatalog() = runBlocking {
        val userEntityId = MemoryEntity.Id("entity-user")
        val request = DirectStructuredMemoryWriteRequest(
            namespace = TEST_NAMESPACE,
            source = source(
                "For future car recommendations, I am choosing between Toyota, Honda, and Ford.",
            ),
        )
        val extractor = LlmMemoryClaimExtractor(
            runtime = FixedJsonRuntime(
                """
                {
                  "claims": [
                    {
                      "subject_entity_id": "${userEntityId.value}",
                      "predicate": "prefers",
                      "object_entity_id": null,
                      "object_value_json": "For future car recommendations, the user is choosing between Toyota, Honda, and Ford.",
                      "normalized_text": "The user prefers the Toyota, Honda, and Ford comparison set.",
                      "scope_json": {"kind": "global", "text": "Namespace-wide memory", "basis": "explicit"},
                      "qualifiers_json": {},
                      "confidence": 0.8,
                      "importance": 7,
                      "evidence_quote": "choosing between Toyota, Honda, and Ford",
                      "evidence_kind": "direct",
                      "evidence_reason": "The target names the candidate set.",
                      "reason": "The target mentions candidate brands."
                    },
                    {
                      "subject_entity_id": "${userEntityId.value}",
                      "predicate": "has_constraint",
                      "object_entity_id": null,
                      "object_value_json": "For future car recommendations, Toyota, Honda, and Ford are the comparison set.",
                      "normalized_text": "For future car recommendations, Toyota, Honda, and Ford are the comparison set.",
                      "scope_json": {"kind": "global", "text": "Namespace-wide memory", "basis": "explicit"},
                      "qualifiers_json": {},
                      "confidence": 0.8,
                      "importance": 7,
                      "evidence_quote": "choosing between Toyota, Honda, and Ford",
                      "evidence_kind": "direct",
                      "evidence_reason": "The target explicitly provides the compared options.",
                      "reason": "The target defines a durable comparison set."
                    }
                  ]
                }
                """.trimIndent()
            ),
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val candidates = extractor.extract(
            request = request,
            routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                memoryTypes = setOf(MemorySemanticType.CLAIM),
                salience = 0.8,
                reason = "The target contains a durable candidate set.",
            ),
            retrievalPlan = MemoryWriteRetrievalPlan(
                predicateHints = listOf("prefers", "has_constraint"),
                memoryTypes = setOf(MemorySemanticType.CLAIM),
            ),
            retrievedHits = emptyList(),
            entityOps = listOf(
                MemoryEntityCanonicalizationOp(
                    mention = "I",
                    action = MemoryEntityCanonicalizationOp.Action.CREATE_NEW,
                    entityId = userEntityId,
                    newEntity = MemoryEntityCanonicalizationOp.NewEntity(
                        entityType = MemoryEntity.Type.USER,
                        canonicalName = "User",
                    ),
                    confidence = 1.0,
                    reason = "Stable user subject.",
                )
            ),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, candidates.size)
        assertEquals("has_constraint", candidates.single().predicate)
        assertNull(candidates.single().objectEntityId)
        assertEquals(
            "For future car recommendations, Toyota, Honda, and Ford are the comparison set.",
            candidates.single().objectValue?.jsonPrimitive?.content,
        )
    }

    @Test
    fun acceptsResponsibilityClaimsWithEntityObject() = runBlocking {
        val miraEntityId = MemoryEntity.Id("entity-mira")
        val tokenRotationEntityId = MemoryEntity.Id("entity-token-rotation")
        val request = DirectStructuredMemoryWriteRequest(
            namespace = TEST_NAMESPACE,
            source = source("For Aurora project context: Mira owns the token rotation."),
        )
        val extractor = LlmMemoryClaimExtractor(
            runtime = FixedJsonRuntime(
                """
                {
                  "claims": [
                    {
                      "subject_entity_id": "${miraEntityId.value}",
                      "predicate": "responsible_for",
                      "object_entity_id": "${tokenRotationEntityId.value}",
                      "object_value_json": null,
                      "normalized_text": "Mira is responsible for token rotation in the Aurora project.",
                      "context_text": "Aurora project responsibility assignment.",
                      "scope_json": {"kind": "global", "text": "Aurora project context", "basis": "explicit"},
                      "qualifiers_json": {"project": "Aurora"},
                      "confidence": 0.86,
                      "importance": 8,
                      "evidence_quote": "Mira owns the token rotation",
                      "evidence_kind": "direct",
                      "evidence_reason": "The target explicitly assigns token rotation ownership to Mira.",
                      "reason": "Ownership of token rotation is a durable responsibility."
                    }
                  ]
                }
                """.trimIndent()
            ),
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val candidates = extractor.extract(
            request = request,
            routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                memoryTypes = setOf(MemorySemanticType.CLAIM),
                salience = 0.8,
                reason = "The target contains a durable responsibility assignment.",
            ),
            retrievalPlan = MemoryWriteRetrievalPlan(
                predicateHints = listOf("responsible_for"),
                memoryTypes = setOf(MemorySemanticType.CLAIM),
            ),
            retrievedHits = emptyList(),
            entityOps = listOf(
                entityOp("Mira", miraEntityId, MemoryEntity.Type.PERSON),
                entityOp("token rotation", tokenRotationEntityId, MemoryEntity.Type.CONCEPT),
            ),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, candidates.size)
        assertEquals("responsible_for", candidates.single().predicate)
        assertEquals(miraEntityId, candidates.single().subjectEntityId)
        assertEquals(tokenRotationEntityId, candidates.single().objectEntityId)
        assertNull(candidates.single().objectValue)
        assertEquals(
            setOf(MemoryPredicateDefinition.SemanticKind.RESPONSIBILITY),
            candidates.single().predicatePolicy?.semanticKinds,
        )
    }

    @Test
    fun resolvesUserLiteralEntityReferenceBeforeMappingClaims() = runBlocking {
        val userEntityId = MemoryEntity.Id("entity-user")
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(
                listOf(
                    claimJson(subjectEntityId = "user"),
                )
            )
        )
        val extractor = LlmMemoryClaimExtractor(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val candidates = extractor.extract(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("I usually prefer concise technical answers."),
            ),
            routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                memoryTypes = setOf(MemorySemanticType.CLAIM),
                salience = 0.8,
                reason = "The target contains a durable preference.",
            ),
            retrievalPlan = MemoryWriteRetrievalPlan(
                predicateHints = listOf("has_constraint"),
                memoryTypes = setOf(MemorySemanticType.CLAIM),
            ),
            retrievedHits = emptyList(),
            entityOps = listOf(entityOp("I", userEntityId, MemoryEntity.Type.USER)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, candidates.size)
        assertEquals(userEntityId, candidates.single().subjectEntityId)
        assertEquals(1, runtime.requests.size)
    }

    @Test
    fun repairsGenericDescriptionPredicateBeforeReturningClaims() = runBlocking {
        val userEntityId = MemoryEntity.Id("entity-user")
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(
                listOf(
                    claimJson(
                        subjectEntityId = userEntityId.value,
                        predicate = "is_described_as",
                        objectValue = "The user prefers concise technical answers.",
                    ),
                    claimJson(
                        subjectEntityId = userEntityId.value,
                        predicate = "has_constraint",
                        objectValue = "concise technical answers",
                    ),
                )
            )
        )
        val extractor = LlmMemoryClaimExtractor(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val candidates = extractor.extract(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("I usually prefer concise technical answers."),
            ),
            routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                memoryTypes = setOf(MemorySemanticType.CLAIM),
                salience = 0.8,
                reason = "The target contains a durable preference.",
            ),
            retrievalPlan = MemoryWriteRetrievalPlan(
                predicateHints = listOf("has_constraint"),
                memoryTypes = setOf(MemorySemanticType.CLAIM),
            ),
            retrievedHits = emptyList(),
            entityOps = listOf(entityOp("I", userEntityId, MemoryEntity.Type.USER)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, candidates.size)
        assertEquals("has_constraint", candidates.single().predicate)
        assertEquals(2, runtime.requests.size)
        assertEquals(true, runtime.requests.last().options.toolContext["memoryStageRepair"])
    }

    @Test
    fun promptRequiresExplicitPresenceBeforeEmittingAttendedEvent() = runBlocking {
        val userEntityId = MemoryEntity.Id("entity-user")
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(listOf("""{"claims":[]}"""))
        )
        val extractor = LlmMemoryClaimExtractor(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        extractor.extract(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("My cousin Emily's wedding in the city was really lovely."),
            ),
            routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                memoryTypes = setOf(MemorySemanticType.CLAIM),
                salience = 0.8,
                reason = "The target may contain event memory.",
            ),
            retrievalPlan = MemoryWriteRetrievalPlan(
                predicateHints = listOf("attended_event"),
                memoryTypes = setOf(MemorySemanticType.CLAIM),
            ),
            retrievedHits = emptyList(),
            entityOps = listOf(entityOp("I", userEntityId, MemoryEntity.Type.USER)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        val prompt = runtime.requests.single().messages.joinToString("\n") { message ->
            message.content.joinToString("\n") { item ->
                when (item) {
                    is Conversation.Message.ContentItem.UserMessage -> item.text
                    is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                    else -> item.toString()
                }
            }
        }
        assertTrue(prompt.contains("Emit attended_event only when the target explicitly establishes"))
        assertTrue(prompt.contains("Do not infer attendance merely because the user says another person's event"))
    }

    @Test
    fun promptUsesSourceSessionDateAsAnchorForDatedImportedEvents() = runBlocking {
        val userEntityId = MemoryEntity.Id("entity-user")
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(listOf("""{"claims":[]}"""))
        )
        val extractor = LlmMemoryClaimExtractor(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        extractor.extract(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("Session date: 2023/02/14. I'm still recovering from my American Airlines flight from LAX to JFK."),
            ),
            routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                memoryTypes = setOf(MemorySemanticType.CLAIM),
                salience = 0.8,
                reason = "The target contains dated imported event memory.",
            ),
            retrievalPlan = MemoryWriteRetrievalPlan(
                predicateHints = listOf("attended_event"),
                memoryTypes = setOf(MemorySemanticType.CLAIM),
            ),
            retrievedHits = emptyList(),
            entityOps = listOf(entityOp("I", userEntityId, MemoryEntity.Type.USER)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        val prompt = runtime.requests.single().messages.joinToString("\n") { message ->
            message.content.joinToString("\n") { item ->
                when (item) {
                    is Conversation.Message.ContentItem.UserMessage -> item.text
                    is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                    else -> item.toString()
                }
            }
        }
        assertTrue(prompt.contains("the source/session date is the best available temporal anchor"))
        assertTrue(prompt.contains("still recovering from"))
        assertTrue(prompt.contains("preserve the explicit event date as valid_from"))
    }

    @Test
    fun promptRequiresCareerTenureAndProgressionClaims() = runBlocking {
        val userEntityId = MemoryEntity.Id("entity-user")
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(listOf("""{"claims":[]}"""))
        )
        val extractor = LlmMemoryClaimExtractor(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        extractor.extract(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source(
                    "I started as a Marketing Coordinator and worked my way up to Senior Marketing Specialist after 2 years and 4 months.",
                ),
            ),
            routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                memoryTypes = setOf(MemorySemanticType.CLAIM),
                salience = 0.8,
                reason = "The target contains durable career progression memory.",
            ),
            retrievalPlan = MemoryWriteRetrievalPlan(
                predicateHints = listOf("current_metric_value", "has_experience_with"),
                memoryTypes = setOf(MemorySemanticType.CLAIM),
            ),
            retrievedHits = emptyList(),
            entityOps = listOf(entityOp("I", userEntityId, MemoryEntity.Type.USER)),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        val prompt = runtime.requests.single().messages.joinToString("\n") { message ->
            message.content.joinToString("\n") { item ->
                when (item) {
                    is Conversation.Message.ContentItem.UserMessage -> item.text
                    is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                    else -> item.toString()
                }
            }
        }
        assertTrue(prompt.contains("Professional, educational, or membership tenure and progression"))
        assertTrue(prompt.contains("started as X"))
        assertTrue(prompt.contains("worked up to Y after 2 years and 4 months"))
    }

    private class FixedJsonRuntime(
        private val responseText: String,
    ) : AiRuntime {
        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse =
            AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                structured = Conversation.Message.StructuredText(responseText),
                            )
                        )
                    )
                )
            )

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }

    private class SequencedJsonRuntime(
        private val responses: ArrayDeque<String>,
    ) : AiRuntime {
        val requests = mutableListOf<AiRuntimeRequest>()
        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            requests += request
            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                structured = Conversation.Message.StructuredText(responses.removeFirst()),
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }

    private companion object {
        val TEST_NAMESPACE = MemoryNamespace("claim-extractor-test")
        val NOW: Instant = Instant.parse("2026-01-02T03:04:05Z")

        fun entityOp(
            mention: String,
            entityId: MemoryEntity.Id,
            entityType: MemoryEntity.Type,
        ): MemoryEntityCanonicalizationOp =
            MemoryEntityCanonicalizationOp(
                mention = mention,
                action = MemoryEntityCanonicalizationOp.Action.CREATE_NEW,
                entityId = entityId,
                newEntity = MemoryEntityCanonicalizationOp.NewEntity(
                    entityType = entityType,
                    canonicalName = mention,
                ),
                confidence = 1.0,
                reason = "Resolved test entity.",
            )

        fun source(text: String): MemorySource =
            MemorySource.ChatTurn(
                id = MemorySource.Id("source"),
                namespace = TEST_NAMESPACE,
                conversationId = Conversation.Id("conversation"),
                threadId = Conversation.Thread.Id("thread"),
                sourceMessageId = Conversation.Message.Id("message"),
                speakerRole = MemorySource.ActorRole.USER,
                contentText = text,
                contentHash = "source-hash",
                observedAt = NOW,
                createdAt = NOW,
            )

        fun claimJson(
            subjectEntityId: String,
            predicate: String = "has_constraint",
            objectValue: String = "concise technical answers",
        ): String =
            """
            {
              "claims": [
                {
                  "subject_entity_id": "$subjectEntityId",
                  "predicate": "$predicate",
                  "object_entity_id": null,
                  "object_value_json": "$objectValue",
                  "normalized_text": "The user prefers concise technical answers.",
                  "scope_json": {"kind": "global", "text": "Namespace-wide memory", "basis": "explicit"},
                  "qualifiers_json": {"category": "communication_style"},
                  "confidence": 0.88,
                  "importance": 7,
                  "evidence_quote": "prefer concise technical answers",
                  "evidence_kind": "direct",
                  "evidence_reason": "The target explicitly states the preference.",
                  "reason": "This is a durable answer style preference."
                }
              ]
            }
            """.trimIndent()
    }
}
