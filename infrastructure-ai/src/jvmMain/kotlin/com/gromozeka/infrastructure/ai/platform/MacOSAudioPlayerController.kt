package com.gromozeka.infrastructure.ai.platform

import klog.KLoggers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.File

@Service
class MacOSAudioPlayerController : AudioPlayerController {
    private val log = KLoggers.logger(this)

    @Volatile
    private var currentProcess: Process? = null

    override suspend fun playAudioFile(audioFile: File) = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            // Kill any existing playback first
            stopPlayback()

            process = ProcessBuilder("afplay", audioFile.absolutePath)
                .start()

            currentProcess = process
            log.info("Started playing: ${audioFile.name}")

            // Cancellation-aware waiting
            while (process.isAlive) {
                ensureActive() // Check cancellation
                Thread.sleep(50) // Short intervals for quick response
            }

            log.info("Finished playing: ${audioFile.name}")

        } catch (e: CancellationException) {
            log.info("Audio playback cancelled")
            process?.destroyForcibly()
            throw e
        } catch (e: Exception) {
            log.error("Error playing audio: ${e.message}")
            e.printStackTrace()
        } finally {
            if (currentProcess == process) {
                currentProcess = null
            }
            process?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }

    override suspend fun stopPlayback(): Unit = withContext(Dispatchers.IO) {
        currentProcess?.let { process ->
            try {
                if (process.isAlive) {
                    log.info("Stopping current playback")
                    process.destroyForcibly()
                    process.waitFor() // Ensure process is fully stopped
                }
            } catch (e: Exception) {
                log.error("Error stopping playback: ${e.message}")
            } finally {
                currentProcess = null
            }
        }
    }

    override fun isPlaying(): Boolean {
        return currentProcess?.isAlive == true
    }
}