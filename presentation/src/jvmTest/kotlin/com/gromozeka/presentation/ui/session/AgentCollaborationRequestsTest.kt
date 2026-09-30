package com.gromozeka.presentation.ui.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.*
import com.gromozeka.presentation.ui.GromozekaTheme
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class AgentCollaborationRequestsTest {
    private val a = AgentEndpoint(Conversation.Id("a"), Conversation.Thread.Id("ta"), AgentDefinition.Id("A"))
    private val b = AgentEndpoint(Conversation.Id("b"), Conversation.Thread.Id("tb"), AgentDefinition.Id("B"))
    private val request = AgentRequest("r", a, b, User.Id("u"), "Synthetic delegated work", Instant.fromEpochMilliseconds(1))
    @Test fun waitingRequestRemainsVisibleWhileConversationIsIdle() = runDesktopComposeUiTest {
        setContent { GromozekaTheme { AgentCollaborationRequests(listOf(request.copy(state = AgentRequest.State.WAITING_USER)), b.conversationId) } }
        onNodeWithTag("runtime-collaboration").assertIsDisplayed()
        onNodeWithText("← A · Waiting for you").assertIsDisplayed()
        onNodeWithText("Synthetic delegated work").assertIsDisplayed()
    }
    @Test fun sourceCanInspectTheResultWithoutTreatingItAsAUserReply() = runDesktopComposeUiTest {
        setContent { GromozekaTheme { AgentCollaborationRequests(listOf(request.copy(state = AgentRequest.State.COMPLETED, result = "Synthetic result")), a.conversationId) } }
        onNodeWithText("→ B · Completed").assertIsDisplayed()
        onNodeWithText("Synthetic result").assertIsDisplayed()
    }
}
