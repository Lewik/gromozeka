package com.gromozeka.bot.ui.state

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.serialization.Serializable

/**
 * Root UI state for the entire Gromozeka application
 * Contains all persistent UI state that survives application restarts
 */
@Serializable
data class UIState(
    val tabs: List<Tab> = emptyList(),
    val currentTabIndex: Int? = null,
) {
    /**
     * UI state for a single tab
     * Follows Android's official UiState pattern with ViewModel
     * 
     * Tabs represent UI concept - what user sees as tabs in interface.
     * Each tab can contain a Claude session, but tabs can exist without sessions
     * (e.g., settings tab, logs tab) and sessions can exist without tabs
     * (e.g., background processing sessions).
     * 
     * This state is:
     * - Immutable (thread-safe)
     * - Serializable (direct persistence) 
     * - Single source of truth for tab UI
     */
    @Serializable
    data class Tab(
        val projectPath: String,
        val claudeSessionId: ClaudeSessionUuid = ClaudeSessionUuid.DEFAULT,
        val activeMessageTags: Set<String> = emptySet(),
        val userInput: String = "",
        val isWaitingForResponse: Boolean = false,
    ) {
        companion object {
            fun initial(projectPath: String, initialActiveMessageTags: Set<String> = emptySet()) = Tab(
                projectPath = projectPath,
                activeMessageTags = initialActiveMessageTags
            )
        }
    }
}