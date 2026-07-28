package com.gromozeka.presentation

import android.Manifest
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
import com.gromozeka.presentation.services.AndroidRemoteClientSettingsStore
import com.gromozeka.presentation.services.InMemoryUIStateStore
import com.gromozeka.presentation.services.NoOpClientAudioRecorder
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.GromozekaApp
import com.gromozeka.presentation.ui.RemoteServerSetupScreen
import com.gromozeka.client.resolveRemoteUrl
import com.gromozeka.client.saveRemoteUrl
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private var remoteApp: RemoteAppComponents? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val explicitRemoteUrl = intent.getStringExtra(EXTRA_REMOTE_URL)
        val bundledRemoteUrl = BuildConfig.DEFAULT_REMOTE_URL.takeIf(String::isNotBlank)

        setContent {
            GromozekaAndroidApp(
                explicitRemoteUrl = explicitRemoteUrl,
                bundledRemoteUrl = bundledRemoteUrl,
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
    }
}

@Composable
private fun GromozekaAndroidApp(
    explicitRemoteUrl: String?,
    bundledRemoteUrl: String?,
    onRemoteAppStarted: (RemoteAppComponents) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var remoteApp by remember { mutableStateOf<RemoteAppComponents?>(null) }
    val currentRemoteApp by rememberUpdatedState(remoteApp)
    val context = LocalContext.current.applicationContext
    val settingsStore = remember { AndroidRemoteClientSettingsStore(context) }
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
        try {
            val app = createRemoteAppComponents(
                remoteUrl = targetUrl,
                scope = scope,
                clientHomeDirectory = "android",
                clientPlatform = ClientPlatform.ANDROID,
                uiStateStore = InMemoryUIStateStore(),
                remoteClientSettingsStore = settingsStore,
                audioRecorder = NoOpClientAudioRecorder,
                deviceLocationService = if (BuildConfig.ENABLE_LOCATION_TELEMETRY) {
                    AndroidDeviceLocationService(context, locationPermissionRequester)
                } else {
                    NoOpDeviceLocationService
                },
            )
            remoteApp = app
            onRemoteAppStarted(app)
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
