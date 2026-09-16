package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FollowLatestLazyColumnTest {
    @Test
    fun preservesDesktopScrollPositionUntilUnreadButtonIsClicked() {
        verifyFollowLatestBehavior(width = 1280, height = 800)
    }

    @Test
    fun preservesCompactScrollPositionUntilUnreadButtonIsClicked() {
        verifyFollowLatestBehavior(width = 390, height = 844)
    }

    @Test
    fun expandingLatestToolGroupKeepsItsHeaderVisible() = runDesktopComposeUiTest(
        width = 390,
        height = 500,
    ) {
        val messages = activityMessages()
        setContent { ActivityTimelineFixture(messages) }
        val groupTag = UiTestTag.ActivityGroup("tools:0:content").value
        waitForTag(groupTag)
        onNodeWithTag(groupTag).performClick()
        waitForIdle()
        onNodeWithTag(groupTag).assertIsDisplayed()
        onNodeWithTag(UiTestTag.UnreadMessagesButton.value).assertIsDisplayed()
    }

    @Test
    fun scrollToLatestKeepsToolGroupExpanded() = runDesktopComposeUiTest(
        width = 390,
        height = 500,
    ) {
        val messages = activityMessages()
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(messages, onEntries = { entries = it }) }
        val groupKey = "tools:0:content"
        waitForTag(UiTestTag.ActivityGroup(groupKey).value)
        onNodeWithTag(UiTestTag.ActivityGroup(groupKey).value).performClick()
        repeat(4) {
            onNodeWithTag(UiTestTag.MessageList.value).performTouchInput { swipeDown() }
            waitForIdle()
        }
        waitForTag(UiTestTag.UnreadMessagesButton.value)
        onNodeWithTag(UiTestTag.UnreadMessagesButton.value).performClick()
        waitForIdle()
        runOnIdle {
            kotlin.test.assertEquals(30, entries.count { it.segment is MessageSegment.Activity })
            kotlin.test.assertTrue(entries.any { it.groupContentKey == groupKey })
        }
    }

    @Test
    fun prependingAdjacentToolsPreservesTheExpandedGroup() = runDesktopComposeUiTest(width = 390, height = 500) {
        val messages = mutableStateListOf(activityTestMessage("tools", *(0 until 4).map { activityTestCall("call-$it") }.toTypedArray()))
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(messages, onEntries = { entries = it }) }
        val groupKey = "tools:0:content"
        waitForTag(UiTestTag.ActivityGroup(groupKey).value)
        onNodeWithTag(UiTestTag.ActivityGroup(groupKey).value).performClick()
        runOnIdle {
            messages.add(0, activityTestMessage("older-tools", activityTestCall("older-1"), activityTestCall("older-2")))
        }
        waitForIdle()
        runOnIdle {
            kotlin.test.assertTrue(entries.any { it.key == groupKey && it.segment is MessageSegment.ActivityGroup })
            kotlin.test.assertEquals(4, entries.count { it.segment is MessageSegment.Activity && it.activityDepth == 1 })
        }
    }

    @Test
    fun prependingHistoryPreservesTheVisibleRowAndDoesNotMarkItUnread() = runDesktopComposeUiTest(width = 390, height = 500) {
        val values = mutableStateListOf<Int>().apply { addAll(0..30) }
        setContent {
            MaterialTheme {
                FollowLatestLazyColumn(
                    items = values, itemKey = { it }, contentRevision = values.toList(),
                    unreadLabel = { "$it new messages" }, modifier = Modifier.fillMaxSize(),
                ) { value, _ ->
                    Text("Message $value", modifier = Modifier.fillMaxWidth().height(72.dp).testTag(itemTag(value)))
                }
            }
        }
        waitForTag(itemTag(30))
        repeat(5) {
            onNodeWithTag(UiTestTag.MessageList.value).performTouchInput { swipeDown() }
            waitForIdle()
        }
        waitForTag(itemTag(0))
        val before = onNodeWithTag(itemTag(0)).fetchSemanticsNode().boundsInRoot.top
        runOnIdle { values.addAll(0, (-30..-1).toList()) }
        waitForIdle()
        onNodeWithTag(itemTag(0)).assertIsDisplayed()
        kotlin.test.assertEquals(before, onNodeWithTag(itemTag(0)).fetchSemanticsNode().boundsInRoot.top)
        onAllNodesWithTag(UiTestTag.UnreadMessagesButton.value).fetchSemanticsNodes().forEach { node ->
            kotlin.test.assertFalse(node.config.toString().contains("new messages"))
        }
    }

    @Test
    fun prependingMarkdownKeepsTheSameMessageAtTheSameOffset() = runDesktopComposeUiTest(width = 800, height = 600) {
        fun message(index: Int) = activityTestMessage("markdown-$index", Conversation.Message.ContentItem.UserMessage("Message $index\n\n" + "A paragraph of text. ".repeat(15)))
        val messages = mutableStateListOf<Conversation.Message>().apply { addAll((0..30).map(::message)) }
        setContent { ActivityTimelineFixture(messages) }
        waitForTag(UiTestTag.MessageItem("markdown-30").value)
        repeat(12) {
            onNodeWithTag(UiTestTag.MessageList.value).performTouchInput { swipeDown() }
            waitForIdle()
        }
        val anchorTag = UiTestTag.MessageItem("markdown-0").value
        waitForTag(anchorTag)
        val before = onNodeWithTag(anchorTag).fetchSemanticsNode().boundsInRoot.top
        runOnIdle { messages.addAll(0, (-30..-1).map(::message)) }
        waitForIdle()
        onNodeWithTag(anchorTag).assertIsDisplayed()
        kotlin.test.assertEquals(before, onNodeWithTag(anchorTag).fetchSemanticsNode().boundsInRoot.top)
    }

    private fun activityMessages(): List<Conversation.Message> =
        (0..8).map { index ->
            activityTestMessage("text-$index", Conversation.Message.ContentItem.AssistantMessage(
                Conversation.Message.StructuredText("Message $index\n\n" + "A line of text.\n".repeat(4)),
            ))
        } + activityTestMessage("tools", *(0 until 30).map { activityTestCall("call-$it") }.toTypedArray())

    private fun verifyFollowLatestBehavior(width: Int, height: Int) = runDesktopComposeUiTest(
        width = width,
        height = height,
    ) {
        val values = mutableStateListOf<Int>().apply { addAll(0..30) }
        setContent {
            MaterialTheme {
                FollowLatestLazyColumn(
                    items = values,
                    itemKey = { it },
                    contentRevision = values.toList(),
                    unreadLabel = { "$it new messages" },
                    modifier = Modifier.fillMaxSize(),
                ) { value, _ ->
                    Text(
                        text = "Message $value",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .testTag(itemTag(value)),
                    )
                }
            }
        }

        waitForTag(itemTag(30))
        onNodeWithTag(itemTag(30)).assertIsDisplayed()

        runOnIdle { values += 31 }
        waitForTag(itemTag(31))
        onNodeWithTag(itemTag(31)).assertIsDisplayed()
        onNodeWithTag(UiTestTag.UnreadMessagesButton.value).assertDoesNotExist()

        repeat(3) {
            onNodeWithTag(UiTestTag.MessageList.value).performTouchInput { swipeDown() }
            waitForIdle()
        }
        waitForTag(itemTag(0))
        onNodeWithTag(itemTag(0)).assertIsDisplayed()
        onNodeWithTag(UiTestTag.UnreadMessagesButton.value).assertIsDisplayed()

        runOnIdle { values += 32 }
        waitForTag(UiTestTag.UnreadMessagesButton.value)
        onNodeWithTag(itemTag(32)).assertDoesNotExist()

        onNodeWithTag(UiTestTag.UnreadMessagesButton.value).performClick()
        waitForTag(itemTag(32))
        onNodeWithTag(itemTag(32)).assertIsDisplayed()
        onNodeWithTag(UiTestTag.UnreadMessagesButton.value).assertDoesNotExist()
    }

    private fun ComposeUiTest.waitForTag(tag: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun itemTag(value: Int): String = "follow-latest-item:$value"

}
