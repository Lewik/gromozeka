package com.gromozeka.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalContext
import com.gromozeka.device.telemetry.AndroidDeviceLocationService
import com.gromozeka.device.telemetry.AndroidLocationPermissionRequester
import com.gromozeka.device.telemetry.NoOpDeviceLocationService
import com.gromozeka.presentation.services.AndroidClientAudioRecorder
import com.gromozeka.presentation.services.AndroidRemoteClientSettingsStore
import com.gromozeka.presentation.services.AndroidRemoteSessionCredentialStore
import com.gromozeka.presentation.services.AndroidClientAudioPlayer
import com.gromozeka.presentation.services.AndroidAttachmentAcquisitionController
import com.gromozeka.presentation.services.AndroidMicrophonePermissionRequester
import com.gromozeka.presentation.services.InMemoryUIStateStore
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.GromozekaApp
import com.gromozeka.presentation.ui.RemoteServerSetupScreen
import com.gromozeka.presentation.ui.RemoteAuthenticationScreen
import com.gromozeka.remote.protocol.AuthenticationStatusResponse
import com.gromozeka.client.resolveRemoteUrl
import com.gromozeka.client.saveRemoteUrl
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private var remoteApp: RemoteAppComponents? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val explicitRemoteUrl = intent.getStringExtra(EXTRA_REMOTE_URL)
        val metadata = packageManager
            .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData
        val bundledRemoteUrl = metadata
            ?.getString(METADATA_DEFAULT_REMOTE_URL)
            ?.takeIf(String::isNotBlank)
        val enableLocationTelemetry = metadata
            ?.getBoolean(METADATA_ENABLE_LOCATION_TELEMETRY, false)
            ?: false

        setContent {
            GromozekaAndroidApp(
                explicitRemoteUrl = explicitRemoteUrl,
                bundledRemoteUrl = bundledRemoteUrl,
                enableLocationTelemetry = enableLocationTelemetry,
                onRemoteAppStarted = { remoteApp = it }
            )
        }
    }

    override fun onDestroy() {
        runCatching { remoteApp?.close() }
        remoteApp = null
        super.onDestroy()
    }

    private companion object {
        const val EXTRA_REMOTE_URL = "gromozeka.remote.url"
        const val METADATA_DEFAULT_REMOTE_URL = "com.gromozeka.DEFAULT_REMOTE_URL"
        const val METADATA_ENABLE_LOCATION_TELEMETRY = "com.gromozeka.ENABLE_LOCATION_TELEMETRY"
    }
}

