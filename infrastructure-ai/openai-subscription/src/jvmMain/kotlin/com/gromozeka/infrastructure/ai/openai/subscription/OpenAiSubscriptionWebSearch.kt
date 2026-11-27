package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.safeToolOutputText
import com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.tool.ProviderNativeTool
import com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID
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
        configuration: AiModelConfiguration,
        enabled: Boolean,
    ): AiRuntimeResponse? {
        val connectionId = configuration.connectionId.value
        val modelName = configuration.providerModelId
        val rootMessageId = (request.options.toolContext[TOOL_CONTEXT_TARGET_MESSAGE_ID] as? String)
            ?.let(Conversation.Message::Id)
            ?: return null
        val pending = pendingCall(request.messages, rootMessageId, connectionId, modelName) ?: return null
        val callId = requireNotNull(pending["call_id"]?.jsonPrimitive?.contentOrNull)
        var isError = false
        val output = if (!request.isSubscriptionWebSearchAllowed(enabled)) {
            isError = true
            "Web search is disabled by the current connection or tool access policy."
        } else {
            try {
                client.search(configuration, session, conversationKey, buildJsonObject {
                    put("id", conversationKey)
                    put("model", modelName)
                    put("commands", Json.parseOpenAiSubscriptionToolArguments(pending.getValue("arguments").jsonPrimitive.content))
                    val input = recentInput(request.messages, rootMessageId)
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
                put("text", output.safeToolOutputText())
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

    internal fun pendingCall(
        messages: List<Conversation.Message>,
        rootMessageId: Conversation.Message.Id,
        connectionId: String,
        modelName: String,
    ): JsonObject? {
        val currentTurn = messages.drop(messages.turnRootIndex(rootMessageId))
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

    internal fun recentInput(
        messages: List<Conversation.Message>,
        rootMessageId: Conversation.Message.Id,
    ): List<JsonObject> {
        val rootIndex = messages.turnRootIndex(rootMessageId)
        val lastInputIndex = messages.indices.lastOrNull { index ->
            index >= rootIndex && messages[index].isUserSideInput()
        } ?: return emptyList()
        val previousUserIndex = (rootIndex - 1 downTo 0).firstOrNull { index ->
            messages[index].role == Conversation.Message.Role.USER && messages[index].isUserSideInput()
        }
        val firstInputIndex = previousUserIndex ?: rootIndex
        val userSideIndices = (firstInputIndex..lastInputIndex).filter { messages[it].isUserSideInput() }
        val sourceText = userSideIndices.associateWith { index ->
            messages[index].content.mapNotNull { it.userInputTextOrNull() }.joinToString("\n")
        }.filterValues(String::isNotBlank)
        val latestUserIndex = userSideIndices.lastOrNull { index ->
            index >= rootIndex && messages[index].role == Conversation.Message.Role.USER && index in sourceText
        }
        val requiredIndices = listOfNotNull(
            rootIndex.takeIf(sourceText::containsKey),
            latestUserIndex,
        ).distinct()
        var userSideCharactersLeft = MAX_USER_SIDE_INPUT_CHARACTERS
        val userSideText = mutableMapOf<Int, String>()
        if (requiredIndices.isNotEmpty()) {
            // Preserve both the original turn root and the latest human steering. A short input gives
            // its unused share back to the other required input before optional context is considered.
            val initialShare = MAX_USER_SIDE_INPUT_CHARACTERS / requiredIndices.size
            val budgets = requiredIndices.associateWithTo(mutableMapOf()) { index ->
                minOf(sourceText.getValue(index).length, initialShare)
            }
            userSideCharactersLeft -= budgets.values.sum()
            requiredIndices.forEach { index ->
                val extra = minOf(sourceText.getValue(index).length - budgets.getValue(index), userSideCharactersLeft)
                budgets[index] = budgets.getValue(index) + extra
                userSideCharactersLeft -= extra
            }
            requiredIndices.forEach { index ->
                userSideText[index] = sourceText.getValue(index).truncateForWebSearch(budgets.getValue(index))
            }
        }
        val optionalIndices = userSideIndices.asReversed().filter {
            it !in requiredIndices && messages[it].role == Conversation.Message.Role.USER && it in sourceText
        } + userSideIndices.asReversed().filter {
            it !in requiredIndices && messages[it].role == Conversation.Message.Role.RUNTIME && it in sourceText
        }
        for (index in optionalIndices) {
            if (userSideCharactersLeft == 0) break
            val bounded = sourceText.getValue(index).truncateForWebSearch(userSideCharactersLeft)
            userSideText[index] = bounded
            userSideCharactersLeft -= bounded.length
        }
        var assistantCharactersLeft = 4000
        return messages.subList(firstInputIndex, lastInputIndex + 1).mapIndexedNotNull { offset, message ->
            val index = firstInputIndex + offset
            val text = when (message.role) {
                Conversation.Message.Role.USER,
                Conversation.Message.Role.RUNTIME,
                -> userSideText[index].orEmpty()

                Conversation.Message.Role.ASSISTANT -> message.content.filterIsInstance<ContentItem.AssistantMessage>()
                    .joinToString("\n") { it.structured.fullText }.take(assistantCharactersLeft).also { assistantCharactersLeft -= it.length }
                Conversation.Message.Role.SYSTEM -> ""
            }
            if (text.isBlank()) null else buildJsonObject {
                val userSide = message.role != Conversation.Message.Role.ASSISTANT
                put("role", if (userSide) "user" else "assistant")
                put("type", "message")
                put("content", buildJsonArray { add(buildJsonObject {
                    put("type", if (userSide) "input_text" else "output_text")
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

private fun String.truncateForWebSearch(maxCharacters: Int): String {
    require(maxCharacters >= 0)
    if (length <= maxCharacters) return this
    if (maxCharacters <= WEB_SEARCH_TRUNCATION_MARKER.length) return WEB_SEARCH_TRUNCATION_MARKER.take(maxCharacters)
    return take(maxCharacters - WEB_SEARCH_TRUNCATION_MARKER.length) + WEB_SEARCH_TRUNCATION_MARKER
}

private fun List<Conversation.Message>.turnRootIndex(rootMessageId: Conversation.Message.Id): Int {
    val exact = indexOfFirst { it.id == rootMessageId }
    if (exact >= 0) return exact
    val checkpoint = indexOfLast { message ->
        message.content.filterIsInstance<ContentItem.ContextCompactionResult>().any { result ->
            rootMessageId in result.sourceMessageIds ||
                result.sourceBoundaryMessageId == rootMessageId ||
                result.coversAllPrevious
        }
    }
    require(checkpoint >= 0) {
        "Web search turn root ${rootMessageId.value} is outside the projected history without a covering checkpoint"
    }
    return checkpoint
}

private fun Conversation.Message.isUserSideInput(): Boolean = when (role) {
    Conversation.Message.Role.USER,
    Conversation.Message.Role.RUNTIME,
    -> content.any { it.userInputTextOrNull() != null }

    Conversation.Message.Role.ASSISTANT,
    Conversation.Message.Role.SYSTEM,
    -> false
}

private const val MAX_USER_SIDE_INPUT_CHARACTERS = 16_000
private const val WEB_SEARCH_TRUNCATION_MARKER = "\n[truncated]"
