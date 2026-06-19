package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteRequest
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryPredicateCatalogDefaults
import com.gromozeka.domain.model.memory.MemoryRouteDecision
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemorySource
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class LlmMemoryWriteRetrievalPlannerTest {
    @Test
    fun promptRequestsCurrentMetricValueForRecurringRoutineSlots() = runBlocking {
        val runtime = CapturingJsonRuntime(
            """
            {
              "need_retrieval": true,
              "entity_queries": ["user"],
              "text_queries": ["routine wake time"],
              "predicate_hints": ["current_metric_value"],
              "memory_types": ["claim", "source"],
              "time_filters": {"from_iso": null, "to_iso": null},
              "limits": {"claims": 4, "notes": 0, "action_items": 0, "sources": 2}
            }
            """.trimIndent()
        )

        LlmMemoryWriteRetrievalPlanner(
            runtime = runtime,
            timezone = "UTC",
            runtimeSystemPrompts = emptyList(),
            runtimeTools = emptyList(),
        ).plan(
            request = DirectStructuredMemoryWriteRequest(
                namespace = TEST_NAMESPACE,
                source = source("I usually start my Saturday run after coffee."),
            ),
            routeDecision = MemoryRouteDecision(
                decision = MemoryRouteDecision.Decision.DIRECT_STRUCTURED_WRITE,
                memoryTypes = setOf(MemorySemanticType.CLAIM),
                salience = 0.8,
                reason = "The target contains a durable routine update.",
            ),
            predicateCatalog = MemoryPredicateCatalogDefaults.forNamespace(TEST_NAMESPACE),
        )

        val prompt = runtime.requests.single().messages.asPromptText()
        assertTrue(prompt.contains("recurring schedule or routine slot values"), prompt)
        assertTrue(prompt.contains("\"current_metric_value\" predicate hint"), prompt)
    }

    private class CapturingJsonRuntime(
        private val responseText: String,
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
                                structured = Conversation.Message.StructuredText(responseText),
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }

    private companion object {
        val TEST_NAMESPACE = MemoryNamespace("write-retrieval-planner-test")
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

        fun List<Conversation.Message>.asPromptText(): String =
            joinToString("\n") { message ->
                message.content.joinToString("\n") { item ->
                    when (item) {
                        is Conversation.Message.ContentItem.UserMessage -> item.text
                        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                        else -> item.toString()
                    }
                }
            }
    }
}
