package com.gromozeka.domain.service

interface SettingsProvider {
    val sttMainLanguage: String
    val ttsModel: String
    val ttsVoice: String
    val ttsSpeed: Float
    val aiProvider: AIProvider
    val mode: AppMode
    val homeDirectory: String  // Absolute path to Gromozeka home directory
    
    // Web Tools
    val enableBraveSearch: Boolean
    val braveApiKey: String?
    val enableJinaReader: Boolean
    val jinaApiKey: String?
}
