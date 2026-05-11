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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import com.gromozeka.presentation.services.BrowserUIStateStore
import com.gromozeka.presentation.ui.GromozekaApp
import kotlinx.browser.window

private data class WebLayoutHints(
    val uiScaleMultiplier: Float,
    val showPromptsPanelInitially: Boolean,
    val forceCompactLayout: Boolean,
)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("composeApp") {
        GromozekaWebApp()
    }
}

@Composable
private fun GromozekaWebApp() {
    val scope = rememberCoroutineScope()
    val layoutHints = remember { resolveWebLayoutHints() }
    var remoteApp by remember { mutableStateOf<RemoteAppComponents?>(null) }
    val currentRemoteApp by rememberUpdatedState(remoteApp)
    var startupError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            createRemoteAppComponents(
                remoteUrl = resolveRemoteUrl(),
                scope = scope,
                clientHomeDirectory = "browser",
                uiStateStore = BrowserUIStateStore(),
            )
        }.onSuccess { app ->
            remoteApp = app
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
            uiScaleMultiplier = layoutHints.uiScaleMultiplier,
            showPromptsPanelInitially = layoutHints.showPromptsPanelInitially,
            forceCompactLayout = layoutHints.forceCompactLayout,
        )
        startupError != null -> StartupError(startupError!!)
        else -> StartupLoading()
    }
}

private fun resolveRemoteUrl(): String {
    val protocol = if (window.location.protocol == "https:") "wss" else "ws"
    return "$protocol://${window.location.host}/ws"
}

private fun resolveWebLayoutHints(): WebLayoutHints {
    val hasTouch = window.navigator.maxTouchPoints > 0
    val compactScreen = window.screen.width <= 430 || window.innerWidth <= 430
    val tabletScreen = window.screen.width <= 820 || window.innerWidth <= 820

    return when {
        compactScreen -> WebLayoutHints(
            uiScaleMultiplier = 1.25f,
            showPromptsPanelInitially = false,
            forceCompactLayout = true,
        )
        hasTouch && tabletScreen -> WebLayoutHints(
            uiScaleMultiplier = 1.15f,
            showPromptsPanelInitially = false,
            forceCompactLayout = true,
        )
        else -> WebLayoutHints(
            uiScaleMultiplier = 1.0f,
            showPromptsPanelInitially = true,
            forceCompactLayout = false,
        )
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
            Text("Failed to start Gromozeka web client: $message")
        }
    }
}
