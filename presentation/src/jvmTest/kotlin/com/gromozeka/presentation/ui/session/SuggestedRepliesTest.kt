package com.gromozeka.presentation.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.viewmodel.appendComposerText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class SuggestedRepliesTest {
    @Test
    fun desktopSuggestionAddsToDraftWithoutSending() {
        verifySuggestionSelection(width = 1280, height = 800)
    }

    @Test
    fun compactSuggestionAddsToDraftWithoutSending() {
        verifySuggestionSelection(width = 390, height = 844)
    }

    @Test
    fun identicalSuggestionsReturnForANewAssistantMessage() = runDesktopComposeUiTest {
        var options by mutableStateOf(replyOptions("first"))
        setContent {
            MaterialTheme {
                SuggestedReplyChips(
                    options = options,
                    onSuggestionSelected = {},
                )
            }
        }

        onNodeWithTag(UiTestTag.SuggestedReply(0).value).performClick()
        onNodeWithTag(UiTestTag.SuggestedReplies.value).assertDoesNotExist()

        runOnIdle {
            options = replyOptions("second")
        }
        onNodeWithTag(UiTestTag.SuggestedReplies.value).assertIsDisplayed()
    }

    @Test
    fun suggestionsBelongOnlyToLatestAssistantBoundary() {
        val assistant = message(
            id = "assistant",
            role = Conversation.Message.Role.ASSISTANT,
            content = Conversation.Message.ContentItem.AssistantMessage(
                Conversation.Message.StructuredText(
                    fullText = "Choose",
                    suggestedReplies = listOf("Yes", "No"),
                )
            ),
        )
        val user = message(
            id = "user",
            role = Conversation.Message.Role.USER,
            content = Conversation.Message.ContentItem.UserMessage("Another question"),
        )

        assertEquals(
            SuggestedReplyOptions(
                sourceMessageId = Conversation.Message.Id("assistant"),
                values = listOf("Yes", "No"),
            ),
            latestSuggestedReplies(listOf(assistant)),
        )
        assertEquals(null, latestSuggestedReplies(listOf(assistant, user)))
    }

    @Test
    fun selectedSuggestionPreservesExistingDraft() {
        assertEquals("Draft Continue", appendComposerText("Draft", "Continue"))
        assertEquals("Draft\nContinue", appendComposerText("Draft\n", " Continue "))
    }

    private fun verifySuggestionSelection(width: Int, height: Int) = runDesktopComposeUiTest(
        width = width,
        height = height,
    ) {
        var selected: String? = null
        setContent {
            MaterialTheme {
                SuggestedReplyChips(
                    options = replyOptions("assistant"),
                    onSuggestionSelected = { selected = it },
                )
            }
        }

        onNodeWithTag(UiTestTag.SuggestedReplies.value).assertIsDisplayed()
        onNodeWithTag(UiTestTag.SuggestedReply(1).value).performClick()

        runOnIdle {
            assertEquals("No", selected)
        }
        onNodeWithTag(UiTestTag.SuggestedReplies.value).assertDoesNotExist()
    }

    private fun replyOptions(messageId: String) = SuggestedReplyOptions(
        sourceMessageId = Conversation.Message.Id(messageId),
        values = listOf("Yes", "No", "Show details"),
    )

    private fun message(
        id: String,
        role: Conversation.Message.Role,
        content: Conversation.Message.ContentItem,
    ): Conversation.Message = Conversation.Message(
        id = Conversation.Message.Id(id),
        conversationId = Conversation.Id("conversation"),
        role = role,
        content = listOf(content),
        createdAt = Clock.System.now(),
    )
}
