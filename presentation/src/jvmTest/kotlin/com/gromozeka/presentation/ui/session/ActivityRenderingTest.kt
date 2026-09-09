package com.gromozeka.presentation.ui.session

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.UiTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ActivityRenderingTest {
    @Test
    fun hiddenReasoningIsCompactAndHasNoExpansionAction() = runDesktopComposeUiTest {
        val message = activityTestMessage("hidden", Conversation.Message.ContentItem.Thinking("", signature = "opaque-signature"))
        setContent { ActivityTimelineFixture(listOf(message)) }
        val header = onNodeWithTag(UiTestTag.ActivityItem("hidden:0:content").value)
        header.assertIsDisplayed()
        val bounds = header.getBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 40.dp)
        header.assertHasNoClickAction()
        onNodeWithText("Hidden thinking").assertIsDisplayed()
        onNodeWithText("opaque-signature").assertDoesNotExist()
    }

    @Test
    fun streamingReasoningBecomesExpandableWhenReadableTextArrives() = runDesktopComposeUiTest {
        val thinking = mutableStateOf(Conversation.Message.ContentItem.Thinking("", state = Conversation.Message.BlockState.STREAMING))
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(listOf(activityTestMessage("stream", thinking.value)), onEntries = { entries = it }) }
        val header = onNodeWithTag(UiTestTag.ActivityItem("stream:0:content").value)
        header.assertHasNoClickAction()
        onNodeWithText("Thinking").assertIsDisplayed()
        onNodeWithText("Hidden thinking").assertDoesNotExist()
        runOnIdle { thinking.value = thinking.value.copy(thinking = "Readable thought") }
        header.performClick()
        onNodeWithText("Readable thought").assertIsDisplayed()
        runOnIdle { thinking.value = thinking.value.copy(thinking = "", state = Conversation.Message.BlockState.COMPLETE) }
        onNodeWithText("Hidden thinking").assertIsDisplayed()
        header.assertHasNoClickAction()
        runOnIdle { assertEquals(1, entries.size) }
    }

    @Test
    fun compactMixedGroupsExpandInEverySummaryStyle() {
        for (style in ActivitySummaryStyle.entries) {
            runDesktopComposeUiTest(width = 390, height = 844) {
                val message = activityTestMessage("mixed", Conversation.Message.ContentItem.Thinking("Readable thought"), activityTestCall("read"))
                setContent { ActivityTimelineFixture(listOf(message), summaryStyle = style) }
                val group = onNodeWithTag(UiTestTag.ActivityGroup("mixed:0:content").value)
                group.assertIsDisplayed()
                onNodeWithText("Readable thought").assertDoesNotExist()
                group.performClick()
                onNodeWithTag(UiTestTag.ActivityGroupContent("mixed:0:content").value).assertIsDisplayed()
                onNodeWithTag(UiTestTag.ActivityItem("mixed:0:content").value).performClick()
                onNodeWithText("Readable thought").assertIsDisplayed()
                onNodeWithTag(UiTestTag.ActivityItem("mixed:1:content").value).performClick()
                onNodeWithText("Tool output read").assertIsDisplayed()
                group.performClick()
                onNodeWithText("Readable thought").assertDoesNotExist()
                group.performClick()
                onNodeWithText("Readable thought").assertIsDisplayed()
                onNodeWithText("Tool output read").assertIsDisplayed()
            }
        }
    }

    @Test
    fun expandedLongReasoningUsesDistinctLazyEntries() = runDesktopComposeUiTest(width = 390, height = 500) {
        val text = (1..60).joinToString("\n\n") { "Paragraph $it of the reasoning." }
        val message = activityTestMessage("long", Conversation.Message.ContentItem.Thinking(text), activityTestCall("read"))
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(listOf(message), onEntries = { entries = it }) }
        onNodeWithTag(UiTestTag.ActivityGroup("long:0:content").value).performClick()
        onNodeWithTag(UiTestTag.ActivityItem("long:0:content").value).performClick()
        waitUntil(timeoutMillis = 10_000) { entries.count { it.segment is MessageSegment.MarkdownBlock } == 60 }
        runOnIdle { assertEquals(entries.size, entries.map { it.key }.distinct().size) }
        onNodeWithText("Paragraph 1 of the reasoning.").assertIsDisplayed()
        onNodeWithText("Paragraph 60 of the reasoning.").assertDoesNotExist()
    }

    @Test
    fun reasoningExpansionSurvivesToolCompletionAndRegrouping() = runDesktopComposeUiTest {
        val message = activityTestMessage("evolving", Conversation.Message.ContentItem.Thinking("Keep this open"), activityTestCall("read"))
        val results = mutableStateOf(emptyMap<String, Conversation.Message.ContentItem.ToolResult>())
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(listOf(message), results.value, onEntries = { entries = it }) }
        onNodeWithTag(UiTestTag.ActivityItem("evolving:0:content").value).performClick()
        onNodeWithText("Keep this open").assertIsDisplayed()
        runOnIdle { results.value = activityTestResults(listOf(message)) }
        onNodeWithTag(UiTestTag.ActivityGroup("evolving:0:content").value).performClick()
        onNodeWithText("Keep this open").assertIsDisplayed()
        runOnIdle { assertTrue(entries.map { it.key }.distinct().size == entries.size) }
    }
}
