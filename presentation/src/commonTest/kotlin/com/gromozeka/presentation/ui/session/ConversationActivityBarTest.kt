package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.Tab
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.service.ConversationRuntimeMemoryOperation
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.presentation.services.VoiceInputTarget
import com.gromozeka.presentation.services.VoiceTranscriptionActivity
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.data.Translation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ConversationActivityBarTest {
    private val translation = Translation.builtIn.getValue("en")
    private val now = Instant.fromEpochSeconds(100)
    private val first = VoiceTranscriptionActivity("first", VoiceInputTarget(Tab.Id("a"), MessageInputContext.Source.PUSH_TO_TALK, false), now)
    private val second = VoiceTranscriptionActivity("second", VoiceInputTarget(Tab.Id("b"), MessageInputContext.Source.LIVE_VOICE, true), now)

    @Test
    fun `recognition appears only in the originating tab alongside runtime work`() {
        val rows = executionActivities(null, null, true, listOf(first, second), "a", translation)
        assertEquals(listOf("voice:first", "agent"), rows.map { it.id })
        assertTrue(rows.first().label.endsWith("Draft"))
        assertEquals(now, rows.first().startedAt)
        assertEquals(listOf("voice:second"), executionActivities(null, null, false, listOf(first, second), "b", translation).map { it.id })
        assertTrue(executionActivities(null, null, false, listOf(first, second), "c", translation).isEmpty())
    }

    @Test
    fun `memory operations stay out of the foreground status while their UI is disabled`() {
        val runtime = snapshot().copy(memoryOperations = listOf(
            ConversationRuntimeMemoryOperation(MemoryRun.Id("memory"), "memory_enrich_context", MemoryRun.Status.RUNNING,
                "Reading context", MemoryRun.Progress(totalUnits = 4, completedUnits = 1), now, updatedAt = now),
        ))
        val rows = executionActivities(runtime, null, true, listOf(first), "a", translation)
        assertEquals(listOf("voice:first", "agent"), rows.map { it.id })
        assertTrue(rows.all { it.progress == null })
        assertFalse(runtime.hasControllableWork())
    }

    @Test
    fun `failed recognition stays scoped and idle state does not manufacture progress`() {
        val error = first.copy(error = LocalizedText.Literal("Recognition failed"))
        val row = executionActivities(snapshot(), null, false, listOf(error), "a", translation).single()
        assertEquals("Recognition failed", row.label)
        assertTrue(row.failed)
        assertEquals(null, row.progress)
        assertTrue(executionActivities(snapshot(), null, false, listOf(error), "b", translation).isEmpty())
        assertFalse(snapshot().hasControllableWork())
    }

    @Test
    fun `composer background jobs have explicit statuses without fake progress`() {
        val rows = executionActivities(null, null, false, emptyList(), "a", translation,
            uploadingArtifacts = true, regeneratingSuggestions = true)
        assertEquals(listOf("upload", "suggestions"), rows.map { it.id })
        assertTrue(rows.all { it.progress == null && it.startedAt == null })
        assertTrue(executionActivities(null, null, false, emptyList(), "a", translation).isEmpty())
    }

    private fun snapshot() = ConversationRuntimeSnapshot(
        revision = 1, conversationId = Conversation.Id("conversation"), state = null, pendingTasks = emptyList(),
    )
}
