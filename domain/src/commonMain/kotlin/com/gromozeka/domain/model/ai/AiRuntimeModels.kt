package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.tool.AiToolCallback
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Canonical request for one model execution step inside Gromozeka.
 *
 * One execution step means one provider call. A full user-visible turn may
 * require multiple model steps because of tool execution loops.
 */
data class AiRuntimeRequest(
    val systemPrompts: List<String>,
    val messages: List<Conversation.Message>,
    val tools: List<AiToolCallback> = emptyList(),
    val options: AiRuntimeOptions = AiRuntimeOptions(),
)

data class AiRuntimeOptions(
    val maxOutputTokens: Int? = null,
    val reasoning: AiReasoningConfig? = null,
    val autoCompactionThresholdTokens: Int? = null,
    val toolChoice: AiToolChoice = AiToolChoice.Auto,
    val responseFormat: AiResponseFormat = AiResponseFormat.Text,
    val toolContext: Map<String, Any?> = emptyMap(),
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0) { "AI runtime max output tokens must be positive" }
        require(autoCompactionThresholdTokens == null || autoCompactionThresholdTokens > 0) {
            "AI runtime auto-compaction threshold tokens must be positive"
        }
    }
}

sealed class AiToolChoice {
    data object Auto : AiToolChoice()
    data object None : AiToolChoice()
    data object RequiredAny : AiToolChoice()
    data class RequiredTool(val name: String) : AiToolChoice()
}

sealed class AiResponseFormat {
    data object Text : AiResponseFormat()

    data class JsonSchema(
        val name: String,
        val schema: JsonObject,
        val description: String? = null,
        val strict: Boolean = true,
    ) : AiResponseFormat()
}

data class AiRuntimeCapabilities(
    val supportsAutoCompaction: Boolean = false,
)

data class AiRuntimeResponse(
    val messages: List<AiAssistantMessage>,
    val usage: AiUsage? = null,
    val finishReason: String? = null,
    val providerMetadata: Map<String, Any?> = emptyMap(),
) {
    val toolCalls: List<Conversation.Message.ContentItem.ToolCall>
        get() = messages.flatMap { assistantMessage ->
            assistantMessage.content.filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
        }
}

data class AiAssistantMessage(
    val content: List<Conversation.Message.ContentItem>,
    val metadata: Map<String, Any?> = emptyMap()
)

@Serializable
data class AiUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val thinkingTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
    val cacheReadTokens: Int = 0,
) {
    val totalInputTokens: Int
        get() = promptTokens + cacheCreationTokens + cacheReadTokens

    val totalOutputTokens: Int
        get() = completionTokens + thinkingTokens

    val totalTokens: Int
        get() = totalInputTokens + totalOutputTokens
}
