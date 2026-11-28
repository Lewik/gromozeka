package com.gromozeka.shared.audio

data class AudioConfig(
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val bitDepth: Int = 16,
    val chunkSizeBytes: Int = 2048,
    val outputFormat: AudioOutputFormat = AudioOutputFormat.WAV,
    val retainRecording: Boolean = true,
)

enum class AudioOutputFormat {
    WAV,
    AU,
    AIFF,
    RAW_PCM
}