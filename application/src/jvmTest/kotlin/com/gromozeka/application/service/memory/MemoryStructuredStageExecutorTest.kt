package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class MemoryStructuredStageExecutorTest {

    @Test
    fun repairsMalformedStructuredOutputInStageThread() = runBlocking {
        val runtime = SequencedRuntime(
            responses = ArrayDeque(
                listOf(
                    """{"value": """,
                    """{"value": "ok"}""",
                )
            )
        )
        val request = AiRuntimeRequest(
            systemPrompts = emptyList(),
            messages = listOf(userMessage("initial memory stage task")),
            options = AiRuntimeOptions(),
        )

        val result = runtime.callMemoryStructuredStage(
            request = request,
            stageName = "test-stage",
            logContext = "test=true",
            parse = { json.decodeFromString<TestEnvelope>(it) },
        )

        assertEquals("ok", result.value.value)
        assertEquals(2, runtime.requests.size)
        val repairMessages = runtime.requests[1].messages.takeLast(2)
        assertEquals(Conversation.Message.Role.ASSISTANT, repairMessages[0].role)
        assertEquals(Conversation.Message.Role.USER, repairMessages[1].role)
        assertTrue(repairMessages[1].plainText().contains("could not be parsed or validated"))
        assertTrue(repairMessages[1].plainText().contains("Output only the required JSON"))
    }

    private class SequencedRuntime(
        private val responses: ArrayDeque<String>,
    ) : AiRuntime {
        val requests = mutableListOf<AiRuntimeRequest>()
        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            requests += request
            return response(responses.removeFirst())
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()

        private fun response(text: String): AiRuntimeResponse =
            AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                structured = Conversation.Message.StructuredText(text),
                            )
                        )
                    )
                )
            )
    }

    @Serializable
    private data class TestEnvelope(
        val value: String,
    )

    private companion object {
        val json: Json = Json { ignoreUnknownKeys = true }

        fun userMessage(text: String): Conversation.Message =
            Conversation.Message(
                id = Conversation.Message.Id("message"),
                conversationId = Conversation.Id("conversation"),
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
                createdAt = Clock.System.now(),
            )

        fun Conversation.Message.plainText(): String =
            content.joinToString("\n") {
                when (it) {
                    is Conversation.Message.ContentItem.UserMessage -> it.text
                    is Conversation.Message.ContentItem.AssistantMessage -> it.structured.fullText
                    else -> it.toString()
                }
            }
    }
}
