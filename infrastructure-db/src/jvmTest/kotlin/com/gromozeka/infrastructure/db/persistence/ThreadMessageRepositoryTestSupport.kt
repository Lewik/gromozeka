package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ThreadMessageLink
import com.gromozeka.domain.repository.ThreadMessageRepository

internal suspend fun ThreadMessageRepository.readArchiveForTest(
    threadId: Conversation.Thread.Id,
): List<Conversation.Message> = readPagesForTest(threadId) { it.message }

internal suspend fun ThreadMessageRepository.readLinksForTest(
    threadId: Conversation.Thread.Id,
): List<ThreadMessageLink> = buildList {
    var afterPosition = -1
    do {
        val page = getLinkPage(threadId, afterPosition, 1_000)
        addAll(page)
        afterPosition = page.lastOrNull()?.position ?: afterPosition
    } while (page.size == 1_000)
}

private suspend fun <T> ThreadMessageRepository.readPagesForTest(
    threadId: Conversation.Thread.Id,
    transform: (com.gromozeka.domain.repository.ThreadMessagePageEntry) -> T,
): List<T> = buildList {
    var afterPosition = -1
    do {
        val page = getMessagePage(threadId, afterPosition, 1_000)
        page.mapTo(this, transform)
        afterPosition = page.lastOrNull()?.position ?: afterPosition
    } while (page.size == 1_000)
}
