package com.gromozeka.presentation.ui

import com.gromozeka.presentation.ui.session.sidePanelResizeHandleWidth

import com.gromozeka.presentation.ui.session.SidePanelResizeHandle

import com.gromozeka.presentation.ui.session.ConversationSidePanelResizeState

import androidx.compose.foundation.layout.offset

import com.gromozeka.presentation.ui.session.SidePanelToggle

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gromozeka.client.RemoteConnectionState
import com.gromozeka.device.telemetry.DeviceLocationResult
import com.gromozeka.device.telemetry.DeviceLocationSnapshot
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.User
import com.gromozeka.presentation.AppComponents
import com.gromozeka.presentation.services.UiFeedbackEvent
import com.gromozeka.presentation.services.UnifiedGestureDetector
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.agents.AgentConstructorScreen
import com.gromozeka.presentation.ui.notifications.UserNotificationsDialog
import com.gromozeka.presentation.ui.notifications.UserNotificationsState
import com.gromozeka.presentation.ui.session.ConversationParticipantsPanel
import com.gromozeka.presentation.ui.session.ConversationPinsPanel
import com.gromozeka.presentation.ui.session.ConversationPinsPanelState
import com.gromozeka.presentation.ui.session.ConversationRuntimePanel
import com.gromozeka.presentation.ui.session.ConversationSidePanel
import com.gromozeka.presentation.ui.session.ConversationSidePanelState
import com.gromozeka.presentation.ui.session.ConversationSidePanelTab
import com.gromozeka.presentation.ui.session.ConversationSidePanelTab.Section as SidebarSection
import com.gromozeka.presentation.ui.session.ConversationThreadPanel
import com.gromozeka.presentation.ui.session.ConversationVisualPanel
import com.gromozeka.presentation.ui.session.RuntimeAgentTabSelection
import com.gromozeka.presentation.ui.session.RuntimeInspectionRequest
import com.gromozeka.presentation.ui.session.RuntimeInspectionSection
import com.gromozeka.presentation.ui.session.RuntimePanelView
import com.gromozeka.presentation.ui.session.SessionScreen
import com.gromozeka.presentation.ui.session.SlotInventoryPanel
import com.gromozeka.presentation.ui.session.SlotInventoryState
import com.gromozeka.presentation.ui.session.VisualContent
import com.gromozeka.presentation.ui.session.VisualPanelState
import com.gromozeka.presentation.ui.session.rememberConversationActivityPresentation
import com.gromozeka.presentation.ui.viewmodel.TabViewModel
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun GromozekaApp(
    appComponents: AppComponents,
    skipLoadingScreen: Boolean = false,
    forceCompactLayout: Boolean = false,
    clientPlatform: ClientPlatform = ClientPlatform.DESKTOP,
) {
    val currentTheme by appComponents.themeService.currentTheme.collectAsState()
    GromozekaTheme(
        currentTheme = currentTheme,
        density = if (clientPlatform.showSoftwareKeyboardControls) UiDensity.TOUCH else UiDensity.COMPACT,
    ) {
        val currentTranslation by appComponents.translationService.currentTranslation.collectAsState()
        TranslationProvider(currentTranslation) {
            GromozekaAppContent(
                appComponents = appComponents,
                skipLoadingScreen = skipLoadingScreen,
                forceCompactLayout = forceCompactLayout,
                clientPlatform = clientPlatform,
            )
        }
    }
}

