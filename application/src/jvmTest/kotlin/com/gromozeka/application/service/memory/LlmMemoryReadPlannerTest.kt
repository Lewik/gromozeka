package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadRequest
import com.gromozeka.domain.model.memory.MemorySemanticType
import com.gromozeka.domain.model.memory.MemoryThreadContext
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

class LlmMemoryReadPlannerTest {
    @Test
    fun plansSourceEvidenceFallbackForPriorConversationRecommendationRecall() = runBlocking {
        val runtime = CapturingJsonRuntime(
            """
            {
              "need_memory": true,
              "context_mode": "factual",
              "coverage_mode": "minimal",
              "core_blocks": [],
              "retrieval_budget": {
                "claims": 2,
                "notes": 1,
                "action_items": 0,
                "sources": 2,
                "episodes": 0
              },
              "retrieval_requests": [
                {
                  "memory_type": "claim",
                  "why": "Check typed facts first.",
                  "query": "Orlando dessert shop giant milkshakes previous recommendation",
                  "top_k": 2,
                  "filters": {},
                  "preferred_claim_predicates": [],
                  "deprioritized_claim_predicates": []
                },
                {
                  "memory_type": "source",
                  "why": "Prior assistant recommendation may exist only in raw conversation evidence.",
                  "query": "Orlando dessert shop giant milkshakes previous recommendation",
                  "top_k": 2,
                  "filters": {},
                  "preferred_claim_predicates": [],
                  "deprioritized_claim_predicates": []
                }
              ],
              "require_evidence_fallback": true
            }
            """.trimIndent()
        )

        val plan = LlmMemoryReadPlanner(
            runtime = runtime,
            timezone = "UTC",
        ).plan(
            readRequest(
                "I'm planning to revisit Orlando. Remind me of the unique dessert shop with giant milkshakes we talked about last time."
            )
        )

        val prompt = runtime.requests.single().messages.asText()
        assertTrue(prompt.contains("recall what was discussed, suggested, recommended, listed, or mentioned"))
        assertTrue(prompt.contains("prior assistant-generated recommendations"))
        assertEquals(true, plan.requireEvidenceFallback)
        assertEquals(2, plan.retrievalBudget.sources)
        assertEquals(
            listOf(MemorySemanticType.CLAIM, MemorySemanticType.SOURCE),
            plan.retrievalRequests.map { it.memoryType },
        )
    }

    @Test
    fun promptPlansCompleteOperandsForMetricDeltaQuestions() = runBlocking {
        val runtime = CapturingJsonRuntime(
            """
            {
              "need_memory": true,
              "context_mode": "factual",
              "coverage_mode": "complete_set",
              "core_blocks": [],
              "retrieval_budget": {
                "claims": 4,
                "notes": 0,
                "action_items": 0,
                "sources": 2,
                "episodes": 0
              },
              "retrieval_requests": [
                {
                  "memory_type": "claim",
                  "why": "Need baseline and later metric operands.",
                  "query": "newsletter subscribers increase baseline later value",
                  "top_k": 4,
                  "filters": {},
                  "preferred_claim_predicates": ["current_metric_value", "metric_observation", "aggregate_increase"],
                  "deprioritized_claim_predicates": []
                }
              ],
              "require_evidence_fallback": true
            }
            """.trimIndent()
        )

        LlmMemoryReadPlanner(
            runtime = runtime,
            timezone = "UTC",
        ).plan(readRequest("What was the increase in newsletter subscribers after two weeks?"))

        val prompt = runtime.requests.single().messages.asText()
        assertTrue(prompt.contains("baseline/previous value"), prompt)
        assertTrue(prompt.contains("later/current/final value"), prompt)
        assertTrue(prompt.contains("Use coverage_mode=\"complete_set\""), prompt)
    }

    @Test
    fun promptDistinguishesNamedOrdinalFactsFromOrderReconstruction() = runBlocking {
        val runtime = CapturingJsonRuntime(minimalReadPlanResponse())

        val plan = LlmMemoryReadPlanner(
            runtime = runtime,
            timezone = "UTC",
        ).plan(readRequest("What is the recommended first implementation for the Gromozeka Claude Code provider?"))

        val prompt = runtime.requests.single().messages.asText()
        assertTrue(prompt.contains("Ordinal words alone do not require complete_set"), prompt)
        assertTrue(prompt.contains("the recommended first implementation"), prompt)
        assertEquals(MemoryReadPlan.CoverageMode.MINIMAL, plan.coverageMode)
        assertEquals(false, plan.requireEvidenceFallback)
    }

