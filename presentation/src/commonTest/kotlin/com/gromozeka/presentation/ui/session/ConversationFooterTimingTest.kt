package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ConversationFooterTimingTest {
    private val id = Conversation.Id("timing")
    private val request = ConversationRuntimeTask(ConversationRuntimeTask.Id("request"), id,
        parentTaskId = ConversationRuntimeTask.Id("root"),
        payload = ConversationRuntimeTask.Payload.LlmCall(Conversation.Message.Id("message"), AgentDefinition.Id("agent"), 1),
        placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "request",
        requirements = ConversationRuntimeTaskRequirements(ConversationRuntimeCapability.entries.toSet(), ConversationRuntimeTaskTarget.Server), createdAt = at(10))
    private val turn = ConversationRuntimeTurnSummary(request.turnId, at(10), completedAt = at(45),
        modelRequest = ConversationRuntimeTurnSummary.ModelRequestTiming(request.id, at(20), at(40)))
    private val idle = ConversationRuntimeSnapshot(1, id, null, pendingTasks = emptyList(), lastTurn = turn)

    @Test
    fun finishedResponseDurationsStayFixedEvenWithLiveBackgroundCommands() {
        val command = CommandTask(CommandTask.Id("background"), id, ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"),
            command = "sleep 60", workingDirectory = "/tmp", status = CommandTask.Status.WORKING,
            processId = 1, processStartedAt = at(20), outputFile = "/tmp/output", outputBytes = 0, createdAt = at(20), updatedAt = at(20))
        val clocks = conversationFooterClocks(idle.copy(commandTasks = listOf(command)), at(5))
        assertFalse(clocks.running)
        for (now in listOf(at(45), at(70), at(100000))) {
            assertEquals("0:40", clocks.total!!.let { conversationElapsedSince(it.startedAt, it.completedAt ?: now) })
            assertEquals("0:20", clocks.request!!.let { activityElapsed(it.startedAt, it.completedAt ?: now) })
        }
        assertEquals(clocks, conversationFooterClocks(idle, at(5)), "A new client view uses the same server timestamps")
    }

    @Test
    fun onlyAnActiveRequestTicksWhileTotalAlsoCoversTheGapsBetweenRequests() {
        val active = idle.copy(activeTask = request, lastTurn = turn.copy(completedAt = null,
            modelRequest = turn.modelRequest!!.copy(completedAt = null)))
        val ticking = conversationFooterClocks(active, at(5))
        assertTrue(ticking.running)
        assertNull(ticking.total!!.completedAt)
        assertNull(ticking.request!!.completedAt)
        val between = idle.copy(continuationTask = request, lastTurn = turn.copy(completedAt = null))
        val gaps = conversationFooterClocks(between, at(5))
        assertTrue(gaps.running)
        assertNull(gaps.total!!.completedAt)
        assertEquals(at(40), gaps.request!!.completedAt)
    }

    @Test
    fun missingCompletionDataCannotTurnAnIdleConversationIntoAnEndlessClock() {
        for (runtime in listOf(null, idle.copy(lastTurn = null), idle.copy(lastTurn = turn.copy(completedAt = null, modelRequest = null)))) {
            val clocks = conversationFooterClocks(runtime, at(5))
            assertFalse(clocks.running)
            assertNull(clocks.total)
            assertNull(clocks.request)
        }
        val stale = idle.copy(lastTurn = turn.copy(completedAt = null, modelRequest = turn.modelRequest!!.copy(completedAt = null)))
        assertFalse(conversationFooterClocks(stale, at(5)).running)
        assertNull(conversationFooterClocks(stale, at(5)).request)
    }

    @Test
    fun aNewMessageDoesNotReuseThePreviousResponsesEndTime() {
        assertNull(conversationFooterClocks(idle, at(50)).total)
        val pending = idle.copy(pendingTasks = listOf(request))
        val clocks = conversationFooterClocks(pending, at(50))
        assertTrue(clocks.running)
        assertEquals(at(50), clocks.total!!.startedAt)
        assertNull(clocks.total!!.completedAt)
    }

    private fun at(seconds: Long) = Instant.fromEpochSeconds(seconds)
}
