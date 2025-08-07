package com.gromozeka.bot.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

@Service
class AudioMuteManager {
    
    private var originalMuteState: Boolean? = null
    
    suspend fun muteIfNeeded(): Unit = withContext(Dispatchers.IO) {
        val script = """
            set currentMuted to output muted of (get volume settings)
            if not currentMuted then set volume with output muted
            return currentMuted
        """.trimIndent()
        
        val process = ProcessBuilder("osascript", "-e", script).start()
        
        originalMuteState = process.inputStream.bufferedReader().use { reader ->
            val output = reader.readText().trim()
            process.waitFor()
            output.equals("true", ignoreCase = true)
        }
    }
    
    suspend fun restoreOriginalState(): Unit = withContext(Dispatchers.IO) {
        originalMuteState?.let { wasMuted ->
            if (!wasMuted) {
                val process = ProcessBuilder("osascript", "-e", "set volume without output muted").start()
                process.waitFor()
            }
            originalMuteState = null
        }
    }
}