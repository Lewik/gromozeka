package com.gromozeka.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.gromozeka.client.GromozekaRemoteDefaults
import com.gromozeka.client.InMemoryRemoteClientSettingsStore
import com.gromozeka.device.telemetry.NoOpDeviceLocationService
import com.gromozeka.presentation.services.InMemoryUIStateStore
import com.gromozeka.presentation.services.IosClientAudioRecorder
import com.gromozeka.presentation.services.PTTEvent
import com.gromozeka.presentation.ui.GromozekaApp
import kotlinx.coroutines.delay
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
    var remoteApp by remember { mutableStateOf<RemoteAppComponents?>(null) }
    val currentRemoteApp by rememberUpdatedState(remoteApp)
    var startupError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            createRemoteAppComponents(
                remoteUrl = GromozekaRemoteDefaults.REMOTE_URL,
                scope = scope,
                clientHomeDirectory = "ios",
                uiStateStore = InMemoryUIStateStore(),
                remoteClientSettingsStore = InMemoryRemoteClientSettingsStore(),
                audioRecorder = IosClientAudioRecorder(),
                deviceLocationService = NoOpDeviceLocationService,
            )
        }.onSuccess { app ->
            remoteApp = app
        }.onFailure { error ->
            startupError = error.message ?: error.toString()
        }
    }

    LaunchedEffect(remoteApp) {
        val app = remoteApp ?: return@LaunchedEffect
        handleActionButtonEvents(app)
    }

    DisposableEffect(Unit) {
        onDispose {
            currentRemoteApp?.close()
        }
    }

    when {
        remoteApp != null -> GromozekaApp(
            appComponents = remoteApp!!.components,
            skipLoadingScreen = true,
            uiScaleMultiplier = 1.2f,
            showPromptsPanelInitially = false,
            forceCompactLayout = true,
        )

        startupError != null -> StartupError(startupError!!)
        else -> StartupLoading()
    }
}

private suspend fun handleActionButtonEvents(app: RemoteAppComponents) {
    val defaults = NSUserDefaults.standardUserDefaults
    var lastCounter = defaults.integerForKey(ActionButtonCounterKey)
    var lastActive = defaults.boolForKey(ActionButtonActiveKey)

    if (lastActive) {
        app.components.pttEventRouter.handlePTTEvent(PTTEvent.BUTTON_DOWN)
    }

    while (true) {
        delay(250)
        val counter = defaults.integerForKey(ActionButtonCounterKey)
        if (counter == lastCounter) {
            continue
        }

        lastCounter = counter
        val active = defaults.boolForKey(ActionButtonActiveKey)
        if (active == lastActive) {
            continue
        }

        lastActive = active
        if (active) {
            app.components.pttEventRouter.handlePTTEvent(PTTEvent.BUTTON_DOWN)
        } else {
            app.components.pttEventRouter.handlePTTRelease()
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

@Composable
private fun StartupError(message: String) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Failed to start Gromozeka iOS client: $message")
        }
    }
}

private const val ActionButtonActiveKey = "gromozeka.actionButton.active"
private const val ActionButtonCounterKey = "gromozeka.actionButton.counter"
