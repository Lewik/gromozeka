package com.gromozeka.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.gromozeka.client.RemoteClientSettings
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.presentation.services.LogEncryptor
import com.gromozeka.presentation.services.OllamaModelService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import com.gromozeka.presentation.services.theming.AIThemeGenerator
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.theming.data.Theme
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.services.translation.data.Translation
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KLoggers.logger("SettingsPanel")

enum class SettingsPanelContentMode {
    Quick,
    AiRuntime,
}
@Composable
fun SettingsPanel(
    isVisible: Boolean,
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    remoteClientSettings: RemoteClientSettings,
    onRemoteClientSettingsChange: (RemoteClientSettings) -> Unit,
    onClose: () -> Unit,
    translationService: TranslationService,
    themeService: ThemeService,
    aiThemeGenerator: AIThemeGenerator,
    logEncryptor: LogEncryptor,
    settingsService: SettingsService,
    ollamaModelService: OllamaModelService,
    coroutineScope: CoroutineScope,
    onOpenTab: (String) -> Unit, // Callback to open new tab with project path
    onOpenTabWithMessage: ((String, String) -> Unit)? = null, // Callback to open new tab with initial message (uses default agent)
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    slideFromRight: Boolean = false,
    contentMode: SettingsPanelContentMode = SettingsPanelContentMode.Quick,
    showCloseButton: Boolean = true,
) {
    val translation = LocalTranslation.current
    val userProfile = settings.userProfile
    val speechSettings = userProfile.speechSettings
    val textToSpeech = speechSettings.textToSpeech
    val speechToText = speechSettings.speechToText
    val agentSettings = userProfile.agentSettings
    val memorySettings = userProfile.memorySettings
    val toolSettings = userProfile.toolSettings
    val deviceSettings = settings.userDeviceSettings
    val uiSettings = deviceSettings.uiSettings
    val themeSettings = uiSettings.theme
    val soundSettings = deviceSettings.soundSettings
    val desktopInputSettings = settings.desktopInputSettings
    val desktopWindowSettings = settings.desktopWindowSettings

    // Refresh themes when panel opens
    LaunchedEffect(isVisible) {
        if (isVisible) {
            themeService.refreshThemes()
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = if (slideFromRight) slideInHorizontally(initialOffsetX = { it }) else expandHorizontally(),
        exit = if (slideFromRight) slideOutHorizontally(targetOffsetX = { it }) else shrinkHorizontally(),
        modifier = modifier // No external padding - panel goes to edge
    ) {
        Surface(
            modifier = if (fullScreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .width(533.dp)
                    .fillMaxHeight()
            },
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

                    if (showCloseButton) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = translation.settings.closeSettingsText)
                        }
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
                    if (contentMode == SettingsPanelContentMode.AiRuntime) {
                    // Audio Settings
                    // Voice Synthesis (TTS) Settings
                    SettingsGroup(title = translation.settings.voiceSynthesisTitle) {
                        SwitchSettingItem(
                            label = translation.settings.enableTtsLabel,
                            description = translation.settings.ttsDescription,
                            value = textToSpeech.enabled,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(
                                            speechSettings = speechSettings.copy(
                                                textToSpeech = speechSettings.textToSpeech.copy(enabled = it)
                                            )
                                        )
                                    }
                                )
                            }
                        )

                        if (textToSpeech.enabled) {
                            DropdownSettingItem(
                                label = translation.settings.voiceTypeLabel,
                                description = translation.settings.ttsVoiceDescription,
                                value = textToSpeech.voice,
                                options = listOf(
                                    "marin",
                                    "cedar",
                                    "alloy",
                                    "ash",
                                    "ballad",
                                    "coral",
                                    "echo",
                                    "fable",
                                    "nova",
                                    "onyx",
                                    "sage",
                                    "shimmer",
                                    "verse",
                                ),
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateUserProfile {
                                            copy(
                                                speechSettings = speechSettings.copy(
                                                    textToSpeech = speechSettings.textToSpeech.copy(voice = it)
                                                )
                                            )
                                        }
                                    )
                                }
                            )

                            SliderSettingItem(
                                label = translation.settings.speechSpeedLabel,
                                description = translation.settings.ttsSpeedDescription,
                                value = textToSpeech.speed,
                                min = 0.25f,
                                max = 4.0f,
                                step = 0.25f,
                                valueFormat = "%.2fx",
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateUserProfile {
                                            copy(
                                                speechSettings = speechSettings.copy(
                                                    textToSpeech = speechSettings.textToSpeech.copy(speed = it)
                                                )
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    }

                    // Speech Recognition (STT) Settings  
                    SettingsGroup(title = translation.settings.speechRecognitionTitle) {
                        SwitchSettingItem(
                            label = translation.settings.enableSttLabel,
                            description = translation.settings.sttDescription,
                            value = speechToText.enabled,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(
                                            speechSettings = speechSettings.copy(
                                                speechToText = speechSettings.speechToText.copy(enabled = it)
                                            )
                                        )
                                    }
                                )
                            }
                        )

                        // Only show STT settings if STT is enabled
                        if (speechToText.enabled) {
                            DropdownSettingItem(
                                label = "Speech-to-text backend",
                                description = "OpenAI uses the regular transcription API. Local Whisper runs whisper.cpp on the server.",
                                value = speechToText.engine,
                                options = UserProfile.SpeechSettings.SpeechToText.Engine.entries.toList(),
                                optionLabel = {
                                    when (it) {
                                        UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API -> "OpenAI API"
                                        UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER -> "Local Whisper"
                                    }
                                },
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateUserProfile {
                                            copy(
                                                speechSettings = speechSettings.copy(
                                                    speechToText = speechSettings.speechToText.copy(engine = it)
                                                )
                                            )
                                        }
                                    )
                                }
                            )

                            if (speechToText.engine == UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER) {
                                val localWhisper = speechToText.localWhisper

                                TextFieldSettingItem(
                                    label = "Whisper executable",
                                    description = "Path or command name for whisper.cpp CLI. Gromozeka starts the sibling whisper-server executable.",
                                    value = localWhisper.executablePath,
                                    placeholder = "whisper-cli",
                                    onValueChange = {
                                        onSettingsChange(
                                            settings.updateUserProfile {
                                                copy(
                                                    speechSettings = speechSettings.copy(
                                                        speechToText = speechSettings.speechToText.copy(
                                                            localWhisper = localWhisper.copy(executablePath = it)
                                                        )
                                                    )
                                                )
                                            }
                                        )
                                    }
                                )

                                DropdownSettingItem(
                                    label = "Whisper model",
                                    description = "Used when model path is empty. Model is expected under Gromozeka home: models/whisper/ggml-<name>.bin",
                                    value = localWhisper.modelName,
                                    options = listOf("tiny", "base", "small", "medium", "large-v3-turbo", "large-v3"),
                                    onValueChange = {
                                        onSettingsChange(
                                            settings.updateUserProfile {
                                                copy(
                                                    speechSettings = speechSettings.copy(
                                                        speechToText = speechSettings.speechToText.copy(
                                                            localWhisper = localWhisper.copy(modelName = it)
                                                        )
                                                    )
                                                )
                                            }
                                        )
                                    }
                                )

                                TextFieldSettingItem(
                                    label = "Whisper model path",
                                    description = "Optional absolute path. Leave empty to use the model name in Gromozeka home.",
                                    value = localWhisper.modelPath,
                                    placeholder = "",
                                    onValueChange = {
                                        onSettingsChange(
                                            settings.updateUserProfile {
                                                copy(
                                                    speechSettings = speechSettings.copy(
                                                        speechToText = speechSettings.speechToText.copy(
                                                            localWhisper = localWhisper.copy(modelPath = it)
                                                        )
                                                    )
                                                )
                                            }
                                        )
                                    }
                                )

                                DropdownSettingItem(
                                    label = "Whisper live profile",
                                    description = "Controls live chunk size. Slow CPU increases latency but reduces missed audio on weak machines.",
                                    value = localWhisper.liveStreaming.profile,
                                    options = UserProfile.SpeechSettings.SpeechToText.LocalWhisper.LiveStreaming.Profile.entries,
                                    optionLabel = { it.label },
                                    onValueChange = { profile ->
                                        onSettingsChange(
                                            settings.updateUserProfile {
                                                copy(
                                                    speechSettings = speechSettings.copy(
                                                        speechToText = speechSettings.speechToText.copy(
                                                            localWhisper = localWhisper.copy(
                                                                liveStreaming = localWhisper.liveStreaming.copy(profile = profile)
                                                            )
                                                        )
                                                    )
                                                )
                                            }
                                        )
                                    }
                                )
                            }

                            DropdownSettingItem(
                                label = translation.settings.recognitionLanguageLabel,
                                description = translation.settings.sttLanguageDescription,
                                value = speechToText.mainLanguageCode,
                                options = listOf("en", "ru", "he", "ar", "es", "fr", "de", "zh", "ja"),
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateUserProfile {
                                            copy(
                                                speechSettings = speechSettings.copy(
                                                    speechToText = speechSettings.speechToText.copy(mainLanguageCode = it)
                                                )
                                            )
                                        }
                                    )
                                }
                            )

                            SwitchSettingItem(
                                label = translation.settings.autoSendMessagesLabel,
                                description = translation.settings.autoSendDescription,
                                value = desktopInputSettings.autoSend,
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateDesktopInputSettings { copy(autoSend = it) }
                                    )
                                }
                            )

                            SwitchSettingItem(
                                label = translation.settings.globalPttHotkeyLabel,
                                description = translation.settings.globalPttDescription,
                                value = desktopInputSettings.globalPttHotkeyEnabled,
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateDesktopInputSettings { copy(globalPttHotkeyEnabled = it) }
                                    )
                                }
                            )

                            // Warning about disabled global hotkeys
                            Text(
                                text = "⚠️ Global hotkeys temporarily disabled - UI PTT button available",
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp)
                            )

                            // Applies to both global hotkey and UI PTT button.
                            SwitchSettingItem(
                                label = translation.settings.muteAudioDuringPttLabel,
                                description = translation.settings.muteAudioDescription,
                                value = desktopInputSettings.muteSystemAudioDuringPtt,
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateDesktopInputSettings { copy(muteSystemAudioDuringPtt = it) }
                                    )
                                }
                            )
                        }
                    }
                    }

                    if (contentMode == SettingsPanelContentMode.AiRuntime) {
                        SettingsGroup(title = translation.settings.aiSettingsTitle) {
                        val aiSettings = userProfile.aiSettings
                        Text(
                            text = "Runtime assignments",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        AiRuntimeAssignment.Purpose.entries.forEach { purpose ->
                            val assignment = aiSettings.assignmentFor(purpose)
                            val selection = aiSettings.runtimeSelectionFor(purpose)
                            val options = aiSettings.modelConfigurations.filter {
                                aiSettings.supportsPurpose(it, purpose)
                            }
                            val selectedConfiguration = options.firstOrNull {
                                it.id == selection?.modelConfigurationId
                            } ?: aiSettings.modelConfigurations.firstOrNull {
                                it.id == selection?.modelConfigurationId
                            } ?: options.firstOrNull()

                            if (selectedConfiguration != null) {
                                DropdownSettingItem(
                                    label = if (assignment == null && purpose.fallbackPurpose != null) {
                                        "${purpose.displayName} (fallback)"
                                    } else {
                                        purpose.displayName
                                    },
                                    description = purpose.description,
                                    value = selectedConfiguration,
                                    options = options.ifEmpty { listOf(selectedConfiguration) },
                                    optionLabel = { "${it.displayName} · ${it.providerModelId}" },
                                    optionEnabled = { aiSettings.supportsPurpose(it, purpose) },
                                    onValueChange = { selected ->
                                        onSettingsChange(
                                            settings.copy(
                                                userProfile = userProfile.copy(
                                                    aiSettings = aiSettings.withRuntimeAssignment(
                                                        purpose = purpose,
                                                        modelConfigurationId = selected.id,
                                                    )
                                                )
                                            )
                                        )
                                    }
                                )
                            } else {
                                InfoSettingItem(
                                    label = purpose.displayName,
                                    message = "No enabled model configuration with capabilities ${purpose.requiredCapabilities}",
                                    isError = true,
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Connections",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        aiSettings.connections.forEach { connection ->
                            AiConnectionSettingsCard(
                                connection = connection,
                                onConnectionChange = { updated ->
                                    onSettingsChange(
                                        settings.copy(
                                            userProfile = userProfile.copy(
                                                aiSettings = aiSettings.copy(
                                                    connections = aiSettings.connections.map {
                                                        if (it.id == updated.id) updated else it
                                                    }
                                                )
                                            )
                                        )
                                    )
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Text(
                            text = "Model configurations",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        aiSettings.modelConfigurations.forEach { configuration ->
                            val assignedPurposes = aiSettings.runtimeAssignments
                                .filter { it.selection.modelConfigurationId == configuration.id }
                                .map { it.purpose.displayName }
                            AiModelConfigurationSettingsCard(
                                configuration = configuration,
                                modelSpec = aiSettings.modelSpecFor(configuration),
                                assignedPurposes = assignedPurposes,
                                onConfigurationChange = { updated ->
                                    onSettingsChange(
                                        settings.copy(
                                            userProfile = userProfile.copy(
                                                aiSettings = aiSettings.copy(
                                                    modelConfigurations = aiSettings.modelConfigurations.map {
                                                        if (it.id == updated.id) updated else it
                                                    }
                                                )
                                            )
                                        )
                                    )
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        SwitchSettingItem(
                            label = translation.settings.includeCurrentTimeLabel,
                            description = translation.settings.includeTimeDescription,
                            value = agentSettings.includeCurrentTime,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(agentSettings = agentSettings.copy(includeCurrentTime = it))
                                    }
                                )
                            }
                        )

                        SwitchSettingItem(
                            label = "Auto-approve all tool requests",
                            description = "Automatically allow all tool executions without showing permission dialogs (affects new sessions only)",
                            value = agentSettings.autoApproveAllTools,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(agentSettings = agentSettings.copy(autoApproveAllTools = it))
                                    }
                                )
                            }
                        )

                        SwitchSettingItem(
                            label = "Auto-remember threads",
                            description = "Automatically write typed memory around each chat message",
                            value = memorySettings.autoRemember,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(memorySettings = memorySettings.copy(autoRemember = it))
                                    }
                                )
                            }
                        )

                        SwitchSettingItem(
                            label = "Auto-recall memory",
                            description = "Automatically recall typed memory before the main model response",
                            value = memorySettings.autoRecall,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(memorySettings = memorySettings.copy(autoRecall = it))
                                    }
                                )
                            }
                        )

                        SwitchSettingItem(
                            label = "Force document ingest",
                            description = "Bypass memory relevance routing for technically valid documents; extraction and reconciliation still validate supported memory",
                            value = memorySettings.forceWriteForDocumentIngest,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(
                                            memorySettings = memorySettings.copy(
                                                forceWriteForDocumentIngest = it
                                            )
                                        )
                                    }
                                )
                            }
                        )
                        }
                    }

                    if (contentMode == SettingsPanelContentMode.AiRuntime) {
                        SettingsGroup(title = translation.settings.apiKeysTitle) {
                        // Brave Search
                        SwitchSettingItem(
                            label = translation.settings.enableBraveSearchLabel,
                            description = translation.settings.braveSearchDescription,
                            value = toolSettings.braveSearch.enabled,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(
                                            toolSettings = toolSettings.copy(
                                                braveSearch = toolSettings.braveSearch.copy(enabled = it)
                                            )
                                        )
                                    }
                                )
                            }
                        )

                        if (toolSettings.braveSearch.enabled) {
                            PasswordSettingItem(
                                label = translation.settings.braveApiKeyLabel,
                                description = translation.settings.braveApiKeyDescription,
                                value = toolSettings.braveSearch.apiKey.secretText(),
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateUserProfile {
                                            copy(
                                                toolSettings = toolSettings.copy(
                                                    braveSearch = toolSettings.braveSearch.copy(
                                                        apiKey = it.inlineSecretOrNull()
                                                    )
                                                )
                                            )
                                        }
                                    )
                                }
                            )
                        }

                        // Jina Reader
                        SwitchSettingItem(
                            label = translation.settings.enableJinaReaderLabel,
                            description = translation.settings.jinaReaderDescription,
                            value = toolSettings.jinaReader.enabled,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(
                                            toolSettings = toolSettings.copy(
                                                jinaReader = toolSettings.jinaReader.copy(enabled = it)
                                            )
                                        )
                                    }
                                )
                            }
                        )

                        if (toolSettings.jinaReader.enabled) {
                            PasswordSettingItem(
                                label = translation.settings.jinaApiKeyLabel,
                                description = translation.settings.jinaApiKeyDescription,
                                value = toolSettings.jinaReader.apiKey.secretText(),
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateUserProfile {
                                            copy(
                                                toolSettings = toolSettings.copy(
                                                    jinaReader = toolSettings.jinaReader.copy(
                                                        apiKey = it.inlineSecretOrNull()
                                                    )
                                                )
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    }

                    }

                    if (contentMode == SettingsPanelContentMode.Quick) {
                    SettingsGroup(title = "Composer shortcuts") {
                        userProfile.messageInstructionGroups.forEach { group ->
                            SwitchSettingItem(
                                label = group.title,
                                description = group.controls.joinToString(" · ") { control ->
                                    "${control.shortLabel} ${control.data.title}"
                                },
                                value = group.showInComposer,
                                onValueChange = { showInComposer ->
                                    onSettingsChange(
                                        settings.updateUserProfile {
                                            copy(
                                                messageInstructionGroups = messageInstructionGroups.map { existingGroup ->
                                                    if (existingGroup.id == group.id) {
                                                        existingGroup.copy(showInComposer = showInComposer)
                                                    } else {
                                                        existingGroup
                                                    }
                                                }
                                            )
                                        }
                                    )
                                },
                            )
                        }
                    }

                    // UI Settings
                    SettingsGroup(title = translation.settings.interfaceSettingsTitle) {
                        SwitchSettingItem(
                            label = translation.settings.showSystemMessagesLabel,
                            description = translation.settings.showSystemDescription,
                            value = deviceSettings.showSystemMessages,
                            onValueChange = { onSettingsChange(settings.updateDeviceSettings { withShowSystemMessages(it) }) }
                        )

                        SwitchSettingItem(
                            label = translation.settings.alwaysOnTopLabel,
                            description = translation.settings.alwaysOnTopDescription,
                            value = desktopWindowSettings.alwaysOnTop,
                            onValueChange = {
                                onSettingsChange(settings.updateDesktopWindowSettings { copy(alwaysOnTop = it) })
                            }
                        )

                        SwitchSettingItem(
                            label = translation.settings.showTabsAtBottomLabel,
                            description = translation.settings.showTabsAtBottomDescription,
                            value = uiSettings.showTabsAtBottom,
                            onValueChange = {
                                onSettingsChange(settings.updateUiSettings { copy(showTabsAtBottom = it) })
                            }
                        )

                        SliderSettingItem(
                            label = "UI Scale",
                            description = "Adjust interface size (0.5 = tiny, 1.0 = normal, 3.0 = huge).",
                            value = uiSettings.uiScale,
                            min = 0.5f,
                            max = 3.0f,
                            step = 0.1f,
                            valueFormat = "${(uiSettings.uiScale * 100).toInt()}%",
                            onValueChange = {
                                onSettingsChange(settings.updateUiSettings { copy(uiScale = it) })
                            }
                        )

                        SliderSettingItem(
                            label = "Font Scale",
                            description = "Adjust text size (0.5 = small, 1.0 = normal, 2.0 = large)",
                            value = uiSettings.fontScale,
                            min = 0.5f,
                            max = 2.0f,
                            step = 0.1f,
                            valueFormat = "${(uiSettings.fontScale * 100).toInt()}%",
                            onValueChange = {
                                onSettingsChange(settings.updateUiSettings { copy(fontScale = it) })
                            }
                        )

                        ButtonSettingItem(
                            label = "Reset Scale",
                            description = "Restore interface and text scale to 100%.",
                            buttonText = "Reset scale",
                            onClick = {
                                onSettingsChange(settings.updateUiSettings { copy(uiScale = 1.0f, fontScale = 1.0f) })
                            }
                        )
                    }

                    // Localization Settings
                    SettingsGroup(title = translation.settings.localizationTitle) {
                        // Language selection
                        DropdownSettingItem(
                            label = translation.switchLanguage,
                            description = translation.settings.languageSelectionDescription,
                            value = uiSettings.languageCode,
                            options = Translation.builtIn.keys.toList(),
                            optionLabel = { languageCode ->
                                Translation.builtIn[languageCode]!!.languageName
                            },
                            onValueChange = { newLanguageCode ->
                                onSettingsChange(settings.updateUiSettings { copy(languageCode = newLanguageCode) })
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
                                is com.gromozeka.presentation.services.translation.TranslationOverrideResult.Success -> {
                                    InfoSettingItem(
                                        label = translation.settings.translationOverrideStatusLabel,
                                        message = translation.settings.overrideSuccessMessage.format(result.overriddenFields.size),
                                        isError = false
                                    )
                                }

                                is com.gromozeka.presentation.services.translation.TranslationOverrideResult.Failure -> {
                                    InfoSettingItem(
                                        label = translation.settings.translationOverrideStatusLabel,
                                        message = translation.settings.overrideFailureMessage.format(result.error),
                                        isError = true
                                    )
                            }
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
                            value = themeSettings.id,
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
                                onSettingsChange(
                                    settings.updateUiSettings {
                                        copy(theme = theme.copy(id = newThemeId))
                                    }
                                )
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
                            value = themeSettings.overrideEnabled,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUiSettings {
                                        copy(theme = theme.copy(overrideEnabled = it))
                                    }
                                )
                            }
                        )

                        // Theme override info (only show when override is enabled)
                        if (themeSettings.overrideEnabled) {
                            InfoSettingItem(
                                label = translation.settings.customThemeInfoLabel,
                                message = translation.settings.customThemeInfoMessage,
                                isError = false
                            )
                        }

                        // Theme override status (only show when override is enabled)
                        if (themeSettings.overrideEnabled) {
                            val overrideResult by themeService.lastOverrideResult.collectAsState()
                            overrideResult?.let { result ->
                                when (result) {
                                    is com.gromozeka.presentation.services.theming.ThemeOverrideResult.Success -> {
                                        InfoSettingItem(
                                            label = translation.settings.themeOverrideStatusLabel,
                                            message = translation.settings.themeOverrideSuccessMessage.format(result.overriddenFields.size),
                                            isError = false
                                        )
                                    }

                                    is com.gromozeka.presentation.services.theming.ThemeOverrideResult.Failure -> {
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
                            value = soundSettings.errorSoundsEnabled,
                            onValueChange = {
                                onSettingsChange(settings.updateSoundSettings { copy(errorSoundsEnabled = it) })
                            }
                        )

                        SwitchSettingItem(
                            label = translation.settings.messageSoundsLabel,
                            description = translation.settings.messageSoundsDescription,
                            value = soundSettings.messageSoundsEnabled,
                            onValueChange = {
                                onSettingsChange(settings.updateSoundSettings { copy(messageSoundsEnabled = it) })
                            }
                        )

                        SwitchSettingItem(
                            label = translation.settings.readySoundsLabel,
                            description = translation.settings.readySoundsDescription,
                            value = soundSettings.readySoundsEnabled,
                            onValueChange = {
                                onSettingsChange(settings.updateSoundSettings { copy(readySoundsEnabled = it) })
                            }
                        )

                        // Volume control (show only if any sound is enabled)
                        if (soundSettings.errorSoundsEnabled || soundSettings.messageSoundsEnabled || soundSettings.readySoundsEnabled) {
                            SliderSettingItem(
                                label = translation.settings.soundVolumeLabel,
                                description = translation.settings.soundVolumeDescription,
                                value = soundSettings.volume,
                                min = 0.0f,
                                max = 1.0f,
                                step = 0.1f,
                                valueFormat = "${(soundSettings.volume * 100).toInt()}%",
                                onValueChange = {
                                    onSettingsChange(settings.updateSoundSettings { copy(volume = it) })
                                }
                            )
                        }
                    }

                    }

                    if (contentMode == SettingsPanelContentMode.AiRuntime) {
                    // Logs & Diagnostics
                    SettingsGroup(title = translation.settings.logsAndDiagnosticsTitle) {
                        ButtonSettingItem(
                            label = "View Application Logs",
                            description = "Open the folder containing application logs to review activity and troubleshoot issues",
                            buttonText = "Open Folder",
                            onClick = {
                                log.info("Opening logs folder is disabled in the remote UI client")
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
                                            log.info("Encrypted logs folder opening is disabled in the remote UI client")
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
                                log.info("Clearing logs is disabled in the remote UI client")
                            }
                        )
                    }

                    // Developer Settings
                    SettingsGroup(title = translation.settings.developerSettingsTitle) {
                        SwitchSettingItem(
                            label = translation.settings.showOriginalJsonLabel,
                            description = translation.settings.showJsonDescription,
                            value = deviceSettings.showOriginalJson,
                            onValueChange = { onSettingsChange(settings.updateDeviceSettings { withShowOriginalJson(it) }) }
                        )

                        DropdownSettingItem(
                            label = "Remote protocol",
                            description = "CBOR is the normal binary transport. JSON is useful when debugging WebSocket frames.",
                            value = remoteClientSettings.protocolEncoding.name,
                            options = RemoteProtocolEncoding.entries.map { it.name },
                            onValueChange = {
                                onRemoteClientSettingsChange(
                                    remoteClientSettings.copy(protocolEncoding = RemoteProtocolEncoding.valueOf(it))
                                )
                            }
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
private fun AiConnectionSettingsCard(
    connection: AiConnection,
    onConnectionChange: (AiConnection) -> Unit,
) {
    val httpConnection = connection as? AiConnection.HttpAiConnection
    val apiKeyConnection = connection as? AiConnection.ApiKeyAiConnection
    val awsConnection = connection as? AiConnection.AwsAiConnection

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(connection.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${connection.kind.name} · ${connection.id.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = connection.enabled,
                    onCheckedChange = { onConnectionChange(connection.withEnabled(it)) }
                )
            }

            TextFieldSettingItem(
                label = "Display name",
                description = "",
                value = connection.displayName,
                onValueChange = { onConnectionChange(connection.withDisplayName(it)) }
            )

            if (httpConnection != null) {
                TextFieldSettingItem(
                    label = "Base URL",
                    description = "Optional endpoint override for this connection",
                    value = httpConnection.baseUrl ?: "",
                    placeholder = "https://api.example.com/v1",
                    onValueChange = { onConnectionChange(connection.withBaseUrl(it.ifBlank { null })) }
                )
            }

            if (apiKeyConnection != null) {
                TextFieldSettingItem(
                    label = "Secret env var",
                    description = "Server-side environment variable that contains the API key",
                    value = (apiKeyConnection.apiKey as? SecretRef.EnvironmentVariable)?.name ?: "",
                    placeholder = "OPENAI_API_KEY",
                    onValueChange = {
                        onConnectionChange(connection.withApiKey(it.ifBlank { null }?.let(SecretRef::EnvironmentVariable)))
                    }
                )

                PasswordSettingItem(
                    label = "Inline API key",
                    description = "Stored in server settings. Prefer env var for shared machines.",
                    value = (apiKeyConnection.apiKey as? SecretRef.Inline)?.value ?: "",
                    onValueChange = {
                        onConnectionChange(connection.withApiKey(it.ifBlank { null }?.let(SecretRef::Inline)))
                    }
                )
            }

            if (awsConnection != null) {
                TextFieldSettingItem(
                    label = "AWS region",
                    description = "Only used by Bedrock-style connections",
                    value = awsConnection.awsRegion ?: "",
                    placeholder = "us-east-1",
                    onValueChange = { onConnectionChange(connection.withAwsRegion(it.ifBlank { null })) }
                )

                TextFieldSettingItem(
                    label = "AWS profile",
                    description = "Only used by Bedrock-style connections",
                    value = awsConnection.awsProfile ?: "",
                    placeholder = "work",
                    onValueChange = { onConnectionChange(connection.withAwsProfile(it.ifBlank { null })) }
                )
            }
        }
    }
}

private fun AiConnection.withEnabled(enabled: Boolean): AiConnection =
    when (this) {
        is AiConnection.OpenAiSubscription -> copy(enabled = enabled)
        is AiConnection.OpenAiApi -> copy(enabled = enabled)
        is AiConnection.OpenAiCompatible -> copy(enabled = enabled)
        is AiConnection.AnthropicApi -> copy(enabled = enabled)
        is AiConnection.AnthropicBedrock -> copy(enabled = enabled)
        is AiConnection.ClaudeCode -> copy(enabled = enabled)
        is AiConnection.GeminiApi -> copy(enabled = enabled)
        is AiConnection.Ollama -> copy(enabled = enabled)
    }

private fun AiConnection.withDisplayName(displayName: String): AiConnection =
    when (this) {
        is AiConnection.OpenAiSubscription -> copy(displayName = displayName)
        is AiConnection.OpenAiApi -> copy(displayName = displayName)
        is AiConnection.OpenAiCompatible -> copy(displayName = displayName)
        is AiConnection.AnthropicApi -> copy(displayName = displayName)
        is AiConnection.AnthropicBedrock -> copy(displayName = displayName)
        is AiConnection.ClaudeCode -> copy(displayName = displayName)
        is AiConnection.GeminiApi -> copy(displayName = displayName)
        is AiConnection.Ollama -> copy(displayName = displayName)
    }

private fun AiConnection.withBaseUrl(baseUrl: String?): AiConnection =
    when (this) {
        is AiConnection.OpenAiApi -> copy(baseUrl = baseUrl)
        is AiConnection.OpenAiCompatible -> baseUrl?.let { copy(baseUrl = it) } ?: this
        is AiConnection.AnthropicApi -> copy(baseUrl = baseUrl)
        is AiConnection.AnthropicBedrock -> copy(baseUrl = baseUrl)
        is AiConnection.GeminiApi -> copy(baseUrl = baseUrl)
        is AiConnection.Ollama -> baseUrl?.let { copy(baseUrl = it) } ?: this
        is AiConnection.OpenAiSubscription,
        is AiConnection.ClaudeCode -> this
    }

private fun AiConnection.withApiKey(apiKey: SecretRef?): AiConnection =
    when (this) {
        is AiConnection.OpenAiApi -> copy(apiKey = apiKey)
        is AiConnection.OpenAiCompatible -> copy(apiKey = apiKey)
        is AiConnection.AnthropicApi -> copy(apiKey = apiKey)
        is AiConnection.GeminiApi -> copy(apiKey = apiKey)
        is AiConnection.OpenAiSubscription,
        is AiConnection.AnthropicBedrock,
        is AiConnection.ClaudeCode,
        is AiConnection.Ollama -> this
    }

private fun AiConnection.withAwsRegion(awsRegion: String?): AiConnection =
    when (this) {
        is AiConnection.AnthropicBedrock -> copy(awsRegion = awsRegion)
        is AiConnection.OpenAiSubscription,
        is AiConnection.OpenAiApi,
        is AiConnection.OpenAiCompatible,
        is AiConnection.AnthropicApi,
        is AiConnection.ClaudeCode,
        is AiConnection.GeminiApi,
        is AiConnection.Ollama -> this
    }

private fun AiConnection.withAwsProfile(awsProfile: String?): AiConnection =
    when (this) {
        is AiConnection.AnthropicBedrock -> copy(awsProfile = awsProfile)
        is AiConnection.OpenAiSubscription,
        is AiConnection.OpenAiApi,
        is AiConnection.OpenAiCompatible,
        is AiConnection.AnthropicApi,
        is AiConnection.ClaudeCode,
        is AiConnection.GeminiApi,
        is AiConnection.Ollama -> this
    }

private fun Settings.updateUserProfile(update: UserProfile.() -> UserProfile): Settings =
    copy(userProfile = userProfile.update())

private fun Settings.updateDeviceSettings(update: UserDeviceSettings.() -> UserDeviceSettings): Settings =
    copy(userDeviceSettings = userDeviceSettings.update())

private fun Settings.updateUiSettings(update: UserDeviceSettings.UiSettings.() -> UserDeviceSettings.UiSettings): Settings =
    updateDeviceSettings { withUiSettings(uiSettings.update()) }

private fun Settings.updateSoundSettings(
    update: UserDeviceSettings.SoundSettings.() -> UserDeviceSettings.SoundSettings,
): Settings =
    updateDeviceSettings { withSoundSettings(soundSettings.update()) }

private fun Settings.updateDesktopInputSettings(
    update: UserDeviceSettings.DesktopInputSettings.() -> UserDeviceSettings.DesktopInputSettings,
): Settings =
    updateDeviceSettings {
        when (this) {
            is UserDeviceSettings.Desktop -> copy(inputSettings = inputSettings.update())
            else -> this
        }
    }

private fun Settings.updateDesktopWindowSettings(
    update: UserDeviceSettings.DesktopWindowSettings.() -> UserDeviceSettings.DesktopWindowSettings,
): Settings =
    updateDeviceSettings {
        when (this) {
            is UserDeviceSettings.Desktop -> copy(windowSettings = windowSettings.update())
            else -> this
        }
    }

private val Settings.desktopInputSettings: UserDeviceSettings.DesktopInputSettings
    get() = (userDeviceSettings as? UserDeviceSettings.Desktop)?.inputSettings
        ?: UserDeviceSettings.DesktopInputSettings()

private val Settings.desktopWindowSettings: UserDeviceSettings.DesktopWindowSettings
    get() = (userDeviceSettings as? UserDeviceSettings.Desktop)?.windowSettings
        ?: UserDeviceSettings.DesktopWindowSettings()

private fun UserDeviceSettings.withUiSettings(uiSettings: UserDeviceSettings.UiSettings): UserDeviceSettings =
    when (this) {
        is UserDeviceSettings.Desktop -> copy(uiSettings = uiSettings)
        is UserDeviceSettings.Android -> copy(uiSettings = uiSettings)
        is UserDeviceSettings.Ios -> copy(uiSettings = uiSettings)
        is UserDeviceSettings.Web -> copy(uiSettings = uiSettings)
    }

private fun UserDeviceSettings.withSoundSettings(soundSettings: UserDeviceSettings.SoundSettings): UserDeviceSettings =
    when (this) {
        is UserDeviceSettings.Desktop -> copy(soundSettings = soundSettings)
        is UserDeviceSettings.Android -> copy(soundSettings = soundSettings)
        is UserDeviceSettings.Ios -> copy(soundSettings = soundSettings)
        is UserDeviceSettings.Web -> copy(soundSettings = soundSettings)
    }

private fun UserDeviceSettings.withShowSystemMessages(showSystemMessages: Boolean): UserDeviceSettings =
    when (this) {
        is UserDeviceSettings.Desktop -> copy(showSystemMessages = showSystemMessages)
        is UserDeviceSettings.Android -> copy(showSystemMessages = showSystemMessages)
        is UserDeviceSettings.Ios -> copy(showSystemMessages = showSystemMessages)
        is UserDeviceSettings.Web -> copy(showSystemMessages = showSystemMessages)
    }

private fun UserDeviceSettings.withShowOriginalJson(showOriginalJson: Boolean): UserDeviceSettings =
    when (this) {
        is UserDeviceSettings.Desktop -> copy(showOriginalJson = showOriginalJson)
        is UserDeviceSettings.Android -> copy(showOriginalJson = showOriginalJson)
        is UserDeviceSettings.Ios -> copy(showOriginalJson = showOriginalJson)
        is UserDeviceSettings.Web -> copy(showOriginalJson = showOriginalJson)
    }

private fun SecretRef?.secretText(): String =
    when (this) {
        is SecretRef.Inline -> value
        is SecretRef.EnvironmentVariable -> name
        null -> ""
    }

private fun String.inlineSecretOrNull(): SecretRef? =
    ifBlank { null }?.let(SecretRef::Inline)

private fun UserProfile.AiSettings.assignmentFor(purpose: AiRuntimeAssignment.Purpose): AiRuntimeAssignment? =
    runtimeAssignments.firstOrNull { it.purpose == purpose }

private fun UserProfile.AiSettings.withRuntimeAssignment(
    purpose: AiRuntimeAssignment.Purpose,
    modelConfigurationId: AiModelConfiguration.Id,
): UserProfile.AiSettings =
    copy(
        runtimeAssignments = (
            runtimeAssignments.filterNot { it.purpose == purpose } +
                AiRuntimeAssignment(purpose, AiRuntimeSelection(modelConfigurationId))
            ).sortedBy { it.purpose.ordinal }
    )

@Composable
private fun AiModelConfigurationSettingsCard(
    configuration: AiModelConfiguration,
    modelSpec: AiModelSpec?,
    assignedPurposes: List<String>,
    onConfigurationChange: (AiModelConfiguration) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(configuration.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${configuration.connectionId.value} · ${configuration.id.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = configuration.enabled,
                    enabled = !configuration.enabled || assignedPurposes.isEmpty(),
                    onCheckedChange = { onConfigurationChange(configuration.copy(enabled = it)) }
                )
            }
            if (assignedPurposes.isNotEmpty()) {
                Text(
                    text = "Assigned to: ${assignedPurposes.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextFieldSettingItem(
                label = "Display name",
                description = "",
                value = configuration.displayName,
                onValueChange = { onConfigurationChange(configuration.copy(displayName = it)) }
            )

            TextFieldSettingItem(
                label = "Provider model id",
                description = "Exact model id sent to the provider",
                value = configuration.providerModelId,
                placeholder = "gpt-5.5",
                onValueChange = { onConfigurationChange(configuration.copy(providerModelId = it)) }
            )

            if (modelSpec?.capabilities?.contains(AiModelCapability.TEXT_GENERATION) == true) {
                DropdownSettingItem(
                    label = "Assistant response format",
                    description = "JSON_SCHEMA is the default. Use XML_INLINE/XML_STRUCTURED for providers without native structured output.",
                    value = configuration.assistantResponseFormat.name,
                    options = AiModelConfiguration.AssistantResponseFormat.entries.map { it.name },
                    onValueChange = {
                        onConfigurationChange(
                            configuration.copy(
                                assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.valueOf(it)
                            )
                        )
                    }
                )
            }

            Text(
                text = modelSpec?.let {
                    "Capabilities: ${it.capabilities.joinToString { capability -> capability.name.lowercase() }}"
                } ?: "Capabilities: missing model spec",
                style = MaterialTheme.typography.bodySmall,
                color = if (modelSpec == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
