package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class OpenAiSubscriptionProgressRealTest {
    @Test
    fun remarksToolResultsAndFinalAnswersSurviveIncrementalAndFreshConnections() = runBlocking {
        if (System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_REAL") != "true") return@runBlocking

        val authFile = requireNotNull(System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_AUTH_FILE")) {
            "Set GROMOZEKA_OPENAI_SUBSCRIPTION_AUTH_FILE to an existing Codex auth.json; the test never refreshes or modifies it"
        }
        val tokens = Json.parseToJsonElement(File(authFile).readText()).jsonObject.getValue("tokens").jsonObject
        val accessToken = tokens.getValue("access_token").jsonPrimitive.content
        val claims = Json.parseToJsonElement(
            Base64.getUrlDecoder().decode(accessToken.split('.')[1]).decodeToString()
        ).jsonObject
        val expiresAt = claims.getValue("exp").jsonPrimitive.long
        require(expiresAt > Clock.System.now().epochSeconds + 300) { "Codex access token needs renewal before this test" }
        val session = OpenAiSubscriptionSession(
            accessToken = accessToken,
            refreshToken = "unused",
            idToken = null,
            accountId = tokens.getValue("account_id").jsonPrimitive.content,
            expiresAt = expiresAt,
        )
        val model = requireNotNull(System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_MODEL"))
        val profile = OpenAiSubscriptionModelsClient(baseUrl, clientVersion, 60_000, 30_000)
            .getProfile(session, model)
        assertTrue("low" in profile.supportedReasoningEfforts)
        println("Live progress check: model=$model effort=low responsesLite=${profile.useResponsesLite}")

        val conversationId = Conversation.Id(UUID.randomUUID().toString())
        val requestMapper = OpenAiSubscriptionRequestMapper()
        val responseMapper = OpenAiSubscriptionResponseMapper()
        fun client() = OpenAiSubscriptionResponsesClient(
            responseMapper, requestMapper, baseUrl, clientVersion,
            websocketIdleMs = 30_000,
            websocketResponseTimeoutMs = 120_000,
            websocketTransportTimeoutMs = 30_000,
            httpResponseTimeoutMs = 120_000,
        )
        val history = mutableListOf(message(
            conversationId,
            Conversation.Message.Role.USER,
            listOf(Conversation.Message.ContentItem.UserMessage(
                "First send a brief Russian commentary sentence saying you will read the file. " +
                    "Then call read_file for README.md. Do not invent the file contents. " +
                    "After the result arrives, report its marker in your final answer."
            )),
        ))
        val options = AiRuntimeOptions(
            reasoning = AiReasoningConfig(effort = AiReasoningEffort.LOW),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
            responseFormat = AiResponseFormat.JsonSchema(
                name = "gromozeka_assistant_response",
                schema = Json.parseToJsonElement("""{
                    "type":"object","additionalProperties":false,
                    "properties":{
                        "fullText":{"type":"string"},"ttsText":{"type":"string"},
                        "voiceTone":{"type":"string"},"attentionRequested":{"type":"boolean"}
                    },"required":["fullText","ttsText","voiceTone","attentionRequested"]
                }""").jsonObject,
            ),
        )
        suspend fun step(client: OpenAiSubscriptionResponsesClient): AiRuntimeResponse {
            val request = requestMapper.toRequest(
                AiRuntimeRequest(
                    systemPrompts = listOf(
                        "You are testing a chat protocol. Send a short plain-text commentary before using tools. " +
                            "Use the configured JSON schema for final answers: fullText is the visible answer; " +
                            "ttsText and voiceTone are empty; attentionRequested is false."
                    ),
                    messages = history.toList(), tools = listOf(readFileTool), options = options,
                ),
                profile, conversationId.value, connectionId = connectionId,
            )
            assertEquals("low", request.reasoning?.get("effort")?.jsonPrimitive?.content)
            val parsed = client.create(session, conversationId.value, request, profile, options.assistantResponseFormat)
            val response = responseMapper.toRuntimeResponse(
                parsed.outputItems, parsed.completed, conversationId.value, connectionId,
                "live-progress-$model", model, options.assistantResponseFormat,
            )
            println("Live progress step: model=$model outcome=${response.outcome} items=" +
                parsed.outputItems.map { it["type"]?.jsonPrimitive?.contentOrNull })
            response.messages.forEach { assistant ->
                history += message(conversationId, Conversation.Message.Role.ASSISTANT, assistant.content).copy(
                    providerMetadata = JsonObject((response.providerMetadata + assistant.metadata).mapValues { (_, value) ->
                        value as? JsonElement ?: JsonPrimitive(value.toString())
                    }),
                )
            }
            return response
        }

        val connectedClient = client()
        val initial = step(connectedClient)
        assertEquals(AiStepOutcome.TOOL_CALLS, initial.outcome)
        val blocks = initial.messages.flatMap { it.content }
        val remarkIndex = blocks.indexOfFirst { it is Conversation.Message.ContentItem.AssistantMessage }
        val callIndex = blocks.indexOfFirst { it is Conversation.Message.ContentItem.ToolCall }
        assertTrue(remarkIndex in 0 until callIndex, "Expected a readable remark before the tool call")
        val toolCall = initial.toolCalls.single()
        assertEquals("read_file", toolCall.call.name)
        assertEquals("README.md", toolCall.call.input.jsonObject["path"]?.jsonPrimitive?.content)
        history += message(conversationId, Conversation.Message.Role.USER, listOf(
            Conversation.Message.ContentItem.ToolResult(
                toolUseId = toolCall.id, toolName = toolCall.call.name,
                result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("File marker: $marker")),
            )
        ))
        assertFinal(step(connectedClient))

        history += message(conversationId, Conversation.Message.Role.USER, listOf(
            Conversation.Message.ContentItem.UserMessage("Repeat the same file marker from the previous result. Do not call any tools.")
        ))
        assertFinal(step(client()))
    }

    private fun assertFinal(response: AiRuntimeResponse) {
        assertEquals(AiStepOutcome.COMPLETE, response.outcome)
        assertTrue(response.toolCalls.isEmpty())
        val text = response.messages.flatMap { it.content }
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }
        assertTrue(marker in text, "Expected the tool result marker in the final answer")
    }

    private fun message(
        conversationId: Conversation.Id,
        role: Conversation.Message.Role,
        content: List<Conversation.Message.ContentItem>,
    ) = Conversation.Message(
        id = Conversation.Message.Id(UUID.randomUUID().toString()),
        conversationId = conversationId, role = role, content = content, createdAt = Clock.System.now(),
    )

    private val readFileTool = object : AiToolCallback {
        override val definition = AiToolDefinition(
            name = "read_file", description = "Read a project file by relative path.",
            inputSchema = """{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string"}},"required":["path"]}""",
        )
        override val metadata = ServerToolMetadata
        override fun call(toolInput: String, context: ToolExecutionContext?): String =
            error("The test supplies synthetic tool results; providers must not execute tools")
    }

    private companion object {
        const val baseUrl = "https://chatgpt.com/backend-api/codex"
        const val clientVersion = "1.4.9"
        const val connectionId = "live-progress-connection"
        const val marker = "PROGRESS_ROUNDTRIP_OK"
    }
}
