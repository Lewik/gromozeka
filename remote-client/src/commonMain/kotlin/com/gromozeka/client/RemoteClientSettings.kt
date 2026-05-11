package com.gromozeka.client

import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class RemoteClientSettings(
    val protocolEncoding: RemoteProtocolEncoding = RemoteProtocolEncoding.CBOR,
)

interface RemoteClientSettingsStore {
    fun load(): RemoteClientSettings?
    fun save(settings: RemoteClientSettings)
}

class InMemoryRemoteClientSettingsStore : RemoteClientSettingsStore {
    private var settings: RemoteClientSettings? = null

    override fun load(): RemoteClientSettings? = settings

    override fun save(settings: RemoteClientSettings) {
        this.settings = settings
    }
}

class RemoteClientSettingsService internal constructor(
    private val client: GromozekaWsClient,
    private val store: RemoteClientSettingsStore,
    initialSettings: RemoteClientSettings,
) {
    private val _settingsFlow = MutableStateFlow(initialSettings)
    val settingsFlow: StateFlow<RemoteClientSettings> = _settingsFlow.asStateFlow()

    init {
        client.setEncoding(initialSettings.protocolEncoding)
    }

    fun saveSettings(settings: RemoteClientSettings) {
        store.save(settings)
        _settingsFlow.value = settings
        client.setEncoding(settings.protocolEncoding)
    }

    fun updateProtocolEncoding(encoding: RemoteProtocolEncoding) {
        saveSettings(_settingsFlow.value.copy(protocolEncoding = encoding))
    }
}
