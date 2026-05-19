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
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    }
}
