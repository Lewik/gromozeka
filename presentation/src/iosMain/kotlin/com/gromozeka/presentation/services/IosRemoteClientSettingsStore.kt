package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteClientSettings
import com.gromozeka.client.RemoteClientSettingsStore
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

class IosRemoteClientSettingsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val key: String = "gromozeka.remoteClientSettings",
) : RemoteClientSettingsStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): RemoteClientSettings? =
        runCatching {
            defaults.stringForKey(key)?.let { json.decodeFromString<RemoteClientSettings>(it) }
        }.getOrNull()

    override fun save(settings: RemoteClientSettings) {
        runCatching {
            defaults.setObject(json.encodeToString(settings), forKey = key)
        }
    }
}
