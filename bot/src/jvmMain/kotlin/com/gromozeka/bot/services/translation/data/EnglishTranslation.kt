package com.gromozeka.bot.services.translation.data
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
@SerialName("english")
data class EnglishTranslation(
    override val languageCode: String = LANGUAGE_CODE,
    override val languageName: String = "English",
    override val textDirection: TextDirection = TextDirection.LTR,

    override val appName: String = "Gromozeka",
    override val helloWorld: String = "Hello World!",
    override val switchLanguage: String = "Switch Language",

    override val newSessionButton: String = "New Session",
    override val newButton: String = "New",
    override val continueButton: String = "Continue",
    override val newSessionShort: String = "New",
    override val cancelButton: String = "Cancel",
    override val saveButton: String = "Save",
    override val builtinStringsMode: String = "Builtin",
    override val externalStringsMode: String = "External",

    override val viewOriginalJson: String = "Original JSON",

    override val renameTabTitle: String = "Rename Tab",
    override val tabNameLabel: String = "Tab Name",
    override val projectsTabTooltip: String = "Projects",

    override val refreshSessionsTooltip: String = "Refresh session list",
    override val settingsTooltip: String = "Settings",
    override val searchSessionsTooltip: String = "Search sessions",
    override val messageCountTooltip: String = "Total messages: %d\n(filtered by settings)",
    override val closeTabTooltip: String = "Close tab",
    override val screenshotTooltip: String = "Window screenshot",
    override val sendingMessageTooltip: String = "Sending message...",
    override val sendMessageTooltip: String = "Send message (Shift+Enter)",
    override val recordingTooltip: String = "Recording... (release to stop)",
    override val pttButtonTooltip: String = "Hold to record (PTT)",
    override val builtinStringsTooltip: String = "Use builtin strings",
    override val externalStringsTooltip: String = "Use external JSON strings",

    override val searchSessionsPlaceholder: String = "Search in sessions...",

    override val voiceSynthesisTitle: String = "Voice Synthesis",
    override val speechRecognitionTitle: String = "Speech Recognition",
    override val aiSettingsTitle: String = "AI",
    override val apiKeysTitle: String = "API Keys",
    override val interfaceSettingsTitle: String = "Interface",
    override val localizationTitle: String = "Localization",
    override val notificationsTitle: String = "Notifications",
    override val developerSettingsTitle: String = "Developer",

    override val enableTtsLabel: String = "Enable Text-to-Speech",
    override val voiceModelLabel: String = "Voice Model",
    override val voiceTypeLabel: String = "Voice Type",
    override val speechSpeedLabel: String = "Speech Speed",
    override val enableSttLabel: String = "Enable Speech-to-Text",
    override val recognitionLanguageLabel: String = "Recognition Language",
    override val autoSendMessagesLabel: String = "Auto-send messages",
    override val globalPttHotkeyLabel: String = "Global PTT Hotkey",
    override val muteAudioDuringPttLabel: String = "Mute system audio during PTT",
    override val claudeModelLabel: String = "Claude Model",
    override val responseFormatLabel: String = "Response Format",
    override val includeCurrentTimeLabel: String = "Include current time",
    override val openaiApiKeyLabel: String = "OpenAI API Key",
    override val showSystemMessagesLabel: String = "Show system messages",
    override val alwaysOnTopLabel: String = "Always on top",
    override val errorSoundsLabel: String = "Error sounds",
    override val messageSoundsLabel: String = "Message sounds",
    override val readySoundsLabel: String = "Ready sounds",
    override val soundVolumeLabel: String = "Sound Volume",
    override val showOriginalJsonLabel: String = "Show original JSON",
    override val localizationModeLabel: String = "String Source",
    override val exportStringsButton: String = "Export Current Translation to File",
    override val exportStringsTooltip: String = "Export currently active translation to custom JSON file for editing",
    override val localizationModeBuiltin: String = "Built-in Languages",
    override val localizationModeCustom: String = "Custom JSON File",
    override val builtinLanguageLabel: String = "Built-in Language",

    override val showJsonMenuItem: String = "Show JSON",
    override val copyMarkdownMenuItem: String = "Copy as Markdown",
    override val speakMenuItem: String = "Speak",

    override val executingStatus: String = "Executing...",
    override val errorClickToViewStatus: String = "Error - click to view",
    override val successClickToViewStatus: String = "Success - click to view result",

    override val alwaysOnTopSuffix: String = " [Always On Top]",
    override val devModeSuffix: String = " [DEV]",

    override val quickActionTongueTwister: String = "🗣 Tongue Twister",
    override val quickActionTable: String = "📊 Table",
    override val quickActionGoogleSearch: String = "🔍 Google About Google",
    override val quickActionFileList: String = "📁 execute ls"
) : Translation() {
    companion object {
        const val LANGUAGE_CODE = "en"
    }
}