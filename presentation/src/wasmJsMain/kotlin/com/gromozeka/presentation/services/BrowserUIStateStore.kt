package com.gromozeka.presentation.services

import com.gromozeka.presentation.ui.state.UIState
import kotlinx.browser.window
import kotlinx.serialization.json.Json

class BrowserUIStateStore(
    private val key: String = "gromozeka.uiState",
) : UIStateStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): UIState? =
        runCatching {
            window.localStorage.getItem(key)?.let { json.decodeFromString<UIState>(it) }
        }.getOrNull()

    override fun save(state: UIState) {
        runCatching {
            window.localStorage.setItem(key, json.encodeToString(state))
        }
    }
}
