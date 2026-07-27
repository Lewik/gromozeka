package com.gromozeka.presentation.services

import android.content.Context
import com.gromozeka.client.RemoteClientSettings
import com.gromozeka.client.RemoteClientSettingsStore
import kotlinx.serialization.json.Json

class AndroidRemoteClientSettingsStore(
    context: Context,
) : RemoteClientSettingsStore {
    private val preferences = context.getSharedPreferences("gromozeka.remote-client", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): RemoteClientSettings? =
        runCatching {
            preferences.getString(SETTINGS_KEY, null)
                ?.let { json.decodeFromString<RemoteClientSettings>(it) }
        }.getOrNull()

    override fun save(settings: RemoteClientSettings) {
        preferences.edit()
            .putString(SETTINGS_KEY, json.encodeToString(settings))
            .apply()
    }

    private companion object {
        const val SETTINGS_KEY = "settings"
    }
}
