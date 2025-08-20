
package com.gromozeka.bot.model

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Represents a Claude Code chat session with metadata
 */
data class ChatSessionMetadata(
    val claudeSessionId: ClaudeSessionUuid,
    val projectPath: String,
    val firstMessage: String,
    val lastTimestamp: Instant,
    val messageCount: Int,
    val preview: String,
) {
    /**
     * Formatted preview for display in session list
     */
    fun displayPreview(): String = if (preview.length > 50) {
        "${preview.take(47)}..."
    } else {
        preview
    }

    /**
     * Formatted timestamp for display
     */
    fun displayTime(): String {
        val now = Clock.System.now()
        val duration = now - lastTimestamp
        val exactTime = lastTimestamp.toString().substring(0, 16).replace('T', ' ')

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

    /**
     * Display project name (last part of path)
     */
    fun displayProject(): String = projectPath
}