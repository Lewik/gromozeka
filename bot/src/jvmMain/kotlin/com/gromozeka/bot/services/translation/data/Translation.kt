package com.gromozeka.bot.services.translation.data

import kotlinx.serialization.Serializable

@Serializable
sealed class Translation {
    @Serializable
    enum class TextDirection {
        LTR,
        RTL
    }

    abstract val languageCode: String
    abstract val languageName: String
    abstract val textDirection: TextDirection

    abstract val appName: String
    abstract val helloWorld: String
    abstract val switchLanguage: String

    abstract val newSessionButton: String
    abstract val newButton: String
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

    abstract val voiceSynthesisTitle: String
    abstract val speechRecognitionTitle: String
    abstract val aiSettingsTitle: String
    abstract val apiKeysTitle: String
    abstract val interfaceSettingsTitle: String
    abstract val localizationTitle: String
    abstract val notificationsTitle: String
    abstract val developerSettingsTitle: String

    abstract val enableTtsLabel: String
    abstract val voiceModelLabel: String
    abstract val voiceTypeLabel: String
    abstract val speechSpeedLabel: String
    abstract val enableSttLabel: String
    abstract val recognitionLanguageLabel: String
    abstract val autoSendMessagesLabel: String
    abstract val globalPttHotkeyLabel: String
    abstract val muteAudioDuringPttLabel: String
    abstract val claudeModelLabel: String
    abstract val responseFormatLabel: String
    abstract val includeCurrentTimeLabel: String
    abstract val openaiApiKeyLabel: String
    abstract val showSystemMessagesLabel: String
    abstract val alwaysOnTopLabel: String
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

    companion object {
        val builtIn = listOf(
            EnglishTranslation(),
            RussianTranslation(),
            HebrewTranslation(),
        ).associateBy { it.languageCode }
    }
}