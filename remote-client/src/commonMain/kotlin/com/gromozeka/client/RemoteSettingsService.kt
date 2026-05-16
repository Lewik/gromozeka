package com.gromozeka.client

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.remote.protocol.GetSettingsRequest
import com.gromozeka.remote.protocol.SaveSettingsRequest
import com.gromozeka.remote.protocol.SavedResponse
import com.gromozeka.remote.protocol.SettingsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class RemoteSettingsService(
    private val client: GromozekaWsClient,
    private val scope: CoroutineScope,
    override val homeDirectory: String,
) : SettingsService {
    private val _settingsFlow = MutableStateFlow(Settings())

    override val settingsFlow: StateFlow<Settings> = _settingsFlow.asStateFlow()
    override val settings: Settings get() = _settingsFlow.value
    override val userProfile get() = settings.userProfile
    override val userDeviceSettings get() = settings.userDeviceSettings
    override val mode: AppMode get() = AppMode.PRODUCTION

    override fun resolveSecret(secretRef: SecretRef?): String? = when (secretRef) {
        null -> null
        is SecretRef.Inline -> secretRef.value
        is SecretRef.EnvironmentVariable -> null
    }

    suspend fun refreshFromServer(): Settings =
        client.requestTyped<GetSettingsRequest, SettingsResponse>(GetSettingsRequest).settings.also {
            _settingsFlow.value = it
        }

    override fun saveSettings(settings: Settings) {
        _settingsFlow.value = settings
        scope.launch {
            client.requestTyped<SaveSettingsRequest, SavedResponse>(SaveSettingsRequest(settings))
        }
    }

    override fun saveSettings(block: Settings.() -> Settings) {
        saveSettings(settings.block())
    }

    override fun reloadSettings() {
        scope.launch { refreshFromServer() }
    }
}
