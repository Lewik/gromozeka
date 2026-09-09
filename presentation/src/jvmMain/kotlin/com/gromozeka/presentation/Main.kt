package com.gromozeka.presentation

import com.gromozeka.shared.logging.JvmClientDiagnosticLogging
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.gromozeka.client.resolveRemoteUrl
import com.gromozeka.client.saveRemoteUrl
import com.gromozeka.client.RemoteConnectionState
import com.gromozeka.presentation.ui.ChatWindow
import com.gromozeka.presentation.ui.ClientTheme
import com.gromozeka.presentation.ui.rememberClientTranslation
import com.gromozeka.presentation.ui.RemoteServerSetupScreen
import com.gromozeka.presentation.ui.RemoteAuthenticationScreen
import com.gromozeka.presentation.services.DesktopNotificationPublisher
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.services.WindowsWindowAppearance
import com.gromozeka.presentation.services.defaultDesktopNotificationService
import com.gromozeka.presentation.services.theming.data.DarkTheme
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.gromozeka.remote.protocol.AuthenticationStatusResponse
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.QuitHandler

fun main(args: Array<String>) {
    JvmClientDiagnosticLogging.install(args)
    val log = KLoggers.logger("ChatApplication")
    System.setProperty("java.awt.headless", "false")

    log.info("Starting Compose Desktop UI...")
    application {
        val settingsStore = remember { createDesktopRemoteClientSettingsStore() }
        val sessionCredentialStore = remember { createDesktopRemoteSessionCredentialStore() }
        val explicitRemoteUrl = remember {
            System.getProperty("gromozeka.remote.url")
                ?: System.getenv("GROMOZEKA_REMOTE_URL")
        }
        val initialResolution = remember {
            runCatching { settingsStore.resolveRemoteUrl(explicitUrl = explicitRemoteUrl) }
        }
        var remoteUrl by remember {
            mutableStateOf(initialResolution.getOrNull())
        }
        var connectionAttempt by remember { mutableIntStateOf(0) }
        var connecting by remember { mutableStateOf(false) }
        var initializationError by remember {
            mutableStateOf(initialResolution.exceptionOrNull())
        }
        var authenticationError by remember { mutableStateOf<Throwable?>(null) }
        var authenticationStatus by remember { mutableStateOf<AuthenticationStatusResponse?>(null) }
        var authenticationConnection by remember { mutableStateOf<RemoteAuthenticationConnection?>(null) }
        var remoteApp by remember { mutableStateOf<RemoteStartedApp?>(null) }
        var trayRemoteConnectionStatus by remember { mutableStateOf<RemoteConnectionState.Status?>(null) }
        var trayPttState by remember { mutableStateOf(PttState.IDLE) }
        var windowVisible by remember { mutableStateOf(true) }
        var quitting by remember { mutableStateOf(false) }
        val traySupported = isTraySupported
        val trayState = rememberTrayState()
        val desktopNotificationService = remember(trayState) {
            defaultDesktopNotificationService(
                windowsFallbackPublisher = DesktopNotificationPublisher { notification ->
                    trayState.sendNotification(Notification(notification.title, notification.message))
                },
            )
        }
        val scope = rememberCoroutineScope()

        suspend fun startAuthenticatedRemoteApp(connection: RemoteAuthenticationConnection) {
            val authenticatedStatus = connection.status()
            authenticationStatus = authenticatedStatus
            remoteApp = startRemotePresentation(
                remoteUrl = requireNotNull(remoteUrl),
                authenticatedUser = requireNotNull(authenticatedStatus.authenticatedUser),
                remoteClientSettingsStore = settingsStore,
                desktopNotificationService = desktopNotificationService,
                httpClient = connection.httpClient,
            )
        }

        fun showWindow() {
            windowVisible = true
        }

        fun quit() {
            if (quitting) return
            quitting = true
            scope.launch {
                remoteApp?.close()
                authenticationConnection?.close()
                exitApplication()
            }
        }

        LaunchedEffect(remoteApp) {
            trayRemoteConnectionStatus = null
            remoteApp?.components?.remoteConnectionState?.collect { state ->
                trayRemoteConnectionStatus = state.status
            }
        }

        LaunchedEffect(remoteApp) {
            trayPttState = PttState.IDLE
            remoteApp?.components?.pttService?.state?.collect { state ->
                trayPttState = state
            }
        }

        DisposableEffect(scope) {
            val desktop = runCatching { Desktop.getDesktop() }.getOrNull()
            val reopenListener = AppReopenedListener { scope.launch { showWindow() } }
            val quitHandler = QuitHandler { _, response ->
                response.cancelQuit()
                scope.launch { quit() }
            }
            runCatching { desktop?.addAppEventListener(reopenListener) }
            runCatching { desktop?.setQuitHandler(quitHandler) }
            onDispose {
                runCatching { desktop?.removeAppEventListener(reopenListener) }
                runCatching { desktop?.setQuitHandler(null) }
            }
        }

        LaunchedEffect(remoteUrl, connectionAttempt) {
            val targetUrl = remoteUrl ?: return@LaunchedEffect
            connecting = true
            initializationError = null
            authenticationError = null
            authenticationStatus = null
            remoteApp?.close()
            remoteApp = null
            authenticationConnection?.close()
            try {
                val connection = RemoteAuthenticationConnection(
                    remoteUrl = targetUrl,
                    clientLabel = "Desktop client",
                    sessionCredentialStore = sessionCredentialStore,
                )
                authenticationConnection = connection
                val status = connection.status()
                authenticationStatus = status
                if (status.authenticatedUser != null) {
                    log.info("Initializing authenticated remote UI client: $targetUrl")
                    remoteApp = startRemotePresentation(
                        remoteUrl = targetUrl,
                        authenticatedUser = requireNotNull(status.authenticatedUser),
                        remoteClientSettingsStore = settingsStore,
                        desktopNotificationService = desktopNotificationService,
                        httpClient = connection.httpClient,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Failed to initialize remote UI client: ${error.message}")
                initializationError = error
            }
            connecting = false
        }

        DisposableEffect(Unit) {
            onDispose {
                remoteApp?.close()
                authenticationConnection?.close()
            }
        }

        val localization = rememberClientTranslation(remoteApp?.components?.translationService, settingsStore)
        val startedApp = remoteApp
        if (traySupported) {
            Tray(
                icon = painterResource("logos/logo-32x32.png"),
                state = trayState,
                tooltip = when (trayPttState) {
                    PttState.IDLE -> "Gromozeka"
                    PttState.PREPARING -> localization.runtime.preparingVoiceStatus
                    PttState.RECORDING -> localization.runtime.recordingVoiceStatus
                    PttState.TRANSCRIBING -> localization.runtime.transcribingVoiceStatus
                },
                onAction = ::showWindow,
            ) {
                Item(localization.text("native.open"), onClick = ::showWindow)
                Separator()
                Item(
                    text = when {
                        connecting -> localization.text("native.serverStatus", "status" to localization.runtime.connectingStatus)
                        trayRemoteConnectionStatus == RemoteConnectionState.Status.CONNECTED -> localization.text("native.serverStatus", "status" to localization.runtime.connectedStatus)
                        trayRemoteConnectionStatus == RemoteConnectionState.Status.CONNECTING -> localization.text("native.serverStatus", "status" to localization.runtime.connectingStatus)
                        trayRemoteConnectionStatus == RemoteConnectionState.Status.RECONNECTING -> localization.text("native.serverStatus", "status" to localization.runtime.reconnectingStatus)
                        trayRemoteConnectionStatus == RemoteConnectionState.Status.OFFLINE -> localization.text("native.serverStatus", "status" to localization.runtime.offlineStatus)
                        trayRemoteConnectionStatus == RemoteConnectionState.Status.DISCONNECTED -> localization.text("native.serverStatus", "status" to localization.runtime.disconnectedStatus)
                        remoteUrl == null -> localization.text("native.serverStatus", "status" to localization.text("native.notConfigured"))
                        else -> localization.text("native.serverStatus", "status" to localization.runtime.disconnectedStatus)
                    },
                    enabled = false,
                    onClick = {},
                )
                if (trayPttState != PttState.IDLE) {
                    Item(
                        text = when (trayPttState) {
                            PttState.PREPARING -> localization.text("native.voiceStatus", "status" to localization.runtime.preparingVoiceStatus)
                            PttState.RECORDING -> localization.text("native.voiceStatus", "status" to localization.runtime.recordingVoiceStatus)
                            PttState.TRANSCRIBING -> localization.text("native.voiceStatus", "status" to localization.runtime.transcribingVoiceStatus)
                            PttState.IDLE -> localization.text("native.voiceStatus", "status" to localization.text("native.idle"))
                        },
                        enabled = false,
                        onClick = {},
                    )
                }
                Separator()
                Item(
                    text = if (quitting) localization.text("native.quitting") else localization.text("native.quit"),
                    enabled = !quitting,
                    onClick = ::quit,
                )
            }
        }
        if (startedApp != null) {
            ChatWindow(
                appComponents = startedApp.components,
                windowStateService = startedApp.windowStateService,
                visible = windowVisible,
                skipLoadingScreen = true,
                onExitRequest = {
                    if (traySupported) windowVisible = false else quit()
                }
            )
        } else {
            Window(
                onCloseRequest = { if (traySupported) windowVisible = false else quit() },
                visible = windowVisible,
                title = "Gromozeka",
                state = rememberWindowState(size = DpSize(640.dp, 480.dp)),
            ) {
                LaunchedEffect(window) {
                    val theme = DarkTheme()
                    WindowsWindowAppearance.apply(
                        window = window,
                        background = theme.background,
                        foreground = theme.onBackground,
                    )
                }
                LaunchedEffect(windowVisible) {
                    if (windowVisible) {
                        window.toFront()
                        window.requestFocus()
                    }
                }
                ClientTheme(localization) {
                    val status = authenticationStatus
                    if (remoteUrl != null && initializationError == null && status != null) {
                        RemoteAuthenticationScreen(
                            initialized = status.initialized,
                            submitting = connecting,
                            error = authenticationError,
                            onSubmit = { input, deviceToken ->
                                val connection = authenticationConnection ?: return@RemoteAuthenticationScreen
                                scope.launch {
                                    connecting = true
                                    authenticationError = null
                                    try {
                                        connection.authenticate(
                                            initialized = status.initialized,
                                            input = input,
                                            deviceToken = deviceToken,
                                        )
                                        startAuthenticatedRemoteApp(connection)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        authenticationError = error
                                    }
                                    connecting = false
                                }
                            },
                            onStartDeviceConnection = {
                                requireNotNull(authenticationConnection).startDeviceConnection(
                                    deviceLabel = "Desktop client",
                                    platform = System.getProperty("os.name"),
                                )
                            },
                            onConsumeDeviceConnection = {
                                requireNotNull(authenticationConnection).consumeDeviceConnection(it)
                            },
                            deviceConnectionVerificationUrl = {
                                requireNotNull(authenticationConnection).deviceConnectionVerificationUrl(it)
                            },
                            onDeviceConnected = {
                                val connection = authenticationConnection ?: return@RemoteAuthenticationScreen
                                scope.launch {
                                    connecting = true
                                    authenticationError = null
                                    try {
                                        startAuthenticatedRemoteApp(connection)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        authenticationError = error
                                    }
                                    connecting = false
                                }
                            },
                        )
                    } else {
                        RemoteServerSetupScreen(
                            initialAddress = remoteUrl.orEmpty(),
                            connecting = connecting,
                            connectionError = initializationError,
                            onConnect = { address ->
                                remoteUrl = settingsStore.saveRemoteUrl(address)
                                connectionAttempt += 1
                            },
                        )
                    }
                }
            }
        }
    }
}
