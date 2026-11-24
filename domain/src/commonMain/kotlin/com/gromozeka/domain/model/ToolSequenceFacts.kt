package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY
import kotlinx.serialization.json.JsonPrimitive

/** Small immutable facts used to validate tool-call sequencing without reading message bodies. */
data class ToolSequenceFacts(
    val callIds: List<String>,
    val resultIds: List<String>,
    val providerManaged: Boolean,
    val toolResultsOnly: Boolean,
    val fullCompactionAnchor: Boolean,
) {
    init {
        require(callIds == callIds.distinct().sorted()) { "Tool call IDs must be sorted and unique" }
        require(resultIds == resultIds.distinct().sorted()) { "Tool result IDs must be sorted and unique" }
    }

    companion object {
        fun from(message: Message): ToolSequenceFacts = ToolSequenceFacts(
            callIds = message.content.filterIsInstance<ContentItem.ToolCall>()
                .map { it.id.value }.distinct().sorted(),
            resultIds = message.content.filterIsInstance<ContentItem.ToolResult>()
                .map { it.toolUseId.value }.distinct().sorted(),
            providerManaged = message.providerMetadata[AI_PROVIDER_MANAGED_TOOL_METADATA_KEY] == JsonPrimitive(true),
            toolResultsOnly = message.content.isNotEmpty() && message.content.all { it is ContentItem.ToolResult },
            fullCompactionAnchor = message.isFullContextCompaction(),
        )
    }
}

/** Ordered body-free replay window returned only when SQL detects a sequence violation. */
data class ToolSequenceMessageHeader(
    val position: Int,
    val id: Message.Id,
    val role: Message.Role,
    val facts: ToolSequenceFacts,
)
