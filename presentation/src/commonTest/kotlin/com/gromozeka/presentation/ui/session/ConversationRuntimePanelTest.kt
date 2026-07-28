package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationRuntimePanelTest {
    @Test
    fun `runtime panel keeps active monitors ordered by useful activity`() {
        val older = monitor("older", updatedAt = 1_000)
        val newer = monitor("newer", updatedAt = 2_000)
        val cancelling = monitor(
            id = "cancelling",
            updatedAt = 3_000,
            cancellationRequestedAt = Instant.fromEpochMilliseconds(3_000),
        )
        val terminal = monitor(
            id = "terminal",
            updatedAt = 4_000,
            status = CommandMonitor.Status.COMPLETED,
        )

        val result = listOf(terminal, older, cancelling, newer).activeForRuntimePanel()

        assertEquals(listOf("newer", "older", "cancelling"), result.map { it.id.value })
    }

    @Test
    fun `latest event time wins over general update time`() {
        val recentlyUpdated = monitor("updated", updatedAt = 5_000)
        val recentlyMatched = monitor(
            id = "matched",
            updatedAt = 1_000,
            lastEventAt = Instant.fromEpochMilliseconds(6_000),
        )

        val result = listOf(recentlyUpdated, recentlyMatched).activeForRuntimePanel()

        assertEquals(listOf("matched", "updated"), result.map { it.id.value })
    }

    private fun monitor(
        id: String,
        updatedAt: Long,
        status: CommandMonitor.Status = CommandMonitor.Status.WORKING,
        lastEventAt: Instant? = null,
        cancellationRequestedAt: Instant? = null,
    ): CommandMonitor {
        val updated = Instant.fromEpochMilliseconds(updatedAt)
        return CommandMonitor(
            id = CommandMonitor.Id(id),
            conversationId = Conversation.Id("conversation-1"),
            commandTaskId = CommandTask.Id("command-1"),
            workerId = ConversationRuntimeWorkerId("worker-1"),
            workspaceMountId = WorkspaceMount.Id("mount-1"),
            filterCommand = "grep READY",
            mode = CommandMonitor.Mode.CONTINUOUS,
            startFrom = CommandMonitor.StartFrom.NOW,
            status = status,
            sourceOutputCursor = 0,
            processId = 101,
            processStartedAt = updated,
            outputFile = "/tmp/$id.log",
            errorFile = "/tmp/$id.err",
            outputBytes = 0,
            eventOutputCursor = 0,
            lastEventAt = lastEventAt,
            cancellationRequestedAt = cancellationRequestedAt,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = updated,
            completedAt = updated.takeIf { status != CommandMonitor.Status.WORKING },
        )
    }
}
