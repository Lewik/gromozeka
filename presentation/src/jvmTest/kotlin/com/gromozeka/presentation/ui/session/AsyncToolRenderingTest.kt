package com.gromozeka.presentation.ui.session

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.presentation.services.translation.data.EnglishTranslation
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO
import kotlin.test.*

class AsyncToolRenderingTest {
    @Test fun asyncCompletionUsesTheOrdinaryCollapsedToolActivity() = runDesktopComposeUiTest {
        val messages = completion("one", listOf(ContentItem.ToolResult.Data.Text("original async body")))
        setContent { ActivityTimelineFixture(messages, results(messages)) }
        val activity = onNodeWithTag(UiTestTag.ActivityItem("one-call:0:content").value)
        activity.assertIsDisplayed()
        onNodeWithText("original async body", substring = true).assertDoesNotExist()
        activity.performClick()
        onNodeWithText("original async body", substring = true).assertIsDisplayed()
        runOnIdle {
            assertIs<Conversation.Message.Author.Runtime>(messages[0].author)
            assertIs<Conversation.Message.Author.Runtime>(messages[1].author)
            assertEquals(Conversation.Message.Role.ASSISTANT, messages[0].role)
            assertEquals(Conversation.Message.Role.USER, messages[1].role)
        }
    }

    @Test fun multipleCompletionsUseTheExistingGroupWithoutMixingOutputs() = runDesktopComposeUiTest {
        val messages = completion("first", listOf(ContentItem.ToolResult.Data.Text("first async body"))) +
            completion("second", listOf(ContentItem.ToolResult.Data.Text("second async body")))
        setContent { ActivityTimelineFixture(messages, results(messages)) }
        val group = onNodeWithTag(UiTestTag.ActivityGroup("first-call:0:content").value)
        group.assertIsDisplayed()
        onNodeWithText("first async body", substring = true).assertDoesNotExist()
        onNodeWithText("second async body", substring = true).assertDoesNotExist()
        group.performClick()
        onNodeWithTag(UiTestTag.ActivityItem("first-call:0:content").value).performClick()
        onNodeWithText("first async body", substring = true).assertIsDisplayed()
        onNodeWithText("second async body", substring = true).assertDoesNotExist()
        onNodeWithTag(UiTestTag.ActivityItem("second-call:0:content").value).performClick()
        onNodeWithText("second async body", substring = true).assertIsDisplayed()
    }

