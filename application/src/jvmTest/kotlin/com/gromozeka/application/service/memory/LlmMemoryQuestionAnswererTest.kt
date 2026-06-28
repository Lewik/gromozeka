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

    private class CapturingRuntime : AiRuntime {
        val prompts = mutableListOf<String>()
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
                                    """
                                    {
                                      "answer": "The Hate U Give",
                                      "reasoning": "The selected memory says The Hate U Give was finished before The Nightingale, so the final answer is The Hate U Give.",
                                      "sufficiency": "answered",
                                      "evidence_refs": ["claim:claim-hate-u-give", "claim:claim-nightingale"],
                                      "counted_items": [],
                                      "excluded_refs": []
                                    }
                                    """.trimIndent()
                                )
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }
}
