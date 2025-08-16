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
    override val quickActionFileList: String = "📁 execute ls",

    override val searchingForText: String = "Searching for \"%s\"...",
    override val enterSearchQuery: String = "Enter search query",
    override val nothingFoundForText: String = "Nothing found for \"%s\"",
    override val foundSessionsText: String = "Found %d sessions:",
    override val noSavedProjectsText: String = "No saved projects\nClick \"New Session\" to start working",
    override val expandCollapseText: String = "Expand/Collapse",
    override val sessionsCountText: String = "%d sessions",
    override val messagesCountText: String = "%d messages",
    override val noSessionsText: String = "No sessions",
    override val contextMenuHint: String = "\nRight click - context menu",
    override val contentUnavailable: String = "Content unavailable",
    override val imageDisplayText: String = "🖼️ [Image %s - %d chars Base64]",
    override val parseErrorText: String = "⚠️ Failed to parse structure",
    override val clearSearchText: String = "Clear search",
    override val recordingText: String = "Recording",
    override val pushToTalkText: String = "Push to Talk"
) : Translation() {

    @Serializable
    data class EnglishSettingsTranslation(
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

        // Settings Descriptions
        override val ttsDescription: String = "Convert AI responses to speech",
        override val ttsModelDescription: String = "Text-to-speech model",
        override val ttsVoiceDescription: String = "Voice for speech synthesis",
        override val ttsSpeedDescription: String = "Speech rate: 0.25x (slowest) to 4.0x (fastest)",
        override val sttDescription: String = "Convert voice input to text",
        override val sttLanguageDescription: String = "Speech recognition language",
        override val autoSendDescription: String = "Send messages immediately after voice input",
        override val globalPttDescription: String = "Enable push-to-talk from anywhere (Cmd+Shift+Space)",
        override val muteAudioDescription: String = "Prevent audio feedback when recording",
        override val claudeModelDescription: String = "AI model to use for responses",
        override val responseFormatDescription: String = "How AI structures voice responses (XML_INLINE recommended)",
        override val includeTimeDescription: String = "Add current date/time once at conversation start",
        override val openaiKeyDescription: String = "Required for TTS and STT services",
        override val showSystemDescription: String = "Display system notifications in chat (errors always shown)",
        override val alwaysOnTopDescription: String = "Keep window above all other applications",
        override val errorSoundsDescription: String = "Play sound notification for error messages",
        override val messageSoundsDescription: String = "Play sound notification for new messages",
        override val readySoundsDescription: String = "Play sound when Claude finishes processing",
        override val soundVolumeDescription: String = "Volume level for all notification sounds",
        override val showJsonDescription: String = "Display raw API responses in chat",

        // Translation Override Section
        override val customTranslationInfoLabel: String = "Custom Translation Info",
        override val customTranslationInfoMessage: String = "💡 Custom translations are loaded automatically if override.json file exists. Use Export → Edit file → Check to customize.",
        override val translationOverrideStatusLabel: String = "Translation Override Status",
        override val overrideSuccessMessage: String = "✅ Custom translations loaded. %d fields customized.",
        override val overrideFailureMessage: String = "❌ Override failed: %s",
        override val refreshTranslationsLabel: String = "Refresh Translations",
        override val refreshTranslationsDescription: String = "Apply current language settings and check for override files",
        override val refreshTranslationsButton: String = "Refresh",
        override val exportTranslationLabel: String = "Export Current Translation",
        override val exportTranslationDescription: String = "Export current translation to override.json file for customization",
        override val exportTranslationButton: String = "Export",

        // Language Selection
        override val languageSelectionDescription: String = "Select interface language",

        // Theming Section
        override val themingTitle: String = "Theming",
        override val themeSelectionLabel: String = "Theme",
        override val themeSelectionDescription: String = "Select the visual theme for the application",
        override val customThemeInfoLabel: String = "Custom Theme Override",
        override val customThemeInfoMessage: String = "You can create a custom JSON file at ~/.gromozeka/themes/override.json to override theme colors. Use the export button below to get started.",
        override val themeOverrideStatusLabel: String = "Theme Override Status",
        override val themeOverrideSuccessMessage: String = "Successfully applied theme override with %d custom fields",
        override val themeOverrideFailureMessage: String = "Failed to apply theme override: %s",
        override val refreshThemesLabel: String = "Refresh Themes",
        override val refreshThemesDescription: String = "Reload theme settings and apply any changes to the override file",
        override val refreshThemesButton: String = "Refresh",
        override val exportThemeLabel: String = "Export Current Theme",
        override val exportThemeDescription: String = "Export the current theme as a JSON file that can be edited and used as override",
        override val exportThemeButton: String = "Export Theme",

        // Theme Names
        override val themeNameDark: String = "Dark",
        override val themeNameLight: String = "Light",
        override val themeNameGromozeka: String = "Gromozeka",

        // Theme Errors
        override val themeDeserializationError: String = "Failed to deserialize",
        override val themeFileError: String = "File error",
        override val themeInvalidFormat: String = "Invalid format",

        override val settingsTitle: String = "Settings",
        override val closeSettingsText: String = "Close settings"
    ) : SettingsTranslation()

    override val settings: SettingsTranslation = EnglishSettingsTranslation()

    companion object {
        const val LANGUAGE_CODE = "en"
    }
}