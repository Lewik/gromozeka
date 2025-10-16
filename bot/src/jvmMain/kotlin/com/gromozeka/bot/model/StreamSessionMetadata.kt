package com.gromozeka.bot.model

import com.gromozeka.shared.domain.session.SessionUuid
import kotlinx.datetime.LocalDateTime

/**
 * Session metadata for UI display
 */
data class StreamSessionMetadata(
    val sessionId: SessionUuid,
    val projectPath: String,
    val title: String,
    val lastModified: LocalDateTime,
    val messageCount: Int,
)