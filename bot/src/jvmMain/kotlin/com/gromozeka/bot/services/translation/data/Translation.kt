package com.gromozeka.bot.services.translation.data

import kotlinx.serialization.Serializable

@Serializable
sealed class Translation {
    @Serializable
    enum class TextDirection {
        LTR,
        RTL
    }

    @Serializable
    abstract class SettingsTranslation {
        // Settings Section Titles
        abstract val voiceSynthesisTitle: String
        abstract val speechRecognitionTitle: String
        abstract val aiSettingsTitle: String
        abstract val apiKeysTitle: String
        abstract val interfaceSettingsTitle: String
        abstract val localizationTitle: String
        abstract val notificationsTitle: String
        abstract val logsAndDiagnosticsTitle: String
        abstract val developerSettingsTitle: String

        // Settings Control Labels
        abstract val enableTtsLabel: String
        abstract val voiceModelLabel: String
        abstract val voiceTypeLabel: String
        abstract val speechSpeedLabel: String
        abstract val enableSttLabel: String
        abstract val recognitionLanguageLabel: String
        abstract val autoSendMessagesLabel: String
        abstract val globalPttHotkeyLabel: String
        abstract val muteAudioDuringPttLabel: String
        abstract val responseFormatLabel: String
        abstract val includeCurrentTimeLabel: String
        abstract val openaiApiKeyLabel: String
        abstract val enableBraveSearchLabel: String
        abstract val braveApiKeyLabel: String
        abstract val enableJinaReaderLabel: String
        abstract val jinaApiKeyLabel: String
        abstract val showSystemMessagesLabel: String
        abstract val alwaysOnTopLabel: String
        abstract val showTabsAtBottomLabel: String
        abstract val errorSoundsLabel: String
        abstract val messageSoundsLabel: String
        abstract val readySoundsLabel: String
        abstract val soundVolumeLabel: String
        abstract val showOriginalJsonLabel: String
        abstract val localizationModeLabel: String
        abstract val exportStringsButton: String
        abstract val exportStringsTooltip: String
        abstract val localizationModeBuiltin: String
        abstract val localizationModeCustom: String
        abstract val builtinLanguageLabel: String

        // Settings Descriptions
        abstract val ttsDescription: String
        abstract val ttsModelDescription: String
        abstract val ttsVoiceDescription: String
        abstract val ttsSpeedDescription: String
        abstract val sttDescription: String
        abstract val sttLanguageDescription: String
        abstract val autoSendDescription: String
        abstract val globalPttDescription: String
        abstract val muteAudioDescription: String
        abstract val responseFormatDescription: String
        abstract val includeTimeDescription: String
        abstract val openaiKeyDescription: String
        abstract val braveSearchDescription: String
        abstract val braveApiKeyDescription: String
        abstract val jinaReaderDescription: String
        abstract val jinaApiKeyDescription: String
        abstract val showSystemDescription: String
        abstract val alwaysOnTopDescription: String
        abstract val showTabsAtBottomDescription: String
        abstract val errorSoundsDescription: String
        abstract val messageSoundsDescription: String
        abstract val readySoundsDescription: String
        abstract val soundVolumeDescription: String
        abstract val showJsonDescription: String

        // Translation Override Section
        abstract val customTranslationInfoLabel: String
        abstract val customTranslationInfoMessage: String
        abstract val translationOverrideStatusLabel: String
        abstract val overrideSuccessMessage: String
        abstract val overrideFailureMessage: String
        abstract val refreshTranslationsLabel: String
        abstract val refreshTranslationsDescription: String
        abstract val refreshTranslationsButton: String
        abstract val exportTranslationLabel: String
        abstract val exportTranslationDescription: String
        abstract val exportTranslationButton: String

        // Language Selection
        abstract val languageSelectionDescription: String

        // Theming Section
        abstract val themingTitle: String
        abstract val themeSelectionLabel: String
        abstract val themeSelectionDescription: String
        abstract val customThemeInfoLabel: String
        abstract val customThemeInfoMessage: String
        abstract val themeOverrideStatusLabel: String
        abstract val themeOverrideSuccessMessage: String
        abstract val themeOverrideFailureMessage: String
        abstract val refreshThemesLabel: String
        abstract val refreshThemesDescription: String
        abstract val refreshThemesButton: String
        abstract val exportThemeLabel: String
        abstract val exportThemeDescription: String
        abstract val exportThemeButton: String

        // Theme Names
        abstract val themeNameDark: String
        abstract val themeNameLight: String
        abstract val themeNameGromozeka: String

        // Theme Errors
        abstract val themeDeserializationError: String
        abstract val themeFileError: String
        abstract val themeInvalidFormat: String

        // Settings UI
        abstract val settingsTitle: String
        abstract val closeSettingsText: String
    }

    abstract val languageCode: String
    abstract val languageName: String
    abstract val textDirection: TextDirection

    abstract val settings: SettingsTranslation

    abstract val appName: String
    abstract val helloWorld: String
    abstract val switchLanguage: String

    abstract val newSessionButton: String
    abstract val newButton: String
    abstract val forkButton: String
    abstract val restartButton: String
    abstract val continueButton: String
    abstract val newSessionShort: String
    abstract val cancelButton: String
    abstract val saveButton: String
    abstract val builtinStringsMode: String
    abstract val externalStringsMode: String

    abstract val viewOriginalJson: String

    abstract val renameTabTitle: String
    abstract val tabNameLabel: String
    abstract val projectsTabTooltip: String

    abstract val refreshSessionsTooltip: String
    abstract val settingsTooltip: String
    abstract val searchSessionsTooltip: String
    abstract val messageCountTooltip: String
    abstract val closeTabTooltip: String
    abstract val screenshotTooltip: String
    abstract val sendingMessageTooltip: String
    abstract val sendMessageTooltip: String
    abstract val recordingTooltip: String
    abstract val pttButtonTooltip: String
    abstract val builtinStringsTooltip: String
    abstract val externalStringsTooltip: String

    abstract val searchSessionsPlaceholder: String

    abstract val showJsonMenuItem: String
    abstract val copyMarkdownMenuItem: String
    abstract val speakMenuItem: String

    abstract val executingStatus: String
    abstract val errorClickToViewStatus: String
    abstract val successClickToViewStatus: String

    abstract val alwaysOnTopSuffix: String
    abstract val devModeSuffix: String

    abstract val quickActionTongueTwister: String
    abstract val quickActionTable: String
    abstract val quickActionGoogleSearch: String
    abstract val quickActionFileList: String

    abstract val searchingForText: String
    abstract val enterSearchQuery: String
    abstract val nothingFoundForText: String
    abstract val foundSessionsText: String
    abstract val noSavedProjectsText: String
    abstract val expandCollapseText: String
    abstract val sessionsCountText: String
    abstract val messagesCountText: String
    abstract val noSessionsText: String
    abstract val contextMenuHint: String
    abstract val contentUnavailable: String
    abstract val imageDisplayText: String
    abstract val parseErrorText: String
    abstract val clearSearchText: String
    abstract val recordingText: String
    abstract val pushToTalkText: String

    companion object {
        val builtIn = listOf(
            EnglishTranslation(),
            RussianTranslation(),
            HebrewTranslation(),
            JapaneseTranslation(),
            ChineseTranslation(),
            ThaiTranslation(),
        ).associateBy { it.languageCode }
    }
}
