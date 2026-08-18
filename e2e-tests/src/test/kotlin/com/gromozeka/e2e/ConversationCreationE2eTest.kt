package com.gromozeka.e2e

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test

class ConversationCreationE2eTest {
    @Test
    fun opensConversationAndAcceptsMessageInput() = runGromozekaUiTest("conversation-creation") { client ->
        val project = runBlocking {
            client.components.projectService.create(
                name = "Conversation project ${UUID.randomUUID().toString().take(8)}",
                description = "Project for conversation UI verification",
            )
        }

        waitForTag(UiTestTag.NewSessionButton(project.id.value))
        onNodeWithTag(UiTestTag.NewSessionButton(project.id.value).value).performClick()
        waitForTag(UiTestTag.SessionScreen)
        waitForTag(UiTestTag.MessageInput)

        val message = "Message prepared by Compose E2E"
        onNodeWithTag(UiTestTag.MessageInput.value).performTextInput(message)
        onNodeWithTag(UiTestTag.MessageInput.value).assertTextContains(message)
        waitUntil(timeoutMillis = 30_000) {
            runBlocking { client.components.conversationService.findByProject(project.id) }.isNotEmpty()
        }
    }
}
