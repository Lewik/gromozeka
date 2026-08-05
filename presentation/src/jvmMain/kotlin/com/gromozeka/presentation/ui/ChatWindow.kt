package com.gromozeka.presentation.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
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
import com.gromozeka.presentation.services.DesktopAttachmentAcquisitionController
import com.gromozeka.presentation.services.WindowStateService
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.io.File

@Composable
@Preview
@OptIn(ExperimentalComposeUiApi::class)
fun ApplicationScope.ChatWindow(
    appComponents: AppComponents,
    windowStateService: WindowStateService,
    onExitRequest: () -> Unit = {},
    visible: Boolean = true,
    skipLoadingScreen: Boolean = false,
) {
    val settingsService = appComponents.settingsService
    val currentSettings by settingsService.settingsFlow.collectAsState()
    val windowSettings = (currentSettings.userDeviceSettings as? UserDeviceSettings.Desktop)?.windowSettings
        ?: UserDeviceSettings.DesktopWindowSettings()
    val savedWindowState = remember { windowStateService.loadWindowState() }
    val coroutineScope = rememberCoroutineScope()
    val attachmentController = remember(appComponents.appViewModel) {
        appComponents.appViewModel.attachmentAcquisitionController as? DesktopAttachmentAcquisitionController
    }
    val fileDropTarget = remember(attachmentController) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val controller = attachmentController ?: return false
                val files = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
                        .filterIsInstance<File>()
                }.getOrElse { return false }
                if (files.isEmpty()) return false
                coroutineScope.launch { controller.acceptDroppedFiles(files) }
                return true
            }
        }
    }

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
        visible = visible,
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
        LaunchedEffect(visible) {
            if (visible) {
                window.toFront()
                window.requestFocus()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { event ->
                        attachmentController != null && runCatching {
                            event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        }.getOrDefault(false)
                    },
                    target = fileDropTarget,
                ),
        ) {
            GromozekaApp(appComponents = appComponents, skipLoadingScreen = skipLoadingScreen)
        }
    }
}
