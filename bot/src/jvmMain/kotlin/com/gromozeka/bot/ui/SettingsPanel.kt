package com.gromozeka.bot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.settings.ResponseFormat
import com.gromozeka.bot.settings.Settings
import com.gromozeka.bot.services.translation.TranslationService
import com.gromozeka.bot.services.translation.data.Translation
import com.gromozeka.bot.services.theming.ThemeService
import com.gromozeka.bot.services.theming.data.Theme
import com.gromozeka.bot.ui.LocalTranslation
import com.gromozeka.bot.platform.ScreenCaptureController
import com.gromozeka.bot.services.SessionManager
import com.gromozeka.bot.services.SettingsService
import com.gromozeka.bot.services.theming.AIThemeGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SettingsPanel(
    isVisible: Boolean,
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onClose: () -> Unit,
    translationService: TranslationService,
    themeService: ThemeService,
    aiThemeGenerator: AIThemeGenerator,
    coroutineScope: CoroutineScope,
    onOpenTab: (String) -> Unit, // Callback to open new tab with project path
    onOpenTabWithMessage: ((String, String) -> Unit)? = null, // Callback to open new tab with initial message
    modifier: Modifier = Modifier,
) {
    val translation = LocalTranslation.current
    
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
                .width(400.dp)
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
                        DropdownSettingItem(
                            label = translation.settings.claudeModelLabel,
                            description = translation.settings.claudeModelDescription,
                            value = settings.claudeModel,
                            options = listOf("sonnet", "haiku", "opus"),
                            onValueChange = { onSettingsChange(settings.copy(claudeModel = it)) }
                        )

                        DropdownSettingItem(
                            label = translation.settings.responseFormatLabel,
                            description = translation.settings.responseFormatDescription,
                            value = settings.responseFormat.name,
                            options = ResponseFormat.values().map { it.name },
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
                                println("[SettingsPanel] Refreshing translations...")
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
                                    println("[SettingsPanel] Successfully exported translation")
                                    // TODO: Show success notification
                                } else {
                                    println("[SettingsPanel] Failed to export translation")
                                    // TODO: Show error notification  
                                }
                            }
                        )
                    }

                    // Theming Settings
                    SettingsGroup(title = translation.settings.themingTitle) {
                        // Theme selection
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
                                    themeInfo.isBuiltIn -> Theme.getThemeNameTranslated(themeId, translation)
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
                            }
                        )

                        // Theme override info
                        InfoSettingItem(
                            label = translation.settings.customThemeInfoLabel,
                            message = translation.settings.customThemeInfoMessage,
                            isError = false
                        )

                        // Theme override status
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

                        // Refresh themes button
                        ButtonSettingItem(
                            label = translation.settings.refreshThemesLabel,
                            description = translation.settings.refreshThemesDescription,
                            buttonText = translation.settings.refreshThemesButton,
                            onClick = {
                                println("[SettingsPanel] Refreshing themes...")
                                themeService.refreshThemes()
                            }
                        )

                        // Export theme button
                        ButtonSettingItem(
                            label = translation.settings.exportThemeLabel,
                            description = translation.settings.exportThemeDescription,
                            buttonText = translation.settings.exportThemeButton,
                            onClick = {
                                val success = themeService.exportToFile()
                                if (success) {
                                    println("[SettingsPanel] Successfully exported theme")
                                    // TODO: Show success notification
                                } else {
                                    println("[SettingsPanel] Failed to export theme")
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
                                        println("[SettingsPanel] Theme generation data prepared successfully")
                                        println("[SettingsPanel] Message length: ${preparedMessage.length}")
                                        
                                        if (onOpenTabWithMessage != null) {
                                            println("[SettingsPanel] Using onOpenTabWithMessage callback")
                                            onOpenTabWithMessage(aiThemeGenerator.getWorkingDirectory(), preparedMessage)
                                        } else {
                                            println("[SettingsPanel] WARNING: onOpenTabWithMessage is null, using fallback onOpenTab")
                                            onOpenTab(aiThemeGenerator.getWorkingDirectory())
                                        }
                                        
                                        println("[SettingsPanel] AI theme generation tab should now be visible with initial message")
                                    } else {
                                        println("[SettingsPanel] AI theme generation failed - data preparation returned null")
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
                                valueFormat = "%.0f%%",
                                onValueChange = { onSettingsChange(settings.copy(soundVolume = it)) }
                            )
                        }
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
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueFormat.format(if (valueFormat.contains("%%")) value * 100 else value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            steps = if (step > 0f) ((max - min) / step).toInt() - 1 else 0
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownSettingItem(
    label: String,
    description: String,
    value: T,
    options: List<T>,
    optionLabel: (T) -> String,
    optionEnabled: (T) -> Boolean = { true },
    onValueChange: (T) -> Unit,
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

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
                                color = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
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

