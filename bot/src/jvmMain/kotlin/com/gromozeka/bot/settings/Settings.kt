package com.gromozeka.bot.settings

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val claudeProjectPath: String? = null,
    val enableTts: Boolean = true,
    val enableStt: Boolean = true,
    val autoSend: Boolean = true
)

@Serializable
enum class AppMode {
    DEV,
    PROD
}