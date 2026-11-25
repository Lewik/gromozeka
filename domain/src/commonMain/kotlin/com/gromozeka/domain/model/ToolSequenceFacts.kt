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
    /** True when this row directly violates the stored call/result sequence. */
    val requiresRepair: Boolean,
)

/**
 * Bodies needed to repair an invalid sequence. Direct violations always need their body. Adjacent
 * assistant call rows are included because the fixer may merge that whole group into one message.
 */
fun toolSequenceRepairBodyIds(headers: List<ToolSequenceMessageHeader>): List<Message.Id> {
    if (headers.isEmpty()) return emptyList()
    require(headers.zipWithNext().all { (left, right) -> left.position < right.position }) {
        "Tool sequence headers must be ordered by position"
    }
    require(headers.any(ToolSequenceMessageHeader::requiresRepair)) {
        "Invalid tool sequence window has no repair rows"
    }

    fun ToolSequenceMessageHeader.mayMergeWithAdjacentCalls(): Boolean =
        role == Message.Role.ASSISTANT &&
            !facts.providerManaged &&
            facts.callIds.isNotEmpty() &&
            facts.resultIds.isEmpty()

    val required = BooleanArray(headers.size) { headers[it].requiresRepair }
    var expanded: Boolean
    do {
        expanded = false
        headers.indices.forEach { index ->
            if (!required[index] || !headers[index].mayMergeWithAdjacentCalls()) return@forEach
            listOf(index - 1, index + 1).forEach { neighbor ->
                if (neighbor in headers.indices &&
                    !required[neighbor] &&
                    headers[neighbor].mayMergeWithAdjacentCalls()
                ) {
                    required[neighbor] = true
                    expanded = true
                }
            }
        }
    } while (expanded)

    return headers.indices.filter(required::get).map { headers[it].id }
}
