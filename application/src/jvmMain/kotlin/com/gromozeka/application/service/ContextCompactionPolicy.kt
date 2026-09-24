package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext

/** Only full checkpoints make source history immutable. Derivation is not an edit. */
internal fun ensureMessagesAreNotCoveredByCompaction(
    messages: List<Conversation.Message>,
    targetMessageIds: Set<Conversation.Message.Id>,
    operation: String,
) {
    val lockedTargets = targetMessageIds.intersect(ConversationContext(messages).protectedMessageIds())
    require(lockedTargets.isEmpty()) {
        "Cannot $operation message(s) covered by full context compaction: ${lockedTargets.joinToString { it.value }}"
    }
}
