package com.gromozeka.presentation.ui

import com.gromozeka.presentation.services.LiveNativeTranslation
import com.gromozeka.presentation.services.LiveSourceBlock
import com.gromozeka.presentation.services.LiveTranscriptEntry
import com.gromozeka.presentation.services.LiveTranslationState
import com.gromozeka.presentation.services.translation.localizedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTranslationRowsTest {
    @Test
    fun `unfinished source remains before later finals and a gap in the original column`() {
        val message = localizedText("interpreter.interrupted")
        val entries = listOf(
            LiveTranscriptEntry.Segment("first", 1, 0, "First.", true),
            LiveTranscriptEntry.Segment("unfinished", 1, 1, "Unfinished original", false, interrupted = true),
            LiveTranscriptEntry.Segment("third", 1, 2, "Third.", true),
            LiveTranscriptEntry.Gap("gap", message),
        )
        val rows = liveRows(entries, LiveTranslationState(blocks = listOf(
            LiveSourceBlock("a", "First.", setOf("first")),
            LiveSourceBlock("b", "Third.", setOf("third")),
        )))
        assertEquals(listOf("block:a", "pending:unfinished", "block:b", "gap:gap"), rows.map { it.id })
        assertEquals("Unfinished original", (rows[1] as LiveRow.Pending).segment.text)
    }

    @Test
    fun `several shared blocks from one original do not duplicate the entire original`() {
        val rows = liveRows(listOf(LiveTranscriptEntry.Segment("one", 1, 0, "First. Second.", true)),
            LiveTranslationState(blocks = listOf(
                LiveSourceBlock("a", "First. ", setOf("one")),
                LiveSourceBlock("b", "Second.", setOf("one")),
            )))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is LiveRow.Block })
        assertEquals("First. Second.", rows.joinToString("") { (it as LiveRow.Block).block.text })
    }

    @Test
    fun `native translations from every source segment are combined in source order`() {
        val block = LiveSourceBlock("joined", "First.\nSecond.", linkedSetOf("one", "two"), speakerId = "1")
        val value = nativeTranslationFor(block, mapOf(
            "one" to LiveNativeTranslation("Первое.", true, "ru", "en", "1"),
            "two" to LiveNativeTranslation("Второе.", false, "ru", "en", "1"),
        ))
        assertEquals("Первое.\nВторое.", value?.text)
        assertEquals(false, value?.isFinal)
        assertEquals("ru", value?.targetLanguage)
    }

    @Test
    fun `independently observed UI snapshots do not display translations for an unknown source`() {
        val entries = listOf(LiveTranscriptEntry.Segment("current", 2, 0, "Current original.", true))
        val rows = liveRows(entries, LiveTranslationState(blocks = listOf(
            LiveSourceBlock("old", "Old original.", setOf("previous")),
        )))
        assertEquals(listOf("pending:current"), rows.map { it.id })
        assertEquals("Current original.", (rows.single() as LiveRow.Pending).segment.text)
    }
}
