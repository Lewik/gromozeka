package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.MemoryItemRef
import com.gromozeka.domain.model.memory.MemoryReadPlan
import com.gromozeka.domain.model.memory.MemoryReadResult
import com.gromozeka.domain.model.memory.MemoryReadTrace
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class LlmMemoryQuestionAnswererTest {
    @Test
    fun promptRequiresDerivedAnswerToMatchReasoningConclusion() = runBlocking {
        val runtime = CapturingRuntime()
        val readResult = MemoryReadResult(
            plan = MemoryReadPlan(),
            retrievedHits = emptyList(),
            runtimePrompt = "The Hate U Give was finished before The Nightingale.",
            trace = MemoryReadTrace(
                selectedHits = listOf(
                    MemoryReadTrace.Hit(
                        ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, "claim-hate-u-give"),
                        score = 1.0,
                        summary = "The user finished The Hate U Give before book club.",
                        predicate = "completed_content",
                        status = "ACTIVE",
                    ),
                    MemoryReadTrace.Hit(
                        ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, "claim-nightingale"),
                        score = 0.9,
                        summary = "The user finished The Nightingale later.",
                        predicate = "completed_content",
                        status = "ACTIVE",
                    ),
                ),
                selectorDecisions = listOf(
                    MemoryReadTrace.SelectorDecision(
                        ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, "claim-hate-u-give"),
                        selected = true,
                        rank = 1,
                        reason = "direct temporal evidence",
                    ),
                    MemoryReadTrace.SelectorDecision(
                        ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, "claim-nightingale"),
                        selected = false,
                        rank = Int.MAX_VALUE,
                        reason = "less direct rejected anchor",
                    ),
                ),
            ),
        )

        val result = LlmMemoryQuestionAnswerer(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
        ).answer(
            question = "Which book did I finish first, The Hate U Give or The Nightingale?",
            readResult = readResult,
            conversationId = Conversation.Id("conversation"),
        )

        val prompt = runtime.prompts.single()
        assertEquals("The Hate U Give", result.answer)
        assertTrue(prompt.contains("For ordering, first/second/latest/earliest"), prompt)
        assertTrue(prompt.contains("compare every selected dated or relative-time candidate"), prompt)
        assertTrue(prompt.contains("A lead time such as \"three months in advance\""), prompt)
        assertTrue(prompt.contains("Never return an answer field that contradicts the reasoning conclusion"), prompt)
        assertTrue(!prompt.contains("less direct rejected anchor"), prompt)
    }

    @Test
    fun repairsElapsedAgoAnswerThatUsesLeadTimeWithoutAnchorDerivation() = runBlocking {
        val runtime = CapturingRuntime(
            responses = listOf(
                """
                {
                  "answer": "Three months ago.",
                  "reasoning": "The relevant memory says the venue was reserved three months in advance. The selected memory directly gives that booking lead time. Final conclusion: three months ago.",
                  "sufficiency": "answered",
                  "evidence_refs": ["claim:reservation-lead-time"],
                  "counted_items": [],
                  "excluded_refs": []
                }
                """.trimIndent(),
                """
                {
                  "answer": "Five months ago.",
                  "reasoning": "The reservation happened three months before the workshop, and the workshop was two months before the question date, so 3 + 2 = 5 months ago.",
                  "sufficiency": "answered",
                  "evidence_refs": ["claim:reservation-lead-time", "source:workshop"],
                  "counted_items": [],
                  "excluded_refs": []
                }
                """.trimIndent(),
            )
        )
        val readResult = MemoryReadResult(
            plan = MemoryReadPlan(),
            retrievedHits = emptyList(),
            runtimePrompt = """
                The venue was reserved three months in advance of the workshop.
                The workshop happened two months before the question date.
            """.trimIndent(),
            trace = MemoryReadTrace(
                selectedHits = listOf(
                    MemoryReadTrace.Hit(
                        ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, "reservation-lead-time"),
                        score = 1.0,
                        summary = "The user's venue reservation was made three months in advance of the workshop.",
                        predicate = "metric_observation",
                        status = "ACTIVE",
                    ),
                    MemoryReadTrace.Hit(
                        ref = MemoryItemRef(MemoryItemRef.Type.SOURCE, "workshop"),
                        score = 0.9,
                        summary = "The workshop happened two months before the question date.",
                    ),
                ),
                selectorDecisions = listOf(
                    MemoryReadTrace.SelectorDecision(
                        ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, "reservation-lead-time"),
                        selected = true,
                        rank = 1,
                        reason = "lead time operand",
                    ),
                    MemoryReadTrace.SelectorDecision(
                        ref = MemoryItemRef(MemoryItemRef.Type.SOURCE, "workshop"),
                        selected = true,
                        rank = 2,
                        reason = "anchor timing operand",
                    ),
                ),
            ),
        )

        val result = LlmMemoryQuestionAnswerer(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
        ).answer(
            question = "How many months ago did I reserve the workshop venue?",
            readResult = readResult,
            conversationId = Conversation.Id("conversation"),
        )

        assertEquals("Five months ago.", result.answer)
        assertEquals(2, runtime.prompts.size)
        assertTrue(runtime.prompts.last().contains("Elapsed-time answer uses a lead-time phrase"), runtime.prompts.last())
    }

    @Test
    fun repairsAnswerThatContradictsExplicitReasoningConclusion() = runBlocking {
        val runtime = CapturingRuntime(
            responses = listOf(
                """
                {
                  "answer": "The Nightingale",
                  "reasoning": "The selected memory says The Hate U Give was finished before The Nightingale. Comparing both items, The Hate U Give came first. However, the answer field must match the computed conclusion: The Hate U Give.",
                  "sufficiency": "answered",
                  "evidence_refs": ["claim:claim-hate-u-give", "claim:claim-nightingale"],
                  "counted_items": [],
                  "excluded_refs": []
                }
                """.trimIndent(),
                """
                {
                  "answer": "The Hate U Give",
                  "reasoning": "The selected memory says The Hate U Give was finished before The Nightingale, so the final answer is The Hate U Give.",
                  "sufficiency": "answered",
                  "evidence_refs": ["claim:claim-hate-u-give", "claim:claim-nightingale"],
                  "counted_items": [],
                  "excluded_refs": []
                }
                """.trimIndent(),
            )
        )

        val result = LlmMemoryQuestionAnswerer(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
        ).answer(
            question = "Which book did I finish first, The Hate U Give or The Nightingale?",
            readResult = defaultReadResult(),
            conversationId = Conversation.Id("conversation"),
        )

        assertEquals("The Hate U Give", result.answer)
        assertEquals(2, runtime.prompts.size)
        assertTrue(runtime.prompts.last().contains("Answer field contradicts the explicit reasoning conclusion"), runtime.prompts.last())
    }

    @Test
    fun repairsAnsweredQualifierMismatchAdmittedInReasoning() = runBlocking {
        val runtime = CapturingRuntime(
            responses = listOf(
                """
                {
                  "answer": "About 4 years and 9 months.",
                  "reasoning": "The question names Google, but the selected memory only contains NovaTech as the current job and does not provide a Google tenure. However, using the adjacent NovaTech operands gives about 4 years and 9 months.",
                  "sufficiency": "answered",
                  "evidence_refs": ["claim:total-experience", "claim:novatech-tenure"],
                  "counted_items": [],
                  "excluded_refs": []
                }
                """.trimIndent(),
                """
                {
                  "answer": "Memory is insufficient to answer because selected memory does not establish that the user started a current job at Google.",
                  "reasoning": "The selected memory contains an adjacent current-job tenure for NovaTech, but the question asks about a current job at Google. Since the selected memory does not contain the Google job start or tenure anchor, sufficiency is insufficient.",
                  "sufficiency": "insufficient",
                  "evidence_refs": ["claim:novatech-tenure"],
                  "counted_items": [],
                  "excluded_refs": ["claim:novatech-tenure"]
                }
                """.trimIndent(),
            )
        )

        val result = LlmMemoryQuestionAnswerer(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
        ).answer(
            question = "How long have I been working before I started my current job at Google?",
            readResult = defaultReadResult(),
            conversationId = Conversation.Id("conversation"),
        )

        assertEquals(MemoryQuestionAnswerResult.Sufficiency.INSUFFICIENT, result.sufficiency)
        assertEquals(2, runtime.prompts.size)
        assertTrue(
            runtime.prompts.last().contains("answers a different qualifier than the question"),
            runtime.prompts.last(),
        )
    }

    @Test
    fun repairsAnsweredQualifiedEmploymentTargetDroppedFromReasoning() = runBlocking {
        val runtime = CapturingRuntime(
            responses = listOf(
                """
                {
                  "answer": "About 4 years and 9 months.",
                  "reasoning": "Selected memory gives total professional work experience as 9 years and current-job tenure at NovaTech as about 4 years and 3 months. Subtracting gives 4 years and 9 months.",
                  "sufficiency": "answered",
                  "evidence_refs": ["claim:total-experience", "claim:novatech-tenure"],
                  "counted_items": [],
                  "excluded_refs": []
                }
                """.trimIndent(),
                """
                {
                  "answer": "Memory is insufficient to answer because selected memory does not establish that the user started a current job at Google.",
                  "reasoning": "The question asks about the user's current job at Google, but selected memory only supports a different employer, NovaTech. It does not establish a Google current-job start date or Google tenure, so sufficiency is insufficient.",
                  "sufficiency": "insufficient",
                  "evidence_refs": ["claim:novatech-tenure"],
                  "counted_items": [],
                  "excluded_refs": ["claim:novatech-tenure"]
                }
                """.trimIndent(),
            )
        )

        val result = LlmMemoryQuestionAnswerer(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
        ).answer(
            question = "How long have I been working before I started my current job at Google?",
            readResult = defaultReadResult(),
            conversationId = Conversation.Id("conversation"),
        )

        assertEquals(MemoryQuestionAnswerResult.Sufficiency.INSUFFICIENT, result.sufficiency)
        assertEquals(2, runtime.prompts.size)
        assertTrue(
            runtime.prompts.last().contains("does not preserve named question target qualifiers: Google"),
            runtime.prompts.last(),
        )
    }

    @Test
    fun repairsBlankAnswerIntroducedByFirstSemanticRepair() = runBlocking {
        val runtime = CapturingRuntime(
            responses = listOf(
                """
                {
                  "answer": "About 4 years and 9 months.",
                  "reasoning": "Selected memory gives total professional work experience as 9 years and current-job tenure at NovaTech as about 4 years and 3 months. Subtracting gives 4 years and 9 months.",
                  "sufficiency": "answered",
                  "evidence_refs": ["claim:total-experience", "claim:novatech-tenure"],
                  "counted_items": [],
                  "excluded_refs": []
                }
                """.trimIndent(),
                """
                {
                  "answer": "",
                  "reasoning": "The question asks about the user's current job at Google, but selected memory only supports a different employer, NovaTech. It does not establish a Google current-job start date or Google tenure, so sufficiency is insufficient.",
                  "sufficiency": "insufficient",
                  "evidence_refs": ["claim:total-experience", "claim:novatech-tenure"],
                  "counted_items": [],
                  "excluded_refs": ["claim:novatech-tenure"]
                }
                """.trimIndent(),
                """
                {
                  "answer": "Memory is insufficient to answer because selected memory does not establish that the user started a current job at Google.",
                  "reasoning": "The question asks about the user's current job at Google, but selected memory only supports a different employer, NovaTech. It does not establish a Google current-job start date or Google tenure, so sufficiency is insufficient.",
                  "sufficiency": "insufficient",
                  "evidence_refs": ["claim:total-experience", "claim:novatech-tenure"],
                  "counted_items": [],
                  "excluded_refs": ["claim:novatech-tenure"]
                }
                """.trimIndent(),
            )
        )

        val result = LlmMemoryQuestionAnswerer(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
        ).answer(
            question = "How long have I been working before I started my current job at Google?",
            readResult = defaultReadResult(),
            conversationId = Conversation.Id("conversation"),
        )

        assertEquals(MemoryQuestionAnswerResult.Sufficiency.INSUFFICIENT, result.sufficiency)
        assertEquals(3, runtime.prompts.size)
        assertTrue(
            runtime.prompts.last().contains("memory question answer must not be blank"),
            runtime.prompts.last(),
        )
        assertTrue(
            runtime.prompts.last().contains("complete corrected structured JSON object"),
            runtime.prompts.last(),
        )
    }

    @Test
    fun promptDistinguishesActualUseFromRecommendationsForUseScopedCounts() = runBlocking {
        val runtime = CapturingRuntime()

        LlmMemoryQuestionAnswerer(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
        ).answer(
            question = "How many different types of ingredients have I used in my recipes?",
            readResult = defaultReadResult(),
            conversationId = Conversation.Id("conversation"),
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("For use-scoped count/list questions"), prompt)
        assertTrue(prompt.contains("Assistant recommendations, option lists, examples"), prompt)
    }

    @Test
    fun promptRequiresNonBlankAnswerForInsufficientAndConflictingResults() = runBlocking {
        val runtime = CapturingRuntime()

        LlmMemoryQuestionAnswerer(
            runtime = runtime,
            runtimeSystemPrompts = emptyList(),
        ).answer(
            question = "Which book did I finish first?",
            readResult = defaultReadResult(),
            conversationId = Conversation.Id("conversation"),
        )

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("The answer field must be non-empty for every sufficiency"), prompt)
        assertTrue(prompt.contains("Memory is insufficient"), prompt)
        assertTrue(prompt.contains("Memory is conflicting"), prompt)
    }

    private class CapturingRuntime(
        responses: List<String> = listOf(defaultResponse),
    ) : AiRuntime {
        val prompts = mutableListOf<String>()
        private val responses = ArrayDeque(responses)
        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            prompts += request.messages.joinToString("\n") { message ->
                message.content.joinToString("\n") { item ->
                    when (item) {
                        is Conversation.Message.ContentItem.UserMessage -> item.text
                        is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
                        else -> item.toString()
                    }
                }
            }
            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                Conversation.Message.StructuredText(
                                    responses.removeFirst()
                                )
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()

        companion object {
            private val defaultResponse = """
                {
                  "answer": "The Hate U Give",
                  "reasoning": "The selected memory says The Hate U Give was finished before The Nightingale, so the final answer is The Hate U Give.",
                  "sufficiency": "answered",
                  "evidence_refs": ["claim:claim-hate-u-give", "claim:claim-nightingale"],
                  "counted_items": [],
                  "excluded_refs": []
                }
            """.trimIndent()
        }
    }
}

