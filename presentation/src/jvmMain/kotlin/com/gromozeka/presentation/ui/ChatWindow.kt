package com.gromozeka.presentation.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.presentation.AppComponents
import com.gromozeka.presentation.services.WindowStateService

@Composable
@Preview
fun ApplicationScope.ChatWindow(
    appComponents: AppComponents,
    windowStateService: WindowStateService,
    onExitRequest: () -> Unit = {},
    skipLoadingScreen: Boolean = false,
) {
    val settingsService = appComponents.settingsService
    val currentSettings by settingsService.settingsFlow.collectAsState()
    val windowSettings = (currentSettings.userDeviceSettings as? UserDeviceSettings.Desktop)?.windowSettings
        ?: UserDeviceSettings.DesktopWindowSettings()
    val savedWindowState = remember { windowStateService.loadWindowState() }

    val windowState = rememberWindowState(
        position = if (savedWindowState.x != -1 && savedWindowState.y != -1) {
            WindowPosition(savedWindowState.x.dp, savedWindowState.y.dp)
        } else {
            WindowPosition.PlatformDefault
        },
        size = DpSize(
            savedWindowState.width.dp,
            savedWindowState.height.dp
        )
    )

    Window(
        state = windowState,
        alwaysOnTop = windowSettings.alwaysOnTop,
        onCloseRequest = {
            windowStateService.saveWindowState(
                UiWindowState(
                    x = windowState.position.x.value.toInt(),
                    y = windowState.position.y.value.toInt(),
                    width = windowState.size.width.value.toInt(),
                    height = windowState.size.height.value.toInt(),
                    isMaximized = windowState.placement == WindowPlacement.Maximized
                )
            )
            onExitRequest()
        },
        title = buildString {
            append("Gromozeka")
            if (windowSettings.alwaysOnTop) {
                append(" [Always on Top]")
            }
            if (settingsService.mode == AppMode.DEV) {
                append(" [DEV]")
            }
            if (settingsService.mode == AppMode.TEST) {
                append(" [TEST]")
            }
        },
        icon = painterResource("logos/logo-256x256.png")
    ) {
        GromozekaApp(appComponents = appComponents, skipLoadingScreen = skipLoadingScreen)
    }
}
