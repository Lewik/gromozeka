package com.gromozeka.presentation

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import com.gromozeka.client.resolveRemoteUrl
import com.gromozeka.client.saveRemoteUrl
import com.gromozeka.client.RemoteConnectionState
import com.gromozeka.presentation.ui.ChatWindow
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.RemoteServerSetupScreen
import com.gromozeka.presentation.ui.RemoteAuthenticationScreen
import com.gromozeka.presentation.services.DesktopLocalWorkerController
import com.gromozeka.presentation.services.LocalWorkerOperation
import com.gromozeka.presentation.services.LocalWorkerStatus
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.gromozeka.remote.protocol.AuthenticationStatusResponse
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.QuitHandler

fun main() {
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
            mutableStateOf(initialResolution.exceptionOrNull()?.message)
        }
        var authenticationError by remember { mutableStateOf<String?>(null) }
        var authenticationStatus by remember { mutableStateOf<AuthenticationStatusResponse?>(null) }
        var authenticationConnection by remember { mutableStateOf<RemoteAuthenticationConnection?>(null) }
        var remoteApp by remember { mutableStateOf<RemoteStartedApp?>(null) }
        var trayRemoteConnectionStatus by remember { mutableStateOf<RemoteConnectionState.Status?>(null) }
        var windowVisible by remember { mutableStateOf(true) }
        var quitting by remember { mutableStateOf(false) }
        val localWorkerController = remember { DesktopLocalWorkerController() }
        val localWorkerStatus by localWorkerController.status.collectAsState()
        val traySupported = isTraySupported
        val scope = rememberCoroutineScope()

        fun showWindow() {
            windowVisible = true
        }

        fun quit() {
            if (quitting) return
            quitting = true
            scope.launch {
                localWorkerController.stopForApplicationExit()
                remoteApp?.close()
                authenticationConnection?.close()
                exitApplication()
            }
        }

        LaunchedEffect(Unit) {
            localWorkerController.initialize()
        }

        LaunchedEffect(remoteApp) {
            trayRemoteConnectionStatus = null
            remoteApp?.components?.remoteConnectionState?.collect { state ->
                trayRemoteConnectionStatus = state.status
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
                        localWorkerController = localWorkerController,
                        httpClient = connection.httpClient,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Failed to initialize remote UI client: ${error.message}")
                initializationError = error.message ?: error.toString()
            }
            connecting = false
        }

        DisposableEffect(Unit) {
            onDispose {
                remoteApp?.close()
                authenticationConnection?.close()
                localWorkerController.close()
            }
        }

        val startedApp = remoteApp
        if (traySupported) {
            Tray(
                icon = painterResource("logos/logo-32x32.png"),
                tooltip = "Gromozeka",
                onAction = ::showWindow,
            ) {
                Item("Open Gromozeka", onClick = ::showWindow)
                Separator()
                Item(
                    text = when {
                        connecting -> "Server: connecting"
                        trayRemoteConnectionStatus == RemoteConnectionState.Status.CONNECTED -> "Server: connected"
                        trayRemoteConnectionStatus == RemoteConnectionState.Status.CONNECTING -> "Server: connecting"
                        trayRemoteConnectionStatus == RemoteConnectionState.Status.RECONNECTING -> "Server: reconnecting"
                        trayRemoteConnectionStatus == RemoteConnectionState.Status.OFFLINE -> "Server: offline"
                        trayRemoteConnectionStatus == RemoteConnectionState.Status.DISCONNECTED -> "Server: disconnected"
                        remoteUrl == null -> "Server: not configured"
                        else -> "Server: disconnected"
                    },
                    enabled = false,
                    onClick = {},
                )
                if (localWorkerStatus.supported) {
                    Item(
                        text = localWorkerStatus.trayLabel(),
                        enabled = false,
                        onClick = {},
                    )
                    when {
                        localWorkerStatus.running -> Item(
                            text = "Stop Local Worker",
                            enabled = localWorkerStatus.operation == null,
                            onClick = { scope.launch { localWorkerController.stop() } },
                        )

                        localWorkerStatus.installed -> Item(
                            text = "Start Local Worker",
                            enabled = localWorkerStatus.operation == null,
                            onClick = { scope.launch { localWorkerController.start() } },
                        )

                        startedApp != null -> Item(
                            text = "Enable Local Worker",
                            enabled = localWorkerStatus.operation == null,
                            onClick = {
                                scope.launch {
                                    localWorkerController.enable(
                                        startedApp.components.distributionService,
                                        startedApp.components.workerCatalogService,
                                    )
                                }
                            },
                        )
                    }
                    if (localWorkerStatus.installed) {
                        Item(
                            text = "Computer Use Permissions...",
                            enabled = localWorkerStatus.operation == null,
                            onClick = { scope.launch { localWorkerController.requestComputerUsePermissions() } },
                        )
                    }
                }
                Separator()
                Item(
                    text = if (quitting) "Quitting..." else "Quit Gromozeka",
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
                LaunchedEffect(windowVisible) {
                    if (windowVisible) {
                        window.toFront()
                        window.requestFocus()
                    }
                }
                GromozekaTheme {
                    val status = authenticationStatus
                    if (remoteUrl != null && initializationError == null && status != null) {
                        RemoteAuthenticationScreen(
                            initialized = status.initialized,
                            submitting = connecting,
                            error = authenticationError,
                            onSubmit = { input ->
                                val connection = authenticationConnection ?: return@RemoteAuthenticationScreen
                                scope.launch {
                                    connecting = true
                                    authenticationError = null
                                    try {
                                        connection.authenticate(status.initialized, input)
                                        val authenticatedStatus = connection.status()
                                        authenticationStatus = authenticatedStatus
                                        remoteApp = startRemotePresentation(
                                            remoteUrl = requireNotNull(remoteUrl),
                                            authenticatedUser = requireNotNull(authenticatedStatus.authenticatedUser),
                                            remoteClientSettingsStore = settingsStore,
                                            localWorkerController = localWorkerController,
                                            httpClient = connection.httpClient,
                                        )
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        authenticationError = error.message ?: error.toString()
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

private fun LocalWorkerStatus.trayLabel(): String = when {
    operation == LocalWorkerOperation.ENROLLING -> "Local Worker: enrolling"
    operation == LocalWorkerOperation.STARTING -> "Local Worker: starting"
    operation == LocalWorkerOperation.STOPPING -> "Local Worker: stopping"
    operation == LocalWorkerOperation.REQUESTING_PERMISSIONS -> "Local Worker: requesting permissions"
    failure != null -> "Local Worker: error"
    running && serverStatus == com.gromozeka.domain.service.WorkerCatalogEntry.Status.ONLINE -> "Local Worker: online"
    running -> "Local Worker: running"
    installed -> "Local Worker: stopped"
    else -> "Local Worker: disabled"
}
