package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AiModelInputCompatibilityTest {
    @Test
    fun `text-only messages do not require binary input capabilities`() {
        textModel().requireSupportsInputs(
            listOf(message(Conversation.Message.ContentItem.UserMessage("hello")))
        )
    }

    @Test
    fun `image content requires image input capability`() {
        val messages = listOf(
            message(
                Conversation.Message.ContentItem.ImageItem(
                    Conversation.Message.ImageSource.UrlImageSource("https://example.test/image.png")
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            textModel().requireSupportsInputs(messages)
        }
        textModel(AiModelCapability.IMAGE_INPUT).requireSupportsInputs(messages)
    }

    @Test
    fun `artifact references are checked before materialization`() {
        val image = artifactReference(Artifact.Kind.IMAGE, "image/png")
        val document = artifactReference(Artifact.Kind.FILE, "application/vnd.test.binary")

        assertFailsWith<IllegalArgumentException> {
            textModel().requireSupportsInputs(
                listOf(message(Conversation.Message.ContentItem.ArtifactItem(image)))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            textModel(AiModelCapability.IMAGE_INPUT).requireSupportsInputs(
                listOf(message(Conversation.Message.ContentItem.ArtifactItem(document)))
            )
        }
        textModel(
            AiModelCapability.IMAGE_INPUT,
            AiModelCapability.DOCUMENT_INPUT,
        ).requireSupportsInputs(
            listOf(
                message(
                    Conversation.Message.ContentItem.ArtifactItem(image),
                    Conversation.Message.ContentItem.ArtifactItem(document),
                )
            )
        )
    }

    @Test
    fun `tool result binary data requires matching input capability`() {
        val toolResult = Conversation.Message.ContentItem.ToolResult(
            toolUseId = Conversation.Message.ContentItem.ToolCall.Id("tool-call"),
            toolName = "binary_tool",
            result = listOf(
                Conversation.Message.ContentItem.ToolResult.Data.Base64Data(
                    data = "AQID",
                    mediaType = Conversation.Message.MediaType.APPLICATION_JSON,
                    fileName = "result.json",
                )
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            textModel().requireSupportsInputs(listOf(message(toolResult)))
        }
        textModel(AiModelCapability.DOCUMENT_INPUT).requireSupportsInputs(listOf(message(toolResult)))
    }

    private fun textModel(vararg inputCapabilities: AiModelCapability): AiModelSpec =
        AiModelSpec(
            id = "test-model",
            provider = AiProvider.OPENAI,
            capabilities = setOf(AiModelCapability.TEXT_GENERATION, *inputCapabilities),
            limits = AiModelSpec.Limits(
                textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 8_192),
            ),
        )

    private fun message(vararg content: Conversation.Message.ContentItem): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id("message"),
            conversationId = Conversation.Id("conversation"),
            role = Conversation.Message.Role.USER,
            content = content.toList(),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun artifactReference(kind: Artifact.Kind, mediaType: String): Artifact.Reference =
        Artifact.Reference(
            id = Artifact.Id("artifact-${kind.name.lowercase()}"),
            fileName = "artifact.bin",
            mediaType = mediaType,
            sizeBytes = 3,
            purpose = Artifact.Purpose.USER_ATTACHMENT,
            kind = kind,
        )
}
