package com.gromozeka.bot.platform

import klog.KLoggers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

@Service
class MacOSSystemAudioController : SystemAudioController {
    private val log = KLoggers.logger(this)

    override suspend fun mute(): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = """
                set currentMuted to output muted of (get volume settings)
                if not currentMuted then set volume with output muted
                return currentMuted
            """.trimIndent()

            val process = ProcessBuilder("osascript", "-e", script).start()
            val originalMuteState = process.inputStream.bufferedReader().use { reader ->
                val output = reader.readText().trim()
                process.waitFor()
                output.equals("true", ignoreCase = true)
            }

            log.info("Audio muted, original state was: $originalMuteState")
            true
        } catch (e: Exception) {
            log.error("Failed to mute audio: ${e.message}")
            false
        }
    }

    override suspend fun unmute(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("osascript", "-e", "set volume without output muted").start()
            process.waitFor()
            log.info("Audio unmuted")
            true
        } catch (e: Exception) {
            log.error("Failed to unmute audio: ${e.message}")
            false
        }
    }

    override suspend fun isSystemMuted(): Boolean = withContext(Dispatchers.IO) {
        try {
            val script = "output muted of (get volume settings)"
            val process = ProcessBuilder("osascript", "-e", script).start()

            process.inputStream.bufferedReader().use { reader ->
                val output = reader.readText().trim()
                process.waitFor()
                output.equals("true", ignoreCase = true)
            }
        } catch (e: Exception) {
            log.error("Failed to check mute state: ${e.message}")
            false
        }
    }

    override suspend fun setVolume(level: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            val volumeLevel = (level * 100).toInt().coerceIn(0, 100)
            val script = "set volume output volume $volumeLevel"
            val process = ProcessBuilder("osascript", "-e", script).start()
            process.waitFor()
            log.info("Volume set to: $volumeLevel%")
            true
        } catch (e: Exception) {
            log.error("Failed to set volume: ${e.message}")
            false
        }
    }

    override suspend fun getVolume(): Float? = withContext(Dispatchers.IO) {
        try {
            val script = "output volume of (get volume settings)"
            val process = ProcessBuilder("osascript", "-e", script).start()

            process.inputStream.bufferedReader().use { reader ->
                val output = reader.readText().trim()
                process.waitFor()
                output.toFloatOrNull()?.div(100f)
            }
        } catch (e: Exception) {
            log.error("Failed to get volume: ${e.message}")
            null
        }
    }
}