    @Test
    fun promptPlansAnchorEventForRelativeDurationQuestions() = runBlocking {
        val runtime = CapturingJsonRuntime(
            """
            {
              "need_memory": true,
              "context_mode": "factual",
              "coverage_mode": "complete_set",
              "core_blocks": [],
              "retrieval_budget": {
                "claims": 4,
                "notes": 0,
                "action_items": 0,
                "sources": 2,
                "episodes": 0
              },
              "retrieval_requests": [
                {
                  "memory_type": "claim",
                  "why": "Need the earlier event date and the anchor event date.",
                  "query": "workshop attended when product launch happened",
                  "top_k": 4,
                  "filters": {},
                  "preferred_claim_predicates": ["attended_event", "has_experience_with"],
                  "deprioritized_claim_predicates": []
                }
              ],
              "require_evidence_fallback": true
            }
            """.trimIndent()
        )

        LlmMemoryReadPlanner(
            runtime = runtime,
            timezone = "UTC",
        ).plan(readRequest("How many days ago did I attend the workshop when the product launch happened?"))

        val prompt = runtime.requests.single().messages.asText()
        assertTrue(prompt.contains("relative-duration questions that name an anchor event"), prompt)
        assertTrue(prompt.contains("question date alone may be the wrong endpoint"), prompt)
        assertTrue(prompt.contains("explicit start/begin/first-participation evidence"), prompt)
        assertTrue(prompt.contains("not a substitute for the start-date operand"), prompt)
    }

    @Test
    fun verifierRequestsSourceForPriorConversationRecallAfterNoMemoryPlan() = runBlocking {
        val runtime = CapturingJsonRuntime(
            """
            {
              "need_memory": false,
              "context_mode": "factual",
              "coverage_mode": "minimal",
              "core_blocks": [],
              "retrieval_budget": {
                "claims": 0,
                "notes": 0,
                "action_items": 0,
                "sources": 0,
                "episodes": 0
              },
              "retrieval_requests": [],
              "require_evidence_fallback": false
            }
            """.trimIndent(),
            """
            {
              "needs_memory": true,
              "context_mode": "factual",
              "needs_source": true,
              "query": "Orlando dessert shop giant milkshakes previous recommendation",
              "reason": "The target asks for prior conversation recall."
            }
            """.trimIndent(),
        )

        val plan = LlmMemoryReadPlanner(
            runtime = runtime,
            timezone = "UTC",
        ).plan(
            readRequest(
                "What was the dessert shop with giant milkshakes you suggested before?"
            )
        )

        val verifierPrompt = runtime.requests.last().messages.asText()
        assertTrue(verifierPrompt.contains("recall what was discussed, suggested, recommended, listed, or mentioned"))
        assertTrue(verifierPrompt.contains("prior assistant recommendation recall"))
        assertEquals(true, plan.requireEvidenceFallback)
        assertTrue(plan.retrievalRequests.any { it.memoryType == MemorySemanticType.SOURCE })
    }

    @Test
    fun plannerAndVerifierTreatPersonalizableRecommendationsAsPotentiallyMemoryDependent() = runBlocking {
        val runtime = CapturingJsonRuntime(
            """
            {
              "need_memory": false,
              "context_mode": "factual",
              "coverage_mode": "minimal",
              "core_blocks": [],
              "retrieval_budget": {
                "claims": 0,
                "notes": 0,
                "action_items": 0,
                "sources": 0,
                "episodes": 0
              },
              "retrieval_requests": [],
              "require_evidence_fallback": false
            }
            """.trimIndent(),
            """
            {
              "needs_memory": true,
              "context_mode": "factual",
              "needs_source": false,
              "query": "user preferences goals experience tools relevant resources",
              "reason": "Remembered context could materially change the recommendation."
            }
            """.trimIndent(),
        )

        val plan = LlmMemoryReadPlanner(
            runtime = runtime,
            timezone = "UTC",
        ).plan(readRequest("Can you recommend resources where I can learn more?"))

        val plannerPrompt = runtime.requests.first().messages.asText()
        val verifierPrompt = runtime.requests.last().messages.asText()
        assertTrue(plannerPrompt.contains("generic answer is possible"), plannerPrompt)
        assertTrue(plannerPrompt.contains("could materially change the useful answer"), plannerPrompt)
        assertTrue(verifierPrompt.contains("could materially change what is useful"), verifierPrompt)
        assertTrue(plan.needMemory)
    }

