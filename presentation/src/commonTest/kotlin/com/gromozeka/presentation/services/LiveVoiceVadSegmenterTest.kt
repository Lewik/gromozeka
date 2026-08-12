package com.gromozeka.presentation.services

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveVoiceVadSegmenterTest {
    private val config = LiveVoiceVadConfig(
        preRollMillis = 200,
        silenceMillis = 200,
        minSpeechMillis = 200,
        maxUtteranceMillis = 5_000,
    )

    @Test
    fun `segments speech after enough trailing silence`() {
        val segmenter = LiveVoiceVadSegmenter(config)
        val events = mutableListOf<LiveVoiceVadEvent>()

        events += segmenter.accept(silenceChunk(200))
        events += segmenter.accept(speechChunk(100))
        events += segmenter.accept(speechChunk(150))
        events += segmenter.accept(silenceChunk(100))
        events += segmenter.accept(silenceChunk(100))

        assertIs<LiveVoiceVadEvent.SpeechStarted>(events.first())
        val utterances = events.filterIsInstance<LiveVoiceVadEvent.Utterance>()
        assertEquals(1, utterances.size)
        assertTrue(utterances.single().pcmBigEndian.size >= bytesForMillis(450))
    }

    @Test
    fun `discards speech shorter than minimum duration`() {
        val segmenter = LiveVoiceVadSegmenter(config)
        val events = mutableListOf<LiveVoiceVadEvent>()

        events += segmenter.accept(speechChunk(100))
        events += segmenter.accept(silenceChunk(300))

        assertEquals(1, events.count { it is LiveVoiceVadEvent.SpeechStarted })
        assertEquals(0, events.count { it is LiveVoiceVadEvent.Utterance })
    }

    @Test
    fun `converts big endian pcm16 to little endian`() {
        assertContentEquals(
            byteArrayOf(0x34, 0x12, 0x78, 0x56),
            pcm16BigEndianToLittleEndian(byteArrayOf(0x12, 0x34, 0x56, 0x78)),
        )
    }

    @Test
    fun `microphone gate suppresses input while tts is playing`() {
        val gate = LiveVoiceMicrophoneGate()

        val initial = assertIs<LiveVoiceMicrophoneGateDecision.Allow>(gate.accept(ttsIsPlaying = false))
        assertFalse(initial.resumed)

        val firstSuppressed = assertIs<LiveVoiceMicrophoneGateDecision.Suppress>(gate.accept(ttsIsPlaying = true))
        assertTrue(firstSuppressed.started)

        val secondSuppressed = assertIs<LiveVoiceMicrophoneGateDecision.Suppress>(gate.accept(ttsIsPlaying = true))
        assertFalse(secondSuppressed.started)

        val resumed = assertIs<LiveVoiceMicrophoneGateDecision.Allow>(gate.accept(ttsIsPlaying = false))
        assertTrue(resumed.resumed)

        val allowed = assertIs<LiveVoiceMicrophoneGateDecision.Allow>(gate.accept(ttsIsPlaying = false))
        assertFalse(allowed.resumed)
    }

    private fun speechChunk(millis: Int): ByteArray =
        chunk(sample = 5_000, millis = millis)

    private fun silenceChunk(millis: Int): ByteArray =
        chunk(sample = 0, millis = millis)

    private fun chunk(sample: Int, millis: Int): ByteArray {
        val sampleCount = config.sampleRate * millis / 1_000
        val output = ByteArray(sampleCount * config.bytesPerSample)
        var index = 0
        repeat(sampleCount) {
            output[index] = ((sample shr 8) and 0xff).toByte()
            output[index + 1] = (sample and 0xff).toByte()
            index += 2
        }
        return output
    }

    private fun bytesForMillis(millis: Int): Int =
        config.sampleRate * config.bytesPerSample * millis / 1_000
}
