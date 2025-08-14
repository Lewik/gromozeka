package com.gromozeka.bot.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem

@Service
class SoundNotificationService {
    private val errorSoundPath = "/sounds/error.wav"
    private val messageSoundPath = "/sounds/message.wav"

    suspend fun playErrorSound() = playSound(errorSoundPath)
    suspend fun playMessageSound() = playSound(messageSoundPath)

    private suspend fun playSound(resourcePath: String) = withContext(Dispatchers.IO) {
        try {
            val audioInputStream = javaClass.getResourceAsStream(resourcePath)?.let { inputStream ->
                AudioSystem.getAudioInputStream(BufferedInputStream(inputStream))
            } ?: return@withContext

            val clip = AudioSystem.getClip()
            clip.open(audioInputStream)
            clip.start()

            // Don't block - let sound play asynchronously
            audioInputStream.close()
        } catch (e: Exception) {
            // Silently ignore sound playback errors
            // We don't want sound issues to affect main functionality
        }
    }
}