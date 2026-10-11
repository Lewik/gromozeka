package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentEndpoint
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** A preparation failure, not the model's legitimate answer that evidence is missing. */
class ContextSnapshotUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Immutable request view. History is pinned to [target]'s thread and ends at a complete group.
 * Runtime/environment observations are read during assembly, not an atomic distributed snapshot.
 * No turn, queue admission, one-use secret, or replay-anchor mutation is performed to build it.
 */
data class ConversationContextSnapshot(
    val target: AgentEndpoint,
    val runtimeSelection: AiRuntimeSelection,
    val resolvedRuntime: com.gromozeka.domain.service.ResolvedAiRuntime,
    val request: AiRuntimeRequest,
    val capturedAt: Instant,
    val sourceMessageCount: Int,
    val omittedTrailingMessageCount: Int,
) {
    val lastIncludedMessageId: Conversation.Message.Id? get() = request.messages.lastOrNull()?.id
}

/**
 * Operates AFTER the normal provider/coverage projection. Never repairs or writes history.
 * A partially completed parallel batch, including its already returned results, is excluded whole.
 */
internal fun completeToolGroupPrefix(messages: List<Conversation.Message>): List<Conversation.Message> {
    val pending = mutableSetOf<Conversation.Message.ContentItem.ToolCall.Id>()
    val seen = mutableSetOf<Conversation.Message.ContentItem.ToolCall.Id>()
    var completeBoundary = 0
    for ((index, message) in messages.withIndex()) {
        if (message.content.any { it.state == Conversation.Message.BlockState.STREAMING }) break
        for (content in message.content) {
            when (content) {
                is Conversation.Message.ContentItem.ToolCall -> {
                    if (!seen.add(content.id)) {
                        throw ContextSnapshotUnavailableException("Projected context contains a duplicate tool call")
                    }
                    pending += content.id
                }
                is Conversation.Message.ContentItem.ToolResult -> {
                    if (!pending.remove(content.toolUseId)) {
                        throw ContextSnapshotUnavailableException("Projected context contains an unpaired tool result")
                    }
                }
                else -> Unit
            }
        }
        if (pending.isEmpty()) completeBoundary = index + 1
    }
    return messages.take(completeBoundary)
}

/** Shared ordering/text only; ordinary-turn loading and its side effects stay in the engine. */
internal fun assembleConversationSystemPrompts(
    base: List<String>,
    agent: AgentDefinition,
    capabilityCatalog: String?,
    environment: String,
    skills: String?,
    responseFormat: AiModelConfiguration.AssistantResponseFormat,
    includeSuggestedReplies: Boolean,
): List<String> = buildList {
    addAll(base)
    add(currentAgentRuntimePrompt(agent))
    capabilityCatalog?.let(::add)
    add(environment)
    skills?.let(::add)
    AssistantResponseFormatContract.instruction(responseFormat, includeSuggestedReplies)?.let(::add)
}

private fun currentAgentRuntimePrompt(agent: AgentDefinition): String {
    val data = buildJsonObject {
        put("agent_definition_id", agent.id.value)
        put("model_configuration_id", agent.runtimeSelection.modelConfigurationId.value)
    }.toString().replace("<", "\\u003c").replace(">", "\\u003e")
    return """
        <current_agent>
        Current server-managed Agent identity (JSON data): $data
        Use `agent_definition_id` when a tool asks for the current Agent id.
        </current_agent>
    """.trimIndent()
}
