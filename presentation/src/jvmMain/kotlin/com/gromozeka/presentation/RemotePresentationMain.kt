package com.gromozeka.presentation

import com.gromozeka.presentation.services.DesktopClientAudioRecorder
import com.gromozeka.presentation.services.DesktopClientAudioPlayer
import com.gromozeka.presentation.services.DesktopSystemAudioMuteService
import com.gromozeka.presentation.services.DesktopLocalWhisperSpeechToTextService
import com.gromozeka.presentation.services.DesktopGlobalHotkeyController
import com.gromozeka.presentation.services.DesktopQuickTextActionExecutor
import com.gromozeka.presentation.services.DesktopNotificationService
import com.gromozeka.presentation.services.GlobalHotkeyEventPhase
import com.gromozeka.presentation.services.HoldToTalkShortcutController
import com.gromozeka.presentation.services.LocalWorkerController
import com.gromozeka.presentation.services.DesktopRemoteClientSettingsStore
import com.gromozeka.presentation.services.DesktopRemoteSessionCredentialStore
import com.gromozeka.presentation.services.DesktopAttachmentAcquisitionController
import com.gromozeka.presentation.services.WindowStateService
import com.gromozeka.presentation.services.TurnCompletionNotificationSink
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.remote.protocol.AuthenticatedUserView
import com.gromozeka.domain.model.KeyboardShortcutAction
import com.gromozeka.domain.model.QuickTextAction
import com.gromozeka.domain.model.UserDeviceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import io.ktor.client.HttpClient

internal suspend fun startRemotePresentation(
    remoteUrl: String,
    authenticatedUser: AuthenticatedUserView,
    remoteClientSettingsStore: DesktopRemoteClientSettingsStore,
    localWorkerController: LocalWorkerController,
    desktopNotificationService: DesktopNotificationService,
    httpClient: HttpClient? = null,
): RemoteStartedApp {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val clientHomeDirectory = desktopRemoteClientHomeDirectory().absolutePath
    val globalHotkeyController = DesktopGlobalHotkeyController()
    val remoteApp = try {
        createRemoteAppComponents(
            remoteUrl = remoteUrl,
            authenticatedUser = authenticatedUser,
            scope = scope,
            clientHomeDirectory = clientHomeDirectory,
            clientPlatform = ClientPlatform.DESKTOP,
            remoteClientSettingsStore = remoteClientSettingsStore,
            audioRecorder = DesktopClientAudioRecorder(),
            audioPlayer = DesktopClientAudioPlayer(),
            systemAudioMuteService = DesktopSystemAudioMuteService(),
            clientSideSpeechToTextServiceFactory = ::DesktopLocalWhisperSpeechToTextService,
            attachmentAcquisitionController = DesktopAttachmentAcquisitionController(),
            globalHotkeyController = globalHotkeyController,
            localWorkerController = localWorkerController,
            turnCompletionNotificationSink = TurnCompletionNotificationSink {
                desktopNotificationService.show("Gromozeka", "Turn completed")
            },
            httpClient = httpClient,
        )
    } catch (error: Throwable) {
        scope.cancel()
        throw error
    }
    File(remoteApp.components.settingsService.homeDirectory).mkdirs()
    System.setProperty("GROMOZEKA_HOME", remoteApp.components.settingsService.homeDirectory)
    val quickTextActionExecutor = DesktopQuickTextActionExecutor(
        quickTextActionService = remoteApp.components.quickTextActionService,
        uiFeedbackController = remoteApp.components.uiFeedbackController,
        notificationService = desktopNotificationService,
    )
    val globalHoldToTalkController = HoldToTalkShortcutController(
        pttEventHandler = remoteApp.components.pttEventRouter,
        coroutineScope = scope,
    )
    scope.launch {
        remoteApp.components.settingsService.settingsFlow.collect { settings ->
            val shortcuts = (settings.userDeviceSettings as? UserDeviceSettings.Desktop)
                ?.inputSettings
                ?.keyboardShortcuts
                ?: return@collect
            globalHotkeyController.applySettings(shortcuts) { event ->
                when (event.phase) {
                    GlobalHotkeyEventPhase.PRESSED -> if (event.action == KeyboardShortcutAction.PUSH_TO_TALK) {
                        globalHoldToTalkController.onPressed()
                    }
                    GlobalHotkeyEventPhase.RELEASED -> if (event.action == KeyboardShortcutAction.PUSH_TO_TALK) {
                        globalHoldToTalkController.onReleased()
                    }
                    GlobalHotkeyEventPhase.CANCELLED -> if (event.action == KeyboardShortcutAction.PUSH_TO_TALK) {
                        globalHoldToTalkController.cancel()
                    }
                    GlobalHotkeyEventPhase.TRIGGERED -> scope.launch {
                        when (event.action) {
                            KeyboardShortcutAction.TOGGLE_LIVE_VOICE ->
                                remoteApp.components.liveVoiceInputService.toggle()
                            KeyboardShortcutAction.FIX_CLIPBOARD_TEXT ->
                                quickTextActionExecutor.run(QuickTextAction.FIX_TEXT_ID)
                            KeyboardShortcutAction.TRANSLATE_CLIPBOARD_TEXT ->
                                quickTextActionExecutor.run(QuickTextAction.TRANSLATE_RU_EN_ID)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    val windowStateService = WindowStateService(remoteApp.components.settingsService)
    return RemoteStartedApp(remoteApp, windowStateService, scope)
}

internal fun createDesktopRemoteClientSettingsStore(): DesktopRemoteClientSettingsStore {
    return DesktopRemoteClientSettingsStore(
        File(desktopRemoteClientHomeDirectory(), "remote-client-settings.json")
    )
}

internal fun createDesktopRemoteSessionCredentialStore(): DesktopRemoteSessionCredentialStore =
    DesktopRemoteSessionCredentialStore(
        File(desktopRemoteClientHomeDirectory(), "remote-sessions.json")
    )

private fun desktopRemoteClientHomeDirectory(): File =
    System.getProperty("GROMOZEKA_CLIENT_HOME")
        ?.let(::File)
        ?: File(System.getProperty("user.home"), ".gromozeka-remote-client")

internal class RemoteStartedApp(
    private val remoteApp: RemoteAppComponents,
    val windowStateService: WindowStateService,
    private val scope: CoroutineScope,
) : AutoCloseable {
    val components: AppComponents = remoteApp.components
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { remoteApp.close() }
        runCatching { runBlocking { components.appViewModel.cleanup() } }
        scope.cancel()
    }
}