    @Test fun failedAndCancelledOutcomesRemainDistinctInsideTheGroup() = runDesktopComposeUiTest {
        val messages = completion("failed", listOf(ContentItem.ToolResult.Data.Text("Effects may have occurred; do not repeat automatically")), error = true) +
            completion("cancelled", listOf(ContentItem.ToolResult.Data.Text("Cancelled before execution")), error = true,
                state = Conversation.Message.BlockState.INTERRUPTED)
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(messages, results(messages), onEntries = { entries = it }) }
        runOnIdle {
            val group = assertIs<MessageSegment.ActivityGroup>(entries.single().segment)
            assertEquals(listOf(ActivityState.FAILED, ActivityState.INTERRUPTED), group.activities.map { it.activity.state })
        }
        val translation = EnglishTranslation()
        onNodeWithContentDescription(translation.text("runtime.failedTaskLabel"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription(translation.text("chat.activity.interrupted"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(UiTestTag.ActivityGroup("failed-call:0:content").value).performClick()
        onNodeWithTag(UiTestTag.ActivityItem("failed-call:0:content").value).performClick()
        onNodeWithText("Effects may have occurred", substring = true).assertIsDisplayed()
        onNodeWithText("Cancelled before execution", substring = true).assertDoesNotExist()
    }

    @Test fun completedInvocationCanContainAnOngoingManagedCommand() = runDesktopComposeUiTest {
        val messages = completion("working", listOf(ContentItem.ToolResult.Data.Text("{\"status\":\"WORKING\",\"task_id\":\"still-running-command\"}")))
        var entries = emptyList<MessageListEntry>()
        setContent { ActivityTimelineFixture(messages, results(messages), onEntries = { entries = it }) }
        runOnIdle { assertEquals(ActivityState.COMPLETE, (entries.single().segment as MessageSegment.Activity).activity.state) }
        onNodeWithText("still-running-command", substring = true).assertDoesNotExist()
        onNodeWithTag(UiTestTag.ActivityItem("working-call:0:content").value).performClick()
        onNodeWithText("WORKING", substring = true).assertIsDisplayed()
        onNodeWithText("still-running-command", substring = true).assertIsDisplayed()
    }

    @Test fun canonicalPairIncludesCapturedArgumentsWithoutLoadingTheOriginalCall() = runDesktopComposeUiTest {
        val messages = completion("detached", listOf(ContentItem.ToolResult.Data.Text("detached result")))
        setContent { ActivityTimelineFixture(messages, results(messages)) }
        val activity = onNodeWithTag(UiTestTag.ActivityItem("detached-call:0:content").value)
        activity.assertIsDisplayed()
        activity.performClick()
        onNodeWithText("original input", substring = true).assertIsDisplayed()
        onNodeWithText("detached result", substring = true).assertIsDisplayed()
    }

    @Test fun imageArtifactKeepsItsIdentityAndLoadsOnlyAfterExpansion() = runDesktopComposeUiTest {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(image, "png", output)); output.toByteArray()
        }
        val id = Artifact.Id("completion-image")
        val reference = Artifact.Reference(id, "capture.png", "image/png", bytes.size.toLong(), Artifact.Purpose.TOOL_OUTPUT, Artifact.Kind.IMAGE)
        val messages = completion("image", listOf(ContentItem.ToolResult.Data.ArtifactData(reference)))
        val loaded = ConcurrentLinkedQueue<Artifact.Id>()
        setContent { ActivityTimelineFixture(messages, results(messages), loadArtifactContent = { requested -> loaded += requested; bytes }) }
        runOnIdle { assertTrue(loaded.isEmpty()) }
        onNodeWithContentDescription("capture.png").assertDoesNotExist()
        onNodeWithTag(UiTestTag.ActivityItem("image-call:0:content").value).performClick()
        waitUntil(timeoutMillis = 5_000) { loaded.size == 1 }
        waitForIdle()
        onNodeWithContentDescription("capture.png").assertIsDisplayed()
        runOnIdle {
            assertEquals(listOf(id), loaded.toList())
            assertEquals(reference, (messages[1].content.single() as ContentItem.ToolResult).result.filterIsInstance<ContentItem.ToolResult.Data.ArtifactData>().single().artifact)
        }
    }

    private fun completion(
        id: String,
        output: List<ContentItem.ToolResult.Data>,
        error: Boolean = false,
        state: Conversation.Message.BlockState = Conversation.Message.BlockState.COMPLETE,
    ): List<Conversation.Message> {
        val callId = syntheticToolCallId("async-tool:$id:completion")
        val metadata = buildJsonObject {
            putSyntheticMessageMetadata(SyntheticMessageKind.ASYNC_TOOL_COMPLETION,
                BackgroundActivityMessageSource(ContentItem.ToolCall.Id("source-$id"), AgentDefinition.Id("agent")))
            put("asyncToolOperationId", id)
        }
        val call = activityTestMessage("$id-call", ContentItem.ToolCall(callId,
            ContentItem.ToolCall.Data("grz_execute_python", buildJsonObject { put("code", "print('original input')") })))
            .copy(author = Conversation.Message.Author.Runtime(), providerMetadata = metadata,
                instructions = listOf(Conversation.Message.Instruction.RuntimeObservation("Runtime completion for $id; not a new execution")))
        val result = activityTestMessage("$id-result", ContentItem.ToolResult(callId, "grz_execute_python", output, isError = error, state = state))
            .copy(role = Conversation.Message.Role.USER, author = Conversation.Message.Author.Runtime(), providerMetadata = metadata)
        return listOf(call, result)
    }

    private fun results(messages: List<Conversation.Message>) = messages.flatMap { it.content }
        .filterIsInstance<ContentItem.ToolResult>().associateBy { it.toolUseId.value }
}
