package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
import com.gromozeka.domain.model.ai.AI_REPLAY_THREAD_METADATA_KEY
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class CompactionCoverage(
    val coversAllPrevious: Boolean,
    val sourceMessageIds: List<Message.Id> = emptyList(),
    val opaque: Boolean,
    val provider: String?,
    val invalidatedReplayMessageIds: List<Message.Id> = emptyList(),
) {
    constructor(result: ContextCompactionResult) : this(
        result.coversAllPrevious, result.sourceMessageIds, result.payload is ContextCompactionResult.Payload.OpaqueProviderState,
        result.providerScope?.provider, result.invalidatedReplayMessageIds,
    )
}

/** Immutable selection metadata. No message text, tool arguments/results or encrypted state. */
@Serializable
data class CompactionMessageHeader(
    val id: Message.Id,
    val role: Message.Role,
    val characters: Long,
    val thinkingCharacters: Long = 0,
    val author: Message.Author.User? = null,
    val streaming: Boolean = false,
    val calls: Set<ContentItem.ToolCall.Id> = emptySet(),
    val results: Set<ContentItem.ToolCall.Id> = emptySet(),
    val providerManaged: Boolean = false,
    val coverage: List<CompactionCoverage> = emptyList(),
    val standaloneCheckpoint: Boolean = false,
    val portableStandaloneCheckpoint: Boolean = false,
    val retainedReplay: RetainedOpenAiReplay? = null,
    val replayMetadata: JsonObject = JsonObject(emptyMap()),
) {
    val node get() = CompactionNode(id, coverage)

    companion object {
        private val replayKeys = setOf("provider", "connectionId", "model", "modelConfigurationId", AI_REPLAY_THREAD_METADATA_KEY)
        fun from(message: Message) = CompactionMessageHeader(
            message.id, message.role, MessageCompactionTextRenderer.characterCount(message),
            thinkingCharacters = message.content.filterIsInstance<ContentItem.Thinking>()
                .sumOf { if (it.thinking.isBlank()) 0L else it.thinking.length.toLong() },
            author = message.author as? Message.Author.User,
            streaming = message.content.any { it.state == Message.BlockState.STREAMING },
            calls = message.content.filterIsInstance<ContentItem.ToolCall>().mapTo(mutableSetOf()) { it.id },
            results = message.content.filterIsInstance<ContentItem.ToolResult>().mapTo(mutableSetOf()) { it.toolUseId },
            providerManaged = message.providerMetadata[AI_PROVIDER_MANAGED_TOOL_METADATA_KEY] == JsonPrimitive(true),
            coverage = message.compactions().map(::CompactionCoverage),
            standaloneCheckpoint = message.content.singleOrNull() is ContextCompactionResult,
            portableStandaloneCheckpoint = message.compactions().isNotEmpty() &&
                message.content.count { it !is ContentItem.Thinking } == 1,
            retainedReplay = message.retainedOpenAiReplay(),
            replayMetadata = JsonObject(message.providerMetadata.filterKeys { it in replayKeys }),
        )
    }
}
