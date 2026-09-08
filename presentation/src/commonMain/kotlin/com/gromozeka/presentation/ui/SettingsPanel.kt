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
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
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
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.KeyboardShortcutBinding
import com.gromozeka.domain.model.KeyboardShortcutKey
import com.gromozeka.domain.model.KeyboardShortcutModifier
import com.gromozeka.domain.model.KeyboardShortcutScope
import com.gromozeka.domain.model.KeyboardShortcutValidationSeverity
import com.gromozeka.domain.model.KeyboardShortcutValidator
import com.gromozeka.domain.model.QuickTextAction
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
import com.gromozeka.presentation.services.GlobalHotkeyController
import com.gromozeka.presentation.services.OllamaModelService
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.AiUsageReportService
import com.gromozeka.domain.service.AgentDomainService
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
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.presentation.services.translation.LocalizedText
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

private enum class SettingsSection(val testTagName: String, val titleKey: String) {
    Interface("Interface", "settingsUi.interface"),
    Voice("Voice", "settingsUi.voice"),
    Keyboard("Keyboard", "settingsUi.keyboard"),
    AiRuntime("AI", "settingsUi.ai"),
    Usage("Usage", "settingsUi.usage"),
    Behavior("Behavior", "settingsUi.behavior"),
    Tools("Tools", "settingsUi.tools"),
    Security("Security", "settingsUi.security"),
    Downloads("Downloads", "settingsUi.downloads"),
    Advanced("Advanced", "settingsUi.advanced"),
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
    globalHotkeyController: GlobalHotkeyController,
    agentService: AgentDomainService,
    aiConfigurationService: AiConfigurationService,
    aiUsageReportService: AiUsageReportService,
    runtimeCatalogTemplateService: RuntimeCatalogTemplateService,
    workerCatalogService: WorkerCatalogService,
    mcpServerService: RemoteMcpServerService,
    distributionService: RemoteDistributionService,
    deviceConnectionService: RemoteDeviceConnectionClient,
    personalAccessTokenService: RemotePersonalAccessTokenService,
    aiUserCredentialService: CurrentUserAiCredentialService,
    namedSecretService: com.gromozeka.domain.service.CurrentUserNamedSecretService,
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
    val suggestedRepliesSettings = userProfile.suggestedRepliesSettings
    val deviceSettings = settings.userDeviceSettings
    val uiSettings = deviceSettings.uiSettings
    val themeSettings = uiSettings.theme
    val soundSettings = deviceSettings.soundSettings
    val voiceInputSettings = deviceSettings.voiceInputSettings
    val desktopInputSettings = settings.desktopInputSettings
    val desktopWindowSettings = settings.desktopWindowSettings
    var availableQuickTextAgents by remember { mutableStateOf(emptyList<AgentDefinition>()) }
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
    val availableSections = SettingsSection.entries.filter {
        (it != SettingsSection.Usage || canAdministerUsers) &&
            (it != SettingsSection.Keyboard || deviceSettings is UserDeviceSettings.Desktop)
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

    LaunchedEffect(isVisible, agentService) {
        if (!isVisible) return@LaunchedEffect
        agentService.observeAll()
            .catch { failure ->
                log.warn(failure) { "Failed to load Agents for quick text actions: ${failure.message}" }
            }
            .collect { agents ->
                availableQuickTextAgents = agents
                    .filter { it.type is AgentDefinition.Type.Global }
                    .sortedBy { it.name.lowercase() }
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
                        selectedTabIndex = availableSections.indexOf(selectedSection),
                        edgePadding = 0.dp,
                    ) {
                        availableSections.forEach { section ->
                            Tab(
                                selected = selectedSection == section,
                                onClick = { selectedSection = section },
                                text = { Text(translation.text(section.titleKey)) },
                                modifier = Modifier.testTag(UiTestTag.SettingsSectionTab(section.testTagName).value),
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
                                label = translation.text("settingsUi.speechToTextBackend"),
                                description = translation.text("settingsUi.chooseTheTranscriptionEngineIndependentlyFromTheDevice"),
                                value = speechToText.engine,
                                options = UserProfile.SpeechSettings.SpeechToText.Engine.entries.toList(),
                                optionLabel = {
                                    when (it) {
                                        UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API -> translation.text("settingsUi.openaiAPI")
                                        UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER -> translation.text("settingsUi.localWhisper")
                                        UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE -> translation.text("settingsUi.claudeCodeVoice")
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
                                        translation.text("settingsUi.createAClaudeCodeConnectionAndEnableVoice"),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    DropdownSettingItem(
                                        label = translation.text("settingsUi.claudeCodeConnection"),
                                        description = translation.text("settingsUi.requiresAnOrganizationApprovedClaudeAiLoginOn"),
                                        value = speechToText.claudeCodeConnectionId,
                                        options = listOf(null) + connectionIds,
                                        optionLabel = { id ->
                                            if (id == null) return@DropdownSettingItem translation.text("settingsUi.selectAClaudeCodeConnection")
                                            val connection = claudeCodeConnections.firstOrNull { it.id == id }
                                            buildString {
                                                append(connection?.displayName ?: id.value)
                                                when {
                                                    connection == null -> append(translation.text("settingsUi.unavailable"))
                                                    !connection.enabled -> append(translation.text("settingsUi.disabled"))
                                                    !connection.voiceTranscriptionEnabled -> append(translation.text("settingsUi.voiceDisabled"))
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
                                add("" to translation.text("settingsUi.thisClient"))
                                workers.forEach { worker ->
                                    add(
                                        worker.workerId.value to
                                            translation.text("settingsUi.workerStatus", "worker" to worker.workerId.value, "status" to worker.status.displayName(translation))
                                    )
                                }
                                selectedWorkerSource?.workerId?.value
                                    ?.takeIf { id -> none { it.first == id } }
                                    ?.let { add(it to translation.text("settingsUi.workerStatus", "worker" to it, "status" to translation.text("settingsUi.unknown"))) }
                            }
                            DropdownSettingItem(
                                label = translation.text("settingsUi.audioSource"),
                                description = translation.text("settingsUi.recordOnThisClientOrOnOneExact"),
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
                                                displayName = translation.text("settingsUi.unavailableInput", "input" to selectedWorkerSource.inputId.value),
                                            )
                                        )
                                    }
                                }
                                DropdownSettingItem(
                                    label = translation.text("settingsUi.workerAudioInput"),
                                    description = translation.text("settingsUi.anOfflineWorkerRemainsSelectableRecordingBecomesAvailable"),
                                    value = selectedWorkerSource.inputId,
                                    options = audioInputs.map { it.id },
                                    optionLabel = { id ->
                                        audioInputs.first { it.id == id }.let { input ->
                                            val name = if (
                                                input.id == WorkerAudioInput.SystemDefault.id &&
                                                input.displayName == WorkerAudioInput.SystemDefault.displayName
                                            ) translation.text("settingsUi.systemDefaultMicrophone") else input.displayName
                                            if (input.isDefault) translation.text("settingsUi.defaultInput", "input" to name) else name
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
                                    label = translation.text("settingsUi.whisperExecutionTarget"),
                                    description = translation.text("settingsUi.finiteTranscriptionRunsOnThisExactTargetLive"),
                                    value = localWhisper.executionTarget,
                                    options = executionTargets,
                                    optionLabel = { target ->
                                        when (target) {
                                            AiExecutionTarget.Server -> translation.text("settingsUi.server")
                                            is AiExecutionTarget.Worker -> {
                                                val status = workers.firstOrNull {
                                                    it.workerId.value == target.workerId
                                                }?.status?.displayName(translation) ?: translation.text("settingsUi.unknown")
                                                translation.text("settingsUi.workerStatus", "worker" to target.workerId, "status" to status)
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
                                    label = translation.text("settingsUi.whisperExecutable"),
                                    description = translation.text("settingsUi.pathOrCommandNameForWhisperCppCLI"),
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
                                    label = translation.text("settingsUi.whisperModel"),
                                    description = translation.text("settingsUi.usedWhenModelPathIsEmptyModelIs"),
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
                                    label = translation.text("settingsUi.whisperModelPath"),
                                    description = translation.text("settingsUi.optionalAbsolutePathLeaveEmptyToUseThe"),
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
                                    label = translation.text("settingsUi.whisperThreads"),
                                    description = translation.text("settingsUi.0KeepsWhisperCppDefaultPositiveValuesAre"),
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
                                    label = translation.text("settingsUi.whisperExtraArguments"),
                                    description = translation.text("settingsUi.advancedWhisperCppArgsAppendedAfterGromozekaRequired"),
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
                                    label = translation.text("settingsUi.whisperLiveProfile"),
                                    description = translation.text("settingsUi.controlsLiveChunkSizeSlowCPUIncreasesLatency"),
                                    value = localWhisper.liveStreaming.profile,
                                    options = UserProfile.SpeechSettings.SpeechToText.LocalWhisper.LiveStreaming.Profile.entries,
                                    optionLabel = { it.displayName(translation) },
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

                            val recognitionLanguageNames = Translation.builtIn.values
                                .associate { it.content.locale.substringBefore('-') to it.languageName }
                            EditableDropdownSettingItem(
                                label = translation.settings.recognitionLanguageLabel,
                                description = translation.settings.sttLanguageDescription,
                                value = speechToText.mainLanguageCode,
                                predefinedOptions = recognitionLanguageNames.keys.toList(),
                                optionLabel = { code -> recognitionLanguageNames[code]?.let { "$it ($code)" } ?: code },
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
                                label = translation.text("settingsUi.continuousVoiceInput"),
                                description = translation.text("settingsUi.keepThisClientSMicrophoneOpenSplitSpeech"),
                                value = voiceInputSettings.liveVoiceInputEnabled,
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateVoiceInputSettings { copy(liveVoiceInputEnabled = it) }
                                    )
                                }
                            )

                            DropdownSettingItem(
                                label = translation.text("settingsUi.continuousVoiceVADMode"),
                                description = translation.text("settingsUi.chooseWhoDecidesPhraseBoundariesProviderVADIs"),
                                value = voiceInputSettings.liveVoiceVadMode,
                                options = UserDeviceSettings.VoiceInputSettings.LiveVoiceVadMode.entries,
                                optionLabel = {
                                    when (it) {
                                        UserDeviceSettings.VoiceInputSettings.LiveVoiceVadMode.LOCAL_VAD ->
                                            translation.text("settingsUi.localEnergyVAD")
                                        UserDeviceSettings.VoiceInputSettings.LiveVoiceVadMode.PROVIDER_VAD ->
                                            translation.text("settingsUi.providerVAD")
                                    }
                                },
                                onValueChange = { mode ->
                                    onSettingsChange(
                                        settings.updateVoiceInputSettings { copy(liveVoiceVadMode = mode) }
                                    )
                                }
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

                    if (
                        contentMode == SettingsPanelContentMode.Full &&
                        selectedSection == SettingsSection.Keyboard
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            KeyboardShortcutSettingsGroup(
                                settings = settings,
                                globalHotkeyController = globalHotkeyController,
                                onSettingsChange = onSettingsChange,
                            )
                            QuickTextActionSettingsGroup(
                                settings = settings,
                                agents = availableQuickTextAgents,
                                onSettingsChange = onSettingsChange,
                            )
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
                        selectedSection == SettingsSection.Usage &&
                        canAdministerUsers
                    ) {
                        AiUsageSettings(aiUsageReportService)
                    }

                    if (
                        contentMode == SettingsPanelContentMode.Full &&
                        selectedSection == SettingsSection.Behavior
                    ) {
                        SettingsGroup(title = translation.text("settingsUi.agentAndMemoryBehavior")) {
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
                            label = translation.settings.includeMessageTemporalContextLabel,
                            description = translation.settings.includeMessageTemporalContextDescription,
                            value = agentSettings.includeMessageTemporalContext,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(
                                            agentSettings = agentSettings.copy(
                                                includeMessageTemporalContext = it
                                            )
                                        )
                                    }
                                )
                            }
                        )

                        if (agentSettings.includeCurrentTime && agentSettings.includeMessageTemporalContext) {
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
                                    text = translation.settings.duplicateTemporalContextWarning,
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        SwitchSettingItem(
                            label = translation.text("settingsUi.autoApproveAllToolRequests"),
                            description = translation.text("settingsUi.automaticallyAllowAllToolExecutionsWithoutShowingPermission"),
                            value = agentSettings.autoApproveAllTools,
                            onValueChange = {
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(agentSettings = agentSettings.copy(autoApproveAllTools = it))
                                    }
                                )
                            }
                        )

                        DropdownSettingItem(
                            label = translation.text("settingsUi.suggestedReplies"),
                            description = translation.text("settingsUi.generateReplyChipsInlineWithTheAnswerOr"),
                            value = suggestedRepliesSettings.mode,
                            options = UserProfile.SuggestedRepliesSettings.Mode.entries,
                            optionLabel = { mode ->
                                when (mode) {
                                    UserProfile.SuggestedRepliesSettings.Mode.DISABLED -> translation.text("settingsUi.off")
                                    UserProfile.SuggestedRepliesSettings.Mode.INLINE -> translation.text("settingsUi.inlineWithTheAnswer")
                                    UserProfile.SuggestedRepliesSettings.Mode.SEPARATE_RUNTIME -> translation.text("settingsUi.separateAIRuntime")
                                }
                            },
                            onValueChange = { mode ->
                                onSettingsChange(
                                    settings.updateUserProfile {
                                        copy(
                                            suggestedRepliesSettings = suggestedRepliesSettings.copy(mode = mode)
                                        )
                                    }
                                )
                            },
                        )

                        SwitchSettingItem(
                            label = translation.text("settingsUi.autoRememberThreads"),
                            description = translation.text("settingsUi.automaticallyWriteTypedMemoryAroundEachChatMessage"),
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
                            label = translation.text("settingsUi.autoRecallMemory"),
                            description = translation.text("settingsUi.automaticallyRecallTypedMemoryBeforeTheMainModel"),
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
                            label = translation.text("settingsUi.forceDocumentIngest"),
                            description = translation.text("settingsUi.bypassMemoryRelevanceRoutingForTechnicallyValidDocuments"),
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
                        NamedSecretSettings(
                            service = namedSecretService,
                            coroutineScope = coroutineScope,
                        )
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
                    SettingsGroup(title = translation.text("settingsUi.composerShortcuts")) {
                        TextFieldSettingItem(
                            label = translation.text("settingsUi.instructionShortcutSeparators"),
                            description = translation.text("settingsUi.commaSeparatedPrefixesTypeAPrefixAnInstruction"),
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
                                label = group.displayTitle(translation),
                                description = group.controls.joinToString(" · ") { control ->
                                    "${control.displayShortLabel(translation)} ${control.data.displayTitle(translation)}"
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
                            SwitchSettingItem(
                                label = translation.text("settingsUi.stickyReminder", "group" to group.displayTitle(translation)),
                                description = translation.text("settingsUi.keepOnlyTheLatestInstructionFromThisGroup"),
                                value = group.retentionMode == MessageInstructionGroup.RetentionMode.STICKY_LATEST,
                                enabled = group.controls.all { it.includeInMessage },
                                onValueChange = { sticky ->
                                    onSettingsChange(
                                        settings.updateUserProfile {
                                            copy(
                                                messageInstructionGroups = messageInstructionGroups.map { existingGroup ->
                                                    if (existingGroup.id == group.id) {
                                                        existingGroup.copy(
                                                            retentionMode = if (sticky) {
                                                                MessageInstructionGroup.RetentionMode.STICKY_LATEST
                                                            } else {
                                                                MessageInstructionGroup.RetentionMode.KEEP_HISTORY
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
                            if (group.retentionMode == MessageInstructionGroup.RetentionMode.STICKY_LATEST) {
                                Text(
                                    text = translation.text("settingsUi.stickyReminderWarning"),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            group.controls.forEach { control ->
                                TextFieldSettingItem(
                                    label = translation.text("settingsUi.instructionAliases", "group" to group.displayTitle(translation), "instruction" to control.data.displayTitle(translation)),
                                    description = translation.text("settingsUi.commaSeparatedCaseInsensitiveAliasesAmbiguousAliasesAre"),
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
                            label = translation.text("settingsUi.uiScale"),
                            description = translation.text("settingsUi.adjustInterfaceSize05Tiny10"),
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
                            label = translation.text("settingsUi.fontScale"),
                            description = translation.text("settingsUi.adjustTextSize05Small10"),
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
                            label = translation.text("settingsUi.resetScale"),
                            description = translation.text("settingsUi.restoreInterfaceAndTextScaleTo100"),
                            buttonText = translation.text("settingsUi.resetScale"),
                            onClick = {
                                onSettingsChange(settings.updateUiSettings { copy(uiScale = 1.0f, fontScale = 1.0f) })
                            }
                        )
                    }

                    // Localization Settings
                    LocalizationSettings(translationService)

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
                                    themeInfo.isBuiltIn -> translation.text(
                                        "settingsUi.builtInTheme",
                                        "theme" to Theme.getThemeNameTranslated(themeId, translation),
                                    )

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
                            label = translation.text("settingsUi.enableThemeOverride"),
                            description = translation.text("settingsUi.allowCustomThemeColorsFromOverrideJsonFile"),
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
                                            message = translation.format("settings.themeOverrideSuccessMessage", result.overriddenFields.size),
                                            isError = false
                                        )
                                    }

                                    is com.gromozeka.presentation.services.theming.ThemeOverrideResult.Failure -> {
                                        InfoSettingItem(
                                            label = translation.settings.themeOverrideStatusLabel,
                                            message = translation.format("settings.themeOverrideFailureMessage", result.error),
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
                            label = translation.text("settingsUi.aiGenerateThemeFromWindow"),
                            description = translation.text("settingsUi.takeAScreenshotOfASelectedWindowAnd"),
                            buttonText = translation.text("settingsUi.generateThemeFromWindow"),
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
                        (deviceSettings as? UserDeviceSettings.Desktop)?.let { desktopSettings ->
                            SwitchSettingItem(
                                label = translation.settings.turnCompletionNotificationsLabel,
                                description = translation.settings.turnCompletionNotificationsDescription,
                                value = desktopSettings.turnCompletionNotificationsEnabled,
                                onValueChange = {
                                    onSettingsChange(
                                        settings.updateDesktopSettings {
                                            copy(turnCompletionNotificationsEnabled = it)
                                        }
                                    )
                                }
                            )
                        }

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
                    // Logs & Diagnostics
                    SettingsGroup(title = translation.settings.logsAndDiagnosticsTitle) {
                        InfoSettingItem(
                            label = translation.text("settingsUi.automaticRetention"),
                            message = translation.text("settingsUi.logRetentionDescription"),
                        )
                    }

                    // Developer Settings
                    SettingsGroup(title = translation.settings.developerSettingsTitle) {
                        TextFieldSettingItem(
                            label = translation.text("settingsUi.serverAddress"),
                            description = translation.text("settingsUi.theNewAddressIsUsedAfterRestartingThis"),
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
                            label = translation.text("settingsUi.remoteProtocol"),
                            description = translation.text("settingsUi.cborIsTheNormalBinaryTransportJSONIs"),
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
private fun DistributionSettings(distributionService: RemoteDistributionService) {
    val translation = LocalTranslation.current
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
            SettingsGroup(title = translation.text("settingsUi.downloads")) {
                Text(
                    text = state.message.ifBlank { translation.text("settingsUi.couldNotLoadDistributions") },
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { reloadKey++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(translation.text("settingsUi.retry"))
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
    val translation = LocalTranslation.current
    val uriHandler = LocalUriHandler.current

    SettingsGroup(title = translation.text("settingsUi.downloads")) {
        Text(
            text = translation.text("settingsUi.distributionsDescription", "version" to manifest.serverVersion),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = translation.text("settingsUi.downloadsComeDirectlyFromTheMatchingGitHubRelease"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DistributionComponent.entries.forEach { component ->
            Text(
                text = component.displayName(translation),
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
            Text(translation.text("settingsUi.sha256Checksums"))
        }
    }
}

@Composable
private fun DistributionArtifactItem(
    artifact: DistributionArtifact,
    onDownload: () -> Unit,
) {
    val translation = LocalTranslation.current
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
                text = artifact.platformDisplayName(translation),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${artifact.format.displayName(translation)} · ${artifact.fileName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = onDownload,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(translation.text("settingsUi.download"))
            }
        }
    }
}

private fun DistributionArtifact.platformDisplayName(translation: Translation): String =
    if (component == DistributionComponent.BROWSER_BRIDGE) {
        translation.text("settingsUi.chromeEdgeOrChromium")
    } else if (
        operatingSystem == DistributionOperatingSystem.ANY &&
        architecture == DistributionArchitecture.ANY
    ) {
        translation.text("settingsUi.anyDockerHost")
    } else {
        "${operatingSystem.displayName(translation)} ${architecture.displayName(translation)}"
    }

@Composable
private fun WorkerEnrollmentSettings(
    availability: Boolean,
    unavailableReason: String?,
    distributionService: RemoteDistributionService,
) {
    val translation = LocalTranslation.current
    val scope = rememberCoroutineScope()
    var enrollmentState by remember {
        mutableStateOf<WorkerEnrollmentState>(WorkerEnrollmentState.Idle)
    }

    SettingsGroup(title = translation.text("settingsUi.addAWorker")) {
        if (!availability) {
            Text(
                text = unavailableReason ?: translation.text("settingsUi.workerEnrollmentIsUnavailableOnThisServer"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsGroup
        }

        Text(
            text = translation.text("settingsUi.extractTheWorkerArchiveAndRunTheCommand"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = translation.text("settingsUi.forAPrivateServerCAAppendCaCertificate"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        WorkerConnectionCommands(distributionService.workerConnectionInstructions())

        Text(
            text = translation.text("settingsUi.advancedGenerateAShortLivedTokenForHeadless"),
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
                Text(translation.text("settingsUi.generateOneTimeToken"))
            }
        }

        when (val state = enrollmentState) {
            WorkerEnrollmentState.Idle,
            WorkerEnrollmentState.Loading -> Unit

            is WorkerEnrollmentState.Failed -> Text(
                text = state.message.ifBlank { translation.text("settingsUi.couldNotGenerateAnEnrollmentToken") },
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
    val translation = LocalTranslation.current
    SelectionContainer {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = "macOS / Linux\n${instructions.macOsLinuxCommand}\n\n" +
                    "Windows\n${instructions.windowsCommand}\n\n" +
                    translation.text("settingsUi.tokenExpiresAt", "time" to instructions.expiresAt),
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

private fun DistributionComponent.displayName(translation: Translation): String =
    when (this) {
        DistributionComponent.CLIENT -> translation.text("settingsUi.clients")
        DistributionComponent.SERVER -> translation.text("settingsUi.servers")
        DistributionComponent.WORKER -> translation.text("settingsUi.workers")
        DistributionComponent.BROWSER_BRIDGE -> translation.text("settingsUi.browserBridge")
    }

private fun DistributionOperatingSystem.displayName(translation: Translation): String =
    when (this) {
        DistributionOperatingSystem.ANY -> translation.text("settingsUi.anyDockerHost")
        DistributionOperatingSystem.MACOS -> "macOS"
        DistributionOperatingSystem.WINDOWS -> "Windows"
        DistributionOperatingSystem.LINUX -> "Linux"
    }

private fun DistributionArchitecture.displayName(translation: Translation): String =
    when (this) {
        DistributionArchitecture.ANY -> translation.text("settingsUi.anyArchitecture")
        DistributionArchitecture.ARM64 -> "ARM64"
        DistributionArchitecture.X64 -> "x64"
    }

private fun DistributionFormat.displayName(translation: Translation): String =
    when (this) {
        DistributionFormat.BROWSER_EXTENSION_ZIP -> translation.text("settingsUi.unpackedExtensionZIP")
        DistributionFormat.DOCKER_COMPOSE_ZIP -> translation.text("settingsUi.dockerComposeZIP")
        DistributionFormat.DMG -> "DMG"
        DistributionFormat.PORTABLE_ZIP -> translation.text("settingsUi.portableZIP")
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
        SettingsGroup(title = translation.text("settingsUi.webTools")) {
            InfoSettingItem(
                label = translation.text("settingsUi.aiCatalogIsLoading"),
                message = translation.text("settingsUi.webToolSettingsWillBecomeAvailableAfterThe"),
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

    SettingsGroup(title = translation.text("settingsUi.webTools")) {
        if (openAiConnections.isEmpty()) {
            InfoSettingItem(
                label = translation.text("settingsUi.openaiHostedWebSearch"),
                message = translation.text("settingsUi.createAnOpenAIAPIOrOpenAISubscriptionConnection"),
            )
        } else {
            openAiConnections.forEach { connection ->
                SwitchSettingItem(
                    label = translation.text("settingsUi.hostedSearch", "connection" to connection.displayName),
                    description = when (connection) {
                        is AiConnection.OpenAiApi ->
                            translation.text("settingsUi.useOpenAIWebSearchThroughThisAPIConnection")
                        is AiConnection.OpenAiSubscription ->
                            translation.text("settingsUi.useOpenAIWebSearchThroughThisSubscriptionConnection")
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
                label = translation.text("settingsUi.claudeCodeWebTools"),
                message = translation.text("settingsUi.createAndEnableAClaudeCodeConnectionAnd"),
            )
        } else {
            DropdownSettingItem<AiModelConfiguration.Id?>(
                label = translation.text("settingsUi.claudeCodeWebModel"),
                description = translation.text("settingsUi.oneCentralModelConfigurationUsedByTheNative"),
                value = selectedClaudeModel,
                options = listOf(null) + claudeModels.map(AiModelConfiguration::id),
                optionLabel = { id ->
                    id?.let(claudeModelById::get)?.let { model ->
                        "${model.displayName} (${model.providerModelId})"
                    } ?: translation.text("settingsUi.notConfigured")
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
                label = translation.text("settingsUi.enableClaudeCodeWebSearch"),
                description = translation.text("settingsUi.exposeClaudeCodeWebSearchOnlyOnWorkers"),
                value = draft.claudeCode.searchEnabled,
                enabled = canEnableClaudeTools,
                onValueChange = { enabled ->
                    draft = draft.copy(
                        claudeCode = draft.claudeCode.copy(searchEnabled = enabled),
                    )
                },
            )
            SwitchSettingItem(
                label = translation.text("settingsUi.enableClaudeCodeWebFetch"),
                description = translation.text("settingsUi.exposeClaudeCodeWebFetchOnlyOnWorkers"),
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
                    Text(translation.text("settingsUi.saveWebTools"))
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
    val translation = LocalTranslation.current
    if (state == null) return
    val removing = pendingMutation is AiCatalogSecretMutation.Remove
    Text(
        text = when {
            removing -> translation.text("settingsUi.theConfiguredAPIKeyWillBeRemoved")
            state.source == AiCatalogSecretState.Source.INLINE ->
                translation.text("settingsUi.anAPIKeyIsStoredOnTheServer")
            else -> translation.text("settingsUi.environmentVariable", "name" to state.environmentVariableName)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (removing) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    TextButton(onClick = if (removing) onKeep else onRemove) {
        Text(if (removing) translation.text("settingsUi.keepConfiguredAPIKey") else translation.text("settingsUi.removeConfiguredAPIKey"))
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
private fun KeyboardShortcutSettingsGroup(
    settings: Settings,
    globalHotkeyController: GlobalHotkeyController,
    onSettingsChange: (Settings) -> Unit,
) {
    val translation = LocalTranslation.current
    val shortcuts = settings.desktopInputSettings.keyboardShortcuts.normalized()
    val validationIssues = remember(shortcuts) { KeyboardShortcutValidator.validate(shortcuts) }
    val globalState by globalHotkeyController.state.collectAsState()

    Box(modifier = Modifier.testTag(UiTestTag.KeyboardShortcuts.value)) {
        SettingsGroup(title = translation.text("settingsUi.keyboardShortcuts")) {
            Text(
                text = translation.text("settingsUi.focusedShortcutsWorkOnlyInsideGromozekaGlobalShortcuts"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!globalState.available || globalState.message != null) {
                Text(
                    text = globalState.message?.resolve(translation) ?: translation.text("settingsUi.globalShortcutsAreUnavailable"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = translation.text("settingsUi.globalBackend", "backend" to globalState.implementationType),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            shortcuts.bindings.forEach { binding ->
                KeyboardShortcutBindingEditor(
                    binding = binding,
                    validationIssues = validationIssues.filter { it.action == binding.action },
                    runtimeError = globalState.bindingErrors[binding.action],
                    onChange = { updated ->
                        onSettingsChange(settings.withKeyboardShortcut(updated))
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickTextActionSettingsGroup(
    settings: Settings,
    agents: List<AgentDefinition>,
    onSettingsChange: (Settings) -> Unit,
) {
    val translation = LocalTranslation.current
    SettingsGroup(title = translation.text("settingsUi.quickTextActions")) {
        Text(
            text = translation.text("settingsUi.theseAgentAndPromptSettingsApplyToDesktop"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        settings.userProfile.quickTextActions.forEach { action ->
            key(action.id.value) {
                val selectedAgentId = action.agentId
                val agentOptions = buildList<AgentDefinition.Id?> {
                    add(null)
                    addAll(agents.map { it.id })
                    if (selectedAgentId != null && selectedAgentId !in this) add(selectedAgentId)
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column {
                            Text(action.displayTitle(translation), fontWeight = FontWeight.Medium)
                            Text(
                                text = action.displayDescription(translation),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        DropdownSettingItem(
                            label = translation.text("settingsUi.agent"),
                            description = translation.text("settingsUi.usesTheAgentModelRuntimeOverridesPromptsAnd"),
                            value = selectedAgentId,
                            options = agentOptions,
                            optionLabel = { agentId ->
                                when (agentId) {
                                    null -> translation.text("settingsUi.quickTextRuntimeNoAgent")
                                    else -> agents.firstOrNull { it.id == agentId }?.name
                                        ?: translation.text("settingsUi.missingAgent", "id" to agentId.value)
                                }
                            },
                            onValueChange = { agentId ->
                                onSettingsChange(settings.withQuickTextAction(action.copy(agentId = agentId)))
                            },
                        )

                        MultilineTextFieldSettingItem(
                            label = translation.text("settingsUi.prompt"),
                            description = translation.text("settingsUi.transformationInstructionAppendedAfterTheSelectedAgentPrompts"),
                            value = action.prompt,
                            onValueChange = { prompt ->
                                if (prompt.isNotBlank()) {
                                    onSettingsChange(settings.withQuickTextAction(action.copy(prompt = prompt)))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardShortcutBindingEditor(
    binding: KeyboardShortcutBinding,
    validationIssues: List<com.gromozeka.domain.model.KeyboardShortcutValidationIssue>,
    runtimeError: LocalizedText?,
    onChange: (KeyboardShortcutBinding) -> Unit,
) {
    val translation = LocalTranslation.current
    var recording by remember(binding.action) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(recording) {
        if (recording) focusRequester.requestFocus()
    }

    Surface(
        modifier = Modifier.testTag(UiTestTag.KeyboardShortcutBinding(binding.action.name).value),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(binding.action.displayName(translation), fontWeight = FontWeight.Medium)
                    Text(
                        binding.action.description(translation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = binding.enabled, onCheckedChange = { onChange(binding.copy(enabled = it)) })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DropdownSettingItem(
                    label = translation.text("settingsUi.scope"),
                    description = "",
                    value = binding.scope,
                    options = KeyboardShortcutScope.entries.filter { it in binding.action.supportedScopes },
                    optionLabel = { it.displayName(translation) },
                    onValueChange = { onChange(binding.copy(scope = it)) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { recording = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(UiTestTag.KeyboardShortcutCapture(binding.action.name).value)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (!recording) return@onPreviewKeyEvent false
                            val key = event.toKeyboardShortcutKey()
                            if (event.type == KeyEventType.KeyDown && key != null) {
                                onChange(
                                    binding.copy(
                                        key = key,
                                        modifiers = event.keyboardShortcutModifiers(),
                                    )
                                )
                                recording = false
                            }
                            true
                        },
                ) {
                    Text(if (recording) translation.text("settingsUi.pressKeys") else binding.displayLabel())
                }
            }

            if (recording) {
                TextButton(onClick = { recording = false }) {
                    Text(translation.text("settingsUi.cancelRecording"))
                }
            }

            if (
                binding.enabled &&
                binding.action == KeyboardShortcutAction.PUSH_TO_TALK &&
                binding.scope == KeyboardShortcutScope.GLOBAL
            ) {
                SwitchSettingItem(
                    label = translation.text("settingsUi.swallowKey"),
                    description = translation.text("settingsUi.whenEnabledTheForegroundApplicationDoesNotReceive"),
                    value = binding.consumeEvent,
                    onValueChange = { onChange(binding.copy(consumeEvent = it)) },
                )
            }

            validationIssues.forEach { issue ->
                Text(
                    text = issue.localizedText().resolve(translation),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (issue.severity) {
                        KeyboardShortcutValidationSeverity.ERROR -> MaterialTheme.colorScheme.error
                        KeyboardShortcutValidationSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            runtimeError?.let { message ->
                Text(
                    text = message.resolve(translation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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

private fun Settings.withQuickTextAction(action: QuickTextAction): Settings =
    updateUserProfile {
        copy(
            quickTextActions = quickTextActions.map { current ->
                if (current.id == action.id) action else current
            },
        )
    }

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

private fun Settings.withKeyboardShortcut(binding: KeyboardShortcutBinding): Settings =
    updateDesktopInputSettings {
        copy(
            keyboardShortcuts = keyboardShortcuts.normalized().copy(
                bindings = keyboardShortcuts.normalized().bindings.map { current ->
                    if (current.action == binding.action) binding else current
                }
            )
        )
    }

private fun KeyboardShortcutBinding.displayLabel(): String =
    (modifiers.sortedBy(KeyboardShortcutModifier::ordinal).map(KeyboardShortcutModifier::displayName) +
        key.displayName()).joinToString(" + ")

private fun KeyboardShortcutModifier.displayName(): String = when (this) {
    KeyboardShortcutModifier.CONTROL -> "Ctrl"
    KeyboardShortcutModifier.ALT -> "Alt"
    KeyboardShortcutModifier.SHIFT -> "Shift"
    KeyboardShortcutModifier.META -> "Meta"
}

private fun KeyboardShortcutKey.displayName(): String = when (this) {
    in KeyboardShortcutKey.A..KeyboardShortcutKey.Z,
    in KeyboardShortcutKey.F1..KeyboardShortcutKey.F24 -> name
    in KeyboardShortcutKey.DIGIT_0..KeyboardShortcutKey.DIGIT_9 -> name.removePrefix("DIGIT_")
    KeyboardShortcutKey.ESCAPE -> "Esc"
    KeyboardShortcutKey.SPACE -> "␣"
    KeyboardShortcutKey.ENTER -> "↵"
    KeyboardShortcutKey.TAB -> "⇥"
    KeyboardShortcutKey.BACKSPACE -> "⌫"
    KeyboardShortcutKey.DELETE -> "⌦"
    KeyboardShortcutKey.ARROW_UP -> "↑"
    KeyboardShortcutKey.ARROW_DOWN -> "↓"
    KeyboardShortcutKey.ARROW_LEFT -> "←"
    KeyboardShortcutKey.ARROW_RIGHT -> "→"
    KeyboardShortcutKey.HOME -> "↖"
    KeyboardShortcutKey.END -> "↘"
    KeyboardShortcutKey.PAGE_UP -> "⇞"
    KeyboardShortcutKey.PAGE_DOWN -> "⇟"
    else -> error("Unknown keyboard key $this")
}

private fun WorkerCatalogEntry.Status.displayName(translation: Translation): String = translation.text(when (this) {
    WorkerCatalogEntry.Status.ONLINE -> "settingsUi.workerOnline"
    WorkerCatalogEntry.Status.OFFLINE -> "settingsUi.workerOffline"
})

private fun UserProfile.SpeechSettings.SpeechToText.LocalWhisper.LiveStreaming.Profile.displayName(
    translation: Translation,
): String = translation.text(when (this) {
    UserProfile.SpeechSettings.SpeechToText.LocalWhisper.LiveStreaming.Profile.LOW_LATENCY -> "settingsUi.whisperProfileLowLatency"
    UserProfile.SpeechSettings.SpeechToText.LocalWhisper.LiveStreaming.Profile.BALANCED -> "settingsUi.whisperProfileBalanced"
    UserProfile.SpeechSettings.SpeechToText.LocalWhisper.LiveStreaming.Profile.SLOW_CPU -> "settingsUi.whisperProfileSlowCpu"
})

private fun KeyboardShortcutScope.displayName(translation: Translation): String = localizedText().resolve(translation)

private fun QuickTextAction.displayTitle(translation: Translation): String {
    val default = QuickTextAction.defaults().firstOrNull { it.id == id }
    if (title != default?.title) return title
    return when (id) {
        QuickTextAction.FIX_TEXT_ID -> translation.text("quickText.fix")
        QuickTextAction.TRANSLATE_INTERFACE_LANGUAGE_ID -> translation.text("quickText.translate")
        else -> title
    }
}

private fun QuickTextAction.displayDescription(translation: Translation): String {
    val default = QuickTextAction.defaults().firstOrNull { it.id == id }
    if (description != default?.description) return description
    return when (id) {
        QuickTextAction.FIX_TEXT_ID -> translation.text("quickText.fixDescription")
        QuickTextAction.TRANSLATE_INTERFACE_LANGUAGE_ID -> translation.text("quickText.translateDescription")
        else -> description
    }
}

private fun KeyboardShortcutAction.displayName(translation: Translation): String = localizedText().resolve(translation)

private fun KeyboardShortcutAction.description(translation: Translation): String = when (this) {
    KeyboardShortcutAction.PUSH_TO_TALK -> translation.text("settingsUi.holdToRecordReleaseToTranscribeAndSend")
    KeyboardShortcutAction.TOGGLE_LIVE_VOICE -> translation.text("settingsUi.startsOrStopsContinuousVoiceInput")
    KeyboardShortcutAction.FIX_CLIPBOARD_TEXT -> translation.text("settingsUi.fixesTheTextCurrentlyStoredInTheClipboard")
    KeyboardShortcutAction.TRANSLATE_CLIPBOARD_TEXT -> translation.text("settingsUi.translatesTheTextCurrentlyStoredInTheClipboard")
    KeyboardShortcutAction.EDIT_LAST_USER_MESSAGE -> translation.text("settingsUi.withAnEmptyComposerOpensTheLatestEditable")
    KeyboardShortcutAction.NEW_CONVERSATION -> translation.text("settingsUi.createsAConversationInTheCurrentProject")
}

private fun Settings.updateDesktopSettings(
    update: UserDeviceSettings.Desktop.() -> UserDeviceSettings.Desktop,
): Settings =
    updateDeviceSettings {
        when (this) {
            is UserDeviceSettings.Desktop -> update()
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
private fun MultilineTextFieldSettingItem(
    label: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 10,
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
    optionLabel: (String) -> String = { it },
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
                            text = { Text(optionLabel(option)) },
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
