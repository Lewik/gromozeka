package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.memory.MemoryThreadContext
import com.gromozeka.domain.service.AiRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class MemoryThreadContextCompactorTest {

    @Test
    fun compactsWhenEstimatedInputExceedsModelThreshold() = runBlocking {
        val runtime = RecordingRuntime("focused digest")
        val context = threadContext(
            priorMessages = List(8) { index ->
                userMessage("prior-$index", "x".repeat(800))
            },
            target = userMessage("target", "remember the latest instruction"),
        )

        val compacted = MemoryThreadContextCompactor(
            runtime = runtime,
            preCompactThresholdTokens = 5_000,
        ).compactIfNeeded(
            context = context,
            targetSourceLabel = "source-target",
            logContext = "test=true",
        )

        assertTrue(runtime.requests.isNotEmpty())
        assertTrue(compacted.messages.size < context.messages.size)
        assertEquals("memory_focused_context", compacted.messages.first().providerMetadata["syntheticKind"]?.jsonPrimitiveContent())
        assertEquals(context.targetMessageId, compacted.messages.last().id)
    }

    @Test
    fun skipsCompactionWhenEstimatedInputFitsModelThreshold() = runBlocking {
        val runtime = RecordingRuntime("unused")
        val context = threadContext(
            priorMessages = List(4) { index ->
                userMessage("prior-$index", "x".repeat(200))
            },
            target = userMessage("target", "short target"),
        )

        val compacted = MemoryThreadContextCompactor(
            runtime = runtime,
            preCompactThresholdTokens = 20_000,
        ).compactIfNeeded(
            context = context,
            targetSourceLabel = "source-target",
            logContext = "test=true",
        )

        assertTrue(runtime.requests.isEmpty())
        assertEquals(context, compacted)
    }

    private class RecordingRuntime(
        private val text: String,
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
                                structured = Conversation.Message.StructuredText(text),
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }

    private companion object {
        fun threadContext(
            priorMessages: List<Conversation.Message>,
            target: Conversation.Message,
        ): MemoryThreadContext =
            MemoryThreadContext(
                conversationId = Conversation.Id("conversation"),
                threadId = Conversation.Thread.Id("thread"),
                targetMessageId = target.id,
                messages = priorMessages + target,
            )

        fun userMessage(id: String, text: String): Conversation.Message =
            Conversation.Message(
                id = Conversation.Message.Id(id),
                conversationId = Conversation.Id("conversation"),
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
                createdAt = Clock.System.now(),
            )

        fun JsonElement.jsonPrimitiveContent(): String? =
            (this as? JsonPrimitive)?.contentOrNull
    }
}
