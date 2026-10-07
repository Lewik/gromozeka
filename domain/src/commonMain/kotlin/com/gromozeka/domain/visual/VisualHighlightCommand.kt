package com.gromozeka.domain.visual

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.Serializable

/** One-shot presentation instruction. Never persisted in Visual, state, or button snapshots. */
@Serializable
data class VisualHighlightCommand(
    val commandId: String,
    val conversationId: Conversation.Id,
    val visualId: String,
    val documentRevision: Long,
    val elementIds: List<String>,
) {
    init {
        require(commandId.isNotBlank() && commandId.length <= 128)
        require(elementIds.size <= MAX_TARGETS && elementIds.distinct().size == elementIds.size)
        require(elementIds.all { it.isNotBlank() && it.length <= 128 })
    }

    companion object { const val MAX_TARGETS = 16 }
}
