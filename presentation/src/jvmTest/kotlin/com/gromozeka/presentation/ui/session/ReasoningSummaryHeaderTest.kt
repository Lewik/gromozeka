package com.gromozeka.presentation.ui.session

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class ReasoningSummaryHeaderTest {
    @Test
    fun providerSummaryBecomesTheHeadingAndExpandedDetailsKeepTheOriginalBody() {
        for ((source, expected) in listOf(
            "**Check gateway configuration**" to "Check gateway configuration",
            "### Проверить настройки шлюза" to "Проверить настройки шлюза",
            "Compare the returned reasoning fields." to "Compare the returned reasoning fields.",
        )) {
            runDesktopComposeUiTest {
                val body = "Details returned by the provider remain available here."
                val text = "$source\n\n$body"
                val thinking = Conversation.Message.ContentItem.Thinking(text, signature = "opaque-provider-state")
                val message = activityTestMessage("reasoning", thinking).copy(providerMetadata = buildJsonObject {
                    put("provider", "OPENAI_COMPATIBLE")
                    put("connectionId", "truefoundry")
                })
                setContent { ActivityTimelineFixture(listOf(message)) }
                val header = onNodeWithTag(UiTestTag.ActivityItem("reasoning:0:content").value)
                onNodeWithText(expected).assertIsDisplayed().assertHasClickAction()
                onNodeWithText("Thinking").assertDoesNotExist()
                onNodeWithText(body, substring = true).assertDoesNotExist()
                onNodeWithText("opaque-provider-state", substring = true).assertDoesNotExist()
                header.performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText(body, substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText(body, substring = true).assertIsDisplayed()
                header.performClick()
                onNodeWithText(body, substring = true).assertDoesNotExist()
                onNodeWithText(expected).assertIsDisplayed()
                runOnIdle {
                    assertEquals(text, thinking.thinking)
                    assertEquals("opaque-provider-state", thinking.signature)
                    assertEquals(listOf(thinking), message.content)
                }
            }
        }
    }

    @Test
    fun emptyAndRedactedBlocksKeepGenericLabelsWithoutExposingOpaqueState() = runDesktopComposeUiTest {
        val block = mutableStateOf(Conversation.Message.ContentItem.Thinking("", "opaque-provider-state"))
        setContent { ActivityTimelineFixture(listOf(activityTestMessage("hidden", block.value))) }
        val header = onNodeWithTag(UiTestTag.ActivityItem("hidden:0:content").value)
        for (kind in Conversation.Message.ContentItem.Thinking.Kind.entries) {
            runOnIdle { block.value = block.value.copy(kind = kind) }
            onNodeWithText("Hidden thinking").assertIsDisplayed()
            header.assertHasNoClickAction()
            onNodeWithText("opaque-provider-state", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun longSummaryTitleIsEllipsizedWithoutGrowingTheActivityRow() = runDesktopComposeUiTest(width = 390, height = 500) {
        val block = mutableStateOf(Conversation.Message.ContentItem.Thinking("Short title\n\nDetails"))
        setContent { ActivityTimelineFixture(listOf(activityTestMessage("reasoning", block.value))) }
        val header = onNodeWithTag(UiTestTag.ActivityItem("reasoning:0:content").value)
        val initialHeight = header.fetchSemanticsNode().boundsInRoot.height
        val long = "Подробный заголовок ".repeat(100).trim()
        runOnIdle { block.value = block.value.copy(thinking = "$long\n\nDetails") }
        onNodeWithText(long).assertIsDisplayed()
        assertEquals(initialHeight, header.fetchSemanticsNode().boundsInRoot.height)
    }
}
