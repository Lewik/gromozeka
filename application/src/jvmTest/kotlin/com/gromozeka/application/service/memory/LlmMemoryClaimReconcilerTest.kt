package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryClaimCandidate
import com.gromozeka.domain.model.memory.MemoryEntity
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import com.gromozeka.domain.model.memory.MemoryReconciliationAction
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LlmMemoryClaimReconcilerTest {

    @Test
    fun repairsGenericDescriptionCanonicalPredicateBeforeReturningOps() = runBlocking {
        val runtime = SequencedJsonRuntime(
            responses = ArrayDeque(
                listOf(
                    operationJson(canonicalPredicate = "is_described_as"),
                    operationJson(canonicalPredicate = "has_constraint"),
                )
            )
        )
        val reconciler = LlmMemoryClaimReconciler(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        )

        val ops = reconciler.reconcile(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("I usually prefer concise technical answers."),
            ),
            claimCandidates = listOf(
                MemoryClaimCandidate(
                    subjectEntityId = USER_ENTITY_ID,
                    predicate = "has_constraint",
                    objectValue = JsonPrimitive("concise technical answers"),
                    normalizedText = "The user prefers concise technical answers.",
                    scope = MemoryScope.Global(
                        text = "Namespace-wide memory",
                        basis = MemoryScope.Basis.EXPLICIT,
                    ),
                    qualifiers = JsonObject(emptyMap()),
                    confidence = 0.88,
                    importance = 7,
                    evidenceQuote = "prefer concise technical answers",
                    evidenceReason = "The target explicitly states the preference.",
                    reason = "This is a durable answer style preference.",
                )
            ),
            retrievedHits = emptyList(),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        assertEquals(1, ops.size)
        assertEquals(MemoryReconciliationAction.INSERT, ops.single().action)
        assertEquals("has_constraint", ops.single().candidate?.predicate)
        assertEquals(2, runtime.requests.size)
        assertEquals(true, runtime.requests.last().options.toolContext["memoryStageRepair"])
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
        val TEST_NAMESPACE = com.gromozeka.domain.model.memory.MemoryNamespace("claim-reconciler-test")
        val USER_ENTITY_ID = MemoryEntity.Id("entity-user")
        val NOW: Instant = Instant.parse("2026-01-02T03:04:05Z")

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

        fun operationJson(canonicalPredicate: String): String =
            """
            {
              "operations": [
                {
                  "action": "insert",
                  "candidate_index": 0,
                  "target_claim_id": null,
                  "canonical_predicate": "$canonicalPredicate",
                  "predicate_family": "$canonicalPredicate",
                  "predicate_description": "Test predicate",
                  "object_kind": "string",
                  "cardinality": "multi",
                  "temporal_policy": "status_like",
                  "conflict_policy": "coexist",
                  "reason": "Test operation."
                }
              ]
            }
            """.trimIndent()
    }
}
