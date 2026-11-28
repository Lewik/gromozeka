package com.gromozeka.server

import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.remote.protocol.LiveInterpreterNativeTranslationEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveTranscriptTimelineTest {
    @Test
    fun `partial text is visible before commit and final text replaces it`() {
        val timeline = LiveTranscriptTimeline("session")
        val partial = assertIs<LiveInterpreterTranscriptEvent>(timeline.accept(LiveTranscriptionEvent.Transcript("a", "שלום", false)).single())
        assertEquals(null, partial.sequenceNumber)
        assertFalse(partial.isFinal)
        val committed = assertIs<LiveInterpreterTranscriptEvent>(timeline.accept(LiveTranscriptionEvent.Committed("a", null)).single())
        assertEquals(0, committed.sequenceNumber)
        val final = assertIs<LiveInterpreterTranscriptEvent>(timeline.accept(LiveTranscriptionEvent.Transcript(
            "a", "שלום world", true, speakerId = "2", language = "he", startMillis = 100, endMillis = 900,
        )).single())
        assertTrue(final.isFinal)
        assertEquals("שלום world", final.text)
        assertEquals("2", final.speakerId)
        assertEquals("he", final.language)
        assertEquals(100L, final.startMillis)
        assertEquals(900L, final.endMillis)
        timeline.requireDrained()
    }

    @Test
    fun `completion order does not change committed turn positions`() {
        val timeline = LiveTranscriptTimeline("session")
        timeline.accept(LiveTranscriptionEvent.Committed("a", null))
        timeline.accept(LiveTranscriptionEvent.Committed("b", "a"))
        val second = assertIs<LiveInterpreterTranscriptEvent>(timeline.accept(LiveTranscriptionEvent.Transcript("b", "second", true)).single())
        val first = assertIs<LiveInterpreterTranscriptEvent>(timeline.accept(LiveTranscriptionEvent.Transcript("a", "first", true)).single())
        assertEquals(1, second.sequenceNumber)
        assertEquals(0, first.sequenceNumber)
        timeline.requireDrained()
    }

    @Test
    fun `final received before its commit is held until position is known`() {
        val timeline = LiveTranscriptTimeline("session")
        assertTrue(timeline.accept(LiveTranscriptionEvent.Transcript("a", "original", true)).isEmpty())
        val event = assertIs<LiveInterpreterTranscriptEvent>(timeline.accept(LiveTranscriptionEvent.Committed("a", null)).single())
        assertEquals(0, event.sequenceNumber)
        assertTrue(event.isFinal)
        timeline.requireDrained()
    }

    @Test
    fun `native translation follows its committed source and must drain to an immutable final`() {
        val timeline = LiveTranscriptTimeline("session")
        timeline.accept(LiveTranscriptionEvent.Committed("a", null))
        timeline.accept(LiveTranscriptionEvent.Transcript("a", "שלום", true, speakerId = "2", language = "he"))
        val partial = assertIs<LiveInterpreterNativeTranslationEvent>(timeline.accept(
            LiveTranscriptionEvent.Translation("a", "При", false, "ru", "he", "2"),
        ).single())
        assertEquals(0, partial.sequenceNumber)
        assertEquals("При", partial.text)
        assertFalse(partial.isFinal)
        assertFailsWith<IllegalStateException> { timeline.requireDrained() }
        val final = assertIs<LiveInterpreterNativeTranslationEvent>(timeline.accept(
            LiveTranscriptionEvent.Translation("a", "Привет", true, "ru", "he", "2"),
        ).single())
        assertTrue(final.isFinal)
        assertEquals("ru", final.targetLanguage)
        timeline.requireDrained()
        assertFailsWith<IllegalStateException> {
            timeline.accept(LiveTranscriptionEvent.Translation("a", "Здравствуйте", false, "ru"))
        }
    }

    @Test
    fun `broken predecessor and mutation of a final are explicit errors`() {
        val timeline = LiveTranscriptTimeline("session")
        assertFailsWith<IllegalStateException> { timeline.accept(LiveTranscriptionEvent.Committed("a", "missing")) }
        timeline.accept(LiveTranscriptionEvent.Committed("a", null))
        timeline.accept(LiveTranscriptionEvent.Transcript("a", "original", true))
        assertFailsWith<IllegalStateException> { timeline.accept(LiveTranscriptionEvent.Transcript("a", "rewritten", false)) }
    }

    @Test
    fun `unfinished original is not silently accepted as complete`() {
        val timeline = LiveTranscriptTimeline("session")
        timeline.accept(LiveTranscriptionEvent.Transcript("a", "unfinished", false))
        assertFailsWith<IllegalStateException> { timeline.requireDrained() }
    }
}

class LiveAudioTurnDetectorTest {
    @Test
    fun `turn ends after speech followed by silence without any overlapping audio`() {
        val detector = LiveAudioTurnDetector()
        assertFalse(detector.accept(pcm(1_600, 16_000)))
        assertFalse(detector.accept(pcm(12_800, 0)))
        assertTrue(detector.accept(pcm(11_200, 0)))
        assertFalse(detector.accept(pcm(24_000, 0)))
    }

    @Test
    fun `short thinking pauses do not split a phrase and renewed speech resets silence`() {
        val detector = LiveAudioTurnDetector()
        assertFalse(detector.accept(pcm(1_600, 16_000)))
        assertFalse(detector.accept(pcm(16_000, 0)))
        assertFalse(detector.accept(pcm(1_600, 16_000)))
        assertFalse(detector.accept(pcm(16_000, 0)))
        assertTrue(detector.accept(pcm(8_000, 0)))
    }

    @Test
    fun `long monologue has bounded turns`() {
        val detector = LiveAudioTurnDetector(maximumTurnMillis = 1_000)
        repeat(9) { assertFalse(detector.accept(pcm(1_600, 16_000))) }
        assertTrue(detector.accept(pcm(1_600, 16_000)))
        assertFalse(detector.accept(pcm(1_600, 16_000)))
    }

    @Test
    fun `empty frames are harmless and incomplete PCM samples fail`() {
        val detector = LiveAudioTurnDetector()
        assertFalse(detector.accept(ByteArray(0)))
        assertFailsWith<IllegalArgumentException> { detector.accept(byteArrayOf(1)) }
    }

    private fun pcm(samples: Int, amplitude: Int): ByteArray = ByteArray(samples * 2).apply {
        for (index in indices step 2) {
            this[index] = (amplitude shr 8).toByte()
            this[index + 1] = amplitude.toByte()
        }
    }
}
