package com.gromozeka.bot.settings

import kotlinx.serialization.Serializable

@Serializable
enum class AIProvider {
    OLLAMA,
    GEMINI
}
