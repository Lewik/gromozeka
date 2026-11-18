package com.gromozeka.presentation.ui.session

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AsyncToolRenderingTest {
    @Test
    fun asyncOutcomeUsesCollapsedToolActivityAndKeepsItsCanonicalData() = runDesktopComposeUiTest {
        val message = completion("one", "async-output-one")
        val notification = message.content.single()
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(listOf(message), onEntries = { entries = it }) }

        val activity = onNodeWithTag(UiTestTag.ActivityItem("one:0:content").value)
        activity.assertIsDisplayed()
        onNodeWithText("async-output-one", substring = true).assertDoesNotExist()
        onNodeWithText("source_thread_id", substring = true).assertDoesNotExist()
        onNodeWithText("operation-one", substring = true).assertDoesNotExist()
        onNodeWithText("output-one.txt", substring = true).assertDoesNotExist()
        onNodeWithText("Async tool invocation completed", substring = true).assertDoesNotExist()

        activity.performClick()
        onNodeWithText("async-output-one", substring = true).assertIsDisplayed()
        onNodeWithText("source_thread_id", substring = true).assertIsDisplayed()
        onNodeWithText("operation-one", substring = true).assertIsDisplayed()
        onNodeWithText("output-one.txt", substring = true).assertIsDisplayed()
        runOnIdle {
            assertSame(message, entries.single().message)
            assertSame(notification, entries.single().message.content.single())
            assertIs<ContentItem.RuntimeNotification>(notification)
            assertIs<Conversation.Message.Author.Runtime>(message.author)
            assertEquals(Conversation.Message.Role.RUNTIME_NOTIFICATION, message.role)
        }
    }

    @Test
    fun multipleAsyncOutcomesUseExistingCollapsedGroupWithoutMixingResults() = runDesktopComposeUiTest {
        val first = completion("first", "first-private-output")
        val second = completion("second", "second-private-output")
        setContent { ActivityTimelineFixture(listOf(first, second)) }

        val group = onNodeWithTag(UiTestTag.ActivityGroup("first:0:content").value)
        group.assertIsDisplayed()
        onNodeWithTag(UiTestTag.ActivityItem("first:0:content").value).assertDoesNotExist()
        onNodeWithTag(UiTestTag.ActivityItem("second:0:content").value).assertDoesNotExist()
        onNodeWithText("first-private-output", substring = true).assertDoesNotExist()
        onNodeWithText("second-private-output", substring = true).assertDoesNotExist()

        group.performClick()
        onNodeWithTag(UiTestTag.ActivityItem("first:0:content").value).performClick()
        onNodeWithText("first-private-output", substring = true).assertIsDisplayed()
        onNodeWithText("second-private-output", substring = true).assertDoesNotExist()
        onNodeWithTag(UiTestTag.ActivityItem("second:0:content").value).performClick()
        onNodeWithText("second-private-output", substring = true).assertIsDisplayed()
    }

    @Test
    fun failureAndCancellationStayDistinctAndUngrouped() = runDesktopComposeUiTest {
        val unknown = completion("unknown", "unknown-output", ContentItem.RuntimeNotification.Level.ERROR,
            "OUTCOME_UNKNOWN", "Unknown async outcome; effects may have occurred; do not repeat")
        val cancelled = completion("cancelled", "cancelled-output", ContentItem.RuntimeNotification.Level.WARNING,
            "CANCELLED", "Async tool invocation was cancelled before execution")
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(listOf(unknown, cancelled), onEntries = { entries = it }) }

        runOnIdle {
            assertEquals(listOf(ActivityState.FAILED, ActivityState.INTERRUPTED), entries.mapNotNull {
                (it.segment as? MessageSegment.Activity)?.activity?.state
            })
        }
        onNodeWithTag(UiTestTag.ActivityGroup("unknown:0:content").value).assertDoesNotExist()
        onNodeWithText("Unknown async outcome; effects may have occurred; do not repeat").assertIsDisplayed()
        onNodeWithTag(UiTestTag.ActivityItem("unknown:0:content").value).performClick()
        onNodeWithText("unknown-output", substring = true).assertIsDisplayed()
        onNodeWithText("cancelled-output", substring = true).assertDoesNotExist()
    }

    @Test
    fun completionWithoutLoadedCallOrToolNameStillRendersAsActivity() = runDesktopComposeUiTest {
        val notification = ContentItem.RuntimeNotification(
            level = ContentItem.RuntimeNotification.Level.INFO,
            content = "Stored completion without a loaded source call",
            kind = ContentItem.RuntimeNotification.Kind.TOOL_COMPLETED,
            attachments = listOf(ContentItem.ToolResult.Data.Text("detached-output")),
        )
        val message = activityTestMessage("detached", notification).copy(
            role = Conversation.Message.Role.RUNTIME_NOTIFICATION,
            author = Conversation.Message.Author.Runtime(),
        )
        var title = ""
        setContent {
            title = LocalTranslation.current.text("chat.service.runtime")
            ActivityTimelineFixture(listOf(message))
        }
        val activity = onNodeWithTag(UiTestTag.ActivityItem("detached:0:content").value)
        activity.assertIsDisplayed()
        onNodeWithText(title).assertIsDisplayed()
        onNodeWithText("detached-output").assertDoesNotExist()
        activity.performClick()
        onNodeWithText("detached-output").assertIsDisplayed()
    }

    @Test
    fun aCompletedInvocationCanStillReportAnOngoingCommand() = runDesktopComposeUiTest {
        val message = completion("working", """{"status":"WORKING","task_id":"still-running-command"}""")
        setContent { ActivityTimelineFixture(listOf(message)) }

        val activity = onNodeWithTag(UiTestTag.ActivityItem("working:0:content").value)
        activity.assertIsDisplayed()
        onNodeWithText("Async tool invocation completed", substring = true).assertDoesNotExist()
        onNodeWithText("still-running-command", substring = true).assertDoesNotExist()
        activity.performClick()
        onNodeWithText("WORKING", substring = true).assertIsDisplayed()
        onNodeWithText("still-running-command", substring = true).assertIsDisplayed()
    }

    @Test
    fun unrelatedRuntimeNoticesRemainReadable() = runDesktopComposeUiTest {
        val notice = ContentItem.RuntimeNotification(
            level = ContentItem.RuntimeNotification.Level.INFO,
            content = "Ordinary Runtime notice",
        )
        val message = activityTestMessage("notice", notice).copy(
            role = Conversation.Message.Role.RUNTIME_NOTIFICATION,
            author = Conversation.Message.Author.Runtime(),
        )
        setContent { ActivityTimelineFixture(listOf(message)) }
        onNodeWithText("Ordinary Runtime notice").assertIsDisplayed()
        onNodeWithTag(UiTestTag.ActivityItem("notice:0:content").value).assertDoesNotExist()
    }

    private fun completion(
        id: String,
        output: String,
        level: ContentItem.RuntimeNotification.Level = ContentItem.RuntimeNotification.Level.INFO,
        status: String = "SUCCEEDED",
        summary: String = "Async tool invocation completed: grz_execute_python",
    ): Conversation.Message = activityTestMessage(id, ContentItem.RuntimeNotification(
        level = level,
        content = summary,
        toolUseId = ContentItem.ToolCall.Id("source-$id"),
        kind = ContentItem.RuntimeNotification.Kind.TOOL_COMPLETED,
        referenceId = "operation-$id",
        details = buildJsonObject {
            put("status", status)
            put("tool_name", "grz_execute_python")
            put("source_thread_id", "source-thread-$id")
            put("origin_readonly", true)
        },
        attachments = listOf(
            ContentItem.ToolResult.Data.Text(output),
            ContentItem.ToolResult.Data.ArtifactData(Artifact.Reference(
                id = Artifact.Id("artifact-$id"), fileName = "output-$id.txt", mediaType = "text/plain",
                sizeBytes = 12, purpose = Artifact.Purpose.TOOL_OUTPUT, kind = Artifact.Kind.FILE,
            )),
        ),
    )).copy(role = Conversation.Message.Role.RUNTIME_NOTIFICATION, author = Conversation.Message.Author.Runtime())
}
