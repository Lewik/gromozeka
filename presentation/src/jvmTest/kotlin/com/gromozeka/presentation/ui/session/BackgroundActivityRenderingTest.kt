package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.displayTitle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackgroundActivityRenderingTest {
    @Test
    fun completionSharesTheCollapsedActivityGroupAndRetainsItsOwnOutputAndIdentity() = runDesktopComposeUiTest {
        val agent = AgentDefinition.Id("agent")
        val author = Conversation.Message.Author.Agent(agent, "Agent")
        val launch = ContentItem.ToolCall(ContentItem.ToolCall.Id("launch-call"), ContentItem.ToolCall.Data("grz_execute_command", buildJsonObject { put("command", "sleep 1") }))
        val call = ContentItem.ToolCall(ContentItem.ToolCall.Id("completion-call"), ContentItem.ToolCall.Data("grz_get_command_task", buildJsonObject { put("task_id", "task") }))
        val result = ContentItem.ToolResult(call.id, call.call.name, listOf(ContentItem.ToolResult.Data.Text("completion-output")))
        val first = activityTestMessage("launch", launch).copy(author = author)
        val completion = activityTestMessage("completion", call).copy(providerMetadata = buildJsonObject {
            putSyntheticMessageMetadata(SyntheticMessageKind.COMMAND_TASK_COMPLETION, BackgroundActivityMessageSource(launch.id, agent))
            put("commandTaskId", "task")
        })
        val resultMessage = activityTestMessage("completion-result", result).copy(role = Conversation.Message.Role.USER, providerMetadata = completion.providerMetadata)
        val next = activityTestMessage("next", activityTestCall("next-call")).copy(author = author)
        val messages = listOf(first, completion, resultMessage, next)
        val results = activityTestResults(messages) + (call.id.value to result)
        setContent { ActivityTimelineFixture(messages, results) }
        val group = onNodeWithTag(UiTestTag.ActivityGroup("launch:0:content").value)
        val notification = onNodeWithTag(UiTestTag.ActivityItem("completion:0:content").value)
        group.assertIsDisplayed()
        notification.assertDoesNotExist()
        group.performClick()
        notification.assertIsDisplayed()
        notification.performClick()
        onNodeWithText("completion-output", substring = true).assertIsDisplayed()
        runOnIdle {
            assertNull(completion.author)
            assertNull(resultMessage.author)
            assertEquals("completion", completion.id.value)
            assertEquals(launch.id, completion.backgroundActivitySource?.toolCallId)
        }
    }

    @Test
    fun newRuntimeObservationAndPreparedCompactionVariantsHaveExplicitUiLabels() = runDesktopComposeUiTest {
        var status = ""
        var expected = ""
        setContent {
            MaterialTheme {
                val translation = LocalTranslation.current
                status = ConversationRuntimeTask.Payload.ApplyPreparedCompaction("prepared").runtimeStatusLabel(null, translation)
                expected = translation.runtime.historyMutationStatus
                Column {
                    Text(Conversation.Message.Instruction.RuntimeObservation("Runtime data").displayTitle(translation))
                    Text(status)
                }
            }
        }
        onNodeWithText("Runtime message").assertIsDisplayed()
        runOnIdle { assertEquals(expected, status) }
        onNodeWithText(status).assertIsDisplayed()
    }
}
