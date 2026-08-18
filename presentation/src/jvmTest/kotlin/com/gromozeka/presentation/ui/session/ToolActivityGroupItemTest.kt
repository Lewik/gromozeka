package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ToolActivityGroupItemTest {
    @Test
    fun successfulDesktopGroupStartsCollapsedAndCanExpand() {
        verifySuccessfulGroup(width = 1280, height = 800)
    }

    @Test
    fun successfulCompactGroupStartsCollapsedAndCanExpand() {
        verifySuccessfulGroup(width = 390, height = 844)
    }

    @Test
    fun unresolvedGroupStartsExpanded() = runDesktopComposeUiTest(width = 1280, height = 800) {
        val group = group()
        val firstCallId = group.calls.first().content.id.value
        setContent {
            MaterialTheme {
                ToolActivityGroupItem(
                    group = group,
                    toolResultsMap = emptyMap(),
                    workspaceRootPath = null,
                    loadArtifactContent = { byteArrayOf() },
                )
            }
        }

        onNodeWithTag(UiTestTag.ToolActivityGroupContent(firstCallId).value).assertIsDisplayed()
    }

    private fun verifySuccessfulGroup(width: Int, height: Int) = runDesktopComposeUiTest(
        width = width,
        height = height,
    ) {
        val group = group()
        val firstCallId = group.calls.first().content.id.value
        setContent {
            MaterialTheme {
                ToolActivityGroupItem(
                    group = group,
                    toolResultsMap = group.calls.associate { reference ->
                        reference.content.id.value to toolResult(reference.content.id)
                    },
                    workspaceRootPath = null,
                    loadArtifactContent = { byteArrayOf() },
                )
            }
        }

        onNodeWithTag(UiTestTag.ToolActivityGroup(firstCallId).value).assertIsDisplayed()
        onNodeWithTag(UiTestTag.ToolActivityGroupContent(firstCallId).value).assertDoesNotExist()

        onNodeWithTag(UiTestTag.ToolActivityGroup(firstCallId).value).performClick()

        onNodeWithTag(UiTestTag.ToolActivityGroupContent(firstCallId).value).assertIsDisplayed()
    }

    private fun group(): MessageSegment.ToolActivityGroup = MessageSegment.ToolActivityGroup(
        calls = listOf("call-1", "call-2").mapIndexed { index, id ->
            ToolCallReference(
                messageId = Conversation.Message.Id("message-1"),
                contentIndex = index,
                content = toolCall(id),
            )
        },
    )

    private fun toolCall(id: String) = Conversation.Message.ContentItem.ToolCall(
        id = Conversation.Message.ContentItem.ToolCall.Id(id),
        call = Conversation.Message.ContentItem.ToolCall.Data(
            name = "grz_read_file",
            input = buildJsonObject {},
        ),
    )

    private fun toolResult(id: Conversation.Message.ContentItem.ToolCall.Id) =
        Conversation.Message.ContentItem.ToolResult(
            toolUseId = id,
            toolName = "grz_read_file",
            result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("ok")),
        )
}
