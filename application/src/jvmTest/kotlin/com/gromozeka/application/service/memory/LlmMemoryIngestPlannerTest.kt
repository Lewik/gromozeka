package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.memory.MemoryIngestBlock
import com.gromozeka.domain.model.memory.MemoryIngestPlan
import com.gromozeka.domain.model.memory.MemoryIngestPlanningRequest
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class LlmMemoryIngestPlannerTest {
    @Test
    fun plansImmutableBlocksWithoutTools() = runBlocking {
        val runtime = CapturingRuntime(
            responseText = """
                {
                  "decision": "ready",
                  "sections": [
                    {"title": "Profile", "block_ids": ["b1", "b2"]}
                  ],
                  "reason": "The existing paragraphs form one coherent profile section."
                }
            """.trimIndent()
        )
        lateinit var plan: MemoryIngestPlan
        val timings = collectMemoryRunTimings { timingCollector ->
            plan = LlmMemoryIngestPlanner(
                runtime = runtime,
                runtimeSystemPrompts = listOf("Runtime context"),
            ).plan(
                MemoryIngestPlanningRequest(
                    sourceLabel = "profile.txt",
                    blocks = listOf(
                        MemoryIngestBlock("b1", 1, 1, "Lev uses Kotlin."),
                        MemoryIngestBlock("b2", 3, 3, "He prefers PostgreSQL."),
                    ),
                    maxSectionChars = 8_000,
                )
            )
            timingCollector.snapshot()
        }

        assertEquals(MemoryIngestPlan.Decision.READY, plan.decision)
        assertEquals(listOf("b1", "b2"), plan.sections.single().blockIds)
        assertEquals(listOf("ingest-planner"), timings.map { it.stageName })
        val request = runtime.requests.single()
        assertTrue(request.tools.isEmpty())
        assertEquals(AiToolChoice.None, request.options.toolChoice)
        assertIs<AiResponseFormat.JsonSchema>(request.options.responseFormat)
        val prompt = request.messages.single().content.single() as Conversation.Message.ContentItem.UserMessage
        assertTrue(prompt.text.contains("Source blocks are immutable evidence"), prompt.text)
        assertTrue(prompt.text.contains("Blank-line paragraphs are valid structure"), prompt.text)
    }

    private class CapturingRuntime(
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
}
