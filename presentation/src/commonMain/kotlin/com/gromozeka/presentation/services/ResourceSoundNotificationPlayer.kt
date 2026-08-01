package com.gromozeka.presentation.services

import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.resources.Res
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalResourceApi::class)
class ResourceSoundNotificationPlayer(
    private val audioPlayer: ClientAudioPlayer,
    private val settingsService: SettingsService,
    private val isTtsPlaying: () -> Boolean,
) : SoundNotificationPlayer {
    private val log = KLoggers.logger(this)
    private val playbackMutex = Mutex()
    private val resourceMutex = Mutex()
    private val resources = mutableMapOf<NotificationSound, ByteArray>()

    override suspend fun playAttentionSound() {
        val settings = settingsService.userDeviceSettings.soundSettings
        if (!settings.attentionSoundsEnabled) return
        play(NotificationSound.ATTENTION, settings)
    }

    override suspend fun playActivitySound() {
        val settings = settingsService.userDeviceSettings.soundSettings
        if (!settings.activitySoundsEnabled || isTtsPlaying()) return
        if (!playbackMutex.tryLock()) return
        try {
            playUnlocked(NotificationSound.ACTIVITY, settings)
        } finally {
            playbackMutex.unlock()
        }
    }

    override suspend fun playErrorSound() {
        val settings = settingsService.userDeviceSettings.soundSettings
        if (!settings.errorSoundsEnabled) return
        play(NotificationSound.ERROR, settings)
    }

    private suspend fun play(sound: NotificationSound, settings: UserDeviceSettings.SoundSettings) {
        playbackMutex.withLock {
            playUnlocked(sound, settings)
        }
    }

    private suspend fun playUnlocked(sound: NotificationSound, settings: UserDeviceSettings.SoundSettings) {
        try {
            audioPlayer.playAudio(
                data = load(sound),
                mediaType = "audio/wav",
                fileExtension = "wav",
                volume = settings.volume,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) { "Failed to play ${sound.name.lowercase()} sound: ${error.message}" }
        }
    }

    private suspend fun load(sound: NotificationSound): ByteArray = resourceMutex.withLock {
        resources.getOrPut(sound) {
            Res.readBytes(sound.resourcePath)
        }
    }

    private enum class NotificationSound(val resourcePath: String) {
        ATTENTION("files/sounds/attention.wav"),
        ACTIVITY("files/sounds/activity.wav"),
        ERROR("files/sounds/error.wav"),
    }
}
