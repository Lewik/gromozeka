package com.gromozeka.infrastructure.ai.openai

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenAiWhisperWindowedTranscriptionTest {
    private val settings = WhisperWindowing(
        windowSamples = 200,
        hopSamples = 100,
        maxBufferedSamples = 600,
        anchorToleranceSamples = 5,
        silenceRadiusSamples = 5,
        minimumAnchorWords = 2,
    )
    private val reconciler = WhisperWindowReconciler(settings)

    @Test
    fun `decorated text range preserves punctuation while timestamps stay word based`() {
        val transcript = transcript(
            start = 0,
            end = 200,
            text = " Alpha, beta — gamma.",
            words = listOf(
                word("Alpha", 10, 20),
                word("beta", 30, 40),
                word("gamma", 50, 60),
            ),
        )

        assertEquals("Alpha, beta —", transcript.textRange(0, 2))
        assertEquals("gamma.", transcript.textRange(2, 3))
    }

    @Test
    fun `clipped tail word may end before the preceding word without changing lexical order`() {
        val transcript = transcript(
            start = 0,
            end = 200,
            text = "Backwards, I'm",
            words = listOf(
                word("Backwards", 100, 198),
                word("I'm", 196, 196),
            ),
        )

        assertEquals("Backwards, I'm", transcript.textRange(0, transcript.words.size))
    }

    @Test
    fun `matching overlap chooses one cut without duplicating boundary words`() {
        val previous = transcript(
            start = 0,
            end = 200,
            text = "Start alpha, beta gamma delta epsilon.",
            words = listOf(
                word("Start", 10, 20),
                word("alpha", 80, 90),
                word("beta", 110, 120),
                word("gamma", 140, 150),
                word("delta", 160, 170),
                word("epsilon", 190, 195),
            ),
        )
        val next = transcript(
            start = 100,
            end = 300,
            text = "Beta gamma delta epsilon zeta.",
            words = listOf(
                word("Beta", 110, 120),
                word("gamma", 140, 150),
                word("delta", 160, 170),
                word("epsilon", 190, 195),
                word("zeta", 220, 230),
            ),
        )

        val merge = assertIs<WhisperWindowMerge.Direct>(reconciler.merge(previous, next))
        val combined = listOf(
            previous.textRange(0, merge.previousUntil),
            next.textRange(merge.nextFrom, next.words.size),
        ).filter { it.isNotBlank() }.joinToString(" ")

        assertEquals("Start alpha, beta gamma delta epsilon zeta.", combined)
    }

    @Test
    fun `real repeated words survive overlap reconciliation`() {
        val previous = transcript(
            start = 0,
            end = 200,
            text = "One two two three.",
            words = listOf(
                word("One", 80, 90),
                word("two", 110, 120),
                word("two", 135, 145),
                word("three", 160, 170),
            ),
        )
        val next = transcript(
            start = 100,
            end = 300,
            text = "Two two three four.",
            words = listOf(
                word("Two", 110, 120),
                word("two", 135, 145),
                word("three", 160, 170),
                word("four", 220, 230),
            ),
        )

        val merge = assertIs<WhisperWindowMerge.Direct>(reconciler.merge(previous, next))
        val combined = listOf(
            previous.textRange(0, merge.previousUntil),
            next.textRange(merge.nextFrom, next.words.size),
        ).filter { it.isNotBlank() }.joinToString(" ")

        assertEquals("One two two three four.", combined)
    }

    @Test
    fun `disagreement around boundary is replaced by a wider third audio pass`() {
        val previous = transcript(
            start = 0,
            end = 200,
            text = "Opening left anchor wrong first right anchor ending.",
            words = listOf(
                word("Opening", 20, 30),
                word("left", 115, 120),
                word("anchor", 125, 130),
                word("wrong", 142, 147),
                word("first", 150, 155),
                word("right", 170, 175),
                word("anchor", 180, 185),
                word("ending", 190, 195),
            ),
        )
        val next = transcript(
            start = 100,
            end = 300,
            text = "Left anchor different middle right anchor ending later.",
            words = listOf(
                word("Left", 115, 120),
                word("anchor", 125, 130),
                word("different", 143, 149),
                word("middle", 152, 158),
                word("right", 170, 175),
                word("anchor", 180, 185),
                word("ending", 190, 195),
                word("later", 230, 240),
            ),
        )
        val required = assertIs<WhisperWindowMerge.RepairRequired>(reconciler.merge(previous, next))
        val repair = transcript(
            start = 0,
            end = 300,
            text = "Opening left anchor correct repaired words right anchor ending later.",
            words = listOf(
                word("Opening", 20, 30),
                word("left", 115, 120),
                word("anchor", 125, 130),
                word("correct", 140, 146),
                word("repaired", 148, 154),
                word("words", 156, 162),
                word("right", 170, 175),
                word("anchor", 180, 185),
                word("ending", 190, 195),
                word("later", 230, 240),
            ),
        )

        val merged = reconciler.mergeRepair(previous, repair, next, required.boundarySample)
        val combined = listOf(
            previous.textRange(0, merged.previousUntil),
            repair.textRange(merged.repairFrom, merged.repairUntil),
            next.textRange(merged.nextFrom, next.words.size),
        ).filter { it.isNotBlank() }.joinToString(" ")

        assertEquals("Opening left anchor correct repaired words right anchor ending later.", combined)
    }

    @Test
    fun `repair may use one timestamp-confirmed word on each side`() {
        val previous = transcript(
            start = 0,
            end = 200,
            text = "Published alpha wrong.",
            words = listOf(
                word("Published", 80, 90),
                word("alpha", 125, 130),
                word("wrong", 145, 150),
            ),
        )
        val next = transcript(
            start = 100,
            end = 300,
            text = "Different omega tail.",
            words = listOf(
                word("Different", 145, 150),
                word("omega", 175, 180),
                word("tail", 220, 230),
            ),
        )
        val repair = transcript(
            start = 0,
            end = 300,
            text = "Published interruption alpha corrected middle omega repaired-tail.",
            words = listOf(
                word("Published", 80, 90),
                word("interruption", 105, 110),
                word("alpha", 125, 130),
                word("corrected", 145, 150),
                word("middle", 155, 160),
                word("omega", 175, 180),
                word("repaired-tail", 220, 230),
            ),
        )

        val merged = reconciler.mergeRepair(previous, repair, next, boundarySample = 150)
        val combined = listOf(
            previous.textRange(0, merged.previousUntil),
            repair.textRange(merged.repairFrom, merged.repairUntil),
            next.textRange(merged.nextFrom, next.words.size),
        ).filter { it.isNotBlank() }.joinToString(" ")

        assertEquals("Published alpha corrected middle omega tail.", combined)
    }

    @Test
    fun `repair falls back to silence on both sides when word hypotheses do not match`() {
        val previous = transcript(
            start = 0,
            end = 200,
            text = "Left.",
            words = listOf(word("Left", 120, 130)),
        )
        val next = transcript(
            start = 100,
            end = 300,
            text = "Right.",
            words = listOf(word("Right", 190, 200)),
        )
        val repair = transcript(
            start = 0,
            end = 300,
            text = "Other-left corrected other-right.",
            words = listOf(
                word("Other-left", 120, 130),
                word("corrected", 150, 160),
                word("other-right", 190, 200),
            ),
        )

        val merged = reconciler.mergeRepair(previous, repair, next, boundarySample = 160)
        val combined = listOf(
            previous.textRange(0, merged.previousUntil),
            repair.textRange(merged.repairFrom, merged.repairUntil),
            next.textRange(merged.nextFrom, next.words.size),
        ).filter { it.isNotBlank() }.joinToString(" ")

        assertEquals("Left. corrected Right.", combined)
    }

    @Test
    fun `shared silence is a valid deterministic boundary without text anchors`() {
        val previous = transcript(
            start = 0,
            end = 200,
            text = "Before.",
            words = listOf(word("Before", 100, 120)),
        )
        val next = transcript(
            start = 100,
            end = 300,
            text = "After.",
            words = listOf(word("After", 180, 200)),
        )

        val merge = assertIs<WhisperWindowMerge.Direct>(reconciler.merge(previous, next))
        assertEquals("Before.", previous.textRange(0, merge.previousUntil))
        assertEquals("After.", next.textRange(merge.nextFrom, next.words.size))
    }

    @Test
    fun `session turns two overlapping windows into one ordered final transcript`() = kotlinx.coroutines.test.runTest {
        val windowing = settings
        val previous = transcript(
            start = 0,
            end = 200,
            text = "Start alpha beta gamma delta.",
            words = listOf(
                word("Start", 10, 20),
                word("alpha", 80, 90),
                word("beta", 110, 120),
                word("gamma", 140, 150),
                word("delta", 160, 170),
            ),
        )
        val next = transcript(
            start = 100,
            end = 300,
            text = "Beta gamma delta ending.",
            words = listOf(
                word("Beta", 110, 120),
                word("gamma", 140, 150),
                word("delta", 160, 170),
                word("ending", 220, 230),
            ),
        )
        val calls = mutableListOf<Pair<Long, Int>>()
        val transcriber = WhisperWindowTranscriber { start, pcm ->
            calls += start to pcm.size
            when (start to (pcm.size / 2).toLong()) {
                0L to 200L -> previous
                100L to 200L -> next
                else -> error("Unexpected window: start=$start samples=${pcm.size / 2}")
            }
        }
        val session = OpenAiWhisperWindowedTranscriptionSession(
            transcriber = transcriber,
            settings = windowing,
            reconciler = WhisperWindowReconciler(windowing),
            scope = backgroundScope,
        )
        session.start()
        val collected = async { session.events.toList() }

        session.appendAudio(ByteArray(600))
        session.finishInput()

        val texts = collected.await().filterIsInstance<com.gromozeka.domain.service.LiveTranscriptionEvent.Transcript>()
            .map { it.text }
        assertEquals(listOf("Start alpha beta gamma", "delta ending."), texts)
        assertEquals(listOf(0L to 400, 100L to 400), calls)
    }

    @Test
    fun `session runs one wider repair request only when the normal overlap disagrees`() = kotlinx.coroutines.test.runTest {
        val windowing = settings
        val previous = transcript(
            start = 0,
            end = 200,
            text = "Opening left anchor wrong first right anchor.",
            words = listOf(
                word("Opening", 20, 30),
                word("left", 115, 120),
                word("anchor", 125, 130),
                word("wrong", 142, 147),
                word("first", 150, 155),
                word("right", 170, 175),
                word("anchor", 180, 185),
            ),
        )
        val next = transcript(
            start = 100,
            end = 300,
            text = "Left anchor different middle right anchor ending.",
            words = listOf(
                word("Left", 115, 120),
                word("anchor", 125, 130),
                word("different", 143, 149),
                word("middle", 152, 158),
                word("right", 170, 175),
                word("anchor", 180, 185),
                word("ending", 230, 240),
            ),
        )
        val repair = transcript(
            start = 0,
            end = 300,
            text = "Opening left anchor correct repaired words right anchor ending.",
            words = listOf(
                word("Opening", 20, 30),
                word("left", 115, 120),
                word("anchor", 125, 130),
                word("correct", 140, 146),
                word("repaired", 148, 154),
                word("words", 156, 162),
                word("right", 170, 175),
                word("anchor", 180, 185),
                word("ending", 230, 240),
            ),
        )
        val calls = mutableListOf<Pair<Long, Int>>()
        val transcriber = WhisperWindowTranscriber { start, pcm ->
            calls += start to pcm.size
            when (start to (pcm.size / 2).toLong()) {
                0L to 200L -> previous
                100L to 200L -> next
                0L to 300L -> repair
                else -> error("Unexpected window: start=$start samples=${pcm.size / 2}")
            }
        }
        val session = OpenAiWhisperWindowedTranscriptionSession(
            transcriber = transcriber,
            settings = windowing,
            reconciler = WhisperWindowReconciler(windowing),
            scope = backgroundScope,
        )
        session.start()
        val collected = async { session.events.toList() }

        session.appendAudio(ByteArray(600))
        session.finishInput()

        val texts = collected.await().filterIsInstance<com.gromozeka.domain.service.LiveTranscriptionEvent.Transcript>()
            .map { it.text }
        assertEquals(listOf("Opening left anchor", "correct repaired words right anchor ending."), texts)
        assertEquals(listOf(0L to 400, 100L to 400, 0L to 600), calls)
    }

    @Test
    fun `repair watermark prevents the following window from replaying consumed words`() = kotlinx.coroutines.test.runTest {
        val windowing = settings
        val first = transcript(
            start = 0,
            end = 200,
            text = "Opening left old.",
            words = listOf(
                word("Opening", 20, 30),
                word("left", 120, 130),
                word("old", 145, 150),
            ),
        )
        val second = transcript(
            start = 100,
            end = 300,
            text = "Different other-middle late anchor carried.",
            words = listOf(
                word("Different", 145, 150),
                word("other-middle", 155, 160),
                word("late", 235, 240),
                word("anchor", 245, 250),
                word("carried", 255, 265),
            ),
        )
        val repair = transcript(
            start = 0,
            end = 300,
            text = "Opening left fixed middle late anchor carried.",
            words = listOf(
                word("Opening", 20, 30),
                word("left", 120, 130),
                word("fixed", 145, 150),
                word("middle", 155, 160),
                word("late", 235, 240),
                word("anchor", 245, 250),
                word("carried", 255, 265),
            ),
        )
        val third = transcript(
            start = 200,
            end = 400,
            text = "Late anchor future.",
            words = listOf(
                word("Late", 235, 240),
                word("anchor", 245, 250),
                word("future", 280, 290),
            ),
        )
        val calls = mutableListOf<Pair<Long, Int>>()
        val transcriber = WhisperWindowTranscriber { start, pcm ->
            calls += start to pcm.size
            when (start to (pcm.size / 2).toLong()) {
                0L to 200L -> first
                100L to 200L -> second
                0L to 300L -> repair
                200L to 200L -> third
                else -> error("Unexpected window: start=$start samples=${pcm.size / 2}")
            }
        }
        val session = OpenAiWhisperWindowedTranscriptionSession(
            transcriber = transcriber,
            settings = windowing,
            reconciler = WhisperWindowReconciler(windowing),
            scope = backgroundScope,
        )
        session.start()
        val collected = async { session.events.toList() }

        session.appendAudio(ByteArray(800))
        session.finishInput()

        val text = collected.await().filterIsInstance<com.gromozeka.domain.service.LiveTranscriptionEvent.Transcript>()
            .joinToString(" ") { it.text }
        assertEquals("Opening left fixed middle late anchor carried. future.", text)
        assertEquals(listOf(0L to 400, 100L to 400, 0L to 600, 200L to 400), calls)
    }

    @Test
    fun `short stopped session is transcribed once without synthetic overlap`() = kotlinx.coroutines.test.runTest {
        val windowing = settings
        val only = transcript(
            start = 0,
            end = 80,
            text = "Short phrase.",
            words = listOf(word("Short", 10, 30), word("phrase", 40, 70)),
        )
        var calls = 0
        val session = OpenAiWhisperWindowedTranscriptionSession(
            transcriber = WhisperWindowTranscriber { start, pcm ->
                calls += 1
                assertEquals(0L, start)
                assertEquals(160, pcm.size)
                only
            },
            settings = windowing,
            reconciler = WhisperWindowReconciler(windowing),
            scope = backgroundScope,
        )
        session.start()
        val collected = async { session.events.toList() }

        session.appendAudio(ByteArray(160))
        session.finishInput()

        assertEquals(1, calls)
        assertEquals(
            listOf("Short phrase."),
            collected.await().filterIsInstance<com.gromozeka.domain.service.LiveTranscriptionEvent.Transcript>().map { it.text },
        )
    }

    private fun transcript(
        start: Long,
        end: Long,
        text: String,
        words: List<WhisperWindowWord>,
    ) = WhisperWindowTranscript(start, end, text, words)

    private fun word(text: String, start: Long, end: Long) = WhisperWindowWord(text, start, end)
}
