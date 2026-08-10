package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.openai.models.responses.ResponseIncludable
import com.openai.models.ChatModel
import com.openai.models.ResponsesModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiResponsesSdkRuntimeTest {
    private val mapper = OpenAiResponsesMessageMapper(
        connectionId = "openai-api",
        modelConfigurationId = "openai-api-gpt-5",
        modelName = "gpt-5",
    )

    @Test
    fun `adds hosted web search alongside function tools`() {
        val params = mapper.toCreateParams(
            modelName = "gpt-5",
            webSearchEnabled = true,
            request = request(tools = listOf(testTool())),
        )

        val tools = params.tools().get()
        assertEquals(2, tools.size)
        assertTrue(tools.any { it.isFunction() && it.asFunction().name() == "test_tool" })
        assertTrue(tools.any { it.isWebSearch() })
        assertTrue(params.include().get().contains(ResponseIncludable.WEB_SEARCH_CALL_ACTION_SOURCES))
        assertTrue(params.include().get().contains(ResponseIncludable.REASONING_ENCRYPTED_CONTENT))
    }

    @Test
    fun `does not send hosted web search when connection disables it`() {
        val params = mapper.toCreateParams(
            modelName = "gpt-5",
            webSearchEnabled = false,
            request = request(tools = listOf(testTool())),
        )

        val tools = params.tools().get()
        assertEquals(1, tools.size)
        assertTrue(tools.single().isFunction())
        assertEquals(
            listOf(ResponseIncludable.REASONING_ENCRYPTED_CONTENT),
            params.include().get(),
        )
    }

    @Test
    fun `tool choice none disables function and hosted tools`() {
        val params = mapper.toCreateParams(
            modelName = "gpt-5",
            webSearchEnabled = true,
            request = request(
                tools = listOf(testTool()),
                toolChoice = AiToolChoice.None,
            ),
        )

        assertFalse(params.tools().isPresent)
        assertEquals(
            listOf(ResponseIncludable.REASONING_ENCRYPTED_CONTENT),
            params.include().get(),
        )
        assertEquals("none", params.toolChoice().get().asOptions().asString())
    }

    @Test
    fun `replays encrypted reasoning state for matching OpenAI connection and model`() {
        val reasoningMessage = Conversation.Message(
            id = Conversation.Message.Id("reasoning-message"),
            conversationId = Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.ASSISTANT,
            content = emptyList(),
            providerMetadata = JsonObject(
                mapOf(
                    "provider" to JsonPrimitive("OPENAI_API"),
                    "connectionId" to JsonPrimitive("openai-api"),
                    "model" to JsonPrimitive("gpt-5"),
                    "openAiApiReasoningItems" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("rs_123"),
                                    "summary" to JsonArray(emptyList()),
                                    "encrypted_content" to JsonPrimitive("encrypted-state"),
                                )
                            )
                        )
                    ),
                )
            ),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        val params = mapper.toCreateParams(
            modelName = "gpt-5",
            webSearchEnabled = true,
            request = request(messages = listOf(reasoningMessage, userMessage())),
        )

        val reasoning = params.input().get().asResponse().first().asReasoning()
        assertEquals("rs_123", reasoning.id())
        assertEquals("encrypted-state", reasoning.encryptedContent().get())
    }

    @Test
    fun `reads typed and custom model ids from Responses SDK`() {
        assertEquals(
            "gpt-4o-mini",
            ResponsesModel.ofChat(ChatModel.GPT_4O_MINI).providerModelId(),
        )
        assertEquals(
            "future-model",
            ResponsesModel.ofString("future-model").providerModelId(),
        )
    }

    @Test
    fun `appends uncited hosted search sources without duplicating inline citations`() {
        val messages = listOf(
            AiAssistantMessage(
                content = listOf(
                    Conversation.Message.ContentItem.AssistantMessage(
                        structured = Conversation.Message.StructuredText(
                            fullText = "Answer with [citation](https://example.com/cited)."
                        )
                    )
                )
            )
        )

        val result = appendWebSources(
            messages = messages,
            sourceUrls = listOf(
                "https://example.com/cited",
                "https://example.com/source",
                "https://example.com/source",
            ),
        )
        val text = result.single().content
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .single()
            .structured.fullText

        assertEquals(
            "Answer with [citation](https://example.com/cited).\n\nSources:\n- <https://example.com/source>",
            text,
        )
    }

    @Test
    fun `maps binary screenshot tool result to a Responses image output`() {
        val params = mapper.toCreateParams(
            modelName = "gpt-5",
            webSearchEnabled = false,
            request = request(messages = listOf(imageToolResultMessage())),
        )

        val output = params.input().orElseThrow().asResponse().single()
            .asFunctionCallOutput().output().asResponseFunctionCallOutputItemList().single()

        assertTrue(output.isInputImage())
        assertEquals("data:image/png;base64,AQID", output.asInputImage().imageUrl().orElseThrow())
    }

    @Test
    fun `maps binary document tool result to a Responses file output`() {
        val params = mapper.toCreateParams(
            modelName = "gpt-5",
            webSearchEnabled = false,
            request = request(messages = listOf(documentToolResultMessage())),
        )

        val output = params.input().orElseThrow().asResponse().single()
            .asFunctionCallOutput().output().asResponseFunctionCallOutputItemList().single()

        assertTrue(output.isInputFile())
        assertEquals("report.pdf", output.asInputFile().filename().orElseThrow())
        assertEquals(
            "data:application/pdf;base64,AQID",
            output.asInputFile().fileData().orElseThrow(),
        )
    }

    private fun request(
        tools: List<AiToolCallback> = emptyList(),
        toolChoice: AiToolChoice = AiToolChoice.Auto,
        messages: List<Conversation.Message> = listOf(userMessage()),
    ): AiRuntimeRequest = AiRuntimeRequest(
        systemPrompts = listOf("Answer precisely."),
        messages = messages,
        tools = tools,
        options = AiRuntimeOptions(toolChoice = toolChoice),
    )

    private fun userMessage() = Conversation.Message(
        id = Conversation.Message.Id("message-1"),
        conversationId = Conversation.Id("conversation-1"),
        role = Conversation.Message.Role.USER,
        content = listOf(Conversation.Message.ContentItem.UserMessage("Current Kotlin release?")),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun imageToolResultMessage() = Conversation.Message(
        id = Conversation.Message.Id("tool-result-1"),
        conversationId = Conversation.Id("conversation-1"),
        role = Conversation.Message.Role.USER,
        content = listOf(
            Conversation.Message.ContentItem.ToolResult(
                toolUseId = Conversation.Message.ContentItem.ToolCall.Id("call-screenshot"),
                toolName = "grz_capture_screenshot",
                result = listOf(
                    Conversation.Message.ContentItem.ToolResult.Data.Base64Data(
                        data = "AQID",
                        mediaType = Conversation.Message.MediaType.parse("image/png"),
                        fileName = "worker-screen.png",
                    )
                ),
                isError = false,
            )
        ),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun documentToolResultMessage() = Conversation.Message(
        id = Conversation.Message.Id("tool-result-document"),
        conversationId = Conversation.Id("conversation-1"),
        role = Conversation.Message.Role.USER,
        content = listOf(
            Conversation.Message.ContentItem.ToolResult(
                toolUseId = Conversation.Message.ContentItem.ToolCall.Id("call-document"),
                toolName = "grz_read_document",
                result = listOf(
                    Conversation.Message.ContentItem.ToolResult.Data.Base64Data(
                        data = "AQID",
                        mediaType = Conversation.Message.MediaType.parse("application/pdf"),
                        fileName = "report.pdf",
                    )
                ),
                isError = false,
            )
        ),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun testTool(): AiToolCallback = object : AiToolCallback {
        override val metadata = ServerToolMetadata
        override val definition = AiToolDefinition(
            name = "test_tool",
            description = "Test function tool",
            inputSchema = """{"type":"object","properties":{}}""",
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String = "ok"
    }
}
