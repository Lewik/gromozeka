package com.gromozeka.domain.service

interface SettingsProvider {
    val sttMainLanguage: String
    val ttsModel: String
    val ttsVoice: String
    val ttsSpeed: Float
    val aiProvider: AIProvider
    val mode: AppMode
}
