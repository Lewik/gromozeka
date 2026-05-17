package com.gromozeka.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalWhisperSettingsTest {
    @Test
    fun audioContextScalesShortWavWithoutExceedingNativeWindow() {
        val settings = UserProfile.SpeechSettings.SpeechToText.LocalWhisper()

        assertEquals(256, settings.audioContextFramesForWavBytes(audioBytes = 44 + (1.9 * 32_000).toInt()))
        assertEquals(313, settings.audioContextFramesForWavBytes(audioBytes = 44 + (3.7 * 32_000).toInt()))
        assertEquals(1_500, settings.audioContextFramesForWavBytes(audioBytes = 44 + 60 * 32_000))
    }

    @Test
    fun audioContextCanBeDisabled() {
        val settings = UserProfile.SpeechSettings.SpeechToText.LocalWhisper(
            audioContext = UserProfile.SpeechSettings.SpeechToText.LocalWhisper.AudioContext(enabled = false)
        )

        assertNull(settings.audioContextFramesForWavBytes(audioBytes = 44 + 16_000))
    }
}