@Composable
private fun GromozekaAndroidApp(
    explicitRemoteUrl: String?,
    bundledRemoteUrl: String?,
    enableLocationTelemetry: Boolean,
    onRemoteAppStarted: (RemoteAppComponents) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var remoteApp by remember { mutableStateOf<RemoteAppComponents?>(null) }
    val currentRemoteApp by rememberUpdatedState(remoteApp)
    val context = LocalContext.current.applicationContext
    val microphonePermissionRequester = remember { ComposeMicrophonePermissionRequester() }
    val audioRecorder = remember { AndroidClientAudioRecorder(context, microphonePermissionRequester) }
    val audioPlayer = remember { AndroidClientAudioPlayer(context) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        microphonePermissionRequester.onPermissionResult(granted)
    }
    microphonePermissionRequester.launchRequest = {
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val attachmentController = remember { AndroidAttachmentAcquisitionController(context.contentResolver) }
    val attachmentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        attachmentController.onDocumentsPicked(uris)
    }
    attachmentController.launchFilePicker = {
        attachmentPickerLauncher.launch(arrayOf("*/*"))
    }
    val settingsStore = remember { AndroidRemoteClientSettingsStore(context) }
    val sessionCredentialStore = remember { AndroidRemoteSessionCredentialStore(context) }
    val initialResolution = remember {
        runCatching {
            settingsStore.resolveRemoteUrl(
                explicitUrl = explicitRemoteUrl,
                fallbackUrl = bundledRemoteUrl,
            )
        }
    }
    var remoteUrl by remember { mutableStateOf(initialResolution.getOrNull()) }
    var connectionAttempt by remember { mutableStateOf(0) }
    var connecting by remember { mutableStateOf(false) }
    var startupError by remember {
        mutableStateOf(initialResolution.exceptionOrNull()?.message)
    }
    var authenticationError by remember { mutableStateOf<String?>(null) }
    var authenticationStatus by remember { mutableStateOf<AuthenticationStatusResponse?>(null) }
    var authenticationConnection by remember { mutableStateOf<RemoteAuthenticationConnection?>(null) }
    val locationPermissionRequester = remember { ComposeLocationPermissionRequester() }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        locationPermissionRequester.onPermissionResult(permissions)
    }
    locationPermissionRequester.launchRequest = {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

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
                clientLabel = "Android client",
                sessionCredentialStore = sessionCredentialStore,
            )
            authenticationConnection = connection
            val status = connection.status()
            authenticationStatus = status
            if (status.authenticatedUser != null) {
                val app = createRemoteAppComponents(
                    remoteUrl = targetUrl,
                    authenticatedUser = requireNotNull(status.authenticatedUser),
                    scope = scope,
                    clientHomeDirectory = "android",
                    clientPlatform = ClientPlatform.ANDROID,
                    uiStateStore = InMemoryUIStateStore(),
                    remoteClientSettingsStore = settingsStore,
                    audioRecorder = audioRecorder,
                    audioPlayer = audioPlayer,
                    attachmentAcquisitionController = attachmentController,
                    deviceLocationService = if (enableLocationTelemetry) {
                        AndroidDeviceLocationService(context, locationPermissionRequester)
                    } else {
                        NoOpDeviceLocationService
                    },
                    httpClient = connection.httpClient,
                )
                remoteApp = app
                onRemoteAppStarted(app)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            startupError = error.message ?: error.toString()
        }
        connecting = false
    }

    DisposableEffect(Unit) {
        onDispose {
            currentRemoteApp?.close()
            authenticationConnection?.close()
            audioRecorder.shutdown()
            audioPlayer.stop()
            attachmentController.close()
        }
    }

    GromozekaTheme {
        when {
            remoteApp != null -> GromozekaApp(
                appComponents = remoteApp!!.components,
                skipLoadingScreen = true,
                showRuntimePanelInitially = false,
                forceCompactLayout = true,
                clientPlatform = ClientPlatform.ANDROID,
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
                                val app = createRemoteAppComponents(
                                    remoteUrl = requireNotNull(remoteUrl),
                                    authenticatedUser = requireNotNull(authenticatedStatus.authenticatedUser),
                                    scope = scope,
                                    clientHomeDirectory = "android",
                                    clientPlatform = ClientPlatform.ANDROID,
                                    uiStateStore = InMemoryUIStateStore(),
                                    remoteClientSettingsStore = settingsStore,
                                    audioRecorder = audioRecorder,
                                    audioPlayer = audioPlayer,
                                    attachmentAcquisitionController = attachmentController,
                                    deviceLocationService = if (enableLocationTelemetry) {
                                        AndroidDeviceLocationService(context, locationPermissionRequester)
                                    } else {
                                        NoOpDeviceLocationService
                                    },
                                    httpClient = connection.httpClient,
                                )
                                remoteApp = app
                                onRemoteAppStarted(app)
                            } catch (error: Throwable) {
                                authenticationError = error.message ?: error.toString()
                            }
                            connecting = false
                        }
                    },
                    onStartDeviceConnection = {
                        requireNotNull(authenticationConnection).startDeviceConnection(
                            deviceLabel = "Android client",
                            platform = "android",
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

private class ComposeMicrophonePermissionRequester : AndroidMicrophonePermissionRequester {
    var launchRequest: (() -> Unit)? = null
    private var pendingContinuation: CancellableContinuation<Boolean>? = null

    override suspend fun requestMicrophonePermission(): Boolean =
        suspendCancellableCoroutine { continuation ->
            val launcher = launchRequest
            if (launcher == null || pendingContinuation != null) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            pendingContinuation = continuation
            continuation.invokeOnCancellation {
                if (pendingContinuation === continuation) {
                    pendingContinuation = null
                }
            }
            launcher()
        }

    fun onPermissionResult(granted: Boolean) {
        pendingContinuation?.let { continuation ->
            pendingContinuation = null
            if (continuation.isActive) continuation.resume(granted)
        }
    }
}

private class ComposeLocationPermissionRequester : AndroidLocationPermissionRequester {
    var launchRequest: (() -> Unit)? = null
    private var pendingContinuation: CancellableContinuation<Boolean>? = null

    override suspend fun requestForegroundLocationPermission(): Boolean =
        suspendCancellableCoroutine { continuation ->
            val launcher = launchRequest
            if (launcher == null || pendingContinuation != null) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            pendingContinuation = continuation
            continuation.invokeOnCancellation {
                if (pendingContinuation === continuation) {
                    pendingContinuation = null
                }
            }
            launcher()
        }

    fun onPermissionResult(permissions: Map<String, Boolean>) {
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        pendingContinuation?.let { continuation ->
            pendingContinuation = null
            if (continuation.isActive) continuation.resume(granted)
        }
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
