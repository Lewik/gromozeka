package com.gromozeka.client

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.AppMode
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
    override val sttMainLanguage: String get() = settings.sttMainLanguage
    override val ttsModel: String get() = settings.ttsModel
    override val ttsVoice: String get() = settings.ttsVoice
    override val ttsSpeed: Float get() = settings.ttsSpeed
    override val aiProvider: AIProvider get() = settings.defaultAiProvider
    override val mode: AppMode get() = AppMode.PRODUCTION
    override val enableBraveSearch: Boolean get() = settings.enableBraveSearch
    override val braveApiKey: String? get() = settings.braveApiKey
    override val enableJinaReader: Boolean get() = settings.enableJinaReader
    override val jinaApiKey: String? get() = settings.jinaApiKey

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
