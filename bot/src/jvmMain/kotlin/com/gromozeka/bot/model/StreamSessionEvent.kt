package com.gromozeka.bot.model

import com.gromozeka.shared.domain.session.ClaudeSessionUuid

/**
 * Session events for reactive UI updates
 */
sealed class StreamSessionEvent {
    data class MessagesUpdated(val messageCount: Int) : StreamSessionEvent()
    data class Error(val message: String) : StreamSessionEvent()
    data class Warning(val message: String) : StreamSessionEvent()
    data object Started : StreamSessionEvent()
    data object Stopped : StreamSessionEvent()
    data object ConversationTurnCompleted : StreamSessionEvent()
    data object StreamReconnected : StreamSessionEvent()
    data object AutoRestarted : StreamSessionEvent()
    data class SessionIdChangedOnStart(val newSessionId: ClaudeSessionUuid) : StreamSessionEvent()
    data class HistoricalMessagesLoaded(val messageCount: Int) : StreamSessionEvent()
    data object InterruptSent : StreamSessionEvent()
    data object InterruptAcknowledged : StreamSessionEvent()
}