package com.gromozeka.bot.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

@Service
class AudioMuteManager {
    
    private var originalMuteState: Boolean? = null
    private var isMutedByUs = false
    
    suspend fun mute(): Unit = withContext(Dispatchers.IO) {
        // Prevent double muting
        if (isMutedByUs) {
            println("[AudioMute] Already muted by us, ignoring mute request")
            return@withContext
        }
        
        try {
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
            
            isMutedByUs = true
            println("[AudioMute] Audio muted, original state was: ${originalMuteState}")
            
        } catch (e: Exception) {
            println("[AudioMute] Failed to mute audio: ${e.message}")
            throw e
        }
    }
    
    suspend fun restoreOriginalState(): Unit = withContext(Dispatchers.IO) {
        if (!isMutedByUs) {
            println("[AudioMute] Not muted by us, ignoring restore request")
            return@withContext
        }
        
        try {
            originalMuteState?.let { wasMuted ->
                if (!wasMuted) {
                    val process = ProcessBuilder("osascript", "-e", "set volume without output muted").start()
                    process.waitFor()
                    println("[AudioMute] Audio unmuted (restored to original state)")
                } else {
                    println("[AudioMute] Audio kept muted (was originally muted)")
                }
            }
        } catch (e: Exception) {
            println("[AudioMute] Failed to restore audio state: ${e.message}")
        } finally {
            // ALWAYS reset state even if restore failed
            originalMuteState = null
            isMutedByUs = false
        }
    }
}