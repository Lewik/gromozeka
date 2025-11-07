package com.gromozeka.bot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.services.LogEncryptor
import com.gromozeka.bot.services.SettingsService
import com.gromozeka.bot.services.theming.AIThemeGenerator
import com.gromozeka.bot.services.theming.ThemeService
import com.gromozeka.bot.services.theming.data.Theme
import com.gromozeka.bot.services.translation.TranslationService
import com.gromozeka.bot.services.translation.data.Translation
import com.gromozeka.bot.settings.AIProvider
import com.gromozeka.bot.settings.ResponseFormat
import com.gromozeka.bot.settings.Settings
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.io.path.exists

private val log = KLoggers.logger("SettingsPanel")

@Composable
fun SettingsPanel(
    isVisible: Boolean,
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onClose: () -> Unit,
    translationService: TranslationService,
    themeService: ThemeService,
    aiThemeGenerator: AIThemeGenerator,
    logEncryptor: LogEncryptor,
    settingsService: SettingsService,
    ollamaModelService: com.gromozeka.bot.services.OllamaModelService,
    coroutineScope: CoroutineScope,
    onOpenTab: (String) -> Unit, // Callback to open new tab with project path
    onOpenTabWithMessage: ((String, String) -> Unit)? = null, // Callback to open new tab with initial message (uses default agent)
    modifier: Modifier = Modifier,
) {
    val translation = LocalTranslation.current

    // Ollama models state
    var ollamaModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var ollamaModelsError by remember { mutableStateOf<String?>(null) }
    var ollamaModelsLoading by remember { mutableStateOf(false) }

    // Load Ollama models when panel opens and Ollama is selected
    LaunchedEffect(isVisible, settings.defaultAiProvider) {
        if (isVisible && settings.defaultAiProvider == AIProvider.OLLAMA) {
            ollamaModelsLoading = true
            coroutineScope.launch {
                val result = ollamaModelService.listModels()
                ollamaModels = result.models
                ollamaModelsError = result.error
                ollamaModelsLoading = false
            }
        }
    }

    // Refresh themes when panel opens
    LaunchedEffect(isVisible) {
        if (isVisible) {
            themeService.refreshThemes()
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = expandHorizontally(),
        exit = shrinkHorizontally(),
        modifier = modifier // No external padding - panel goes to edge
    ) {
        Surface(
            modifier = Modifier
                .width(533.dp)
                .fillMaxHeight(), // No corner radius for slide-out panel - it's part of the main interface
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                    start = 16.dp // Add left padding since panel is now in Row
                )
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        translation.settings.settingsTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = translation.settings.closeSettingsText)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Settings content
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Audio Settings
                    // Voice Synthesis (TTS) Settings
                    SettingsGroup(title = translation.settings.voiceSynthesisTitle) {
                        SwitchSettingItem(
                            label = translation.settings.enableTtsLabel,
                            description = translation.settings.ttsDescription,
                            value = settings.enableTts,
                            onValueChange = { onSettingsChange(settings.copy(enableTts = it)) }
                        )

                        // Only show TTS settings if TTS is enabled
                        if (settings.enableTts) {
                            DropdownSettingItem(
                                label = translation.settings.voiceModelLabel,
                                description = translation.settings.ttsModelDescription,
                                value = settings.ttsModel,
                                options = listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
                                onValueChange = { onSettingsChange(settings.copy(ttsModel = it)) }
                            )

                            DropdownSettingItem(
                                label = translation.settings.voiceTypeLabel,
                                description = translation.settings.ttsVoiceDescription,
                                value = settings.ttsVoice,
                                options = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer"),
                                onValueChange = { onSettingsChange(settings.copy(ttsVoice = it)) }
                            )

                            SliderSettingItem(
                                label = translation.settings.speechSpeedLabel,
                                description = translation.settings.ttsSpeedDescription,
                                value = settings.ttsSpeed,
                                min = 0.25f,
                                max = 4.0f,
                                step = 0.25f,
                                valueFormat = "%.2fx",
                                onValueChange = { onSettingsChange(settings.copy(ttsSpeed = it)) }
                            )
                        }
                    }

                    // Speech Recognition (STT) Settings  
                    SettingsGroup(title = translation.settings.speechRecognitionTitle) {
                        SwitchSettingItem(
                            label = translation.settings.enableSttLabel,
                            description = translation.settings.sttDescription,
                            value = settings.enableStt,
                            onValueChange = { onSettingsChange(settings.copy(enableStt = it)) }
                        )

                        // Only show STT settings if STT is enabled
                        if (settings.enableStt) {
                            DropdownSettingItem(
                                label = translation.settings.recognitionLanguageLabel,
                                description = translation.settings.sttLanguageDescription,
                                value = settings.sttMainLanguage,
                                options = listOf("en", "ru", "es", "fr", "de", "zh", "ja"),
                                onValueChange = { onSettingsChange(settings.copy(sttMainLanguage = it)) }
                            )

                            SwitchSettingItem(
                                label = translation.settings.autoSendMessagesLabel,
                                description = translation.settings.autoSendDescription,
                                value = settings.autoSend,
                                onValueChange = { onSettingsChange(settings.copy(autoSend = it)) }
                            )

                            SwitchSettingItem(
                                label = translation.settings.globalPttHotkeyLabel,
                                description = translation.settings.globalPttDescription,
                                value = settings.globalPttHotkeyEnabled,
                                onValueChange = { onSettingsChange(settings.copy(globalPttHotkeyEnabled = it)) }
                            )

                            // Warning about disabled global hotkeys
                            Text(
                                text = "⚠️ Global hotkeys temporarily disabled - UI PTT button available",
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp)
                            )

                            // Only show mute option if global PTT is enabled
                            if (settings.globalPttHotkeyEnabled) {
                                SwitchSettingItem(
                                    label = translation.settings.muteAudioDuringPttLabel,
                                    description = translation.settings.muteAudioDescription,
                                    value = settings.muteSystemAudioDuringPTT,
                                    onValueChange = { onSettingsChange(settings.copy(muteSystemAudioDuringPTT = it)) }
                                )
                            }
                        }
                    }

                    // AI Settings
                    SettingsGroup(title = translation.settings.aiSettingsTitle) {
                        // AI Provider Selection
                        DropdownSettingItem(
                            label = "Default AI Provider",
                            description = "Choose the AI provider for new conversations",
                            value = settings.defaultAiProvider.name,
                            options = AIProvider.entries.map { it.name },
                            onValueChange = {
                                val provider = AIProvider.valueOf(it)
                                onSettingsChange(settings.copy(defaultAiProvider = provider))
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Provider-specific settings
                        when (settings.defaultAiProvider) {
                            AIProvider.OLLAMA -> {
                                Text(
                                    text = "Ollama Settings",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )

                                // Ollama Model dropdown with refresh button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        when {
                                            ollamaModelsError != null -> {
                                                Text(
                                                    text = "Ollama Model",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = "Error: $ollamaModelsError",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                                TextFieldSettingItem(
                                                    label = "",
                                                    description = "",
                                                    value = settings.ollamaModel,
                                                    placeholder = "llama3.2",
                                                    onValueChange = {
                                                        onSettingsChange(settings.copy(ollamaModel = it))
                                                    }
                                                )
                                            }

                                            ollamaModelsLoading -> {
                                                Text(
                                                    text = "Ollama Model",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = "Loading models...",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                            }

                                            ollamaModels.isNotEmpty() -> {
                                                DropdownSettingItem(
                                                    label = "Ollama Model",
                                                    description = "Available models from 'ollama list'",
                                                    value = settings.ollamaModel,
                                                    options = ollamaModels,
                                                    onValueChange = {
                                                        onSettingsChange(settings.copy(ollamaModel = it))
                                                    }
                                                )
                                            }

                                            else -> {
                                                TextFieldSettingItem(
                                                    label = "Ollama Model",
                                                    description = "Model name (e.g., llama3.2, mistral, qwen)",
                                                    value = settings.ollamaModel,
                                                    placeholder = "llama3.2",
                                                    onValueChange = {
                                                        onSettingsChange(settings.copy(ollamaModel = it))
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                ollamaModelsLoading = true
                                                val result = ollamaModelService.listModels()
                                                ollamaModels = result.models
                                                ollamaModelsError = result.error
                                                ollamaModelsLoading = false
                                            }
                                        },
                                        modifier = Modifier.padding(top = 24.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh models")
                                    }
                                }

                                TextFieldSettingItem(
                                    label = "Ollama Base URL",
                                    description = "URL of Ollama server",
                                    value = settings.ollamaBaseUrl,
                                    placeholder = "http://localhost:11434",
                                    onValueChange = {
                                        onSettingsChange(settings.copy(ollamaBaseUrl = it))
                                    }
                                )
                            }

                            AIProvider.GEMINI -> {
                                Text(
                                    text = "Gemini Settings",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )

                                // Get available models from Spring AI
                                val geminiModels = remember {
                                    org.springframework.ai.google.genai.GoogleGenAiChatModel.ChatModel
                                        .values()
                                        .map { it.value }
                                        .sortedByDescending { it } // Latest versions first
                                }

                                EditableDropdownSettingItem(
                                    label = "Gemini Model",
                                    description = "Select predefined or enter custom model name",
                                    value = settings.geminiModel,
                                    predefinedOptions = geminiModels,
                                    placeholder = "gemini-2.0-flash",
                                    onValueChange = {
                                        onSettingsChange(settings.copy(geminiModel = it))
                                    }
                                )

                                Text(
                                    text = "Note: Gemini credentials configured in application.yaml",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            AIProvider.CLAUDE_CODE -> {
                                Text(
                                    text = "Claude Code Settings",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )

                                TextFieldSettingItem(
                                    label = "Claude Model",
                                    description = "Model name (e.g., claude-sonnet-4-5, claude-opus-4)",
                                    value = settings.claudeModel ?: "claude-sonnet-4-5",
                                    placeholder = "claude-sonnet-4-5",
                                    onValueChange = {
                                        onSettingsChange(settings.copy(claudeModel = it))
                                    }
                                )

                                Text(
                                    text = "Note: Uses Claude Code CLI with your subscription",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // Common AI settings
                        DropdownSettingItem(
                            label = translation.settings.responseFormatLabel,
                            description = translation.settings.responseFormatDescription,
                            value = settings.responseFormat.name,
                            options = ResponseFormat.entries.map { it.name },
                            onValueChange = {
                                val format = ResponseFormat.valueOf(it)
                                onSettingsChange(settings.copy(responseFormat = format))
                            }
                        )

                        SwitchSettingItem(
                            label = translation.settings.includeCurrentTimeLabel,
                            description = translation.settings.includeTimeDescription,
                            value = settings.includeCurrentTime,
                            onValueChange = { onSettingsChange(settings.copy(includeCurrentTime = it)) }
                        )

                        SwitchSettingItem(
                            label = "Auto-approve all tool requests",
                            description = "Automatically allow all tool executions without showing permission dialogs (affects new sessions only)",
                            value = settings.autoApproveAllTools,
                            onValueChange = { onSettingsChange(settings.copy(autoApproveAllTools = it)) }
                        )
                    }

                    // API Keys
                    SettingsGroup(title = translation.settings.apiKeysTitle) {
                        PasswordSettingItem(
                            label = translation.settings.openaiApiKeyLabel,
                            description = translation.settings.openaiKeyDescription,
                            value = settings.openAiApiKey ?: "",
                            onValueChange = {
                                onSettingsChange(settings.copy(openAiApiKey = it.ifBlank { null }))
                            }
                        )
                    }

                    // UI Settings
                    SettingsGroup(title = translation.settings.interfaceSettingsTitle) {
                        SwitchSettingItem(
                            label = translation.settings.showSystemMessagesLabel,
                            description = translation.settings.showSystemDescription,
                            value = settings.showSystemMessages,
                            onValueChange = { onSettingsChange(settings.copy(showSystemMessages = it)) }
                        )

                        SwitchSettingItem(
                            label = translation.settings.alwaysOnTopLabel,
                            description = translation.settings.alwaysOnTopDescription,
                            value = settings.alwaysOnTop,
                            onValueChange = { onSettingsChange(settings.copy(alwaysOnTop = it)) }
                        )

                        SwitchSettingItem(
                            label = translation.settings.showTabsAtBottomLabel,
                            description = translation.settings.showTabsAtBottomDescription,
                            value = settings.showTabsAtBottom,
                            onValueChange = { onSettingsChange(settings.copy(showTabsAtBottom = it)) }
                        )

                        // UI Scale slider
                        SliderSettingItem(
                            label = "UI Scale",
                            description = "Adjust interface size (0.5 = tiny, 1.0 = normal, 3.0 = huge). Auto-detected on first launch.",
                            value = settings.uiScale,
                            min = 0.5f,
                            max = 3.0f,
                            step = 0.1f,
                            valueFormat = "${(settings.uiScale * 100).toInt()}%",
                            onValueChange = {
                                onSettingsChange(settings.copy(uiScale = it))
                            }
                        )

                        // Font Scale slider
                        SliderSettingItem(
                            label = "Font Scale",
                            description = "Adjust text size (0.5 = small, 1.0 = normal, 2.0 = large)",
                            value = settings.fontScale,
                            min = 0.5f,
                            max = 2.0f,
                            step = 0.1f,
                            valueFormat = "${(settings.fontScale * 100).toInt()}%",
                            onValueChange = {
                                onSettingsChange(settings.copy(fontScale = it))
                            }
                        )
                    }

                    // Localization Settings
                    SettingsGroup(title = translation.settings.localizationTitle) {
                        // Language selection
                        DropdownSettingItem(
                            label = translation.switchLanguage,
                            description = translation.settings.languageSelectionDescription,
                            value = settings.currentLanguageCode,
                            options = Translation.builtIn.keys.toList(),
                            optionLabel = { languageCode ->
                                Translation.builtIn[languageCode]!!.languageName
                            },
                            onValueChange = { newLanguageCode ->
                                onSettingsChange(settings.copy(currentLanguageCode = newLanguageCode))
                            }
                        )

                        InfoSettingItem(
                            label = translation.settings.customTranslationInfoLabel,
                            message = translation.settings.customTranslationInfoMessage,
                            isError = false
                        )

                        // Show override status - automatically based on file existence
                        val overrideResult by translationService.lastOverrideResult.collectAsState()
                        overrideResult?.let { result ->
                            when (result) {
                                is com.gromozeka.bot.services.translation.TranslationOverrideResult.Success -> {
                                    InfoSettingItem(
                                        label = translation.settings.translationOverrideStatusLabel,
                                        message = translation.settings.overrideSuccessMessage.format(result.overriddenFields.size),
                                        isError = false
                                    )
                                }

                                is com.gromozeka.bot.services.translation.TranslationOverrideResult.Failure -> {
                                    InfoSettingItem(
                                        label = translation.settings.translationOverrideStatusLabel,
                                        message = translation.settings.overrideFailureMessage.format(result.error),
                                        isError = true
                                    )
                                }
                            }
                        }

                        ButtonSettingItem(
                            label = translation.settings.refreshTranslationsLabel,
                            description = translation.settings.refreshTranslationsDescription,
                            buttonText = translation.settings.refreshTranslationsButton,
                            onClick = {
                                log.info("Refreshing translations...")
                                translationService.refreshTranslations()
                            }
                        )

                        ButtonSettingItem(
                            label = translation.settings.exportTranslationLabel,
                            description = translation.settings.exportTranslationDescription,
                            buttonText = translation.settings.exportTranslationButton,
                            onClick = {
                                val success = translationService.exportToFile()

                                if (success) {
                                    log.info("Successfully exported translation")
                                    // TODO: Show success notification
                                } else {
                                    log.warn("Failed to export translation")
                                    // TODO: Show error notification  
                                }
                            }
                        )
                    }

                    // Theming Settings
                    SettingsGroup(title = translation.settings.themingTitle) {
                        // Theme selection with refresh button
                        val availableThemes by themeService.availableThemes.collectAsState()
                        DropdownSettingItem(
                            label = translation.settings.themeSelectionLabel,
                            description = translation.settings.themeSelectionDescription,
                            value = settings.currentThemeId,
                            options = availableThemes.keys.toList(),
                            optionLabel = { themeId ->
                                val themeInfo = availableThemes[themeId]
                                when {
                                    themeInfo == null -> themeId
                                    themeInfo.isBuiltIn -> "${
                                        Theme.getThemeNameTranslated(
                                            themeId,
                                            translation
                                        )
                                    } (built-in)"

                                    !themeInfo.isValid -> "${themeInfo.themeName} (${translation.settings.themeInvalidFormat})"
                                    else -> themeInfo.themeName
                                }
                            },
                            optionEnabled = { themeId ->
                                val themeInfo = availableThemes[themeId]
                                themeInfo?.isValid ?: true
                            },
                            onValueChange = { newThemeId ->
                                // Trigger refresh when opening dropdown (lazy loading)
                                if (availableThemes.isEmpty()) {
                                    themeService.refreshThemes()
                                }
                                onSettingsChange(settings.copy(currentThemeId = newThemeId))
                            },
                            trailingContent = {
                                CompactButton(
                                    onClick = {
                                        log.info("Refreshing themes...")
                                        themeService.refreshThemes()
                                    },
                                    tooltip = translation.settings.refreshThemesDescription,
                                    modifier = Modifier.fillMaxHeight()
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = translation.settings.refreshThemesLabel
                                    )
                                }
                            }
                        )

                        // Theme override toggle
                        SwitchSettingItem(
                            label = "Enable Theme Override",
                            description = "Allow custom theme colors from override.json file to modify the selected theme",
                            value = settings.themeOverrideEnabled,
                            onValueChange = { onSettingsChange(settings.copy(themeOverrideEnabled = it)) }
                        )

                        // Theme override info (only show when override is enabled)
                        if (settings.themeOverrideEnabled) {
                            InfoSettingItem(
                                label = translation.settings.customThemeInfoLabel,
                                message = translation.settings.customThemeInfoMessage,
                                isError = false
                            )
                        }

                        // Theme override status (only show when override is enabled)
                        if (settings.themeOverrideEnabled) {
                            val overrideResult by themeService.lastOverrideResult.collectAsState()
                            overrideResult?.let { result ->
                                when (result) {
                                    is com.gromozeka.bot.services.theming.ThemeOverrideResult.Success -> {
                                        InfoSettingItem(
                                            label = translation.settings.themeOverrideStatusLabel,
                                            message = translation.settings.themeOverrideSuccessMessage.format(result.overriddenFields.size),
                                            isError = false
                                        )
                                    }

                                    is com.gromozeka.bot.services.theming.ThemeOverrideResult.Failure -> {
                                        InfoSettingItem(
                                            label = translation.settings.themeOverrideStatusLabel,
                                            message = translation.settings.themeOverrideFailureMessage.format(result.error),
                                            isError = true
                                        )
                                    }
                                }
                            }
                        }


                        // Export theme button
                        ButtonSettingItem(
                            label = translation.settings.exportThemeLabel,
                            description = translation.settings.exportThemeDescription,
                            buttonText = translation.settings.exportThemeButton,
                            onClick = {
                                val success = themeService.exportToFile()
                                if (success) {
                                    log.info("Successfully exported theme")
                                    // TODO: Show success notification
                                } else {
                                    log.warn("Failed to export theme")
                                    // TODO: Show error notification  
                                }
                            }
                        )

                        // AI-powered theme generation from window screenshot
                        ButtonSettingItem(
                            label = "AI Generate Theme from Window",
                            description = "Take a screenshot of a selected window and use AI to automatically generate a theme based on its colors. Opens a new tab with Claude Code for interactive theme generation.",
                            buttonText = "Generate Theme from Window",
                            onClick = {
                                coroutineScope.launch {
                                    val preparedMessage = aiThemeGenerator.prepareThemeGenerationData(coroutineScope)
                                    if (preparedMessage != null) {
                                        if (onOpenTabWithMessage != null) {
                                            onOpenTabWithMessage(
                                                aiThemeGenerator.getWorkingDirectory(),
                                                preparedMessage
                                            )
                                        } else {
                                            onOpenTab(aiThemeGenerator.getWorkingDirectory())
                                        }
                                    }
                                }
                            }
                        )
                    }

                    // Notifications Settings
                    SettingsGroup(title = translation.settings.notificationsTitle) {
                        SwitchSettingItem(
                            label = translation.settings.errorSoundsLabel,
                            description = translation.settings.errorSoundsDescription,
                            value = settings.enableErrorSounds,
                            onValueChange = { onSettingsChange(settings.copy(enableErrorSounds = it)) }
                        )

                        SwitchSettingItem(
                            label = translation.settings.messageSoundsLabel,
                            description = translation.settings.messageSoundsDescription,
                            value = settings.enableMessageSounds,
                            onValueChange = { onSettingsChange(settings.copy(enableMessageSounds = it)) }
                        )

                        SwitchSettingItem(
                            label = translation.settings.readySoundsLabel,
                            description = translation.settings.readySoundsDescription,
                            value = settings.enableReadySounds,
                            onValueChange = { onSettingsChange(settings.copy(enableReadySounds = it)) }
                        )

                        // Volume control (show only if any sound is enabled)
                        if (settings.enableErrorSounds || settings.enableMessageSounds || settings.enableReadySounds) {
                            SliderSettingItem(
                                label = translation.settings.soundVolumeLabel,
                                description = translation.settings.soundVolumeDescription,
                                value = settings.soundVolume,
                                min = 0.0f,
                                max = 1.0f,
                                step = 0.1f,
                                valueFormat = "${(settings.soundVolume * 100).toInt()}%",
                                onValueChange = { onSettingsChange(settings.copy(soundVolume = it)) }
                            )
                        }
                    }

                    // Logs & Diagnostics
                    SettingsGroup(title = translation.settings.logsAndDiagnosticsTitle) {
                        ButtonSettingItem(
                            label = "View Application Logs",
                            description = "Open the folder containing application logs to review activity and troubleshoot issues",
                            buttonText = "Open Folder",
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        val logsPath = settingsService.getLogsDirectory()
                                        if (logsPath.exists()) {
                                            val osName = System.getProperty("os.name").lowercase()
                                            when {
                                                osName.contains("mac") -> {
                                                    ProcessBuilder("open", logsPath.toString()).start()
                                                }

                                                osName.contains("windows") -> {
                                                    ProcessBuilder("explorer", logsPath.toString()).start()
                                                }

                                                else -> {
                                                    ProcessBuilder("xdg-open", logsPath.toString()).start()
                                                }
                                            }
                                            log.info("Opened logs folder: $logsPath")
                                        } else {
                                            log.warn("Logs folder does not exist: $logsPath")
                                        }
                                    } catch (e: Exception) {
                                        log.error(e) { "Failed to open logs folder" }
                                    }
                                }
                            }
                        )

                        ButtonSettingItem(
                            label = "Encrypt Logs",
                            description = "Encrypt logs for secure transmission to developer. Personal information is not logged.",
                            buttonText = "Encrypt Logs",
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        log.info("Starting log encryption...")
                                        val result = logEncryptor.encryptLogs()

                                        if (result.success && result.encryptedFile != null) {
                                            log.info("Log encryption successful: ${result.encryptedFile}")

                                            // Open folder with encrypted file
                                            try {
                                                val encryptedFolder = result.encryptedFile.parent
                                                val osName = System.getProperty("os.name").lowercase()
                                                when {
                                                    osName.contains("mac") -> {
                                                        ProcessBuilder("open", encryptedFolder.toString()).start()
                                                    }

                                                    osName.contains("windows") -> {
                                                        ProcessBuilder("explorer", encryptedFolder.toString()).start()
                                                    }

                                                    else -> {
                                                        ProcessBuilder("xdg-open", encryptedFolder.toString()).start()
                                                    }
                                                }
                                                log.info("Opened encrypted logs folder: $encryptedFolder")
                                            } catch (e: Exception) {
                                                log.error(e) { "Failed to open encrypted logs folder" }
                                            }
                                        } else {
                                            log.error { "Log encryption failed: ${result.error}" }
                                            // TODO: Show error notification
                                        }
                                    } catch (e: Exception) {
                                        log.error(e) { "Unexpected error during log encryption" }
                                        // TODO: Show error notification
                                    }
                                }
                            }
                        )

                        ButtonSettingItem(
                            label = "Clear Application Logs",
                            description = "Delete all log files to immediately free up disk space and start fresh logging.",
                            buttonText = "Clear All",
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        val logsPath = settingsService.getLogsDirectory()
                                        if (logsPath.exists()) {
                                            var deletedCount = 0
                                            java.nio.file.Files.walk(logsPath)
                                                .filter { java.nio.file.Files.isRegularFile(it) }
                                                .forEach { file ->
                                                    try {
                                                        java.nio.file.Files.delete(file)
                                                        deletedCount++
                                                    } catch (e: Exception) {
                                                        log.warn(e) { "Failed to delete log file: $file" }
                                                    }
                                                }
                                            log.info("Cleared $deletedCount log files from $logsPath")
                                            // TODO: Show success notification
                                        } else {
                                            log.warn("Logs folder does not exist: $logsPath")
                                        }
                                    } catch (e: Exception) {
                                        log.error(e) { "Failed to clear logs" }
                                        // TODO: Show error notification
                                    }
                                }
                            }
                        )
                    }

                    // Developer Settings
                    SettingsGroup(title = translation.settings.developerSettingsTitle) {
                        SwitchSettingItem(
                            label = translation.settings.showOriginalJsonLabel,
                            description = translation.settings.showJsonDescription,
                            value = settings.showOriginalJson,
                            onValueChange = { onSettingsChange(settings.copy(showOriginalJson = it)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            content()
        }
    }
}

@Composable
private fun SwitchSettingItem(
    label: String,
    description: String,
    value: Boolean,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.5f
                )
            )
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.3f
                    )
                )
            }
        }

        Switch(
            checked = value,
            onCheckedChange = onValueChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SliderSettingItem(
    label: String,
    description: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    valueFormat: String,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.38f
                )
            )
            Text(
                text = valueFormat,  // valueFormat is already a ready string, not a format template
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.38f
                )
            )
        }

        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.38f
                )
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            steps = if (step > 0f) ((max - min) / step).toInt() - 1 else 0,
            enabled = enabled
        )
    }
}

