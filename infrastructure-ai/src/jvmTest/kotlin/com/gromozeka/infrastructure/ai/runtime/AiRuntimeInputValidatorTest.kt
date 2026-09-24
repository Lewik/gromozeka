package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AI_REPLAY_THREAD_METADATA_KEY
import com.gromozeka.domain.service.AiRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.service.ResolvedAiRuntime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AiRuntimeInputValidatorTest {
    @Test
    fun `runtime wrapper projects before validation and tags call and stream replay with branch`() = runBlocking {
        val source = message(image("image/bmp", "AQID"))
        val summary = source.copy(id = Conversation.Message.Id("summary"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.ContextCompactionResult(
                payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary("Image description"),
                origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
                sourceMessageIds = listOf(source.id),
            )))
        val captured = mutableListOf<AiRuntimeRequest>()
        val response = AiRuntimeResponse(listOf(AiAssistantMessage(listOf(
            Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("Answer")),
        ))))
        val delegate = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                captured += request
                return response
            }
            override fun stream(request: AiRuntimeRequest) = flowOf(response).also { captured += request }
        }
        val wrapper = AiInputValidatingRuntime(
            runtime = runtime(AiConnection.OpenAiApi(CONNECTION_ID, "OpenAI", true)), delegate = delegate,
        )
        val request = AiRuntimeRequest(emptyList(), listOf(source, summary),
            options = AiRuntimeOptions(toolContext = mapOf("threadId" to "thread")))
        assertEquals("thread", wrapper.call(request).messages.single().metadata[AI_REPLAY_THREAD_METADATA_KEY])
        assertEquals("thread", wrapper.stream(request).toList().single().messages.single().metadata[AI_REPLAY_THREAD_METADATA_KEY])
        assertEquals(listOf(listOf(summary), listOf(summary)), captured.map { it.messages })
    }

    @Test
    fun `OpenAI accepts supported image and document inputs`() {
        val runtime = runtime(
            AiConnection.OpenAiApi(
                id = CONNECTION_ID,
                displayName = "OpenAI",
                enabled = true,
            )
        )

        AiRuntimeInputValidator.requireSupported(
            runtime,
            listOf(
                message(
                    image("image/png", "AQID"),
                    document(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "report.docx",
                        "AQID",
                    ),
                )
            ),
        )
    }

    @Test
    fun `OpenAI rejects unsupported image and document types before dispatch`() {
        val runtime = runtime(
            AiConnection.OpenAiApi(
                id = CONNECTION_ID,
                displayName = "OpenAI",
                enabled = true,
            )
        )

        assertFailsWith<IllegalArgumentException> {
            AiRuntimeInputValidator.requireSupported(runtime, listOf(message(image("image/bmp", "AQID"))))
        }
        assertFailsWith<IllegalArgumentException> {
            AiRuntimeInputValidator.requireSupported(
                runtime,
                listOf(message(document("application/octet-stream", "archive.bin", "AQID"))),
            )
        }
    }

    @Test
    fun `Anthropic Bedrock rejects URL images and oversized base64 images`() {
        val runtime = runtime(
            AiConnection.AnthropicBedrock(
                id = CONNECTION_ID,
                displayName = "Anthropic Bedrock",
                enabled = true,
            )
        )

        assertFailsWith<IllegalArgumentException> {
            AiRuntimeInputValidator.requireSupported(
                runtime,
                listOf(
                    message(
                        Conversation.Message.ContentItem.ImageItem(
                            Conversation.Message.ImageSource.UrlImageSource("https://example.test/image.png")
                        )
                    )
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AiRuntimeInputValidator.requireSupported(
                runtime,
                listOf(message(image("image/png", "A".repeat(5 * 1024 * 1024 + 4)))),
            )
        }
    }

    @Test
    fun `invalid base64 fails before provider mapping`() {
        val runtime = runtime(
            AiConnection.AnthropicApi(
                id = CONNECTION_ID,
                displayName = "Anthropic",
                enabled = true,
            )
        )

        assertFailsWith<IllegalArgumentException> {
            AiRuntimeInputValidator.requireSupported(runtime, listOf(message(image("image/png", "not base64"))))
        }
    }

    private fun runtime(connection: AiConnection): ResolvedAiRuntime {
        val configuration = AiModelConfiguration(
            id = AiModelConfiguration.Id("model-configuration"),
            connectionId = connection.id,
            providerModelId = "test-model",
            displayName = "Test model",
        )
        val modelSpec = AiModelSpec(
            id = configuration.providerModelId,
            provider = connection.kind.provider,
            capabilities = setOf(
                AiModelCapability.TEXT_GENERATION,
                AiModelCapability.IMAGE_INPUT,
                AiModelCapability.DOCUMENT_INPUT,
            ),
            limits = AiModelSpec.Limits(
                textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 200_000),
            ),
        )
        return ResolvedAiRuntime(connection, configuration, modelSpec)
    }

    private fun image(mediaType: String, data: String): Conversation.Message.ContentItem.ImageItem =
        Conversation.Message.ContentItem.ImageItem(
            Conversation.Message.ImageSource.Base64ImageSource(data, mediaType)
        )

    private fun document(
        mediaType: String,
        fileName: String,
        data: String,
    ): Conversation.Message.ContentItem.DocumentItem =
        Conversation.Message.ContentItem.DocumentItem(
            Conversation.Message.DocumentSource.Base64DocumentSource(data, mediaType, fileName)
        )

    private fun message(vararg content: Conversation.Message.ContentItem): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id("message"),
            conversationId = Conversation.Id("conversation"),
            role = Conversation.Message.Role.USER,
            content = content.toList(),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private companion object {
        val CONNECTION_ID = AiConnection.Id("connection")
    }
}
