package com.gromozeka.bot.model

data class ProjectGroup(
    val projectPath: String,
    val projectName: String,
    val sessions: List<ChatSession>,
    val isExpanded: Boolean = true,
) {
    fun displayName(): String = projectName

    fun sessionCount(): Int = sessions.size

    fun lastActivity() = sessions.maxOfOrNull { it.lastTimestamp }
}