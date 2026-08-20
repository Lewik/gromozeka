package com.gromozeka.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MessageEditingE2eTest {
    @Test
    fun editsSelectedMessageThroughRemoteServer() = runGromozekaUiTest("message-editing") { client ->
        val project = runBlocking {
            client.components.projectService.create(
                name = "Editing project ${UUID.randomUUID().toString().take(8)}",
                description = "Project for message editing verification",
            )
        }

        waitForTag(UiTestTag.NewSessionButton(project.id.value))
        onNodeWithTag(UiTestTag.NewSessionButton(project.id.value).value).performClick()
        waitForTag(UiTestTag.SessionScreen)

        val conversation = runBlocking {
            client.components.conversationService.findByProject(project.id).single()
        }
        onNodeWithTag(UiTestTag.MessageInput.value).performTextInput("Original message")
        onNodeWithTag(UiTestTag.SendButton.value).performClick()
        waitUntil(timeoutMillis = 30_000) {
            runBlocking {
                client.components.conversationService.loadCurrentMessages(conversation.id).any { message ->
                    message.content
                        .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                        .any { it.text == "Original message" }
                }
            }
        }
        val originalMessage = runBlocking {
            client.components.conversationService.loadCurrentMessages(conversation.id)
                .first { message ->
                    message.content
                        .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                        .any { it.text == "Original message" }
                }
        }

        waitForTag(UiTestTag.MessageItem(originalMessage.id.value))
        onNodeWithTag(UiTestTag.MessageItem(originalMessage.id.value).value).performClick()
        onNodeWithTag(UiTestTag.EditSelectedMessageButton.value).assertIsEnabled().performClick()
        waitForTag(UiTestTag.EditMessageInput)
        onNodeWithTag(UiTestTag.EditMessageInput.value).performTextReplacement("Edited message")
        onNodeWithTag(UiTestTag.EditMessageSaveButton.value).performClick()

        waitUntil(timeoutMillis = 30_000) {
            runBlocking {
                client.components.conversationService.loadCurrentMessages(conversation.id)
                    .any { message ->
                        message.content
                            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                            .any { it.text == "Edited message" }
                    }
            }
        }

        onNodeWithTag(UiTestTag.EditMessageInput.value).assertDoesNotExist()
        val editedMessage = runBlocking {
            client.components.conversationService.loadCurrentMessages(conversation.id)
                .first { message ->
                    message.content
                        .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                        .any { it.text == "Edited message" }
                }
        }
        assertNotEquals(originalMessage.id, editedMessage.id)
        assertEquals(listOf(originalMessage.id), editedMessage.originalIds)
    }
}
