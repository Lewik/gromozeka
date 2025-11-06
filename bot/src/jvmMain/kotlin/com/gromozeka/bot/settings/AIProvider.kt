package com.gromozeka.bot.settings

import kotlinx.serialization.Serializable

@Serializable
enum class AIProvider {
    CLAUDE_CODE,
    OLLAMA,
    GEMINI
}
