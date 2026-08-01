package com.gromozeka.presentation.services

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.service.SettingsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceSoundNotificationPlayerTest {
    @Test
    fun loadsSharedSoundResourcesWithConfiguredVolume() = runBlocking {
        val audioPlayer = RecordingAudioPlayer()
        val settingsService = TestSettingsService(
            Settings(
                userDeviceSettings = UserDeviceSettings.Desktop(
                    soundSettings = UserDeviceSettings.SoundSettings(volume = 0.6f)
                )
            )
        )
        val player = ResourceSoundNotificationPlayer(audioPlayer, settingsService) { false }

        player.playAttentionSound()
        player.playActivitySound()
        player.playErrorSound()

        assertEquals(3, audioPlayer.playbacks.size)
        assertTrue(audioPlayer.playbacks.all { it.data.size > 1_000 })
        assertTrue(audioPlayer.playbacks.all { it.volume == 0.6f })
    }

    @Test
    fun skipsActivityWhileTtsIsPlaying() = runBlocking {
        val audioPlayer = RecordingAudioPlayer()
        val player = ResourceSoundNotificationPlayer(audioPlayer, TestSettingsService(Settings())) { true }

        player.playActivitySound()

        assertTrue(audioPlayer.playbacks.isEmpty())
    }

    private data class Playback(val data: ByteArray, val volume: Float)

    private class RecordingAudioPlayer : ClientAudioPlayer {
        val playbacks = mutableListOf<Playback>()

        override suspend fun playAudio(data: ByteArray, mediaType: String, fileExtension: String, volume: Float) {
            playbacks += Playback(data, volume)
        }

        override suspend fun playPcmStream(
            chunks: Flow<ByteArray>,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int,
        ) = Unit

        override fun stop() = Unit
    }

    private class TestSettingsService(initial: Settings) : SettingsService {
        private val mutableSettings = MutableStateFlow(initial)

        override val settingsFlow: StateFlow<Settings> = mutableSettings
        override val settings: Settings get() = mutableSettings.value
        override val userProfile get() = settings.userProfile
        override val userDeviceSettings get() = settings.userDeviceSettings
        override val mode: AppMode = AppMode.PRODUCTION
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
