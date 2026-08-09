package com.gromozeka.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.gromozeka.client.resolveRemoteUrl
import com.gromozeka.client.saveRemoteUrl
import com.gromozeka.device.telemetry.NoOpDeviceLocationService
import com.gromozeka.presentation.services.InMemoryUIStateStore
import com.gromozeka.presentation.services.IosClientAudioPlayer
import com.gromozeka.presentation.services.IosClientAudioRecorder
import com.gromozeka.presentation.services.IosAttachmentAcquisitionController
import com.gromozeka.presentation.services.IosRemoteClientSettingsStore
import com.gromozeka.presentation.services.IosRemoteSessionCredentialStore
import com.gromozeka.presentation.services.PTTEvent
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.GromozekaApp
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.RemoteServerSetupScreen
import com.gromozeka.presentation.ui.RemoteAuthenticationScreen
import com.gromozeka.remote.protocol.AuthenticationStatusResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

fun GromozekaMainViewController(): UIViewController =
    ComposeUIViewController(
        configure = {
            onFocusBehavior = OnFocusBehavior.DoNothing
        }
    ) {
        GromozekaIosApp()
    }

@Composable
private fun GromozekaIosApp() {
    val scope = rememberCoroutineScope()
    val settingsStore = remember { IosRemoteClientSettingsStore() }
    val sessionCredentialStore = remember { IosRemoteSessionCredentialStore() }
    val initialResolution = remember {
        runCatching {
            settingsStore.resolveRemoteUrl(fallbackUrl = resolveBundledRemoteUrl())
        }
    }
    var remoteApp by remember { mutableStateOf<RemoteAppComponents?>(null) }
    val currentRemoteApp by rememberUpdatedState(remoteApp)
    var remoteUrl by remember { mutableStateOf(initialResolution.getOrNull()) }
    var connectionAttempt by remember { mutableStateOf(0) }
    var connecting by remember { mutableStateOf(false) }
    var startupError by remember {
        mutableStateOf(initialResolution.exceptionOrNull()?.message)
    }
    var authenticationError by remember { mutableStateOf<String?>(null) }
    var authenticationStatus by remember { mutableStateOf<AuthenticationStatusResponse?>(null) }
    var authenticationConnection by remember { mutableStateOf<RemoteAuthenticationConnection?>(null) }

    LaunchedEffect(remoteUrl, connectionAttempt) {
        val targetUrl = remoteUrl ?: return@LaunchedEffect
        connecting = true
        startupError = null
        authenticationError = null
        authenticationStatus = null
        remoteApp?.close()
        remoteApp = null
        authenticationConnection?.close()
        try {
            val connection = RemoteAuthenticationConnection(
                remoteUrl = targetUrl,
                clientLabel = "iOS client",
                sessionCredentialStore = sessionCredentialStore,
            )
            authenticationConnection = connection
            val status = connection.status()
            authenticationStatus = status
            if (status.authenticatedUser != null) {
                remoteApp = createRemoteAppComponents(
                    remoteUrl = targetUrl,
                    authenticatedUser = requireNotNull(status.authenticatedUser),
                    scope = scope,
                    clientHomeDirectory = "ios",
                    clientPlatform = ClientPlatform.IOS,
                    uiStateStore = InMemoryUIStateStore(),
                    remoteClientSettingsStore = settingsStore,
                    audioRecorder = IosClientAudioRecorder(),
                    audioPlayer = IosClientAudioPlayer(),
                    attachmentAcquisitionController = IosAttachmentAcquisitionController(),
                    deviceLocationService = NoOpDeviceLocationService,
                    httpClient = connection.httpClient,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            startupError = error.message ?: error.toString()
        }
        connecting = false
    }

    LaunchedEffect(remoteApp) {
        val app = remoteApp ?: return@LaunchedEffect
        handleActionButtonEvents(app)
    }

    DisposableEffect(Unit) {
        onDispose {
            currentRemoteApp?.close()
            authenticationConnection?.close()
        }
    }

    GromozekaTheme {
        when {
            remoteApp != null -> GromozekaApp(
                appComponents = remoteApp!!.components,
                skipLoadingScreen = true,
                showRuntimePanelInitially = false,
                forceCompactLayout = true,
                clientPlatform = ClientPlatform.IOS,
            )

            remoteUrl != null && startupError == null && authenticationStatus != null ->
                RemoteAuthenticationScreen(
                    initialized = requireNotNull(authenticationStatus).initialized,
                    submitting = connecting,
                    error = authenticationError,
                    onSubmit = { input, deviceToken ->
                        val connection = authenticationConnection ?: return@RemoteAuthenticationScreen
                        scope.launch {
                            connecting = true
                            authenticationError = null
                            try {
                                connection.authenticate(
                                    requireNotNull(authenticationStatus).initialized,
                                    input,
                                    deviceToken,
                                )
                                val authenticatedStatus = connection.status()
                                authenticationStatus = authenticatedStatus
                                remoteApp = createRemoteAppComponents(
                                    remoteUrl = requireNotNull(remoteUrl),
                                    authenticatedUser = requireNotNull(authenticatedStatus.authenticatedUser),
                                    scope = scope,
                                    clientHomeDirectory = "ios",
                                    clientPlatform = ClientPlatform.IOS,
                                    uiStateStore = InMemoryUIStateStore(),
                                    remoteClientSettingsStore = settingsStore,
                                    audioRecorder = IosClientAudioRecorder(),
                                    audioPlayer = IosClientAudioPlayer(),
                                    attachmentAcquisitionController = IosAttachmentAcquisitionController(),
                                    deviceLocationService = NoOpDeviceLocationService,
                                    httpClient = connection.httpClient,
                                )
                            } catch (error: Throwable) {
                                authenticationError = error.message ?: error.toString()
                            }
                            connecting = false
                        }
                    },
                    onStartDeviceConnection = {
                        requireNotNull(authenticationConnection).startDeviceConnection(
                            deviceLabel = "iOS client",
                            platform = "ios",
                        )
                    },
                    onConsumeDeviceConnection = {
                        requireNotNull(authenticationConnection).consumeDeviceConnection(it)
                    },
                    deviceConnectionVerificationUrl = {
                        requireNotNull(authenticationConnection).deviceConnectionVerificationUrl(it)
                    },
                    onDeviceConnected = {
                        connectionAttempt += 1
                    },
                )

            remoteUrl == null || startupError != null -> RemoteServerSetupScreen(
                initialAddress = remoteUrl.orEmpty(),
                connecting = connecting,
                connectionError = startupError,
                onConnect = { address ->
                    remoteUrl = settingsStore.saveRemoteUrl(address)
                    connectionAttempt += 1
                },
            )

            else -> StartupLoading()
        }
    }
}

private fun resolveBundledRemoteUrl(): String? {
    val configuredUrl = (NSBundle.mainBundle.objectForInfoDictionaryKey(RemoteUrlInfoKey) as? String)
        ?.trim()
        .orEmpty()
    return configuredUrl.takeIf(String::isNotEmpty)
}

private suspend fun handleActionButtonEvents(app: RemoteAppComponents) {
    val defaults = NSUserDefaults.standardUserDefaults
    var handledCommandCounter = defaults.integerForKey(ActionButtonHandledCommandCounterKey)

    try {
        while (true) {
            val commandCounter = defaults.integerForKey(ActionButtonCommandCounterKey)
            if (commandCounter < handledCommandCounter) {
                handledCommandCounter = commandCounter
                defaults.setInteger(handledCommandCounter, ActionButtonHandledCommandCounterKey)
            }

            while (handledCommandCounter < commandCounter) {
                handledCommandCounter += 1
                defaults.setInteger(handledCommandCounter, ActionButtonHandledCommandCounterKey)
                toggleActionButtonConversation(app)
            }

            delay(250)
        }
    } finally {
        withContext(NonCancellable) {
            runCatching { app.components.pttEventRouter.handlePTTCancel() }
        }
    }
}

private suspend fun toggleActionButtonConversation(app: RemoteAppComponents) {
    when (app.components.pttService.state.value) {
        PttState.IDLE -> {
            app.components.pttEventRouter.handlePTTEvent(PTTEvent.BUTTON_DOWN)
            app.components.pttEventRouter.handlePTTEvent(PTTEvent.SINGLE_PUSH)
        }

        PttState.PREPARING,
        PttState.RECORDING,
        PttState.TRANSCRIBING -> app.components.pttEventRouter.handlePTTRelease()
    }
}

@Composable
private fun StartupLoading() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

private const val ActionButtonCommandCounterKey = "gromozeka.actionButton.commandCounter"
private const val ActionButtonHandledCommandCounterKey = "gromozeka.actionButton.handledCommandCounter"
private const val RemoteUrlInfoKey = "GromozekaRemoteURL"
