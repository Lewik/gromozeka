package com.gromozeka.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteClientSettings
import com.gromozeka.client.RemoteDistributionService
import com.gromozeka.client.RemoteDeviceConnectionClient
import com.gromozeka.client.RemoteMcpServerService
import com.gromozeka.client.RemotePersonalAccessTokenService
import com.gromozeka.client.RemoteUserAdministrationService
import com.gromozeka.client.RemoteSecurityAuditService
import com.gromozeka.client.RemoteUserDirectoryService
import com.gromozeka.client.WorkerEnrollmentInstructions
import com.gromozeka.client.WorkerConnectionInstructions
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.WorkerAudioInput
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiCatalogSecretMutation
import com.gromozeka.domain.model.ai.AiCatalogSecretSlot
import com.gromozeka.domain.model.ai.AiCatalogSecretState
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.presentation.services.LocalWorkerController
import com.gromozeka.presentation.services.LocalWorkerOperation
import com.gromozeka.presentation.services.LocalWorkerPermissionState
import com.gromozeka.presentation.services.LocalWorkerStatus
import com.gromozeka.presentation.services.OllamaModelService
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.CurrentUserAiCredentialService
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.RuntimeCatalogTemplateService
import com.gromozeka.domain.service.WorkerCatalogService
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import com.gromozeka.remote.protocol.DistributionArchitecture
import com.gromozeka.remote.protocol.DistributionArtifact
import com.gromozeka.remote.protocol.DistributionComponent
import com.gromozeka.remote.protocol.DistributionFormat
import com.gromozeka.remote.protocol.DistributionManifest
import com.gromozeka.remote.protocol.DistributionOperatingSystem
import com.gromozeka.presentation.services.theming.AIThemeGenerator
import com.gromozeka.presentation.services.theming.ThemeService
import com.gromozeka.presentation.services.theming.data.Theme
import com.gromozeka.presentation.services.translation.TranslationService
import com.gromozeka.presentation.services.translation.data.Translation
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch

private val log = KLoggers.logger("SettingsPanel")

enum class SettingsPanelContentMode {
    Quick,
    Full,
}

private enum class SettingsSection(val title: String) {
    Interface("Interface"),
    Voice("Voice"),
    AiRuntime("AI"),
    Behavior("Behavior"),
    Tools("Tools"),
    Security("Security"),
    Downloads("Downloads"),
    Advanced("Advanced"),
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
    settingsService: SettingsService,
    aiConfigurationService: AiConfigurationService,
    runtimeCatalogTemplateService: RuntimeCatalogTemplateService,
    workerCatalogService: WorkerCatalogService,
    mcpServerService: RemoteMcpServerService,
    distributionService: RemoteDistributionService,
    deviceConnectionService: RemoteDeviceConnectionClient,
    localWorkerController: LocalWorkerController,
    personalAccessTokenService: RemotePersonalAccessTokenService,
    aiUserCredentialService: CurrentUserAiCredentialService,
    userAdministrationService: RemoteUserAdministrationService,
    securityAuditService: RemoteSecurityAuditService,
    userDirectoryService: RemoteUserDirectoryService,
    canAdministerUsers: Boolean,
    ollamaModelService: OllamaModelService,
    coroutineScope: CoroutineScope,
    onOpenTab: () -> Unit,
    onOpenTabWithMessage: ((String) -> Unit)? = null,
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
    val deviceSettings = settings.userDeviceSettings
    val uiSettings = deviceSettings.uiSettings
    val themeSettings = uiSettings.theme
    val soundSettings = deviceSettings.soundSettings
    val voiceInputSettings = deviceSettings.voiceInputSettings
    val desktopInputSettings = settings.desktopInputSettings
    val desktopWindowSettings = settings.desktopWindowSettings
    val aiCatalogSnapshot by aiConfigurationService.snapshotFlow.collectAsState()
    val claudeCodeConnections = aiCatalogSnapshot?.catalog?.connections
        ?.filterIsInstance<AiConnection.ClaudeCode>()
        .orEmpty()
    val eligibleClaudeCodeConnectionIds = claudeCodeConnections
        .filter { it.enabled && it.voiceTranscriptionEnabled }
        .map { it.id }
    var workers by remember { mutableStateOf(emptyList<WorkerCatalogEntry>()) }
    var selectedSection by remember(contentMode) {
        mutableStateOf(SettingsSection.AiRuntime)
    }

    LaunchedEffect(
        speechToText.engine,
        speechToText.claudeCodeConnectionId,
        eligibleClaudeCodeConnectionIds,
    ) {
        val soleConnectionId = eligibleClaudeCodeConnectionIds.singleOrNull()
        if (
            speechToText.engine == UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE &&
            speechToText.claudeCodeConnectionId == null &&
            soleConnectionId != null
        ) {
            onSettingsChange(
                settings.updateUserProfile {
                    copy(
                        speechSettings = speechSettings.copy(
                            speechToText = speechSettings.speechToText.copy(
                                claudeCodeConnectionId = soleConnectionId
                            )
                        )
                    )
                }
            )
        }
    }

