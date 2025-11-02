package com.gromozeka.bot.ui

import com.gromozeka.shared.domain.Conversation
import kotlin.time.Clock
import kotlin.time.Instant

fun Conversation.displayPreview(): String {
    // Messages are loaded separately via ConversationService.loadCurrentMessages()
    // so we can only show displayName here
    return effectiveDisplayName()
}

fun Conversation.displayTime(): String {
    val now = Clock.System.now()
    val duration = now - updatedAt
    val exactTime = updatedAt.toString().substring(0, 16).replace('T', ' ')

    val relativeTime = when {
        duration.inWholeMinutes < 1 -> "сейчас"
        duration.inWholeMinutes < 60 -> "${duration.inWholeMinutes}м назад"
        duration.inWholeHours < 24 -> "${duration.inWholeHours}ч назад"
        duration.inWholeDays < 7 -> "${duration.inWholeDays}д назад"
        else -> null
    }

    return if (relativeTime != null) {
        "$exactTime ($relativeTime)"
    } else {
        exactTime
    }
}

fun Conversation.effectiveDisplayName(): String {
    return displayName.takeIf { it.isNotBlank() } ?: "New Conversation"
}

fun formatRelativeTime(timestamp: Instant): String {
    val now = Clock.System.now()
    val duration = now - timestamp
    return when {
        duration.inWholeMinutes < 1 -> "now"
        duration.inWholeMinutes < 60 -> "${duration.inWholeMinutes}m ago"
        duration.inWholeHours < 24 -> "${duration.inWholeHours}h ago"
        duration.inWholeDays < 7 -> "${duration.inWholeDays}d ago"
        else -> timestamp.toString().substring(0, 16).replace('T', ' ')
    }
}
