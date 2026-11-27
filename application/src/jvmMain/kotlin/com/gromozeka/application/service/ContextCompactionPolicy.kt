package com.gromozeka.application.service

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation

/** Only the latest full checkpoint and the archive before it are immutable. */
internal fun ensureMessagesAreInMutableCompactionWindow(
    headers: List<CompactionMessageHeader>,
    targetMessageIds: Set<Conversation.Message.Id>,
    operation: String,
) {
    val mutableIds = headers.asSequence()
        .drop(if (headers.firstOrNull()?.node?.isFull == true) 1 else 0)
        .mapTo(mutableSetOf(), CompactionMessageHeader::id)
    val lockedTargets = targetMessageIds - mutableIds
    require(lockedTargets.isEmpty()) {
        "Cannot $operation message(s) covered by full context compaction: ${lockedTargets.joinToString { it.value }}"
    }
}
