package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteClientSettings
import com.gromozeka.client.RemoteClientSettingsStore
import kotlinx.browser.window
import kotlinx.serialization.json.Json

class BrowserRemoteClientSettingsStore(
    private val key: String = "gromozeka.remoteClientSettings",
) : RemoteClientSettingsStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): RemoteClientSettings? =
        runCatching {
            window.localStorage.getItem(key)?.let { json.decodeFromString<RemoteClientSettings>(it) }
        }.getOrNull()

    override fun save(settings: RemoteClientSettings) {
        runCatching {
            window.localStorage.setItem(key, json.encodeToString(settings))
        }
    }
}
