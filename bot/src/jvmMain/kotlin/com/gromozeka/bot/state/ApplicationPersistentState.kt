package com.gromozeka.bot.state

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.serialization.Serializable

@Serializable
data class ApplicationPersistentState(
    val tabs: List<Tab> = emptyList()
) {
    @Serializable
    data class Tab(
        val claudeSessionId: ClaudeSessionUuid,
        val projectPath: String
    )
}