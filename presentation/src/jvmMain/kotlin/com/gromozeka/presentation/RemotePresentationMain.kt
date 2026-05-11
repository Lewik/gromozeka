package com.gromozeka.presentation

import com.gromozeka.presentation.services.WindowStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File

internal fun startRemotePresentation(remoteUrl: String): RemoteStartedApp {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val clientHomeDirectory = System.getProperty("GROMOZEKA_CLIENT_HOME")
        ?: File(System.getProperty("user.home"), ".gromozeka-remote-client").absolutePath
    val remoteApp = runBlocking {
        createRemoteAppComponents(
            remoteUrl = remoteUrl,
            scope = scope,
            clientHomeDirectory = clientHomeDirectory,
        )
    }
    File(remoteApp.components.settingsService.homeDirectory).mkdirs()
    System.setProperty("GROMOZEKA_HOME", remoteApp.components.settingsService.homeDirectory)

    val windowStateService = WindowStateService(remoteApp.components.settingsService)
    return RemoteStartedApp(remoteApp, windowStateService, scope)
}

internal class RemoteStartedApp(
    private val remoteApp: RemoteAppComponents,
    val windowStateService: WindowStateService,
    private val scope: CoroutineScope,
) : AutoCloseable {
    val components: AppComponents = remoteApp.components

    override fun close() {
        runCatching { remoteApp.close() }
        runCatching { runBlocking { components.appViewModel.cleanup() } }
        scope.cancel()
    }
}
