package com.gromozeka.presentation

import androidx.compose.ui.window.application
import com.gromozeka.presentation.ui.ChatWindow
import com.gromozeka.presentation.ui.ErrorDialog
import klog.KLoggers
import kotlin.system.exitProcess

fun getTabDisplayName(tabUiState: com.gromozeka.presentation.ui.state.UIState.Tab, index: Int): String {
    return tabUiState.customName?.takeIf { it.isNotBlank() }
        ?: tabUiState.agent.name
}

fun main() {
    val log = KLoggers.logger("ChatApplication")
    System.setProperty("java.awt.headless", "false")

    val remoteUrl = System.getProperty("gromozeka.remote.url")
        ?: System.getenv("GROMOZEKA_REMOTE_URL")
        ?: "ws://127.0.0.1:8765/ws"

    var initializationError: Throwable? = null
    var remoteApp: RemoteStartedApp? = null

    try {
        log.info("Initializing remote UI client: $remoteUrl")
        remoteApp = startRemotePresentation(remoteUrl)
    } catch (e: Throwable) {
        log.error("Failed to initialize remote UI client: ${e.message}")
        e.printStackTrace()
        initializationError = e
    }

    log.info("Starting Compose Desktop UI...")
    application {
        when {
            initializationError != null -> {
                ErrorDialog(
                    error = initializationError,
                    onClose = { exitProcess(1) }
                )
            }

            remoteApp != null -> {
                ChatWindow(
                    appComponents = remoteApp.components,
                    skipLoadingScreen = true,
                    onExitRequest = {
                        remoteApp.close()
                        exitApplication()
                    }
                )
            }
        }
    }
}
