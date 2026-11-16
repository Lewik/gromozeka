package com.gromozeka.presentation.ui.session

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.presentation.services.translation.data.EnglishTranslation
import com.gromozeka.presentation.ui.LocalTranslation
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ServiceMessageRenderingTest {
    @Test fun peerHeaderShowsRouteWithoutPretendingTheMessageIsHumanInput() = runDesktopComposeUiTest(width = 650, height = 600) {
        val sender = PeerMessageEndpoint(Conversation.Id("source"), Conversation.Thread.Id("source-thread"), AgentDefinition.Id("reviewer"), "Review", "Reviewer")
        val recipient = PeerMessageEndpoint(Conversation.Id("target"), Conversation.Thread.Id("target-thread"), AgentDefinition.Id("developer"), "Implementation", "Developer")
        val block = ContentItem.PeerMessage(ContentItem.PeerMessage.Kind.RESULT, sender, recipient, "Detailed review body", "request-1")
        val translation = EnglishTranslation()
        val expanded = mutableStateOf(false)
        var opened: Conversation.Id? = null
        setContent {
            CompositionLocalProvider(LocalTranslation provides translation) {
                ServiceMessageItem(block, "peer", expanded.value, { expanded.value = !expanded.value },
                    loadArtifactContent = { error("No attachments") }, onOpenConversation = { opened = it }, onOpenMessage = null)
            }
        }
        onNodeWithText("Reviewer · «Review» → Developer · Result").assertIsDisplayed()
        onNodeWithText("Detailed review body").assertDoesNotExist()
        onNodeWithTag("service-source:peer").performClick()
        assertEquals(sender.conversationId, opened)
        assertFalse(expanded.value)
        onNodeWithText(serviceMessageTitle(block, translation)).performClick()
        onNodeWithText("request_id: request-1").assertIsDisplayed()
        onNodeWithText("source_thread: source-thread").assertIsDisplayed()
    }

    @Test fun failureRemainsVisibleWhileDiagnosticOutputIsCollapsed() = runDesktopComposeUiTest(width = 650, height = 500) {
        val translation = EnglishTranslation()
        val block = ContentItem.RuntimeNotification(ContentItem.RuntimeNotification.Level.ERROR, "Run tests",
            kind = ContentItem.RuntimeNotification.Kind.COMMAND_COMPLETED,
            attachments = listOf(ContentItem.ToolResult.Data.Text("Private diagnostic details")))
        val expanded = mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalTranslation provides translation) {
                ServiceMessageItem(block, "command", expanded.value, { expanded.value = !expanded.value },
                    loadArtifactContent = { error("No attachments") }, onOpenConversation = null, onOpenMessage = null)
            }
        }
        onNodeWithText(serviceMessageTitle(block, translation)).assertIsDisplayed()
        assertContains(serviceMessageTitle(block, translation), translation.text("runtime.failedTaskLabel"))
        onNodeWithText("Private diagnostic details").assertDoesNotExist()
        onNodeWithText(serviceMessageTitle(block, translation)).performClick()
        onNodeWithText("Private diagnostic details").assertIsDisplayed()
    }

    @Test fun promptReplacementLinksToTheExactHistoricalMessage() = runDesktopComposeUiTest(width = 650, height = 500) {
        val previous = Conversation.Message.Id("turn:first:prompt:agent")
        val block = ContentItem.PromptMessage(AgentDefinition.Id("agent"), "Developer",
            PromptSnapshot(listOf(PromptSnapshot.Entry.Catalog(Prompt.Id("rules"), "Rules", "Updated instructions")), "a".repeat(64)),
            ContentItem.PromptMessage.Action.REPLACE, previous)
        val translation = EnglishTranslation()
        var opened: Conversation.Message.Id? = null
        setContent {
            CompositionLocalProvider(LocalTranslation provides translation) {
                ServiceMessageItem(block, "prompt", true, {}, loadArtifactContent = { error("No attachments") },
                    onOpenConversation = null, onOpenMessage = { opened = it }, replacementPromptId = Conversation.Message.Id("newer"))
            }
        }
        onNodeWithText(translation.text("chat.service.previousPrompt")).performClick()
        assertEquals(previous, opened)
        onNodeWithText(translation.text("chat.service.newerPrompt")).performClick()
        assertEquals(Conversation.Message.Id("newer"), opened)
    }
}
