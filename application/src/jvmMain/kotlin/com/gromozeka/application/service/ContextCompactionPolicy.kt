package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation

internal fun ensureMessagesAreNotCoveredByCompaction(
    messages: List<Conversation.Message>,
    targetMessageIds: Set<Conversation.Message.Id>,
    operation: String,
    allowLatestReadableCompaction: Boolean = false,
) {
    val compactionIndex = messages.indexOfLast { message ->
        message.content.any { it is Conversation.Message.ContentItem.ContextCompactionResult }
    }
    if (compactionIndex < 0) return

    val lockedMessageIds = messages.take(compactionIndex + 1).mapTo(mutableSetOf()) { it.id }
    val latestCompactionMessage = messages[compactionIndex]
    val latestCompactionResults = latestCompactionMessage.content
        .filterIsInstance<Conversation.Message.ContentItem.ContextCompactionResult>()
    val latestCompactionIsReadable = latestCompactionResults.isNotEmpty() &&
        latestCompactionResults.all { result ->
            result.payload is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary
        }
    if (allowLatestReadableCompaction && latestCompactionIsReadable) {
        lockedMessageIds.remove(latestCompactionMessage.id)
    }
    val lockedTargets = targetMessageIds.intersect(lockedMessageIds)
    require(lockedTargets.isEmpty()) {
        "Cannot $operation message(s) covered by context compaction: ${lockedTargets.joinToString { it.value }}"
    }
}
