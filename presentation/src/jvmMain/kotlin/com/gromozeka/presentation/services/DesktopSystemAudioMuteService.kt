package com.gromozeka.presentation.services

import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DesktopSystemAudioMuteService : SystemAudioMuteService {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private var originalMuteState: Boolean? = null

    override suspend fun mute() {
        if (!isMacOs()) return

        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (originalMuteState != null) return@withLock

                runCatching {
                    val output = runAppleScript(
                        """
                        set currentMuted to output muted of (get volume settings)
                        if not currentMuted then set volume with output muted
                        return currentMuted
                        """.trimIndent()
                    )
                    originalMuteState = output.equals("true", ignoreCase = true)
                }.onFailure { error ->
                    log.warn(error) { "Failed to mute system audio for PTT: ${error.message}" }
                }
            }
        }
    }

    override suspend fun restore() {
        if (!isMacOs()) return

        withContext(Dispatchers.IO) {
            mutex.withLock {
                val wasMuted = originalMuteState ?: return@withLock
                originalMuteState = null

                if (!wasMuted) {
                    runCatching {
                        runAppleScript("set volume without output muted")
                    }.onFailure { error ->
                        log.warn(error) { "Failed to restore system audio after PTT: ${error.message}" }
                    }
                }
            }
        }
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").lowercase().contains("mac")

    private fun runAppleScript(script: String): String {
        val process = ProcessBuilder("osascript", "-e", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        require(exitCode == 0) { "osascript exited with code $exitCode: $output" }
        return output
    }
}