    @Test
    fun completeSetPlanAddsSourceFallbackWithoutReclassifyingTheTarget() = runBlocking {
        val runtime = CapturingJsonRuntime(
            """
            {
              "need_memory": true,
              "context_mode": "factual",
              "coverage_mode": "complete_set",
              "core_blocks": [],
              "retrieval_budget": {
                "claims": 4,
                "notes": 0,
                "action_items": 0,
                "sources": 0,
                "episodes": 0
              },
              "retrieval_requests": [
                {
                  "memory_type": "claim",
                  "why": "Find direct booking facts.",
                  "query": "Airbnb booking months ago",
                  "top_k": 4,
                  "filters": {},
                  "preferred_claim_predicates": [],
                  "deprioritized_claim_predicates": []
                }
              ],
              "require_evidence_fallback": false
            }
            """.trimIndent()
        )

        val plan = LlmMemoryReadPlanner(
            runtime = runtime,
            timezone = "UTC",
        ).plan(
            readRequest("How many months ago did I book the Airbnb in San Francisco?")
        )

        assertEquals(MemoryReadPlan.CoverageMode.COMPLETE_SET, plan.coverageMode)
        assertEquals(true, plan.requireEvidenceFallback)
        assertTrue(plan.retrievalBudget.sources >= 2)
        assertTrue(plan.retrievalRequests.any { it.memoryType == MemorySemanticType.SOURCE })
    }

    @Test
    fun addsNoteAndSourceFallbackForUsualTimeFactRecall() = runBlocking {
        val runtime = CapturingJsonRuntime(
            """
            {
              "need_memory": true,
              "context_mode": "factual",
              "coverage_mode": "minimal",
              "core_blocks": [],
              "retrieval_budget": {
                "claims": 3,
                "notes": 0,
                "action_items": 0,
                "sources": 0,
                "episodes": 0
              },
              "retrieval_requests": [
                {
                  "memory_type": "claim",
                  "why": "Find direct remembered gym time.",
                  "query": "usual gym time",
                  "top_k": 3,
                  "filters": {},
                  "preferred_claim_predicates": [],
                  "deprioritized_claim_predicates": []
                }
              ],
              "require_evidence_fallback": false
            }
            """.trimIndent()
        )

        val plan = LlmMemoryReadPlanner(
            runtime = runtime,
            timezone = "UTC",
        ).plan(
            readRequest("What time do I usually go to the gym?")
        )

        val prompt = runtime.requests.single().messages.asText()
        assertTrue(prompt.contains("current/usual preferences"))
        assertEquals(MemoryReadPlan.CoverageMode.MINIMAL, plan.coverageMode)
        assertEquals(true, plan.requireEvidenceFallback)
        assertTrue(plan.retrievalBudget.claims >= 4)
        assertTrue(plan.retrievalBudget.notes >= 2)
        assertTrue(plan.retrievalBudget.sources >= 2)
        assertEquals(
            listOf(MemorySemanticType.CLAIM, MemorySemanticType.NOTE, MemorySemanticType.SOURCE),
            plan.retrievalRequests.map { it.memoryType },
        )
    }

    private class CapturingJsonRuntime(
        vararg responses: String,
    ) : AiRuntime {
        private val responses = ArrayDeque(responses.toList())
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
        val TEST_NAMESPACE = MemoryNamespace("read-planner-test")
        val NOW: Instant = Instant.parse("2026-01-02T03:04:05Z")

        fun readRequest(text: String): MemoryReadRequest {
            val message = Conversation.Message(
                id = Conversation.Message.Id("target-message"),
                conversationId = Conversation.Id("conversation"),
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
                createdAt = NOW,
            )
            return MemoryReadRequest(
                namespace = TEST_NAMESPACE,
                threadContext = MemoryThreadContext(
                    conversationId = Conversation.Id("conversation"),
                    threadId = Conversation.Thread.Id("thread"),
                    targetMessageId = message.id,
                    messages = listOf(message),
                ),
            )
        }

        fun List<Conversation.Message>.asText(): String =
            joinToString("\n") { message ->
                message.content.joinToString("\n") { item ->
                    when (item) {
                        is Conversation.Message.ContentItem.UserMessage -> item.text
                        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                        is Conversation.Message.ContentItem.System -> item.content
                        else -> item.toString()
                    }
                }
            }

        fun minimalReadPlanResponse(): String =
            """
            {
              "need_memory": true,
              "context_mode": "factual",
              "coverage_mode": "minimal",
              "core_blocks": [],
              "retrieval_budget": {
                "claims": 2,
                "notes": 0,
                "action_items": 0,
                "sources": 0,
                "episodes": 0
              },
              "retrieval_requests": [
                {
                  "memory_type": "claim",
                  "why": "Find the directly named recommendation.",
                  "query": "recommended first implementation",
                  "top_k": 2,
                  "filters": {},
                  "preferred_claim_predicates": [],
                  "deprioritized_claim_predicates": []
                }
              ],
              "require_evidence_fallback": false
            }
            """.trimIndent()
    }
}
