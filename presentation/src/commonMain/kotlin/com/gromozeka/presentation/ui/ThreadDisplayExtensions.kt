package com.gromozeka.presentation.ui

import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.services.translation.data.Translation
import kotlin.time.Clock
import kotlin.time.Instant

fun Conversation.displayPreview(translation: Translation): String {
    // Messages are loaded separately via ConversationService.loadCurrentMessages()
    // so we can only show displayName here
    return effectiveDisplayName(translation)
}

fun Conversation.displayTime(translation: Translation): String {
    val duration = Clock.System.now() - updatedAt
    val exactTime = updatedAt.toString().substring(0, 16).replace('T', ' ')

    val relativeTime = formatRelativeTime(updatedAt, translation).takeIf { duration.inWholeDays < 7 }

    return if (relativeTime != null) {
        "$exactTime ($relativeTime)"
    } else {
        exactTime
    }
}

fun Conversation.effectiveDisplayName(translation: Translation): String {
    return displayName.takeIf { it.isNotBlank() } ?: translation.text("common.newConversation")
}

fun formatRelativeTime(timestamp: Instant, translation: Translation): String {
    val now = Clock.System.now()
    val duration = now - timestamp
    return when {
        duration.inWholeMinutes < 1 -> translation.text("common.now")
        duration.inWholeMinutes < 60 -> translation.plural("common.minutesAgo", duration.inWholeMinutes)
        duration.inWholeHours < 24 -> translation.plural("common.hoursAgo", duration.inWholeHours)
        duration.inWholeDays < 7 -> translation.plural("common.daysAgo", duration.inWholeDays)
        else -> timestamp.toString().substring(0, 16).replace('T', ' ')
    }
}
