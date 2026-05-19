package com.gromozeka.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface UserDeviceSettings {
    val uiSettings: UiSettings
    val soundSettings: SoundSettings
    val showSystemMessages: Boolean
    val showOriginalJson: Boolean

    @Serializable
    @SerialName("desktop")
    data class Desktop(
        override val uiSettings: UiSettings = UiSettings(),
        override val soundSettings: SoundSettings = SoundSettings(),
        override val showSystemMessages: Boolean = true,
        override val showOriginalJson: Boolean = false,
        val inputSettings: DesktopInputSettings = DesktopInputSettings(),
        val windowSettings: DesktopWindowSettings = DesktopWindowSettings(),
    ) : UserDeviceSettings

    @Serializable
    @SerialName("android")
    data class Android(
        override val uiSettings: UiSettings = UiSettings(),
        override val soundSettings: SoundSettings = SoundSettings(),
        override val showSystemMessages: Boolean = true,
        override val showOriginalJson: Boolean = false,
        val keepScreenAwakeDuringVoice: Boolean = true,
    ) : UserDeviceSettings

    @Serializable
    @SerialName("ios")
    data class Ios(
        override val uiSettings: UiSettings = UiSettings(),
        override val soundSettings: SoundSettings = SoundSettings(),
        override val showSystemMessages: Boolean = true,
        override val showOriginalJson: Boolean = false,
        val keepScreenAwakeDuringVoice: Boolean = true,
    ) : UserDeviceSettings

    @Serializable
    @SerialName("web")
    data class Web(
        override val uiSettings: UiSettings = UiSettings(),
        override val soundSettings: SoundSettings = SoundSettings(),
        override val showSystemMessages: Boolean = true,
        override val showOriginalJson: Boolean = false,
    ) : UserDeviceSettings

    @Serializable
    data class UiSettings(
        val languageCode: String = "en",
        val theme: ThemeSettings = ThemeSettings(),
        val showTabsAtBottom: Boolean = false,
        val uiScale: Float = 1.0f,
        val fontScale: Float = 1.0f,
    ) {
        init {
            require(languageCode.isNotBlank()) { "Device language code must not be blank" }
            require(uiScale > 0.0f) { "UI scale must be positive" }
            require(fontScale > 0.0f) { "Font scale must be positive" }
        }
    }

    @Serializable
    data class ThemeSettings(
        val id: String = "dark",
        val overrideEnabled: Boolean = false,
    ) {
        init {
            require(id.isNotBlank()) { "Theme id must not be blank" }
        }
    }

    @Serializable
    data class SoundSettings(
        val errorSoundsEnabled: Boolean = false,
        val messageSoundsEnabled: Boolean = false,
        val readySoundsEnabled: Boolean = false,
        val volume: Float = 1.0f,
    ) {
        init {
            require(volume in 0.0f..1.0f) { "Sound volume must be between 0.0 and 1.0" }
        }
    }

    @Serializable
    data class DesktopInputSettings(
        val autoSend: Boolean = false,
        val globalPttHotkeyEnabled: Boolean = false,
        val muteSystemAudioDuringPtt: Boolean = true,
    )

    @Serializable
    data class DesktopWindowSettings(
        val alwaysOnTop: Boolean = false,
    )
}
