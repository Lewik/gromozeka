package com.gromozeka.e2e

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationRenameE2eTest {
    @Test
    fun renameFromAnotherClientUpdatesTheTabWithRuntimeAndProjectPanelsClosed() =
        runGromozekaUiTest("conversation-rename-sync") { client ->
            val conversation = client.openConversation()
            val tabIndex = client.components.appViewModel.tabs.value.indexOfFirst { it.conversationId == conversation.id }
            waitForTag(UiTestTag.SessionTab(tabIndex))
            onNodeWithTag(UiTestTag.SessionTab(tabIndex).value).assertTextContains(conversation.displayName)

            client.openAnotherClient().use { writer ->
                runBlocking {
                    writer.components.conversationService.updateDisplayName(conversation.id, "Renamed by another client")
                }
                waitUntil(timeoutMillis = 30_000) {
                    client.components.appViewModel.conversations.value[conversation.id]?.displayName ==
                        "Renamed by another client"
                }
                onNodeWithTag(UiTestTag.SessionTab(tabIndex).value).assertTextContains("Renamed by another client")
            }
            saveScreenshot("conversation-rename-synced")
        }

    @Test
    fun renamePopupPersistsTheTitleAndUpdatesTheTab() = runGromozekaUiTest("conversation-rename-popup") { client ->
        val conversation = client.openConversation()
        val tabIndex = client.components.appViewModel.tabs.value.indexOfFirst { it.conversationId == conversation.id }
        waitForTag(UiTestTag.SessionTab(tabIndex))
        onNodeWithTag(UiTestTag.SessionTab(tabIndex).value).performMouseInput { enter(center) }
        waitForTag(UiTestTag.SessionTabRename(tabIndex))
        onNodeWithTag(UiTestTag.SessionTabRename(tabIndex).value).performClick()
        waitForTag(UiTestTag.NameEditDialog)
        onNodeWithTag(UiTestTag.NameEditInput.value).performTextReplacement("  Renamed from popup  ")
        onNodeWithTag(UiTestTag.NameEditSave.value).performClick()

        waitUntil(timeoutMillis = 30_000) {
            onAllNodesWithTag(UiTestTag.NameEditDialog.value).fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag(UiTestTag.NameEditDialog.value).assertDoesNotExist()
        onNodeWithTag(UiTestTag.SessionTab(tabIndex).value).assertTextContains("Renamed from popup")
        client.openAnotherClient().use { reader ->
            assertEquals("Renamed from popup", runBlocking {
                reader.components.conversationService.findById(conversation.id)
            }?.displayName)
        }
        saveScreenshot("conversation-rename-popup-saved")
    }

    private fun E2eClient.openConversation(): Conversation = runBlocking {
        val project = components.projectService.create("Rename ${UUID.randomUUID().toString().take(8)}", "Rename regression")
        val conversation = components.conversationService.create(
            projectId = project.id,
            participants = setOf(Conversation.Participant.User(components.authenticatedUser.id)),
            displayName = "Original title",
        )
        components.appViewModel.createTab(
            projectId = project.id,
            agent = null,
            conversationId = conversation.id,
            initialMessage = null,
            setAsCurrent = true,
            initiator = ConversationInitiator.User,
        )
        conversation
    }
}