@Composable
fun GromozekaAppContent(
    appComponents: AppComponents,
    skipLoadingScreen: Boolean = false,
    forceCompactLayout: Boolean = false,
    clientPlatform: ClientPlatform = ClientPlatform.DESKTOP,
) {
    val localization = LocalTranslation.current
    val log = KLoggers.logger("ChatWindow")
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    var recordingShortcut by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var isLoadingComplete by remember(skipLoadingScreen) { mutableStateOf(skipLoadingScreen) }
    val sidePanels = remember(appComponents) { ConversationSidePanelState() }
    val sidePanelResize = remember { ConversationSidePanelResizeState() }
    val notificationState = remember(appComponents) { UserNotificationsState(appComponents.authenticatedUser.id) }
    LaunchedEffect(appComponents, notificationState.observationRetry) {
        notificationState.observe(appComponents.userNotificationService, appComponents.userNotificationSink)
    }
    UserNotificationsDialog(notificationState, appComponents.userNotificationService) { id ->
        val conversation = appComponents.conversationService.findById(id)
            ?: error("Notification conversation is not available")
        appComponents.appViewModel.createTab(projectId = conversation.projectId, conversationId = id, setAsCurrent = true)
    }
    val pinsPanelState = remember(appComponents) { ConversationPinsPanelState() }
    val slotInventory = remember(appComponents.slotService) { SlotInventoryState() }
    LaunchedEffect(appComponents.slotService, slotInventory.observationAttempt) {
        slotInventory.observe(appComponents.slotService)
    }
    var pinSourceLoads by remember(appComponents) { mutableStateOf(emptySet<Conversation.Id>()) }
    val runtimeAgentTabSelection = remember(appComponents) { RuntimeAgentTabSelection() }
    val visualPanelState = remember(appComponents) { VisualPanelState() }
    LaunchedEffect(appComponents.clientPresentationService) {
        appComponents.clientPresentationService.directives.collect { directive ->
            if (directive is com.gromozeka.remote.protocol.HighlightVisualDirective) {
                visualPanelState.applyHighlight(directive.command)
            }
        }
    }
    var runtimeInspectionRequest by remember { mutableStateOf<RuntimeInspectionRequest?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var hoveredTabIndex by remember { mutableStateOf(-1) }
    var projectArea by remember { mutableStateOf(ProjectArea.CONVERSATIONS) }
    var workspaceManagerProjectId by remember { mutableStateOf<Project.Id?>(null) }

    val currentSettings by appComponents.settingsService.settingsFlow.collectAsState()
    val remoteClientSettings by appComponents.remoteClientSettingsService.settingsFlow.collectAsState()
    val remoteConnectionState by appComponents.remoteConnectionState.collectAsState()
    val tabs by appComponents.appViewModel.tabs.collectAsState()
    val conversations by appComponents.appViewModel.conversations.collectAsState()
    val unreadConversationIds by appComponents.appViewModel.unreadConversationIds.collectAsState()
    val currentTabIndex by appComponents.appViewModel.currentTabIndex.collectAsState()
    val currentTab by appComponents.appViewModel.currentTab.collectAsState()
    LaunchedEffect(currentTab?.conversationId, appComponents.visualService) {
        val conversationId = currentTab?.conversationId ?: return@LaunchedEffect
        try {
            appComponents.visualService.observe(conversationId).collect { snapshots ->
                visualPanelState.accept(conversationId, snapshots)
                sidePanels.acceptVisuals(conversationId, snapshots.mapTo(mutableSetOf()) { it.id })
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { visualPanelState.loadError = error.message ?: localization.text("visuals.loadFailed") }
    }
    val pttState by appComponents.pttService.state.collectAsState()
    val pttTarget by appComponents.pttService.target.collectAsState()
    val liveVoiceTarget by appComponents.liveVoiceInputService.target.collectAsState()
    val voiceTranscriptions by appComponents.voiceInputDelivery.transcriptions.collectAsState()
    // The selected-tab flow may lag a tab-list update; keep the actually displayed tab observed until it switches.
    val conversationActivities = (tabs + listOfNotNull(currentTab)).distinctBy { it.conversationId }.associate { tab ->
        tab.conversationId to key(tab.conversationId) {
            rememberConversationActivityPresentation(tab, tab.conversationId in unreadConversationIds,
                remoteConnectionState.status == RemoteConnectionState.Status.CONNECTED,
                appComponents.aiRequestActivityService, voiceTranscriptions)
        }
    }
    val pttStatusMessage by appComponents.pttService.statusMessage.collectAsState()
    val pttUnavailableReason by appComponents.pttService.unavailableReason.collectAsState()
    val liveVoiceInputState by appComponents.liveVoiceInputService.state.collectAsState()
    val liveVoiceInputStatusMessage by appComponents.liveVoiceInputService.statusMessage.collectAsState()
    val liveVoiceInputUnavailableReason by appComponents.liveVoiceInputService.unavailableReason.collectAsState()
    val keyboardPttController = remember {
        UnifiedGestureDetector(appComponents.pttEventRouter, coroutineScope)
    }
    val isWindowFocused = LocalWindowInfo.current.isWindowFocused
    val reportsComposeWindowFocus =
        clientPlatform != ClientPlatform.WEB_DESKTOP &&
            clientPlatform != ClientPlatform.WEB_TOUCH

    LaunchedEffect(Unit) {
        initialized = true
    }

    LaunchedEffect(appComponents.uiFeedbackController) {
        appComponents.uiFeedbackController.events.collect { event ->
            when (event) {
                UiFeedbackEvent.ERROR -> {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Reject)
                    delay(ERROR_HAPTIC_GAP_MILLIS)
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Reject)
                }
            }
        }
    }

    LaunchedEffect(isWindowFocused, reportsComposeWindowFocus) {
        appComponents.appViewModel.reportWindowFocus(isWindowFocused)
        appComponents.turnCompletionNotificationService.reportWindowFocus(isWindowFocused)
        if (reportsComposeWindowFocus && isWindowFocused) {
            appComponents.clientPresentationService.reportWindowFocused()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            coroutineScope.launch {
                appComponents.appViewModel.cleanup()
            }
        }
    }

    val createSession: (Project.Id, AgentDefinition?, String?) -> Unit = { projectId, agent, initialText ->
        coroutineScope.launch {
            runCatching {
                val initialMessage = initialText?.let { text ->
                    Conversation.Message(
                        id = Conversation.Message.Id(uuid7()),
                        conversationId = Conversation.Id(""),
                        role = Conversation.Message.Role.USER,
                        content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
                        createdAt = Clock.System.now(),
                        instructions = listOf(Conversation.Message.Instruction.Source.User),
                    )
                }
                val tabIndex = appComponents.appViewModel.createTab(
                    projectId = projectId,
                    agent = agent,
                    initialMessage = initialMessage,
                    initiator = ConversationInitiator.User,
                )
                appComponents.appViewModel.selectTab(tabIndex)
            }.onFailure { it.printStackTrace() }
        }
    }

    val createNewSessionForProject: (Project) -> Unit = { project ->
        createSession(project.id, null, null)
    }

    val createNewSessionInCurrentProject: () -> Unit = {
        val tab = currentTab
        if (tab == null) {
            log.warn { "Cannot create a session without an explicitly selected project" }
        } else {
            createSession(tab.projectId, null, null)
        }
    }

    val createNewSessionWithMessage: (String) -> Unit = { initialMessage ->
        val tab = currentTab
        if (tab == null) {
            log.warn { "Cannot create a session without an explicitly selected project" }
        } else {
            createSession(tab.projectId, null, initialMessage)
        }
    }

    val onSettingsChange: (Settings) -> Unit = { newSettings ->
        appComponents.settingsService.saveSettings(newSettings)
    }
    val onRemoteClientSettingsChange: (com.gromozeka.client.RemoteClientSettings) -> Unit = { settings ->
        coroutineScope.launch { appComponents.remoteClientSettingsService.saveSettings(settings) }
    }
    val currentUiSettings = currentSettings.userDeviceSettings.uiSettings
    val platformDensity = LocalDensity.current
    val effectiveUiScale = currentUiSettings.uiScale.coerceIn(0.5f, 3.0f)

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = platformDensity.density * effectiveUiScale,
            fontScale = platformDensity.fontScale * currentUiSettings.fontScale,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusTarget()
                .pointerInput(appComponents.clientPresentationService) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        appComponents.clientPresentationService.reportUserInteraction()
                    }
                }
                .focusedKeyboardShortcuts(
                    settings = currentSettings.userProfile.keyboardShortcuts,
                    globalShortcutsAreLocal = clientPlatform.isBrowser,
                    enabled = !recordingShortcut,
                    pttGestureDetector = keyboardPttController,
                    onActivate = { action ->
                        when (action) {
                            KeyboardShortcutAction.TOGGLE_LIVE_VOICE -> coroutineScope.launch {
                                appComponents.liveVoiceInputService.toggle()
                            }
                            KeyboardShortcutAction.FIX_CLIPBOARD_TEXT -> coroutineScope.launch {
                                appComponents.quickTextActionRunner.run(QuickTextAction.FIX_TEXT_ID)
                            }
                            KeyboardShortcutAction.TRANSLATE_CLIPBOARD_TEXT -> coroutineScope.launch {
                                appComponents.quickTextActionRunner.run(QuickTextAction.TRANSLATE_INTERFACE_LANGUAGE_ID)
                            }
                            KeyboardShortcutAction.NEW_CONVERSATION -> createNewSessionInCurrentProject()
                            else -> Unit
                        }
                    },
                )
                .testTag(UiTestTag.AppRoot.value)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        appComponents.clientPresentationService.reportUserInteraction()
                    }
                    false
                }
        ) {
            when {
                !initialized -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        GromozekaLoadingIndicator(Modifier.size(80.dp))
                    }
                }

                !isLoadingComplete -> {
                    LoadingScreen(
                        loadingViewModel = appComponents.loadingViewModel,
                        onComplete = { isLoadingComplete = true }
                    )
                }

                else -> {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val scaledDensity = LocalDensity.current
                        val unscaledMaxWidth = with(platformDensity) {
                            with(scaledDensity) { maxWidth.toPx() }.toDp()
                        }
                        val isCompactLayout = forceCompactLayout || unscaledMaxWidth < 700.dp
                        val conversationId = currentTab?.conversationId
                        val sidePanel = conversationId?.let { sidePanels.panel(it, isCompactLayout) }
                            ?: sidePanels.settings(isCompactLayout)
                        val sidePanelVisible = sidePanel.visible
                        val sidePanelTab = sidePanel.selected
                        val hideSidePanel: () -> Unit = sidePanel::hide
                        val openSidePanel: (ConversationSidePanelTab) -> Unit = { tab ->
                            check(conversationId != null || tab == SidebarSection.SETTINGS) {
                                "Only settings can be opened without a conversation"
                            }
                            sidePanel.open(tab)
                        }
                        val availableWidth = maxWidth
                        val resizable = !isCompactLayout && (clientPlatform == ClientPlatform.DESKTOP || clientPlatform == ClientPlatform.WEB_DESKTOP)
                        val contentPadding = if (isCompactLayout) GromozekaTheme.spacing.panelPadding else GromozekaTheme.spacing.contentPadding
                        val leaveDisconnectedConversation: () -> Unit = {
                            hideSidePanel()
                            coroutineScope.launch { appComponents.appViewModel.selectTab(null) }
                        }
                        val settingsContent: @Composable (SettingsPanelContentMode) -> Unit = { mode ->
                            SettingsPanel(
                                clientPlatform = clientPlatform,
                                onRecordingShortcutChange = { recordingShortcut = it },
                                isVisible = true,
                                settings = currentSettings,
                                onSettingsChange = onSettingsChange,
                                remoteClientSettings = remoteClientSettings,
                                onRemoteClientSettingsChange = onRemoteClientSettingsChange,
                                translationService = appComponents.translationService,
                                themeService = appComponents.themeService,
                                aiThemeGenerator = appComponents.aiThemeGenerator,
                                settingsService = appComponents.settingsService,
                                messageDeliveryPreferenceService = appComponents.messageDeliveryPreferenceService,
                                globalHotkeyController = appComponents.globalHotkeyController,
                                agentService = appComponents.agentService,
                                aiConfigurationService = appComponents.aiConfigurationService,
                                aiUsageReportService = appComponents.aiUsageReportService,
                                runtimeCatalogTemplateService = appComponents.runtimeCatalogTemplateService,
                                workerCatalogService = appComponents.workerCatalogService,
                                mcpServerService = appComponents.mcpServerService,
                                distributionService = appComponents.distributionService,
                                deviceConnectionService = appComponents.deviceConnectionService,
                                personalAccessTokenService = appComponents.personalAccessTokenService,
                                aiUserCredentialService = appComponents.aiUserCredentialService,
                                namedSecretService = appComponents.namedSecretService,
                                userAdministrationService = appComponents.userAdministrationService,
                                telegramService = appComponents.telegramService,
                                securityAuditService = appComponents.securityAuditService,
                                userDirectoryService = appComponents.userDirectoryService,
                                canAdministerUsers =
                                    appComponents.authenticatedUser.role == User.Role.OWNER,
                                ollamaModelService = appComponents.ollamaModelService,
                                coroutineScope = coroutineScope,
                                onOpenTab = createNewSessionInCurrentProject,
                                onOpenTabWithMessage = createNewSessionWithMessage,

                                contentMode = mode,
                            )
                        }
                        val sidePanelContent: @Composable () -> Unit = {
                            val tabViewModel = currentTab
                            val conversationId = tabViewModel?.conversationId
                            val visuals = conversationId?.let(visualPanelState::visuals).orEmpty()
                            val selected = sidePanelTab
                            val closeConversation: (() -> Unit)? = conversationId?.let { id ->
                                { coroutineScope.launch { appComponents.appViewModel.closeConversationTab(id) }; Unit }
                            }
                            ConversationSidePanel(
                                selected = selected,
                                onSelect = { runtimeInspectionRequest = null; openSidePanel(it) },
                                conversationAvailable = tabViewModel != null,
                                visuals = visuals,
                                dirtyVisualIds = conversationId?.let(visualPanelState::dirtyVisualIds).orEmpty(),
                                highlightedVisualIds = conversationId?.let(visualPanelState::highlightedVisualIds).orEmpty(),
                                onCloseVisual = { visualPanelState.close(it, appComponents.visualService, coroutineScope, localization.text("visuals.closeFailed")) },
                                onClearVisualHighlights = visualPanelState::clearHighlights,
                                onCloseConversation = closeConversation,
                            ) {
                                when (selected) {
                                    SidebarSection.SETTINGS -> settingsContent(SettingsPanelContentMode.Quick)
                                    SidebarSection.PINS -> {
                                        checkNotNull(tabViewModel)
                                        ConversationPinsPanel(
                                            isVisible = true,
                                            conversationId = tabViewModel.conversationId,
                                            service = appComponents.conversationPinService,
                                            state = pinsPanelState,
                                            modifier = Modifier.testTag("conversation-pins-panel"),

                                        )
                                    }
                                    SidebarSection.PARTICIPANTS -> {
                                        checkNotNull(tabViewModel)
                                        val conversation = conversations[tabViewModel.conversationId]
                                        if (conversation == null) Text(localization.text("chat.activity.stateLoading"))
                                        else Box(Modifier.testTag(UiTestTag.ParticipantsPanel.value)) {
                                            ConversationParticipantsPanel(
                                                isVisible = true,
                                                initialConversation = conversation,
                                                currentUserId = appComponents.authenticatedUser.id,
                                                conversationService = appComponents.conversationService,
                                                agentService = appComponents.agentService,
                                                projectMembershipService = appComponents.projectMembershipService,
                                                userDirectoryService = appComponents.userDirectoryService,
                                                onConversationUpdated = {
                                                    appComponents.appViewModel.mergeConversationSnapshots(listOf(it))
                                                },
                                                onCurrentUserDisconnected = leaveDisconnectedConversation,

                                            )
                                        }
                                    }
                                    SidebarSection.THREAD -> {
                                        checkNotNull(tabViewModel)
                                        ConversationThreadPanel(
                                            viewModel = tabViewModel,
                                            coroutineScope = coroutineScope,
                                            onNewSession = { createSession(tabViewModel.projectId, null, null) },
                                            onForkSession = {
                                                coroutineScope.launch {
                                                    runCatching {
                                                        val forked = appComponents.conversationService.fork(tabViewModel.conversationId)
                                                        val index = appComponents.appViewModel.createTab(projectId = forked.projectId,
                                                            conversationId = forked.id, initiator = ConversationInitiator.User)
                                                        appComponents.appViewModel.selectTab(index)
                                                    }.onFailure { error -> log.warn(error) { "Failed to fork conversation: ${error.message}" } }
                                                }
                                            },
                                            pinMessageEnabled = !pinsPanelState.hasDraft(tabViewModel.conversationId) && tabViewModel.conversationId !in pinSourceLoads,
                                            onPinMessage = { messageId ->
                                                val owner = tabViewModel.conversationId
                                                openSidePanel(SidebarSection.PINS)
                                                if (!pinsPanelState.hasDraft(owner) && owner !in pinSourceLoads) {
                                                    pinSourceLoads = pinSourceLoads + owner
                                                    coroutineScope.launch {
                                                        try {
                                                            val text = tabViewModel.loadPinMessageText(messageId)
                                                            if (text == null) {
                                                                if (!pinsPanelState.hasDraft(owner)) pinsPanelState.sourceLoadFailed(owner)
                                                            } else {
                                                                pinsPanelState.beginCreate(owner, text,
                                                                    com.gromozeka.domain.model.ConversationPinSource(owner, messageId))
                                                            }
                                                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                            throw cancelled
                                                        } catch (_: Exception) {
                                                            if (!pinsPanelState.hasDraft(owner)) pinsPanelState.sourceLoadFailed(owner)
                                                        } finally {
                                                            pinSourceLoads = pinSourceLoads - owner
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    SidebarSection.SLOTS -> SlotInventoryPanel(slotInventory, appComponents.slotService) { id ->
                                        hideSidePanel()
                                        coroutineScope.launch {
                                            val target = appComponents.conversationService.findById(id) ?: return@launch
                                            val index = appComponents.appViewModel.createTab(projectId = target.projectId,
                                                conversationId = target.id, initiator = ConversationInitiator.User)
                                            appComponents.appViewModel.selectTab(index)
                                        }
                                    }
                                    SidebarSection.MEMORY -> {
                                        checkNotNull(tabViewModel)
                                        val refreshKey by tabViewModel.memoryActionItemsRefreshKey.collectAsState()
                                        MemoryActionItemsPanel(true, tabViewModel.conversationId, refreshKey,
                                            appComponents.memoryActionItemService, coroutineScope,
                                            Modifier.testTag(UiTestTag.MemoryActionItemsPanel.value), )
                                    }
                                    SidebarSection.RUNTIME, SidebarSection.TASKS -> {
                                        checkNotNull(tabViewModel)
                                        key(tabViewModel.conversationId) {
                                            val tokenStats by tabViewModel.tokenStats.collectAsState()
                                            val waiting by tabViewModel.isWaitingForResponse.collectAsState()
                                            val pending by tabViewModel.pendingMessages.collectAsState()
                                            val runtime by tabViewModel.runtimeSnapshot.collectAsState()
                                            ConversationRuntimePanel(
                                                view = if (selected == SidebarSection.TASKS) RuntimePanelView.TASKS else RuntimePanelView.RUNTIME,
                                                conversationId = tabViewModel.conversationId,
                                                participants = conversations[tabViewModel.conversationId]?.participants,
                                                tabSelection = runtimeAgentTabSelection,
                                                replyRoutingContent = { RuntimeReplyRouting(tabViewModel) },
                                                agentService = appComponents.agentService,
                                                aiConfigurationProvider = appComponents.aiConfigurationService,
                                                aiSubscriptionQuotaService = appComponents.aiSubscriptionQuotaService,
                                                tokenStats = tokenStats, isWaitingForResponse = waiting, pendingMessages = pending, runtimeSnapshot = runtime,
                                                inspectionRequest = runtimeInspectionRequest,
                                                onCancelCommandTask = tabViewModel::cancelCommandTask,
                                                onCancelCommandMonitor = tabViewModel::cancelCommandMonitor,
                                                onSendInCurrentTurn = tabViewModel::sendPendingMessageInCurrentTurn,
                                                onEditPendingMessage = tabViewModel::editPendingMessage,
                                                onCancelPendingMessage = tabViewModel::cancelPendingMessage,
                                                modifier = Modifier.testTag(UiTestTag.RuntimePanel.value),
                                            )
                                        }
                                    }
                                    is ConversationSidePanelTab.VisualPanel -> {
                                        val visual = visuals.single { it.id == selected.id }
                                        ConversationVisualPanel(visual, visualPanelState.loadError) {
                                            VisualContent(it, visualPanelState.draft(it), appComponents.visualService,
                                                highlightedIds = visualPanelState.highlightedIds(it), onClearHighlights = { visualPanelState.clearHighlights(it.id) })
                                        }
                                    }
                                }
                            }
                        }

                        Row(modifier = Modifier.fillMaxSize()) {
                            Column(modifier = Modifier.weight(1f)) {
                                val selectedTabIndex = when (val currentIndex = currentTabIndex) {
                                    null -> 0
                                    -1 -> 1
                                    -2 -> 2
                                    -3 -> 3
                                    else -> currentIndex + 4
                                }

                                val tabRowComponent = @Composable {
                                    CustomTabRow(
                                        selectedTabIndex = selectedTabIndex,
                                        showTabsAtBottom = currentUiSettings.showTabsAtBottom,
                                        isCompactLayout = isCompactLayout,
                                        tabs = tabs,
                                        conversations = conversations,
                                        activities = conversationActivities,
                                        hoveredTabIndex = hoveredTabIndex,
                                        onTabSelect = { tabIndex ->
                                            coroutineScope.launch {
                                                appComponents.appViewModel.selectTab(tabIndex)
                                            }
                                        },
                                        onTabHover = { index -> hoveredTabIndex = index },
                                        onTabHoverExit = { hoveredTabIndex = -1 },
                                        onRenameConversation = { conversationId, newName ->
                                            appComponents.appViewModel.renameConversation(conversationId, newName)
                                        },
                                        trailingContent = {
                                            if (currentTab != null || (sidePanelVisible && currentTabIndex != -2)) {
                                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                                    SidePanelToggle(sidePanelVisible, sidePanel::toggle)
                                                }
                                            }
                                            androidx.compose.material3.BadgedBox(badge = {
                                                val unread = notificationState.summary?.unreadCount ?: 0
                                                if (notificationState.summaryFailed || unread > 0) androidx.compose.material3.Badge {
                                                    Text(if (notificationState.summaryFailed) "!" else if (unread > 99) "99+" else unread.toString())
                                                }
                                            }) {
                                                androidx.compose.material3.IconButton(
                                                    onClick = { coroutineScope.launch { notificationState.open(appComponents.userNotificationService) } },
                                                    modifier = Modifier.size(48.dp).testTag("notifications-open"),
                                                ) {
                                                    com.gromozeka.presentation.ui.icons.Icon(
                                                        com.gromozeka.presentation.ui.icons.Icons.Default.Inventory2,
                                                        localization.text("notifications.title"),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }

                                if (!currentUiSettings.showTabsAtBottom) {
                                    tabRowComponent()
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(modifier = Modifier.weight(1f)) {
                                        Column(modifier = Modifier.weight(1f).padding(if ((currentTabIndex ?: -1) < 0) contentPadding else 0.dp)) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                if (currentTabIndex == -3) {
                                                    LiveInterpreterScreen(
                                                        settings = currentSettings,
                                                        aiConfigurationProvider = appComponents.aiConfigurationService,
                                                        liveInterpreterService = appComponents.liveInterpreterService,
                                                        liveAudioStreamer = appComponents.liveAudioStreamer,
                                                        clientSideSpeechToTextService = appComponents.clientSideSpeechToTextService,
                                                        coroutineScope = coroutineScope,
                                                        isCompactLayout = isCompactLayout,
                                                    )
                                                } else if (currentTab != null) {
                                                    val tabViewModel = currentTab!!
                                                    SessionScreen(
                                                        viewModel = tabViewModel,
                                                        activityPresentation = conversationActivities.getValue(tabViewModel.conversationId),
                                                        conversationSidePanelVisible = !isCompactLayout && sidePanelVisible,
                                                        messageSelectionEnabled = sidePanel.messageSelectionEnabled,
                                                        conversationSidePanelContent = sidePanelContent,
                                                        sidePanelResize = sidePanelResize,
                                                        slotInventory = slotInventory,
                                                        onOpenSlots = { openSidePanel(SidebarSection.SLOTS) },
                                                        contentPadding = contentPadding,
                                                        coroutineScope = coroutineScope,
                                                        pttEventHandler = appComponents.pttEventRouter,
                                                        pttState = pttState,
                                                        pttStatusMessage = pttStatusMessage?.resolve(localization),
                                                        pttUnavailableReason = pttUnavailableReason?.resolve(localization),
                                                        liveVoiceInputService = appComponents.liveVoiceInputService,
                                                        liveVoiceInputState = liveVoiceInputState,
                                                        liveVoiceInputStatusMessage = liveVoiceInputStatusMessage?.resolve(localization),
                                                        liveVoiceInputUnavailableReason = liveVoiceInputUnavailableReason?.resolve(localization),
                                                        pttTarget = pttTarget,
                                                        liveVoiceTarget = liveVoiceTarget,
                                                        voiceTranscriptions = voiceTranscriptions,
                                                        remoteConnectionState = remoteConnectionState,
                                                        settings = currentSettings,


                                                        onInspectRuntime = { section ->
                                                            runtimeInspectionRequest = RuntimeInspectionRequest(
                                                                tabViewModel.conversationId, section, (runtimeInspectionRequest?.sequence ?: 0) + 1,
                                                            )
                                                            openSidePanel(if (section == RuntimeInspectionSection.OVERVIEW) SidebarSection.RUNTIME else SidebarSection.TASKS)
                                                        },
                                                        onInsertCurrentLocation = if (appComponents.deviceLocationService.isSupported) {
                                                            {
                                                                coroutineScope.launch {
                                                                    val locationText = appComponents.deviceLocationService
                                                                        .getCurrentLocation()
                                                                        .toInputText(localization)
                                                                    val currentInput = tabViewModel.userInput.trimEnd()
                                                                    tabViewModel.updateUserInput(
                                                                        if (currentInput.isBlank()) {
                                                                            locationText
                                                                        } else {
                                                                            "$currentInput\n\n$locationText"
                                                                        }
                                                                    )
                                                                }
                                                            }
                                                        } else {
                                                            null
                                                        },
                                                        isDev = appComponents.settingsService.mode == AppMode.DEV,
                                                        isCompactLayout = isCompactLayout,
                                                        clientPlatform = clientPlatform,
                                                    )
                                                } else {
                                                    Box(modifier = Modifier.padding(contentPadding)) {
                                                        when (selectedTabIndex) {
                                                            1 -> {
                                                                AgentConstructorScreen(
                                                                    projectId = tabs.firstOrNull()?.projectId,
                                                                    projectService = appComponents.projectService,
                                                                    agentService = appComponents.agentService,
                                                                    agentSkillService = appComponents.agentSkillService,
                                                                    promptService = appComponents.promptService,
                                                                    aiConfigurationService = appComponents.aiConfigurationService,
                                                                    runtimeCatalogTemplateService = appComponents.runtimeCatalogTemplateService,
                                                                    coroutineScope = coroutineScope,
                                                                )
                                                            }

                                                            2 -> {
                                                                settingsContent(SettingsPanelContentMode.Full)
                                                            }

                                                            else -> when (projectArea) {
                                                                ProjectArea.CONVERSATIONS -> SessionListScreen(
                                                                    onConversationSelected = { _, _ -> refreshTrigger++ },
                                                                    coroutineScope = coroutineScope,
                                                                    onNewSession = createNewSessionForProject,
                                                                    projectService = appComponents.projectService,
                                                                    conversationTreeService = appComponents.conversationService,
                                                                    appViewModel = appComponents.appViewModel,
                                                                    searchViewModel = appComponents.conversationSearchViewModel,
                                                                    showSettingsPanel = sidePanelVisible && sidePanelTab == SidebarSection.SETTINGS,
                                                                    onShowSettingsPanelChange = { if (it) openSidePanel(SidebarSection.SETTINGS) else hideSidePanel() },
                                                                    onManageProjects = { projectArea = ProjectArea.PROJECTS },
                                                                    onManageWorkspaces = {
                                                                        workspaceManagerProjectId = null
                                                                        projectArea = ProjectArea.WORKSPACES
                                                                    },
                                                                    refreshTrigger = refreshTrigger,
                                                                )

                                                                ProjectArea.PROJECTS -> ProjectManagerScreen(
                                                                    projectService = appComponents.projectService,
                                                                    projectMembershipService = appComponents.projectMembershipService,
                                                                    userDirectoryService = appComponents.userDirectoryService,
                                                                    onBack = { projectArea = ProjectArea.CONVERSATIONS },
                                                                    onManageWorkspaces = { projectId ->
                                                                        workspaceManagerProjectId = projectId
                                                                        projectArea = ProjectArea.WORKSPACES
                                                                    },
                                                                    onChanged = { refreshTrigger++ },
                                                                )

                                                                ProjectArea.WORKSPACES -> WorkspaceManagerScreen(
                                                                    initialProjectId = workspaceManagerProjectId,
                                                                    projectService = appComponents.projectService,
                                                                    workspaceCatalogService = appComponents.workspaceCatalogService,
                                                                    workspaceManagementService = appComponents.workspaceManagementService,
                                                                    workerCatalogService = appComponents.workerCatalogService,
                                                                    onBack = { projectArea = ProjectArea.CONVERSATIONS },
                                                                    onManageProjects = { projectArea = ProjectArea.PROJECTS },
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }


                                    }
                                }

                                if (currentUiSettings.showTabsAtBottom) {
                                    tabRowComponent()
                                }
                            }

                            if (!isCompactLayout && sidePanelVisible && currentTab == null &&
                                sidePanelTab == SidebarSection.SETTINGS && currentTabIndex != -2) {
                                Box(Modifier.width(sidePanelResize.width(availableWidth)).fillMaxHeight()) {
                                    sidePanelContent()
                                    if (resizable) SidePanelResizeHandle(
                                        Modifier.align(Alignment.CenterStart).offset(x = -sidePanelResizeHandleWidth / 2)
                                            .width(sidePanelResizeHandleWidth).fillMaxHeight(),
                                        onReset = sidePanelResize::reset,
                                    ) { delta -> sidePanelResize.dragBy(availableWidth, with(scaledDensity) { delta.toDp() }) }
                                }
                            }
                        }

                        if (isCompactLayout && sidePanelVisible &&
                            (currentTab != null || (sidePanelTab == SidebarSection.SETTINGS && currentTabIndex != -2))) {
                            Box(Modifier.fillMaxSize().padding(
                                top = if (currentUiSettings.showTabsAtBottom) 0.dp else 48.dp,
                                bottom = if (currentUiSettings.showTabsAtBottom) 48.dp else 0.dp,
                            ).zIndex(3f)) { sidePanelContent() }
                        }
                    }
                }
            }
        }
    }
}

private const val ERROR_HAPTIC_GAP_MILLIS = 120L

private enum class ProjectArea {
    CONVERSATIONS,
    PROJECTS,
    WORKSPACES,
}

private fun DeviceLocationResult.toInputText(localization: Translation): String = when (this) {
    is DeviceLocationResult.Available -> snapshot.toInputText(localization)
    is DeviceLocationResult.Unavailable -> {
        val reasonText = localization.text(when (reason) {
            com.gromozeka.device.telemetry.DeviceLocationUnavailableReason.UNSUPPORTED -> "chat.location.reason.unsupported"
            com.gromozeka.device.telemetry.DeviceLocationUnavailableReason.PERMISSION_DENIED -> "chat.location.reason.permissionDenied"
            com.gromozeka.device.telemetry.DeviceLocationUnavailableReason.LOCATION_DISABLED -> "chat.location.reason.disabled"
            com.gromozeka.device.telemetry.DeviceLocationUnavailableReason.TIMEOUT -> "chat.location.reason.timeout"
            com.gromozeka.device.telemetry.DeviceLocationUnavailableReason.ERROR -> "chat.location.reason.error"
        })
        message?.takeIf(String::isNotBlank)?.let {
            localization.text("chat.location.unavailableWithMessage", "reason" to reasonText, "message" to it)
        } ?: localization.text("chat.location.unavailable", "reason" to reasonText)
    }
}

private fun DeviceLocationSnapshot.toInputText(localization: Translation): String =
    localization.text("chat.location.current", "details" to buildString {
        append("latitude=$latitude, longitude=$longitude")
        accuracyMeters?.let { append(", accuracyMeters=$it") }
        altitudeMeters?.let { append(", altitudeMeters=$it") }
        provider?.let { append(", provider=$it") }
        append(", capturedAt=$capturedAt")
    })

/** Draft routing is observed here, so typing does not recompose the application shell. */
@Composable
private fun RuntimeReplyRouting(viewModel: TabViewModel) {
    val state by viewModel.uiState.collectAsState()
    val candidates by viewModel.agentMentionCandidates.collectAsState()
    val externalChannel by viewModel.externalChannel.collectAsState()
    if (externalChannel != null) return
    Text(
        text = agentResponseHint(state.userInput, candidates, LocalTranslation.current),
        modifier = Modifier.testTag(UiTestTag.AgentResponseHint.value),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
