package com.gromozeka.presentation

import com.gromozeka.presentation.services.DesktopClientAudioRecorder
import com.gromozeka.presentation.services.DesktopClientAudioPlayer
import com.gromozeka.presentation.services.DesktopSystemAudioMuteService
import com.gromozeka.presentation.services.DesktopLocalWhisperSpeechToTextService
import com.gromozeka.presentation.services.DesktopRemoteClientSettingsStore
import com.gromozeka.presentation.services.WindowStateService
import com.gromozeka.presentation.ui.ClientPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import io.ktor.client.HttpClient

internal suspend fun startRemotePresentation(
    remoteUrl: String,
    remoteClientSettingsStore: DesktopRemoteClientSettingsStore,
    httpClient: HttpClient? = null,
): RemoteStartedApp {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val clientHomeDirectory = System.getProperty("GROMOZEKA_CLIENT_HOME")
        ?: File(System.getProperty("user.home"), ".gromozeka-remote-client").absolutePath
    val remoteApp = try {
        createRemoteAppComponents(
            remoteUrl = remoteUrl,
            scope = scope,
            clientHomeDirectory = clientHomeDirectory,
            clientPlatform = ClientPlatform.DESKTOP,
            remoteClientSettingsStore = remoteClientSettingsStore,
            audioRecorder = DesktopClientAudioRecorder(),
            audioPlayer = DesktopClientAudioPlayer(),
            systemAudioMuteService = DesktopSystemAudioMuteService(),
            clientSideSpeechToTextServiceFactory = ::DesktopLocalWhisperSpeechToTextService,
            httpClient = httpClient,
        )
    } catch (error: Throwable) {
        scope.cancel()
        throw error
    }
    File(remoteApp.components.settingsService.homeDirectory).mkdirs()
    System.setProperty("GROMOZEKA_HOME", remoteApp.components.settingsService.homeDirectory)

    val windowStateService = WindowStateService(remoteApp.components.settingsService)
    return RemoteStartedApp(remoteApp, windowStateService, scope)
}

internal fun createDesktopRemoteClientSettingsStore(): DesktopRemoteClientSettingsStore {
    val clientHomeDirectory = System.getProperty("GROMOZEKA_CLIENT_HOME")
        ?: File(System.getProperty("user.home"), ".gromozeka-remote-client").absolutePath
    return DesktopRemoteClientSettingsStore(
        File(clientHomeDirectory, "remote-client-settings.json")
    )
}

internal class RemoteStartedApp(
    private val remoteApp: RemoteAppComponents,
    val windowStateService: WindowStateService,
    private val scope: CoroutineScope,
) : AutoCloseable {
    val components: AppComponents = remoteApp.components
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { remoteApp.close() }
        runCatching { runBlocking { components.appViewModel.cleanup() } }
        scope.cancel()
    }
}
