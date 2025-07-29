package com.gromozeka.bot.model

import kotlinx.datetime.Instant

/**
 * Represents a Claude Code chat session with metadata
 */
data class ChatSession(
    val sessionId: String,
    val projectPath: String,
    val firstMessage: String,
    val lastTimestamp: Instant,
    val messageCount: Int,
    val preview: String
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
    fun displayTime(): String = lastTimestamp.toString().substringBefore('T')
    
    /**
     * Display project name (last part of path)
     */
    fun displayProject(): String = projectPath
}