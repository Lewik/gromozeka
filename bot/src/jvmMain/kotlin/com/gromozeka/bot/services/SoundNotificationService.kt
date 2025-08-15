package com.gromozeka.bot.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem

@Service
class SoundNotificationService(
    private val settingsService: SettingsService
) {
    private val errorSoundPath = "/sounds/error.wav"
    private val messageSoundPath = "/sounds/message.wav"
    private val readySoundPath = "/sounds/ready.wav"

    suspend fun playErrorSound() {
        if (settingsService.settings.enableErrorSounds) {
            playSound(errorSoundPath)
        }
    }

    suspend fun playMessageSound() {
        if (settingsService.settings.enableMessageSounds) {
            playSound(messageSoundPath)
        }
    }

    suspend fun playReadySound() {
        if (settingsService.settings.enableReadySounds) {
            playSound(readySoundPath)
        }
    }

    private suspend fun playSound(resourcePath: String) = withContext(Dispatchers.IO) {
        try {
            val audioInputStream = javaClass.getResourceAsStream(resourcePath)?.let { inputStream ->
                AudioSystem.getAudioInputStream(BufferedInputStream(inputStream))
            } ?: return@withContext

            val clip = AudioSystem.getClip()
            clip.open(audioInputStream)
            
            // Apply volume control
            val volume = settingsService.settings.soundVolume
            if (clip.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) {
                val gainControl = clip.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN) as javax.sound.sampled.FloatControl
                val range = gainControl.maximum - gainControl.minimum
                val gain = gainControl.minimum + range * volume
                gainControl.value = gain
            }
            
            clip.start()

            // Don't block - let sound play asynchronously
            audioInputStream.close()
        } catch (e: Exception) {
            // Silently ignore sound playback errors
            // We don't want sound issues to affect main functionality
        }
    }
}