private fun defaultReadResult(): MemoryReadResult =
    MemoryReadResult(
        plan = MemoryReadPlan(),
        retrievedHits = emptyList(),
        runtimePrompt = "The Hate U Give was finished before The Nightingale.",
        trace = MemoryReadTrace(
            selectedHits = listOf(
                MemoryReadTrace.Hit(
                    ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, "claim-hate-u-give"),
                    score = 1.0,
                    summary = "The user finished The Hate U Give before book club.",
                    predicate = "completed_content",
                    status = "ACTIVE",
                ),
                MemoryReadTrace.Hit(
                    ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, "claim-nightingale"),
                    score = 0.9,
                    summary = "The user finished The Nightingale later.",
                    predicate = "completed_content",
                    status = "ACTIVE",
                ),
            ),
            selectorDecisions = listOf(
                MemoryReadTrace.SelectorDecision(
                    ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, "claim-hate-u-give"),
                    selected = true,
                    rank = 1,
                    reason = "direct temporal evidence",
                ),
                MemoryReadTrace.SelectorDecision(
                    ref = MemoryItemRef(MemoryItemRef.Type.CLAIM, "claim-nightingale"),
                    selected = false,
                    rank = Int.MAX_VALUE,
                    reason = "less direct rejected anchor",
                ),
            ),
        ),
    )
