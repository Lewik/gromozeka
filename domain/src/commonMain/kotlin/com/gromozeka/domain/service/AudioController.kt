package com.gromozeka.domain.service

/**
 * Audio playback controller.
 * 
 * Platform-agnostic interface for audio file playback.
 * 
 * @see playAudioFile for starting playback
 * @see stopPlayback for stopping current playback
 */
interface AudioController {
    
    /**
     * Plays audio file at specified path.
     * 
     * Blocking operation - waits until playback completes or is cancelled.
     * Automatically stops any existing playback before starting new one.
     * 
     * @param filePath absolute path to audio file (e.g., "/tmp/audio.mp3")
     * @throws kotlinx.coroutines.CancellationException if playback is cancelled
     */
    suspend fun playAudioFile(filePath: String)
    
    /**
     * Stops current audio playback if any.
     * 
     * Safe to call when no playback is active.
     * Forcibly terminates playback process.
     */
    suspend fun stopPlayback()
}
