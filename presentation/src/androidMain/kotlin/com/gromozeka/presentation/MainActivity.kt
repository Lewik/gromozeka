package com.gromozeka.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.gromozeka.client.InMemoryRemoteClientSettingsStore
import com.gromozeka.presentation.services.InMemoryUIStateStore
import com.gromozeka.presentation.services.NoOpClientAudioRecorder
import com.gromozeka.presentation.ui.GromozekaApp

class MainActivity : ComponentActivity() {
    private var remoteApp: RemoteAppComponents? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val remoteUrl = intent.getStringExtra(EXTRA_REMOTE_URL) ?: BuildConfig.DEFAULT_REMOTE_URL

        setContent {
            GromozekaAndroidApp(
                remoteUrl = remoteUrl,
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
    remoteUrl: String,
    onRemoteAppStarted: (RemoteAppComponents) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var remoteApp by remember { mutableStateOf<RemoteAppComponents?>(null) }
    val currentRemoteApp by rememberUpdatedState(remoteApp)
    var startupError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(remoteUrl) {
        runCatching {
            createRemoteAppComponents(
                remoteUrl = remoteUrl,
                scope = scope,
                clientHomeDirectory = "android",
                uiStateStore = InMemoryUIStateStore(),
                remoteClientSettingsStore = InMemoryRemoteClientSettingsStore(),
                audioRecorder = NoOpClientAudioRecorder,
            )
        }.onSuccess { app ->
            remoteApp = app
            onRemoteAppStarted(app)
        }.onFailure { error ->
            startupError = error.message ?: error.toString()
        }
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
            uiScaleMultiplier = 1.15f,
            showPromptsPanelInitially = false,
            forceCompactLayout = true,
        )

        startupError != null -> StartupError(startupError!!)
        else -> StartupLoading()
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
            Text("Failed to start Gromozeka Android client: $message")
        }
    }
}
