package com.gromozeka.presentation.services

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.service.SettingsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLocalWhisperSpeechToTextServiceTest {
    @Test
    fun `does not intercept ordinary transcription when Local Whisper has a Server target`() {
        val service = DesktopLocalWhisperSpeechToTextService(
            TestSettingsService(localWhisperSettings(AiExecutionTarget.Server))
        )

        assertFalse(service.isEnabled())
        assertTrue(service.isAvailable())
    }

    @Test
    fun `does not intercept ordinary transcription when Local Whisper has a Worker target`() {
        val service = DesktopLocalWhisperSpeechToTextService(
            TestSettingsService(localWhisperSettings(AiExecutionTarget.Worker("worker-1")))
        )

        assertFalse(service.isEnabled())
        assertTrue(service.isAvailable())
    }

    @Test
    fun `is unavailable when another speech-to-text engine is selected`() {
        val service = DesktopLocalWhisperSpeechToTextService(TestSettingsService(Settings()))

        assertFalse(service.isEnabled())
        assertFalse(service.isAvailable())
    }

    private fun localWhisperSettings(target: AiExecutionTarget): Settings =
        Settings(
            userProfile = UserProfile(
                speechSettings = UserProfile.SpeechSettings(
                    speechToText = UserProfile.SpeechSettings.SpeechToText(
                        enabled = true,
                        engine = UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER,
                        localWhisper = UserProfile.SpeechSettings.SpeechToText.LocalWhisper(
                            executionTarget = target
                        ),
                    )
                )
            )
        )

    private class TestSettingsService(initial: Settings) : SettingsService {
        private val mutableSettings = MutableStateFlow(initial)

        override val settingsFlow: StateFlow<Settings> = mutableSettings
        override val settings: Settings get() = mutableSettings.value
        override val userProfile get() = settings.userProfile
        override val userDeviceSettings get() = settings.userDeviceSettings
        override val mode: AppMode = AppMode.TEST
        override val homeDirectory: String = "/tmp/gromozeka-test"

        override fun saveSettings(settings: Settings) {
            mutableSettings.value = settings
        }

        override fun saveSettings(block: Settings.() -> Settings) {
            saveSettings(settings.block())
        }

        override fun reloadSettings() = Unit
    }
}