    // Refresh themes when panel opens
    LaunchedEffect(isVisible) {
        if (isVisible) {
            themeService.refreshThemes()
            workerCatalogService.observeWorkers()
                .catch { failure ->
                    log.warn(failure) { "Failed to load Workers for settings: ${failure.message}" }
                }
                .collect { observedWorkers ->
                    workers = observedWorkers
                    if (localWorkerController.status.value.supported) {
                        localWorkerController.refresh(workerCatalogService)
                    }
                }
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

                if (contentMode == SettingsPanelContentMode.Full) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = SettingsSection.entries.indexOf(selectedSection),
                        edgePadding = 0.dp,
                    ) {
                        SettingsSection.entries.forEach { section ->
                            Tab(
                                selected = selectedSection == section,
                                onClick = { selectedSection = section },
                                text = { Text(section.title) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Settings content
                val scrollState = rememberScrollState()
                LaunchedEffect(selectedSection) {
                    scrollState.scrollTo(0)
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (
                        contentMode == SettingsPanelContentMode.Full &&
                        selectedSection == SettingsSection.Voice
                    ) {
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
                                description = "Choose the transcription engine independently from the device that records audio.",
                                value = speechToText.engine,
                                options = UserProfile.SpeechSettings.SpeechToText.Engine.entries.toList(),
                                optionLabel = {
                                    when (it) {
                                        UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API -> "OpenAI API"
                                        UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER -> "Local Whisper"
                                        UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE -> "Claude Code voice"
                                    }
                                },
                                onValueChange = { engine ->
                                    val claudeConnectionId = if (
                                        engine == UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE
                                    ) {
                                        speechToText.claudeCodeConnectionId
                                            ?: eligibleClaudeCodeConnectionIds.singleOrNull()
                                    } else {
                                        speechToText.claudeCodeConnectionId
                                    }
                                    onSettingsChange(
                                        settings.updateUserProfile {
                                            copy(
                                                speechSettings = speechSettings.copy(
                                                    speechToText = speechSettings.speechToText.copy(
                                                        engine = engine,
                                                        claudeCodeConnectionId = claudeConnectionId,
                                                    )
                                                )
                                            )
                                        }
                                    )
                                }
                            )

                            if (speechToText.engine == UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE) {
                                val connectionIds = buildList {
                                    addAll(claudeCodeConnections.map { it.id })
                                    speechToText.claudeCodeConnectionId?.takeIf { it !in this }?.let(::add)
                                }
                                if (connectionIds.isEmpty()) {
                                    Text(
                                        "Create a Claude Code connection and enable voice transcription on it first.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    DropdownSettingItem(
                                        label = "Claude Code connection",
                                        description = "Requires an organization-approved Claude.ai login on the execution target; API keys, Bedrock, Vertex, and Foundry cannot use Claude voice.",
                                        value = speechToText.claudeCodeConnectionId,
                                        options = listOf(null) + connectionIds,
                                        optionLabel = { id ->
                                            if (id == null) return@DropdownSettingItem "Select a Claude Code connection"
                                            val connection = claudeCodeConnections.firstOrNull { it.id == id }
                                            buildString {
                                                append(connection?.displayName ?: id.value)
                                                when {
                                                    connection == null -> append(" · unavailable")
                                                    !connection.enabled -> append(" · disabled")
                                                    !connection.voiceTranscriptionEnabled -> append(" · voice disabled")
                                                }
                                            }
                                        },
                                        optionEnabled = { it != null },
                                        onValueChange = { connectionId ->
                                            if (connectionId == null) return@DropdownSettingItem
                                            onSettingsChange(
                                                settings.updateUserProfile {
                                                    copy(
                                                        speechSettings = speechSettings.copy(
                                                            speechToText = speechSettings.speechToText.copy(
                                                                claudeCodeConnectionId = connectionId
                                                            )
                                                        )
                                                    )
                                                }
                                            )
                                        },
                                    )
                                }
                            }

                            val selectedWorkerSource = speechToText.audioSource as? SpeechAudioSource.WorkerInput
                            val sourceOptions = buildList {
                                add("" to "This client")
                                workers.forEach { worker ->
                                    add(
                                        worker.workerId.value to
                                            "Worker ${worker.workerId.value} · ${worker.status.name.lowercase()}"
                                    )
                                }
                                selectedWorkerSource?.workerId?.value
                                    ?.takeIf { id -> none { it.first == id } }
                                    ?.let { add(it to "Worker $it · unavailable") }
                            }
                            DropdownSettingItem(
                                label = "Audio source",
                                description = "Record on this client or on one exact Worker. The transcription backend may run elsewhere.",
                                value = selectedWorkerSource?.workerId?.value.orEmpty(),
                                options = sourceOptions.map { it.first },
                                optionLabel = { id -> sourceOptions.first { it.first == id }.second },
                                onValueChange = { workerId ->
                                    val source = if (workerId.isBlank()) {
                                        SpeechAudioSource.CurrentClient
                                    } else {
                                        val worker = workers.firstOrNull { it.workerId.value == workerId }
                                        val input = worker?.environmentProfile?.audioInputs
                                            ?.firstOrNull { it.isDefault }
                                            ?: worker?.environmentProfile?.audioInputs?.firstOrNull()
                                            ?: WorkerAudioInput.SystemDefault
                                        SpeechAudioSource.WorkerInput(
                                            ConversationRuntimeWorkerId(workerId),
                                            input.id,
                                        )
                                    }
                                    onSettingsChange(
                                        settings.updateUserProfile {
                                            copy(
                                                speechSettings = speechSettings.copy(
                                                    speechToText = speechSettings.speechToText.copy(audioSource = source)
                                                )
                                            )
                                        }
                                    )
                                },
                            )

                            if (selectedWorkerSource != null) {
                                val selectedWorker = workers.firstOrNull {
                                    it.workerId == selectedWorkerSource.workerId
                                }
                                val audioInputs = buildList {
                                    addAll(selectedWorker?.environmentProfile?.audioInputs.orEmpty())
                                    if (none { it.id == selectedWorkerSource.inputId }) {
                                        add(
                                            WorkerAudioInput(
                                                id = selectedWorkerSource.inputId,
                                                displayName = "Unavailable input ${selectedWorkerSource.inputId.value}",
                                            )
                                        )
                                    }
                                }
                                DropdownSettingItem(
                                    label = "Worker audio input",
                                    description = "An offline Worker remains selectable; recording becomes available when it reconnects with this input.",
                                    value = selectedWorkerSource.inputId,
                                    options = audioInputs.map { it.id },
                                    optionLabel = { id ->
                                        audioInputs.first { it.id == id }.let { input ->
                                            if (input.isDefault) "${input.displayName} · default" else input.displayName
                                        }
                                    },
                                    onValueChange = { inputId ->
                                        onSettingsChange(
                                            settings.updateUserProfile {
                                                copy(
                                                    speechSettings = speechSettings.copy(
                                                        speechToText = speechSettings.speechToText.copy(
                                                            audioSource = selectedWorkerSource.copy(inputId = inputId)
                                                        )
                                                    )
                                                )
                                            }
                                        )
                                    },
                                )
                            }

                            if (speechToText.engine == UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER) {
                                val localWhisper = speechToText.localWhisper
                                val executionTargets = buildList {
                                    add(AiExecutionTarget.Server)
                                    workers.forEach {
                                        add(AiExecutionTarget.Worker(it.workerId.value))
                                    }
                                    if (localWhisper.executionTarget !in this) {
                                        add(localWhisper.executionTarget)
                                    }
                                }

                                DropdownSettingItem(
                                    label = "Whisper execution target",
                                    description = "Finite transcription runs on this exact target. Live interpretation requires Server.",
                                    value = localWhisper.executionTarget,
                                    options = executionTargets,
                                    optionLabel = { target ->
                                        when (target) {
                                            AiExecutionTarget.Server -> "Server"
                                            is AiExecutionTarget.Worker -> {
                                                val status = workers.firstOrNull {
                                                    it.workerId.value == target.workerId
                                                }?.status?.name?.lowercase() ?: "unknown"
                                                "Worker ${target.workerId} · $status"
                                            }
                                        }
                                    },
                                    onValueChange = { target ->
                                        onSettingsChange(
                                            settings.updateUserProfile {
                                                copy(
                                                    speechSettings = speechSettings.copy(
                                                        speechToText = speechSettings.speechToText.copy(
                                                            localWhisper = localWhisper.copy(executionTarget = target)
                                                        )
                                                    )
                                                )
                                            }
                                        )
                                    },
                                )

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

                                TextFieldSettingItem(
                                    label = "Whisper threads",
                                    description = "0 keeps whisper.cpp default. Positive values are passed as -t to whisper-cli and whisper-server.",
                                    value = localWhisper.threadCount.takeIf { it > 0 }?.toString().orEmpty(),
                                    placeholder = "0",
                                    onValueChange = { value ->
                                        value.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { threadCount ->
                                            onSettingsChange(
                                                settings.updateUserProfile {
                                                    copy(
                                                        speechSettings = speechSettings.copy(
                                                            speechToText = speechSettings.speechToText.copy(
                                                                localWhisper = localWhisper.copy(threadCount = threadCount)
                                                            )
                                                        )
                                                    )
                                                }
                                            )
                                        }
                                        if (value.isBlank()) {
                                            onSettingsChange(
                                                settings.updateUserProfile {
                                                    copy(
                                                        speechSettings = speechSettings.copy(
                                                            speechToText = speechSettings.speechToText.copy(
                                                                localWhisper = localWhisper.copy(threadCount = 0)
                                                            )
                                                        )
                                                    )
                                                }
                                            )
                                        }
                                    }
                                )

                                TextFieldSettingItem(
                                    label = "Whisper extra arguments",
                                    description = "Advanced whisper.cpp args appended after Gromozeka required args. Split by spaces.",
                                    value = localWhisper.extraArguments.joinToString(" "),
                                    placeholder = "--no-gpu -bo 1",
                                    onValueChange = { value ->
                                        onSettingsChange(
                                            settings.updateUserProfile {
                                                copy(
                                                    speechSettings = speechSettings.copy(
                                                        speechToText = speechSettings.speechToText.copy(
                                                            localWhisper = localWhisper.copy(
                                                                extraArguments = value.splitWhisperExtraArguments()
                                                            )
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
                                value = voiceInputSettings.autoSend,
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateVoiceInputSettings { copy(autoSend = it) }
                                    )
                                }
                            )

                            SwitchSettingItem(
                                label = "Continuous voice input",
                                description = "Keep this client's microphone open, split speech into complete phrases, and send each transcript like sequential PTT.",
                                value = voiceInputSettings.liveVoiceInputEnabled,
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateVoiceInputSettings { copy(liveVoiceInputEnabled = it) }
                                    )
                                }
                            )

                            DropdownSettingItem(
                                label = "Continuous voice VAD mode",
                                description = "Choose who decides phrase boundaries. Provider VAD is explicit and never falls back to local VAD.",
                                value = voiceInputSettings.liveVoiceVadMode,
                                options = UserDeviceSettings.VoiceInputSettings.LiveVoiceVadMode.entries,
                                optionLabel = {
                                    when (it) {
                                        UserDeviceSettings.VoiceInputSettings.LiveVoiceVadMode.LOCAL_VAD ->
                                            "Local energy VAD"
                                        UserDeviceSettings.VoiceInputSettings.LiveVoiceVadMode.PROVIDER_VAD ->
                                            "Provider VAD"
                                    }
                                },
                                onValueChange = { mode ->
                                    onSettingsChange(
                                        settings.updateVoiceInputSettings { copy(liveVoiceVadMode = mode) }
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

                            Row(
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                                Text(
                                    text = "Quick text hotkeys: macOS Cmd+Ctrl+Option+F/T, Windows Ctrl+Alt+Win+F/T. F fixes clipboard text, T translates clipboard text. The result is copied back to clipboard.",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

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

                    if (
                        contentMode == SettingsPanelContentMode.Full &&
                        selectedSection == SettingsSection.AiRuntime
                    ) {
                        AiCatalogSettings(
                            aiConfigurationService = aiConfigurationService,
                            runtimeCatalogTemplateService = runtimeCatalogTemplateService,
                            workerCatalogService = workerCatalogService,
                            aiUserCredentialService = aiUserCredentialService,
                            canManageCatalog = canAdministerUsers,
                            coroutineScope = coroutineScope,
                        )
                    }

                    if (
                        contentMode == SettingsPanelContentMode.Full &&
                        selectedSection == SettingsSection.Behavior
                    ) {
                        SettingsGroup(title = "Agent and memory behavior") {
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

                    if (
                        contentMode == SettingsPanelContentMode.Full &&
                        selectedSection == SettingsSection.Tools
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            BrowserUseSettings(
                                service = mcpServerService,
                                distributionService = distributionService,
                                workers = workers,
                                canManage = canAdministerUsers,
                            )
                            WebToolSettingsEditor(
                                aiConfigurationService = aiConfigurationService,
                                coroutineScope = coroutineScope,
                                translation = translation,
                            )
                        }
                    }

                    if (
                        contentMode == SettingsPanelContentMode.Full &&
                        selectedSection == SettingsSection.Downloads
                    ) {
                        DistributionSettings(distributionService)
                    }

                    if (
                        contentMode == SettingsPanelContentMode.Full &&
                        selectedSection == SettingsSection.Security
                    ) {
                        DeviceConnectionApprovalSettings(deviceConnectionService)
                        Spacer(modifier = Modifier.height(24.dp))
                        PersonalAccessTokenSettings(
                            service = personalAccessTokenService,
                            coroutineScope = coroutineScope,
                        )
                        if (canAdministerUsers) {
                            Spacer(modifier = Modifier.height(24.dp))
                            UserAdministrationSettings(
                                service = userAdministrationService,
                                coroutineScope = coroutineScope,
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            SecurityAuditSettings(
                                service = securityAuditService,
                                userDirectoryService = userDirectoryService,
                                coroutineScope = coroutineScope,
                            )
                        }
                    }

                    if (
                        contentMode == SettingsPanelContentMode.Quick ||
                        (
                            contentMode == SettingsPanelContentMode.Full &&
                                selectedSection == SettingsSection.Interface
                            )
                    ) {
                    SettingsGroup(title = "Composer shortcuts") {
                        TextFieldSettingItem(
                            label = "Instruction shortcut separators",
                            description = "Comma-separated prefixes. Type a prefix, an instruction alias, then two spaces.",
                            value = userProfile.messageInstructionTextShortcuts.separators.joinToString(", "),
                            placeholder = "/, =",
                            onValueChange = { value ->
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(
                                            messageInstructionTextShortcuts = messageInstructionTextShortcuts.copy(
                                                separators = value.splitShortcutValues(),
                                            )
                                        )
                                    }
                                )
                            },
                        )
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
                            group.controls.forEach { control ->
                                TextFieldSettingItem(
                                    label = "${group.title}: ${control.data.title} aliases",
                                    description = "Comma-separated, case-insensitive aliases. Ambiguous aliases are ignored.",
                                    value = control.textShortcutAliases.joinToString(", "),
                                    onValueChange = { value ->
                                        onSettingsChange(
                                            settings.updateUserProfile {
                                                copy(
                                                    messageInstructionGroups = messageInstructionGroups.map { existingGroup ->
                                                        if (existingGroup.id == group.id) {
                                                            existingGroup.copy(
                                                                controls = existingGroup.controls.map { existingControl ->
                                                                    if (existingControl.data.id == control.data.id) {
                                                                        existingControl.copy(
                                                                            textShortcutAliases = value.splitShortcutValues(),
                                                                        )
                                                                    } else {
                                                                        existingControl
                                                                    }
                                                                }
                                                            )
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
                                            onOpenTabWithMessage(preparedMessage)
                                        } else {
                                            onOpenTab()
                                        }
                                    }
                                }
                            }
                        )
                    }

                    // Notifications Settings
                    SettingsGroup(title = translation.settings.notificationsTitle) {
                        SwitchSettingItem(
                            label = translation.settings.attentionSoundsLabel,
                            description = translation.settings.attentionSoundsDescription,
                            value = soundSettings.attentionSoundsEnabled,
                            onValueChange = {
                                onSettingsChange(settings.updateSoundSettings { copy(attentionSoundsEnabled = it) })
                            }
                        )

                        SwitchSettingItem(
                            label = translation.settings.activitySoundsLabel,
                            description = translation.settings.activitySoundsDescription,
                            value = soundSettings.activitySoundsEnabled,
                            onValueChange = {
                                onSettingsChange(settings.updateSoundSettings { copy(activitySoundsEnabled = it) })
                            }
                        )

                        SwitchSettingItem(
                            label = translation.settings.errorSoundsLabel,
                            description = translation.settings.errorSoundsDescription,
                            value = soundSettings.errorSoundsEnabled,
                            onValueChange = {
                                onSettingsChange(settings.updateSoundSettings { copy(errorSoundsEnabled = it) })
                            }
                        )

                        // Volume control (show only if any sound is enabled)
                        if (
                            soundSettings.attentionSoundsEnabled ||
                            soundSettings.activitySoundsEnabled ||
                            soundSettings.errorSoundsEnabled
                        ) {
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

                    if (
                        contentMode == SettingsPanelContentMode.Full &&
                        selectedSection == SettingsSection.Advanced
                    ) {
                    if (localWorkerController.status.value.supported) {
                        LocalWorkerSettings(
                            controller = localWorkerController,
                            distributionService = distributionService,
                            workerCatalogService = workerCatalogService,
                            coroutineScope = coroutineScope,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                    // Logs & Diagnostics
                    SettingsGroup(title = translation.settings.logsAndDiagnosticsTitle) {
                        InfoSettingItem(
                            label = "Automatic retention",
                            message = "Native application logs are size-bounded and rotated automatically. " +
                                "Browser diagnostics stay in the browser console.",
                        )
                    }

                    // Developer Settings
                    SettingsGroup(title = translation.settings.developerSettingsTitle) {
                        TextFieldSettingItem(
                            label = "Server address",
                            description = "The new address is used after restarting this client. Clear it to choose a server on the next launch.",
                            value = remoteClientSettings.remoteUrl.orEmpty(),
                            placeholder = "https://gromozeka.example",
                            onValueChange = {
                                onRemoteClientSettingsChange(
                                    remoteClientSettings.copy(remoteUrl = it.trim().ifEmpty { null })
                                )
                            },
                        )

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
}

@Composable
private fun LocalWorkerSettings(
    controller: LocalWorkerController,
    distributionService: RemoteDistributionService,
    workerCatalogService: WorkerCatalogService,
    coroutineScope: CoroutineScope,
) {
    val status by controller.status.collectAsState()
    SettingsGroup(title = "This ${status.deviceDisplayName}") {
        SwitchSettingItem(
            label = "Use this ${status.deviceDisplayName} as a Worker",
            description = "Run trusted tools, local Claude Code, voice capture, and Computer Use on this computer.",
            value = status.installed,
            enabled = status.operation == null,
            onValueChange = { enabled ->
                coroutineScope.launch {
                    if (enabled) {
                        controller.enable(distributionService, workerCatalogService)
                    } else {
                        controller.disable()
                    }
                }
            },
        )

        Text(
            text = status.description(),
            style = MaterialTheme.typography.bodySmall,
            color = if (status.failure == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        status.workerId?.let { workerId ->
            Text(
                text = "Worker: ${workerId.value}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (status.installed) {
            status.permissions?.let { permissions ->
                Text(
                    text = "Screen Recording: ${permissions.screenRecording.displayName()} · " +
                        "Accessibility: ${permissions.accessibility.displayName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = status.operation == null,
                    onClick = {
                        coroutineScope.launch {
                            if (status.running) controller.stop() else controller.start()
                        }
                    },
                ) {
                    Text(if (status.running) "Stop" else "Start")
                }
                if (status.permissions != null) {
                    OutlinedButton(
                        enabled = status.operation == null,
                        onClick = { coroutineScope.launch { controller.requestComputerUsePermissions() } },
                    ) {
                        Text("Permissions...")
                    }
                }
                OutlinedButton(
                    enabled = status.operation == null,
                    onClick = { coroutineScope.launch { controller.refresh(workerCatalogService) } },
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh")
                }
            }
        }
    }
}

private fun LocalWorkerStatus.description(): String = when {
    operation == LocalWorkerOperation.STARTING -> "Starting the Local Worker..."
    operation == LocalWorkerOperation.STOPPING -> "Stopping the Local Worker..."
    operation == LocalWorkerOperation.ENROLLING -> "Enrolling this ${deviceDisplayName} with the Server..."
    operation == LocalWorkerOperation.REQUESTING_PERMISSIONS -> "Opening operating system privacy settings..."
    operation == LocalWorkerOperation.REFRESHING -> "Refreshing Local Worker status..."
    failure != null -> failure
    running && serverStatus == WorkerCatalogEntry.Status.ONLINE -> "Online and connected to the Server."
    running -> "Running locally; waiting for the Server connection."
    installed -> "Enabled but currently stopped."
    else -> "Disabled. Existing standalone Workers are unaffected."
}

private fun LocalWorkerPermissionState.displayName(): String = when (this) {
    LocalWorkerPermissionState.GRANTED -> "Granted"
    LocalWorkerPermissionState.NOT_GRANTED -> "Required"
    LocalWorkerPermissionState.UNKNOWN -> "Unknown"
}

@Composable
private fun DistributionSettings(distributionService: RemoteDistributionService) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var loadState by remember { mutableStateOf<DistributionLoadState>(DistributionLoadState.Loading) }

    LaunchedEffect(reloadKey) {
        loadState = DistributionLoadState.Loading
        loadState = try {
            DistributionLoadState.Ready(distributionService.getManifest())
        } catch (error: Throwable) {
            DistributionLoadState.Failed(error.message ?: error::class.simpleName.orEmpty())
        }
    }

    when (val state = loadState) {
        DistributionLoadState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is DistributionLoadState.Failed -> {
            SettingsGroup(title = "Downloads") {
                Text(
                    text = state.message.ifBlank { "Could not load distributions." },
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { reloadKey++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Retry")
                }
            }
        }

        is DistributionLoadState.Ready -> {
            DistributionCatalog(state.manifest)
            WorkerEnrollmentSettings(
                availability = state.manifest.workerEnrollment.available,
                unavailableReason = state.manifest.workerEnrollment.unavailableReason,
                distributionService = distributionService,
            )
        }
    }
}

@Composable
private fun DistributionCatalog(manifest: DistributionManifest) {
    val uriHandler = LocalUriHandler.current

    SettingsGroup(title = "Downloads") {
        Text(
            text = "Native clients, standalone and Docker Servers, the Browser Bridge, and trusted Workers for version ${manifest.serverVersion}.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Downloads come directly from the matching GitHub Release.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DistributionComponent.entries.forEach { component ->
            Text(
                text = component.displayName(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            manifest.artifacts
                .filter { it.component == component }
                .forEach { artifact ->
                    DistributionArtifactItem(artifact) {
                        uriHandler.openUri(artifact.downloadUrl)
                    }
                }
        }

        TextButton(onClick = { uriHandler.openUri(manifest.checksumsUrl) }) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("SHA-256 checksums")
        }
    }
}

@Composable
private fun DistributionArtifactItem(
    artifact: DistributionArtifact,
    onDownload: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = artifact.platformDisplayName(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${artifact.format.displayName()} · ${artifact.fileName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onDownload,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download")
            }
        }
    }
}

private fun DistributionArtifact.platformDisplayName(): String =
    if (component == DistributionComponent.BROWSER_BRIDGE) {
        "Chrome, Edge, or Chromium"
    } else if (
        operatingSystem == DistributionOperatingSystem.ANY &&
        architecture == DistributionArchitecture.ANY
    ) {
        "Any Docker host"
    } else {
        "${operatingSystem.displayName()} ${architecture.displayName()}"
    }

@Composable
private fun WorkerEnrollmentSettings(
    availability: Boolean,
    unavailableReason: String?,
    distributionService: RemoteDistributionService,
) {
    val scope = rememberCoroutineScope()
    var enrollmentState by remember {
        mutableStateOf<WorkerEnrollmentState>(WorkerEnrollmentState.Idle)
    }

    SettingsGroup(title = "Add a Worker") {
        if (!availability) {
            Text(
                text = unavailableReason ?: "Worker enrollment is unavailable on this Server.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsGroup
        }

        Text(
            text = "Extract the Worker archive and run the command for its operating system. It prints a short code that you approve in Security.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "For a private Server CA, append --ca-certificate /path/to/root-or-chain.pem.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        WorkerConnectionCommands(distributionService.workerConnectionInstructions())

        Text(
            text = "Advanced: generate a short-lived token for headless provisioning.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            enabled = enrollmentState !is WorkerEnrollmentState.Loading,
            onClick = {
                scope.launch {
                    enrollmentState = WorkerEnrollmentState.Loading
                    enrollmentState = try {
                        WorkerEnrollmentState.Ready(distributionService.createWorkerEnrollment())
                    } catch (error: Throwable) {
                        WorkerEnrollmentState.Failed(error.message ?: error::class.simpleName.orEmpty())
                    }
                }
            },
        ) {
            if (enrollmentState is WorkerEnrollmentState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Generate one-time token")
            }
        }

        when (val state = enrollmentState) {
            WorkerEnrollmentState.Idle,
            WorkerEnrollmentState.Loading -> Unit

            is WorkerEnrollmentState.Failed -> Text(
                text = state.message.ifBlank { "Could not generate an enrollment token." },
                color = MaterialTheme.colorScheme.error,
            )

            is WorkerEnrollmentState.Ready -> WorkerEnrollmentCommands(state.instructions)
        }
    }
}

@Composable
private fun WorkerConnectionCommands(instructions: WorkerConnectionInstructions) {
    SelectionContainer {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = "macOS / Linux\n${instructions.macOsLinuxCommand}\n\n" +
                    "Windows\n${instructions.windowsCommand}",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WorkerEnrollmentCommands(instructions: WorkerEnrollmentInstructions) {
    SelectionContainer {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = "macOS / Linux\n${instructions.macOsLinuxCommand}\n\n" +
                    "Windows\n${instructions.windowsCommand}\n\n" +
                    "Token expires at ${instructions.expiresAt}",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private sealed interface DistributionLoadState {
    data object Loading : DistributionLoadState
    data class Ready(val manifest: DistributionManifest) : DistributionLoadState
    data class Failed(val message: String) : DistributionLoadState
}

private sealed interface WorkerEnrollmentState {
    data object Idle : WorkerEnrollmentState
    data object Loading : WorkerEnrollmentState
    data class Ready(val instructions: WorkerEnrollmentInstructions) : WorkerEnrollmentState
    data class Failed(val message: String) : WorkerEnrollmentState
}

private fun DistributionComponent.displayName(): String =
    when (this) {
        DistributionComponent.CLIENT -> "Clients"
        DistributionComponent.SERVER -> "Servers"
        DistributionComponent.WORKER -> "Workers"
        DistributionComponent.BROWSER_BRIDGE -> "Browser Bridge"
    }

private fun DistributionOperatingSystem.displayName(): String =
    when (this) {
        DistributionOperatingSystem.ANY -> "Any Docker host"
        DistributionOperatingSystem.MACOS -> "macOS"
        DistributionOperatingSystem.WINDOWS -> "Windows"
        DistributionOperatingSystem.LINUX -> "Linux"
    }

private fun DistributionArchitecture.displayName(): String =
    when (this) {
        DistributionArchitecture.ANY -> "any architecture"
        DistributionArchitecture.ARM64 -> "ARM64"
        DistributionArchitecture.X64 -> "x64"
    }

private fun DistributionFormat.displayName(): String =
    when (this) {
        DistributionFormat.BROWSER_EXTENSION_ZIP -> "unpacked extension ZIP"
        DistributionFormat.DOCKER_COMPOSE_ZIP -> "Docker Compose ZIP"
        DistributionFormat.DMG -> "DMG"
        DistributionFormat.PORTABLE_ZIP -> "portable ZIP"
        DistributionFormat.TAR_GZ -> "tar.gz"
    }

@Composable
private fun WebToolSettingsEditor(
    aiConfigurationService: AiConfigurationService,
    coroutineScope: CoroutineScope,
    translation: Translation,
) {
    val snapshot by aiConfigurationService.snapshotFlow.collectAsState()
    val currentSnapshot = snapshot
    if (currentSnapshot == null) {
        SettingsGroup(title = "Web tools") {
            InfoSettingItem(
                label = "AI catalog is loading",
                message = "Web tool settings will become available after the server catalog is loaded.",
            )
        }
        return
    }

    var draft by remember(currentSnapshot.revision) {
        mutableStateOf(currentSnapshot.catalog.webTools)
    }
    var openAiConnections by remember(currentSnapshot.revision) {
        mutableStateOf(
            currentSnapshot.catalog.connections.filter {
                it is AiConnection.OpenAiApi || it is AiConnection.OpenAiSubscription
            }
        )
    }
    var secretMutations by remember(currentSnapshot.revision) {
        mutableStateOf(emptyList<AiCatalogSecretMutation>())
    }
    val braveApiKeyInputState = remember(currentSnapshot.revision) {
        TextFieldState(currentSnapshot.catalog.webTools.braveSearch.apiKey.secretText())
    }
    val jinaApiKeyInputState = remember(currentSnapshot.revision) {
        TextFieldState(currentSnapshot.catalog.webTools.jinaReader.apiKey.secretText())
    }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember(currentSnapshot.revision) { mutableStateOf<String?>(null) }
    val braveSecretState = currentSnapshot.secretStates.firstOrNull {
        it.slot == AiCatalogSecretSlot.BraveSearchApiKey
    }
    val jinaSecretState = currentSnapshot.secretStates.firstOrNull {
        it.slot == AiCatalogSecretSlot.JinaReaderApiKey
    }
    val claudeModels = currentSnapshot.catalog.modelConfigurations
        .filter { configuration ->
            configuration.enabled &&
                currentSnapshot.catalog.connectionFor(configuration).let {
                    it is AiConnection.ClaudeCode && it.enabled
                }
        }
        .sortedBy(AiModelConfiguration::displayName)
    val claudeModelById = claudeModels.associateBy(AiModelConfiguration::id)
    val selectedClaudeModel = draft.claudeCode.modelConfigurationId
    val canEnableClaudeTools = selectedClaudeModel in claudeModelById
    val isDirty = draft != currentSnapshot.catalog.webTools ||
        openAiConnections != currentSnapshot.catalog.connections.filter {
            it is AiConnection.OpenAiApi || it is AiConnection.OpenAiSubscription
        } ||
        secretMutations.isNotEmpty()

    LaunchedEffect(braveApiKeyInputState, currentSnapshot.revision) {
        snapshotFlow { braveApiKeyInputState.text.toString() }.collect { value ->
            if (value == draft.braveSearch.apiKey.secretText()) return@collect
            val secret = value.inlineSecretOrNull()
            draft = draft.copy(
                braveSearch = draft.braveSearch.copy(apiKey = secret),
            )
            secretMutations = secretMutations.withSecretMutation(
                secret?.let {
                    AiCatalogSecretMutation.Set(
                        slot = AiCatalogSecretSlot.BraveSearchApiKey,
                        value = it,
                    )
                },
                AiCatalogSecretSlot.BraveSearchApiKey,
            )
        }
    }
    LaunchedEffect(jinaApiKeyInputState, currentSnapshot.revision) {
        snapshotFlow { jinaApiKeyInputState.text.toString() }.collect { value ->
            if (value == draft.jinaReader.apiKey.secretText()) return@collect
            val secret = value.inlineSecretOrNull()
            draft = draft.copy(
                jinaReader = draft.jinaReader.copy(apiKey = secret),
            )
            secretMutations = secretMutations.withSecretMutation(
                secret?.let {
                    AiCatalogSecretMutation.Set(
                        slot = AiCatalogSecretSlot.JinaReaderApiKey,
                        value = it,
                    )
                },
                AiCatalogSecretSlot.JinaReaderApiKey,
            )
        }
    }

    SettingsGroup(title = "Web tools") {
        if (openAiConnections.isEmpty()) {
            InfoSettingItem(
                label = "OpenAI hosted web search",
                message = "Create an OpenAI API or OpenAI subscription connection to use its native web search.",
            )
        } else {
            openAiConnections.forEach { connection ->
                SwitchSettingItem(
                    label = "${connection.displayName} hosted search",
                    description = when (connection) {
                        is AiConnection.OpenAiApi ->
                            "Use OpenAI web_search through this API connection when its model needs current information."
                        is AiConnection.OpenAiSubscription ->
                            "Use OpenAI web_search through this subscription connection when its model needs current information."
                        else -> error("Unexpected OpenAI web search connection ${connection.kind}")
                    },
                    value = connection.openAiWebSearchEnabled(),
                    enabled = connection.enabled,
                    onValueChange = { enabled ->
                        openAiConnections = openAiConnections.map { candidate ->
                            if (candidate.id == connection.id) {
                                candidate.withOpenAiWebSearchEnabled(enabled)
                            } else {
                                candidate
                            }
                        }
                    },
                )
            }
        }

        HorizontalDivider()

        SwitchSettingItem(
            label = translation.settings.enableBraveSearchLabel,
            description = translation.settings.braveSearchDescription,
            value = draft.braveSearch.enabled,
            onValueChange = { enabled ->
                draft = draft.copy(
                    braveSearch = draft.braveSearch.copy(enabled = enabled),
                )
            },
        )
        if (draft.braveSearch.enabled) {
            PasswordSettingItem(
                label = translation.settings.braveApiKeyLabel,
                description = translation.settings.braveApiKeyDescription,
                state = braveApiKeyInputState,
            )
            ConfiguredSecretControls(
                state = braveSecretState,
                pendingMutation = secretMutations.forSlot(AiCatalogSecretSlot.BraveSearchApiKey),
                onRemove = {
                    braveApiKeyInputState.clearText()
                    draft = draft.copy(
                        braveSearch = draft.braveSearch.copy(apiKey = null)
                    )
                    secretMutations = secretMutations.withSecretMutation(
                        AiCatalogSecretMutation.Remove(AiCatalogSecretSlot.BraveSearchApiKey),
                        AiCatalogSecretSlot.BraveSearchApiKey,
                    )
                },
                onKeep = {
                    braveApiKeyInputState.setTextAndPlaceCursorAtEnd(
                        currentSnapshot.catalog.webTools.braveSearch.apiKey.secretText()
                    )
                    draft = draft.copy(
                        braveSearch = draft.braveSearch.copy(
                            apiKey = currentSnapshot.catalog.webTools.braveSearch.apiKey
                        )
                    )
                    secretMutations = secretMutations.withSecretMutation(
                        null,
                        AiCatalogSecretSlot.BraveSearchApiKey,
                    )
                },
            )
        }

        SwitchSettingItem(
            label = translation.settings.enableJinaReaderLabel,
            description = translation.settings.jinaReaderDescription,
            value = draft.jinaReader.enabled,
            onValueChange = { enabled ->
                draft = draft.copy(
                    jinaReader = draft.jinaReader.copy(enabled = enabled),
                )
            },
        )
        if (draft.jinaReader.enabled) {
            PasswordSettingItem(
                label = translation.settings.jinaApiKeyLabel,
                description = translation.settings.jinaApiKeyDescription,
                state = jinaApiKeyInputState,
            )
            ConfiguredSecretControls(
                state = jinaSecretState,
                pendingMutation = secretMutations.forSlot(AiCatalogSecretSlot.JinaReaderApiKey),
                onRemove = {
                    jinaApiKeyInputState.clearText()
                    draft = draft.copy(
                        jinaReader = draft.jinaReader.copy(apiKey = null)
                    )
                    secretMutations = secretMutations.withSecretMutation(
                        AiCatalogSecretMutation.Remove(AiCatalogSecretSlot.JinaReaderApiKey),
                        AiCatalogSecretSlot.JinaReaderApiKey,
                    )
                },
                onKeep = {
                    jinaApiKeyInputState.setTextAndPlaceCursorAtEnd(
                        currentSnapshot.catalog.webTools.jinaReader.apiKey.secretText()
                    )
                    draft = draft.copy(
                        jinaReader = draft.jinaReader.copy(
                            apiKey = currentSnapshot.catalog.webTools.jinaReader.apiKey
                        )
                    )
                    secretMutations = secretMutations.withSecretMutation(
                        null,
                        AiCatalogSecretSlot.JinaReaderApiKey,
                    )
                },
            )
        }

        HorizontalDivider()

        if (claudeModels.isEmpty()) {
            InfoSettingItem(
                label = "Claude Code web tools",
                message = "Create and enable a Claude Code connection and model configuration first.",
            )
        } else {
            DropdownSettingItem<AiModelConfiguration.Id?>(
                label = "Claude Code web model",
                description = "One central model configuration used by the native WebSearch and WebFetch proxies.",
                value = selectedClaudeModel,
                options = listOf(null) + claudeModels.map(AiModelConfiguration::id),
                optionLabel = { id ->
                    id?.let(claudeModelById::get)?.let { model ->
                        "${model.displayName} (${model.providerModelId})"
                    } ?: "Not configured"
                },
                onValueChange = { modelConfigurationId ->
                    draft = draft.copy(
                        claudeCode = draft.claudeCode.copy(
                            modelConfigurationId = modelConfigurationId,
                            searchEnabled = if (modelConfigurationId == null) {
                                false
                            } else {
                                draft.claudeCode.searchEnabled
                            },
                            fetchEnabled = if (modelConfigurationId == null) {
                                false
                            } else {
                                draft.claudeCode.fetchEnabled
                            },
                        )
                    )
                },
            )
            SwitchSettingItem(
                label = "Enable Claude Code WebSearch",
                description = "Expose claude_code_web_search only on Workers that can run the selected Claude Code connection.",
                value = draft.claudeCode.searchEnabled,
                enabled = canEnableClaudeTools,
                onValueChange = { enabled ->
                    draft = draft.copy(
                        claudeCode = draft.claudeCode.copy(searchEnabled = enabled),
                    )
                },
            )
            SwitchSettingItem(
                label = "Enable Claude Code WebFetch",
                description = "Expose claude_code_web_fetch only on Workers that can run the selected Claude Code connection.",
                value = draft.claudeCode.fetchEnabled,
                enabled = canEnableClaudeTools,
                onValueChange = { enabled ->
                    draft = draft.copy(
                        claudeCode = draft.claudeCode.copy(fetchEnabled = enabled),
                    )
                },
            )
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                enabled = isDirty && !isSaving,
                onClick = {
                    coroutineScope.launch {
                        isSaving = true
                        error = null
                        runCatching {
                            val latest = aiConfigurationService.snapshot
                            val openAiConnectionsById = openAiConnections.associateBy(AiConnection::id)
                            aiConfigurationService.replaceCatalog(
                                catalog = latest.catalog.copy(
                                    connections = latest.catalog.connections.map { connection ->
                                        openAiConnectionsById[connection.id] ?: connection
                                    },
                                    webTools = draft,
                                ),
                                expectedRevision = latest.revision,
                                secretMutations = secretMutations,
                            )
                        }.onFailure {
                            error = it.message ?: it::class.simpleName
                        }
                        isSaving = false
                    }
                },
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Save web tools")
                }
            }
        }
    }
}

private fun AiConnection.openAiWebSearchEnabled(): Boolean = when (this) {
    is AiConnection.OpenAiApi -> webSearchEnabled
    is AiConnection.OpenAiSubscription -> webSearchEnabled
    else -> error("Connection $id does not support OpenAI hosted web search")
}

private fun AiConnection.withOpenAiWebSearchEnabled(enabled: Boolean): AiConnection = when (this) {
    is AiConnection.OpenAiApi -> copy(webSearchEnabled = enabled)
    is AiConnection.OpenAiSubscription -> copy(webSearchEnabled = enabled)
    else -> error("Connection $id does not support OpenAI hosted web search")
}

@Composable
private fun ConfiguredSecretControls(
    state: AiCatalogSecretState?,
    pendingMutation: AiCatalogSecretMutation?,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
) {
    if (state == null) return
    val removing = pendingMutation is AiCatalogSecretMutation.Remove
    Text(
        text = when {
            removing -> "The configured API key will be removed."
            state.source == AiCatalogSecretState.Source.INLINE ->
                "An API key is stored on the Server. Leave the field empty to keep it."
            else -> "Using environment variable ${state.environmentVariableName}."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (removing) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    TextButton(onClick = if (removing) onKeep else onRemove) {
        Text(if (removing) "Keep configured API key" else "Remove configured API key")
    }
}

private fun List<AiCatalogSecretMutation>.forSlot(
    slot: AiCatalogSecretSlot,
): AiCatalogSecretMutation? =
    firstOrNull { it.slot == slot }

private fun List<AiCatalogSecretMutation>.withSecretMutation(
    mutation: AiCatalogSecretMutation?,
    slot: AiCatalogSecretSlot,
): List<AiCatalogSecretMutation> =
    filterNot { it.slot == slot } + listOfNotNull(mutation)

@Composable
internal fun SettingsGroup(
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

private fun Settings.updateUserProfile(update: UserProfile.() -> UserProfile): Settings =
    copy(userProfile = userProfile.update())

private fun String.splitWhisperExtraArguments(): List<String> =
    trim().takeIf { it.isNotBlank() }
        ?.split(Regex("\\s+"))
        .orEmpty()

private fun String.splitShortcutValues(): List<String> =
    split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)

private fun Settings.updateDeviceSettings(update: UserDeviceSettings.() -> UserDeviceSettings): Settings =
    copy(userDeviceSettings = userDeviceSettings.update())

private fun Settings.updateUiSettings(update: UserDeviceSettings.UiSettings.() -> UserDeviceSettings.UiSettings): Settings =
    updateDeviceSettings { withUiSettings(uiSettings.update()) }

private fun Settings.updateSoundSettings(
    update: UserDeviceSettings.SoundSettings.() -> UserDeviceSettings.SoundSettings,
): Settings =
    updateDeviceSettings { withSoundSettings(soundSettings.update()) }

private fun Settings.updateVoiceInputSettings(
    update: UserDeviceSettings.VoiceInputSettings.() -> UserDeviceSettings.VoiceInputSettings,
): Settings =
    updateDeviceSettings { withVoiceInputSettings(voiceInputSettings.update()) }

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

private fun UserDeviceSettings.withVoiceInputSettings(
    voiceInputSettings: UserDeviceSettings.VoiceInputSettings,
): UserDeviceSettings =
    when (this) {
        is UserDeviceSettings.Desktop -> copy(voiceInputSettings = voiceInputSettings)
        is UserDeviceSettings.Android -> copy(voiceInputSettings = voiceInputSettings)
        is UserDeviceSettings.Ios -> copy(voiceInputSettings = voiceInputSettings)
        is UserDeviceSettings.Web -> copy(voiceInputSettings = voiceInputSettings)
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
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
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
    state: TextFieldState,
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

        OutlinedSecretTextField(
            state = state,
            modifier = Modifier.fillMaxWidth(),
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
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
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
