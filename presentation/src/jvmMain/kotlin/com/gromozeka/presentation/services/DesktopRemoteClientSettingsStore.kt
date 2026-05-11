package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteClientSettings
import com.gromozeka.client.RemoteClientSettingsStore
import kotlinx.serialization.json.Json
import java.io.File

class DesktopRemoteClientSettingsStore(
    private val file: File,
) : RemoteClientSettingsStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): RemoteClientSettings? =
        runCatching {
            if (!file.exists()) null else json.decodeFromString<RemoteClientSettings>(file.readText())
        }.getOrNull()

    override fun save(settings: RemoteClientSettings) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(settings))
        }
    }
}