@Composable
private fun DropdownSettingItem(
    label: String,
    description: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordSettingItem(
    label: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true
        )
    }
}

@Composable
private fun TextFieldSettingItem(
    label: String,
    description: String,
    value: String,
    placeholder: String = "",
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
            } else null,
            singleLine = true
        )
    }
}

@Composable
private fun <T> DropdownSettingItem(
    label: String,
    description: String,
    value: T,
    options: List<T>,
    optionLabel: (T) -> String,
    optionEnabled: (T) -> Boolean = { true },
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (trailingContent != null) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = optionLabel(value),
                        onValueChange = { },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        options.forEach { option ->
                            val enabled = optionEnabled(option)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = optionLabel(option),
                                        color = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(
                                            alpha = 0.38f
                                        )
                                    )
                                },
                                onClick = {
                                    if (enabled) {
                                        onValueChange(option)
                                        expanded = false
                                    }
                                },
                                enabled = enabled
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                trailingContent()
            }
        } else {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = optionLabel(value),
                    onValueChange = { },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        val enabled = optionEnabled(option)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = optionLabel(option),
                                    color = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(
                                        alpha = 0.38f
                                    )
                                )
                            },
                            onClick = {
                                if (enabled) {
                                    onValueChange(option)
                                    expanded = false
                                }
                            },
                            enabled = enabled
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonSettingItem(
    label: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        CompactButton(
            onClick = onClick,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(buttonText)
        }
    }
}

@Composable
private fun InfoSettingItem(
    label: String,
    message: String,
    isError: Boolean = false,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun EditableDropdownSettingItem(
    label: String,
    description: String,
    value: String,
    predefinedOptions: List<String>,
    placeholder: String = "",
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    // Two-level sort: matches first, then by descending order within each group
    val filteredOptions = remember(value, predefinedOptions) {
        if (value.isEmpty()) {
            predefinedOptions
        } else {
            predefinedOptions.sortedWith(
                compareBy<String> { !it.contains(value, ignoreCase = true) } // matches first (false < true)
                    .thenByDescending { it } // preserve descending order within groups
            )
        }
    }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = false, // Editable!
                placeholder = if (placeholder.isNotEmpty()) {
                    { Text(placeholder, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                } else null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                singleLine = true
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                filteredOptions.forEach { option ->
                    // Use key() for better recomposition performance
                    key(option) {
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onValueChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

