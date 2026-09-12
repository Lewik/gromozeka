package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.tool.ProviderNativeTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.stereotype.Component

internal fun JsonObject.isStandaloneWebCall(): Boolean =
    this["type"] == JsonPrimitive("function_call") &&
        this["namespace"] == JsonPrimitive("web") && this["name"] == JsonPrimitive("run")

internal fun AiRuntimeRequest.isSubscriptionWebSearchAllowed(enabled: Boolean): Boolean =
    enabled && options.toolChoice !is AiToolChoice.None &&
        ProviderNativeTool.OPENAI_SUBSCRIPTION_WEB_SEARCH.isAllowed(options.toolAccess)

@Component
class OpenAiSubscriptionWebSearch(private val client: OpenAiSubscriptionResponsesClient) {
    suspend fun executePending(
        request: AiRuntimeRequest,
        session: OpenAiSubscriptionSession,
        conversationKey: String,
        connectionId: String,
        modelName: String,
        enabled: Boolean,
    ): AiRuntimeResponse? {
        val pending = pendingCall(request.messages, connectionId, modelName) ?: return null
        val callId = requireNotNull(pending["call_id"]?.jsonPrimitive?.contentOrNull)
        var isError = false
        val output = if (!request.isSubscriptionWebSearchAllowed(enabled)) {
            isError = true
            "Web search is disabled by the current connection or tool access policy."
        } else {
            try {
                client.search(session, conversationKey, buildJsonObject {
                    put("id", conversationKey)
                    put("model", modelName)
                    put("commands", Json.parseOpenAiSubscriptionToolArguments(pending.getValue("arguments").jsonPrimitive.content))
                    val input = recentInput(request.messages)
                    if (input.isNotEmpty()) put("input", JsonArray(input))
                    put("settings", buildJsonObject {
                        put("external_web_access", true)
                        put("allowed_callers", JsonArray(listOf(JsonPrimitive("direct"))))
                    })
                    put("max_output_tokens", 10000)
                })
            } catch (error: OpenAiSubscriptionUnauthorizedException) {
                throw error
            } catch (error: OpenAiSubscriptionApiException) {
                isError = true
                error.message ?: "Web search failed"
            }
        }
        val result = ContentItem.ToolResult(
            toolUseId = ContentItem.ToolCall.Id(callId),
            toolName = "web.run",
            result = listOf(ContentItem.ToolResult.Data.Text(output)),
            isError = isError,
        )
        val replayItem = buildJsonObject {
            put("type", "function_call_output")
            put("call_id", callId.toOpenAiSubscriptionKey())
            put("output", buildJsonArray { add(buildJsonObject {
                put("type", "input_text")
                put("text", output)
            }) })
        }
        return AiRuntimeResponse(
            messages = listOf(AiAssistantMessage(
                content = listOf(result),
                metadata = mapOf(
                    AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to true,
                    "openaiSubscriptionProviderItems" to JsonArray(listOf(replayItem)),
                ),
            )),
            outcome = AiStepOutcome.CONTINUE,
            providerMetadata = mapOf(
                "provider" to AiConnection.Kind.OPENAI_SUBSCRIPTION.name,
                "connectionId" to connectionId,
                "model" to modelName,
                "conversationKey" to conversationKey,
            ),
        )
    }

    internal fun pendingCall(messages: List<Conversation.Message>, connectionId: String, modelName: String): JsonObject? {
        val currentTurn = messages.drop(messages.indexOfLast { message ->
            message.role == Conversation.Message.Role.USER && message.content.any { it is ContentItem.UserMessage }
        }.coerceAtLeast(0))
        val completed = currentTurn.flatMap { it.content }.filterIsInstance<ContentItem.ToolResult>()
            .mapTo(mutableSetOf()) { it.toolUseId.value }
        return currentTurn.asSequence().filter { message ->
            message.role == Conversation.Message.Role.ASSISTANT &&
                message.providerMetadata[AI_PROVIDER_MANAGED_TOOL_METADATA_KEY] == JsonPrimitive(true) &&
                message.providerMetadata["provider"] == JsonPrimitive(AiConnection.Kind.OPENAI_SUBSCRIPTION.name) &&
                message.providerMetadata["connectionId"] == JsonPrimitive(connectionId) &&
                message.providerMetadata["model"] == JsonPrimitive(modelName)
        }.flatMap { (it.providerMetadata["openaiSubscriptionProviderItems"] as? JsonArray).orEmpty() }
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.isStandaloneWebCall() && it["call_id"]?.jsonPrimitive?.contentOrNull !in completed }
    }

    internal fun recentInput(messages: List<Conversation.Message>): List<JsonObject> {
        val userIndices = messages.indices.filter { index ->
            messages[index].role == Conversation.Message.Role.USER && messages[index].content.any { it is ContentItem.UserMessage }
        }.takeLast(2)
        if (userIndices.isEmpty()) return emptyList()
        var assistantCharactersLeft = 4000
        return messages.subList(userIndices.first(), userIndices.last() + 1).mapNotNull { message ->
            val text = when (message.role) {
                Conversation.Message.Role.USER -> message.content.filterIsInstance<ContentItem.UserMessage>().joinToString("\n") { it.text }
                Conversation.Message.Role.ASSISTANT -> message.content.filterIsInstance<ContentItem.AssistantMessage>()
                    .joinToString("\n") { it.structured.fullText }.take(assistantCharactersLeft).also { assistantCharactersLeft -= it.length }
                else -> ""
            }
            if (text.isBlank()) null else buildJsonObject {
                put("role", if (message.role == Conversation.Message.Role.USER) "user" else "assistant")
                put("type", "message")
                put("content", buildJsonArray { add(buildJsonObject {
                    put("type", if (message.role == Conversation.Message.Role.USER) "input_text" else "output_text")
                    put("text", text)
                }) })
            }
        }
    }

    companion object {
        val toolDefinition: JsonObject = Json.parseToJsonElement(
            requireNotNull(OpenAiSubscriptionWebSearch::class.java.getResourceAsStream("/openai-subscription/web-tool.json")) {
                "OpenAI subscription web tool schema is missing"
            }.bufferedReader().use { it.readText() }
        ).jsonObject
    }
}
