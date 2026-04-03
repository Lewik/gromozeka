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

    var initializationError: Throwable? = null
    var startedApp: StartedApp? = null

    try {
        log.info("Initializing Spring context...")
        startedApp = AppBootstrap.start()
    } catch (e: Throwable) {
        log.error("Failed to initialize application: ${e.message}")
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

            startedApp != null -> {
                ChatWindow(
                    appComponents = startedApp!!.appComponents,
                    onExitRequest = {
                        startedApp?.close()
                        exitApplication()
                    }
                )
            }
        }
    }
}
