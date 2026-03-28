package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.tool.AiToolCallback

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
    val maxTokens: Int? = null,
    val thinking: AgentDefinition.ThinkingConfig? = null,
    val outputConfig: AgentDefinition.OutputConfig? = null,
    val toolChoice: AiToolChoice = AiToolChoice.Auto,
    val toolContext: Map<String, Any?> = emptyMap(),
)

sealed class AiToolChoice {
    data object Auto : AiToolChoice()
    data object RequiredAny : AiToolChoice()
    data class RequiredTool(val name: String) : AiToolChoice()
}

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
