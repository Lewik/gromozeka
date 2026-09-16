package com.gromozeka.e2e

import androidx.compose.ui.test.*
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class ConversationHistoryE2eTest {
    @Test
    fun largeHistoryLoadsPagesAndPreservesNavigationAndEditing() = runGromozekaUiTest("history-pagination", isolatedServer = true) { client ->
        val conversation = client.createHistory(1_200)
        client.openHistory(conversation)
        val tab = client.components.appViewModel.tabs.value.single { it.conversationId == conversation.id }
        waitUntil(timeoutMillis = 30_000) { tab.allMessages.value.isNotEmpty() && !tab.historyLoading.value }
        assertEquals(50, tab.allMessages.value.size)
        assertTrue(tab.allMessages.value.all { it.providerMetadata.isEmpty() })
        assertTrue(client.historyNetwork.responseSizes.isNotEmpty())
        assertTrue(client.historyNetwork.responseSizes.all { it < 256 * 1024 })
        println("History initial page: messages=${tab.allMessages.value.size}, responseBytes=${client.historyNetwork.responseSizes.last()}")
        assertNotNull(tab.olderHistory.value)
        val historyUrl = client.historyNetwork.urls.last().substringBefore('?')
        HttpClient().use { anonymous -> runBlocking {
            for (suffix in listOf("", "/messages/${conversation.id.value}-1199", "/selection/ALL", "/latest-user-message")) {
                assertEquals(HttpStatusCode.Unauthorized, anonymous.get(historyUrl + suffix).status)
            }
        } }
        waitForTag(UiTestTag.MessageItem("${conversation.id.value}-1199"))
        saveScreenshot("history-initial-tail")

        repeat(30) {
            if (tab.allMessages.value.size == 50) {
                onNodeWithTag(UiTestTag.MessageList.value).performTouchInput { swipeDown() }
                waitForIdle()
                if (tab.historyLoading.value) waitUntil(timeoutMillis = 30_000) { !tab.historyLoading.value }
            }
        }
        waitUntil(timeoutMillis = 30_000) { tab.allMessages.value.size > 50 && !tab.historyLoading.value }
        saveScreenshot("history-older-page-before-check")
        assertTrue(tab.allMessages.value.size <= 100, "Loaded ${tab.allMessages.value.size} messages after one boundary; requests=${client.historyNetwork.requests.get()}")
        onNodeWithTag(UiTestTag.UnreadMessagesButton.value).assertIsDisplayed()
        saveScreenshot("history-older-page")

        tab.requestMessageFocus(Conversation.Message.Id("${conversation.id.value}-20"))
        waitForTag(UiTestTag.MessageItem("${conversation.id.value}-20"))
        assertTrue(tab.allMessages.value.size <= 50)
        saveScreenshot("history-search-target")
        tab.toggleHistorySelection(ConversationMessageSelection.ALL)
        waitUntil(timeoutMillis = 10_000) { tab.uiState.value.selectedMessageIds.size == 1_200 }
        tab.clearMessageSelection()

        val editedId = Conversation.Message.Id("${conversation.id.value}-20")
        tab.startEditMessage(editedId)
        waitForTag(UiTestTag.EditMessageInput)
        onNodeWithTag(UiTestTag.EditMessageInput.value).performTextReplacement("Edited old message")
        onNodeWithTag(UiTestTag.EditMessageSaveButton.value).performClick()
        waitUntil(timeoutMillis = 30_000) { tab.uiState.value.editingMessageId == null && !tab.historyLoading.value }
        val original = runBlocking { client.components.conversationService.loadCurrentMessages(conversation.id) }
        val edited = original.single { editedId in it.originalIds }
        assertEquals(20_000, edited.content.filterIsInstance<Conversation.Message.ContentItem.Thinking>().single().signature?.length)
        assertTrue(original.filterNot { it.id == edited.id }.all { (it.providerMetadata["providerReplay"] as JsonPrimitive).content.length == 12_000 })
        saveScreenshot("history-old-message-edited")
    }

    @Test
    fun waitingForHistoryDoesNotBlockTypingSwitchingTabsSendingOrStopping() = runGromozekaUiTest("history-command-responsiveness", isolatedServer = true) { client ->
        val conversation = client.createHistory(1_000)
        client.openHistory(conversation)
        val tab = client.components.appViewModel.tabs.value.single { it.conversationId == conversation.id }
        waitUntil(timeoutMillis = 30_000) { tab.allMessages.value.isNotEmpty() && !tab.historyLoading.value }
        val other = client.createHistory(1)
        client.openHistory(other)
        val otherTab = client.components.appViewModel.tabs.value.single { it.conversationId == other.id }
        waitUntil(timeoutMillis = 30_000) { otherTab.allMessages.value.isNotEmpty() }
        val firstIndex = client.components.appViewModel.tabs.value.indexOf(tab)
        val otherIndex = client.components.appViewModel.tabs.value.indexOf(otherTab)
        onNodeWithTag(UiTestTag.SessionTab(firstIndex).value).performClick()
        val gate = CompletableDeferred<Unit>()
        client.historyNetwork.gate = gate
        try {
            tab.loadOlderHistory()
            waitUntil(timeoutMillis = 10_000) { tab.historyLoading.value }
            onNodeWithTag(UiTestTag.MessageInput.value).performTextInput("Message while history is loading")
            onNodeWithTag(UiTestTag.SessionTab(otherIndex).value).performClick()
            onNodeWithTag(UiTestTag.SessionTab(firstIndex).value).performClick()
            onNodeWithTag(UiTestTag.MessageInput.value).assertTextContains("Message while history is loading")
            onNodeWithTag(UiTestTag.SendButton.value).assertIsEnabled().performClick()
            runBlocking { withTimeout(3_000) {
                tab.stopExecution().join()
                client.components.conversationService.updateDisplayName(conversation.id, "Responsive while loading")
            } }
            waitUntil(timeoutMillis = 3_000) { tab.uiState.value.userInput.isBlank() }
            assertTrue(tab.historyLoading.value)
            saveScreenshot("history-loading-command-responsive")
        } finally {
            client.historyNetwork.gate = null
            gate.complete(Unit)
        }
        waitUntil(timeoutMillis = 30_000) { !tab.historyLoading.value }
        assertTrue(tab.allMessages.value.map { it.id }.distinct().size == tab.allMessages.value.size)
    }

    @Test
    fun toolResultsAcrossPageBoundariesLoadTheirFullContentOnDemand() = runGromozekaUiTest("history-tool-result", isolatedServer = true) { client ->
        val callId = Conversation.Message.ContentItem.ToolCall.Id("cross-page-call")
        val conversation = client.createHistory(80) { messages -> messages.mapIndexed { index, message -> when (index) {
            5 -> message.copy(role = Conversation.Message.Role.ASSISTANT, content = listOf(Conversation.Message.ContentItem.ToolCall(
                callId, Conversation.Message.ContentItem.ToolCall.Data("example_tool", buildJsonObject { put("input", JsonPrimitive("test")) }),
            )))
            79 -> message.copy(content = listOf(Conversation.Message.ContentItem.ToolResult(
                callId, "example_tool", listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("Tool output. ".repeat(10_000))), isError = true,
            )))
            else -> message
        } } }
        client.openHistory(conversation)
        val tab = client.components.appViewModel.tabs.value.single { it.conversationId == conversation.id }
        waitUntil(timeoutMillis = 30_000) { tab.allMessages.value.isNotEmpty() && !tab.historyLoading.value }
        val callMessageId = Conversation.Message.Id("${conversation.id.value}-5")
        tab.requestMessageFocus(callMessageId)
        waitUntil(timeoutMillis = 30_000) { tab.toolResultsMap.value[callId.value] != null && tab.allMessages.value.any { it.id == callMessageId } }
        assertTrue(tab.toolResultsMap.value.getValue(callId.value).isError)
        tab.toggleHistorySelection(ConversationMessageSelection.TOOL)
        waitUntil(timeoutMillis = 10_000) { tab.uiState.value.selectedMessageIds.size == 2 }
        tab.clearMessageSelection()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("history-details:${callMessageId.value}").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("history-details:${callMessageId.value}").performClick()
        waitUntil(timeoutMillis = 30_000) {
            (tab.toolResultsMap.value[callId.value]?.result?.firstOrNull() as? Conversation.Message.ContentItem.ToolResult.Data.Text)?.content?.length == 130_000
        }
        waitForIdle()
        onAllNodesWithText("New activity").assertCountEquals(0)
        saveScreenshot("history-tool-result-loaded")
    }

    private fun E2eClient.createHistory(size: Int, customize: (List<Conversation.Message>) -> List<Conversation.Message> = { it }): Conversation = runBlocking {
        val project = components.projectService.create("History ${UUID.randomUUID()}", "Pagination regression")
        val conversation = components.conversationService.create(project.id, setOf(Conversation.Participant.User(components.authenticatedUser.id)), "Large history")
        val now = Clock.System.now()
        seedHistory(conversation, customize((0 until size).map { index -> Conversation.Message(
            id = Conversation.Message.Id("${conversation.id.value}-$index"),
            conversationId = conversation.id,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("History message $index\n" + "Readable content. ".repeat(20))) +
                if (index == 20) listOf(Conversation.Message.ContentItem.Thinking("Preserved signed block", "s".repeat(20_000))) else emptyList(),
            providerMetadata = buildJsonObject { put("providerReplay", JsonPrimitive("x".repeat(12_000))) },
            createdAt = now + index.milliseconds,
        ) }))
        conversation
    }

    private fun E2eClient.openHistory(conversation: Conversation) = runBlocking {
        components.appViewModel.createTab(conversation.projectId, null, conversation.id, null, true, ConversationInitiator.User)
    }
}
