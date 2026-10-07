package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.GromozekaTheme
import com.gromozeka.presentation.ui.viewmodel.editableText
import com.gromozeka.presentation.ui.viewmodel.withEditedText
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class VisualInteractionItemTest {
    @Test fun eventUsesTimelineInsetsWhileTypedUserTextKeepsItsBubblePadding() = runComposeUiTest {
        val snapshot = Json.parseToJsonElement("""{"form":{},"data":{}}""").jsonObject
        val event = Conversation.Message.ContentItem.VisualInteraction("v", "Panel", 1, "layout-event", "go", "Go", snapshot)
        val time = Instant.fromEpochMilliseconds(0)
        val action = Conversation.Message(Conversation.Message.Id("action"), Conversation.Id("conversation"),
            role = Conversation.Message.Role.USER, content = listOf(event), createdAt = time)
        val typed = action.copy(id = Conversation.Message.Id("typed"),
            content = listOf(Conversation.Message.ContentItem.UserMessage("Typed user text")))
        val reply = action.copy(id = Conversation.Message.Id("reply"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("Reply"))))
        setContent {
            GromozekaTheme {
                Column(Modifier.width(420.dp)) {
                    MessageItem(MessageListEntry(action, MessageSegment.Content(0, event), true, true),
                        loadArtifactContent = { error("No artifacts") })
                    MessageItem(MessageListEntry(typed,
                        MessageSegment.RawMarkdownBlock(MarkdownKind.USER, 0, "Typed user text", 0, true, true), true, true),
                        loadArtifactContent = { error("No artifacts") })
                    MessageItem(MessageListEntry(reply,
                        MessageSegment.RawMarkdownBlock(MarkdownKind.ASSISTANT, 0, "Reply", 0, true, true), true, true),
                        loadArtifactContent = { error("No artifacts") })
                }
            }
        }
        val eventBounds = onNodeWithTag("visual-interaction-layout-event", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val replyBounds = onNodeWithText("Reply").fetchSemanticsNode().boundsInRoot
        val typedBounds = onNodeWithText("Typed user text").fetchSemanticsNode().boundsInRoot
        assertEquals(replyBounds.left, eventBounds.left, "A user action card must not inherit the text bubble's extra indent")
        assertEquals(with(density) { 8.dp.toPx() }, typedBounds.left - eventBounds.left)
    }

    @Test fun actionIsCompactUntilExpandedAndHasNoEditableUtterance() = runComposeUiTest {
        val state = Json.parseToJsonElement("""{"form":{"query":"saved at click"},"data":{"count":7}}""").jsonObject
        val event = Conversation.Message.ContentItem.VisualInteraction("v1", "Pipeline Runs", 2, "event-1", "refresh", "Refresh", state)
        val message = Conversation.Message(Conversation.Message.Id("click"), Conversation.Id("conversation"),
            role = Conversation.Message.Role.USER, content = listOf(event), createdAt = Instant.fromEpochMilliseconds(0))
        assertNull(message.editableText())
        assertFailsWith<IllegalArgumentException> { message.withEditedText("not a click") }
        var resizeCalls = 0
        setContent { GromozekaTheme { VisualInteractionItem(event) { resizeCalls++ } } }
        onNodeWithText("Pipeline Runs → Refresh").assertExists()
        onNodeWithTag("visual-interaction-data-event-1").assertDoesNotExist()
        onNodeWithTag("visual-interaction-toggle-event-1").performClick()
        onNodeWithTag("visual-interaction-data-event-1").assertExists().assertTextContains("saved at click", substring = true)
        onNodeWithTag("visual-interaction-toggle-event-1").performClick()
        onNodeWithTag("visual-interaction-data-event-1").assertDoesNotExist()
        runOnIdle { assertEquals(2, resizeCalls); assertEquals(state, event.snapshot) }
    }
}
