package com.gromozeka.domain.model

import com.gromozeka.shared.audio.SpeechPcmWav
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SpeechAudioFormat(
    val mediaType: String,
    val fileExtension: String,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
) {
    @SerialName("wav_pcm_s16le_mono_16khz")
    WAV_PCM_S16LE_MONO_16_KHZ(
        mediaType = SpeechPcmWav.MEDIA_TYPE,
        fileExtension = SpeechPcmWav.FILE_EXTENSION,
        sampleRate = SpeechPcmWav.SAMPLE_RATE,
        channels = SpeechPcmWav.CHANNELS,
        bitDepth = SpeechPcmWav.BITS_PER_SAMPLE,
    );

    fun requireValid(audioData: ByteArray) {
        when (this) {
            WAV_PCM_S16LE_MONO_16_KHZ -> SpeechPcmWav.requireValid(audioData)
        }
    }
}
