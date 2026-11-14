package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.*
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.viewmodel.PendingUserMessage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class FooterSummaryRowTest {
    @Test
    fun localBadgesKeepFramesAndInspectTheCorrectSectionWithoutPersistentStatusText() = runDesktopComposeUiTest(width = 800, height = 250) {
        val count = mutableStateOf(0)
        val clicks = mutableListOf<RuntimeInspectionSection>()
        setContent { GromozekaTheme { Box(Modifier.width(620.dp)) {
            TestFooterSummary(footerRuntime(count.value), footerPending(count.value), footerTokenStats(), null, clicks::add)
        } } }
        val frames = tags.associateWith { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        for (n in listOf(1, 9, 12, 128, 0)) {
            runOnIdle { count.value = n }
            for (tag in tags) {
                assertEquals(frames.getValue(tag), onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot, tag)
                val text = onNode(hasText(n.toString()) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                text.assertIsDisplayed()
                val layout = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                assertTrue(!layout.single().isLineEllipsized(0))
            }
        }
        tags.forEach { onNodeWithTag(it).performClick() }
        onNodeWithTag("composer-context-usage").performClick()
        runOnIdle { assertEquals(listOf(RuntimeInspectionSection.QUEUED_MESSAGES, RuntimeInspectionSection.COMMANDS,
            RuntimeInspectionSection.MONITORS, RuntimeInspectionSection.OVERVIEW), clicks) }
        onNodeWithText("Running commands", substring = true).assertDoesNotExist()
        onNodeWithTag("composer-current-activity").assertDoesNotExist()
        onNodeWithTag("composer-background-commands").assertDoesNotExist()
    }

    @Test
    fun unknownRuntimeIsNotReportedAsZeroAndTerminalJobsAreNotCounted() = runDesktopComposeUiTest(width = 800, height = 250) {
        val runtime = mutableStateOf<ConversationRuntimeSnapshot?>(null)
        setContent { GromozekaTheme { TestFooterSummary(runtime.value, emptyList(), null, null, {}) } }
        for (tag in tags) onNode(hasText("—") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()
        runOnIdle {
            val active = footerRuntime(2)
            runtime.value = active.copy(commandTasks = active.commandTasks + active.commandTasks.first().copy(
                id = CommandTask.Id("completed"), status = CommandTask.Status.COMPLETED, completedAt = footerNow),
                commandMonitors = active.commandMonitors + active.commandMonitors.first().copy(
                    id = CommandMonitor.Id("completed"), status = CommandMonitor.Status.COMPLETED, completedAt = footerNow))
        }
        for (tag in tags.drop(1)) onNode(hasText("2") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()
    }

    @Test
    fun compactMetricsHaveExplicitRightPaddingAndTimeContextGap() = runDesktopComposeUiTest(width = 800, height = 250) {
        setContent { GromozekaTheme { Box(Modifier.width(620.dp)) {
            TestFooterSummary(footerRuntime(), emptyList(), footerTokenStats(), null, {})
        } } }
        val row = onNodeWithTag("conversation-footer-summary").fetchSemanticsNode().boundsInRoot
        val first = onNodeWithTag("conversation-message-count").fetchSemanticsNode().boundsInRoot
        val time = onNodeWithTag("conversation-elapsed-time").fetchSemanticsNode().boundsInRoot
        val context = onNodeWithTag("composer-context-usage").fetchSemanticsNode().boundsInRoot
        assertEquals(8f, context.left - time.right)
        assertTrue(abs(first.center.y - time.center.y) <= 1f)
        assertTrue(abs(first.center.y - context.center.y) <= 1f)
        // The summary tag is on the content inside its horizontal padding.
        assertEquals(16f, first.left)
        assertEquals(604f, context.right)
        assertTrue(context.right <= row.right)
        for (tag in listOf("conversation-elapsed-time", "composer-context-usage")) {
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
            assertEquals(1, layouts.single().lineCount)
            assertTrue(!layouts.single().isLineEllipsized(0))
        }
    }

    @Test
    fun hoveringEveryPillAddsNoRectangleOrOtherPaint() = runDesktopComposeUiTest(width = 800, height = 250) {
        mainClock.autoAdvance = false
        setContent { GromozekaTheme { Box(Modifier.width(620.dp)) {
            TestFooterSummary(footerRuntime(), emptyList(), footerTokenStats(), null, {})
        } } }
        for (tag in tags) {
            val node = onNodeWithTag(tag)
            val before = node.captureToImage().toPixelMap()
            node.performMouseInput { enter(center) }
            mainClock.advanceTimeBy(100)
            val after = node.captureToImage().toPixelMap()
            for (y in 0 until before.height) for (x in 0 until before.width) assertEquals(before[x, y], after[x, y], "$tag hover $x/$y")
            node.performMouseInput { exit() }
        }
    }

    @Test
    fun contextTooltipReportsCurrentOccupancyNotLifetimeTotalAndFramesStayFixed() = runDesktopComposeUiTest(width = 800, height = 250) {
        val percent = mutableStateOf(24)
        setContent { GromozekaTheme { Box(Modifier.width(620.dp)) {
            TestFooterSummary(footerRuntime(), emptyList(), footerTokenStats(percent.value), null, {})
        } } }
        val node = onNodeWithTag("composer-context-usage")
        val frame = node.fetchSemanticsNode().boundsInRoot
        for (value in listOf(9, 99, 100, 0, 24)) {
            runOnIdle { percent.value = value }
            node.assertTextEquals(contextUsageText(value))
            assertEquals(frame, node.fetchSemanticsNode().boundsInRoot)
            node.assertContentDescriptionEquals("Context window usage: $value%\n${String.format(java.util.Locale.US, "%,d", value * 1000)} of 100,000 tokens")
        }
    }

    @Test
    fun totalClockKeepsHoursWhileStageClockKeepsMinutesAndNeitherCanBeNegative() {
        val start = Instant.fromEpochSeconds(10000)
        assertEquals("4:12", conversationElapsedSince(start, Instant.fromEpochSeconds(10252)))
        assertEquals("2:00:00", conversationElapsedSince(start, Instant.fromEpochSeconds(17200)))
        assertEquals("65:00", activityElapsed(start, Instant.fromEpochSeconds(13900)))
        assertEquals("0:00", conversationElapsedSince(start, Instant.fromEpochSeconds(9999)))
        assertEquals("0:00", activityElapsed(start, Instant.fromEpochSeconds(9999)))
    }

    private val tags = listOf("conversation-queued-messages", "conversation-running-commands", "conversation-command-monitors")
}

private val footerNow = Instant.fromEpochSeconds(100)
internal fun footerRuntime(count: Int = 0): ConversationRuntimeSnapshot {
    val id = Conversation.Id("footer")
    val commands = (0 until count).map { index -> CommandTask(CommandTask.Id("command-$index"), id,
        ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"), command = "echo $index", workingDirectory = "/tmp",
        status = CommandTask.Status.WORKING, processId = index + 1L, processStartedAt = footerNow,
        outputFile = "/tmp/output", outputBytes = 0, createdAt = footerNow, updatedAt = footerNow) }
    val monitors = commands.mapIndexed { index, command -> CommandMonitor(CommandMonitor.Id("monitor-$index"), id, command.id,
        command.workerId, command.workspaceMountId, filterCommand = "grep READY", mode = CommandMonitor.Mode.CONTINUOUS,
        startFrom = CommandMonitor.StartFrom.NOW, status = CommandMonitor.Status.WORKING, sourceOutputCursor = 0,
        processId = 2000L + index, processStartedAt = footerNow, outputFile = "/tmp/monitor", errorFile = "/tmp/error",
        outputBytes = 0, eventOutputCursor = 0, createdAt = footerNow, updatedAt = footerNow) }
    return ConversationRuntimeSnapshot(1, id, null, pendingTasks = emptyList(), commandTasks = commands, commandMonitors = monitors)
}

internal fun footerPending(count: Int) = (0 until count).map { index -> PendingUserMessage(
    Conversation.Message(Conversation.Message.Id("pending-$index"), Conversation.Id("footer"), role = Conversation.Message.Role.USER,
        content = listOf(Conversation.Message.ContentItem.UserMessage("Queued instruction $index")), createdAt = footerNow),
    null, QueuedMessagePlacement.END_OF_TURN) }

internal fun footerTokenStats(percent: Int = 24) = TokenUsageStatistics.ThreadTotals(
    totalPromptTokens = 1_000_000, totalCompletionTokens = 0, totalCacheReadTokens = 0, totalCacheCreationTokens = 0,
    totalThinkingTokens = 0, lastCallTokens = null, recentCalls = emptyList(), currentContextSize = percent * 1000,
    contextStatus = TokenUsageStatistics.ContextStatus.AVAILABLE, contextWindowTokens = 100_000)
