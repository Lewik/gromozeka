package com.gromozeka.presentation

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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.gromozeka.client.resolveRemoteUrl
import com.gromozeka.client.saveRemoteUrl
import com.gromozeka.presentation.ui.ChatWindow
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.RemoteServerSetupScreen
import com.gromozeka.presentation.ui.RemoteAuthenticationScreen
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.gromozeka.remote.protocol.AuthenticationStatusResponse

fun main() {
    val log = KLoggers.logger("ChatApplication")
    System.setProperty("java.awt.headless", "false")

    log.info("Starting Compose Desktop UI...")
    application {
        val settingsStore = remember { createDesktopRemoteClientSettingsStore() }
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
        val scope = rememberCoroutineScope()

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
                val connection = RemoteAuthenticationConnection(targetUrl, "Desktop client")
                authenticationConnection = connection
                val status = connection.status()
                authenticationStatus = status
                if (status.authenticatedUser != null) {
                    log.info("Initializing authenticated remote UI client: $targetUrl")
                    remoteApp = startRemotePresentation(targetUrl, settingsStore, connection.httpClient)
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
            }
        }

        val startedApp = remoteApp
        if (startedApp != null) {
            ChatWindow(
                appComponents = startedApp.components,
                windowStateService = startedApp.windowStateService,
                skipLoadingScreen = true,
                onExitRequest = {
                    startedApp.close()
                    exitApplication()
                }
            )
        } else {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Gromozeka",
                state = rememberWindowState(size = DpSize(640.dp, 480.dp)),
            ) {
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
                                        authenticationStatus = connection.status()
                                        remoteApp = startRemotePresentation(
                                            requireNotNull(remoteUrl),
                                            settingsStore,
                                            connection.httpClient,
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
