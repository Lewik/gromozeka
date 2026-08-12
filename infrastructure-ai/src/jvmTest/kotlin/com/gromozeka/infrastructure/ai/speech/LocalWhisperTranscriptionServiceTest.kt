package com.gromozeka.infrastructure.ai.speech

import com.gromozeka.domain.model.UserProfile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalWhisperTranscriptionServiceTest {
    @Test
    fun cliCommandAppliesThreadAndAudioContextSettings() {
        val command = buildLocalWhisperCliCommand(
            settings = UserProfile.SpeechSettings.SpeechToText.LocalWhisper(
                executablePath = "whisper-cli",
                threadCount = 8,
                extraArguments = listOf("--no-gpu", "-bo", "1"),
            ),
            modelFile = File("/models/ggml-base.bin"),
            outputPrefix = File("/tmp/transcript"),
            wavFile = File("/tmp/input.wav"),
            language = "ru",
            prompt = "names: Gromozeka",
            audioBytes = 44 + (3.7 * 32_000).toInt(),
        )

        assertEquals("whisper-cli", command.first())
        assertContainsInOrder(command, listOf("-t", "8"))
        assertContainsInOrder(command, listOf("-ac", "313"))
        assertContainsInOrder(command, listOf("--no-gpu", "-bo", "1"))
        assertContainsInOrder(command, listOf("--prompt", "names: Gromozeka"))
        assertEquals("/tmp/input.wav", command.last())
    }

    private fun assertContainsInOrder(command: List<String>, expected: List<String>) {
        val start = command.windowed(expected.size).indexOf(expected)
        assertTrue(start >= 0, "Expected ${expected.joinToString(" ")} in ${command.joinToString(" ")}")
    }
}
