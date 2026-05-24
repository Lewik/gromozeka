package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.model.memory.MemoryStore
import com.gromozeka.domain.model.memory.MemoryWriteRetrievalPlan
import com.gromozeka.domain.model.memory.isValidMemoryEntityId
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject

class LlmMemoryEntityCanonicalizerTest {

    @Test
    fun createNewIgnoresBareEntityIdFromModel() = runBlocking {
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "token rotation",
                      "action": "create_new",
                      "entity_id": "0cdfb4d3913a9344",
                      "new_entity": {
                        "entity_type": "concept",
                        "canonical_name": "token rotation",
                        "summary": "Token rotation responsibility."
                      },
                      "confidence": 0.9,
                      "reason": "Concrete project concept."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = request("Mira owns the token rotation."),
            retrievalPlan = MemoryWriteRetrievalPlan(),
            retrievedHits = emptyList(),
        )

        assertEquals(1, ops.size)
        val entityId = ops.single().entityId
        assertTrue(entityId != null)
        assertNotEquals("0cdfb4d3913a9344", entityId.value)
        assertTrue(entityId.value.isValidMemoryEntityId())
    }

    @Test
    fun linkExistingRestoresStrippedEntityPrefixFromCandidateId() = runBlocking {
        val candidateId = MemoryEntity.Id("entity:0cdfb4d3913a9344")
        val canonicalizer = LlmMemoryEntityCanonicalizer(
            runtime = FixedJsonRuntime(
                """
                {
                  "operations": [
                    {
                      "mention": "Mira",
                      "action": "link_existing",
                      "entity_id": "0cdfb4d3913a9344",
                      "confidence": 0.9,
                      "reason": "Matches the existing candidate."
                    }
                  ]
                }
                """.trimIndent()
            ),
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = canonicalizer.canonicalize(
            request = request("Mira owns the token rotation."),
            retrievalPlan = MemoryWriteRetrievalPlan(),
            retrievedHits = listOf(MemoryStore.SearchHit.EntityHit(entity(candidateId, "Mira"), score = 1.0)),
        )

        assertEquals(candidateId, ops.single().entityId)
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
        val TEST_NAMESPACE = MemoryNamespace("entity-canonicalizer-test")
        val NOW: Instant = Instant.parse("2026-01-02T03:04:05Z")

        fun request(text: String): DirectStructuredMemoryWriteRequest =
            DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = MemorySource.ChatTurn(
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
                ),
            )

        fun entity(id: MemoryEntity.Id, name: String): MemoryEntity =
            MemoryEntity(
                id = id,
                namespace = TEST_NAMESPACE,
                entityType = MemoryEntity.Type.PERSON,
                canonicalName = name,
                normalizedName = name.lowercase(),
                attributes = JsonObject(emptyMap()),
                firstSeenAt = NOW,
                lastSeenAt = NOW,
                createdAt = NOW,
                updatedAt = NOW,
            )
    }
}
