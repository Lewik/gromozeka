package com.gromozeka.server

import com.gromozeka.application.service.ConversationSearchApplicationService
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationSearchHit
import com.gromozeka.domain.model.ConversationSearchPage
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ControlMcpConversationSearchToolsTest {
    @Test
    fun `message search returns bounded page data and forwards opaque cursor`() = runBlocking {
        val context = testControlMcpContext()
        val project = project()
        val request = ConversationSearchRequest(
            query = "needle",
            projectId = project.id,
            threadScope = ConversationSearchRequest.ThreadScope.ALL,
            includeMetadataMatches = false,
            limit = 2,
            cursor = "opaque-cursor",
        )
        val service = Mockito.mock(ConversationSearchApplicationService::class.java)
        Mockito.`when`(service.search(context.user.id, request)).thenReturn(
            ConversationSearchPage(
                hits = listOf(hit(project)),
                nextCursor = "next-cursor",
            )
        )
        val tool = ControlMcpConversationSearchTools(service).tools.single()

        val response = tool.invokeStructured(
            context,
            buildJsonObject {
                put("query", "needle")
                put("projectId", project.id.value)
                put("threadScope", "ALL")
                put("limit", 2)
                put("cursor", "opaque-cursor")
            },
        )

        assertTrue(response.getValue("success").jsonPrimitive.content.toBoolean())
        val result = response.getValue("result").jsonObject
        val returnedHit = result.getValue("hits").jsonArray.single().jsonObject
        assertEquals("message-1", returnedHit.getValue("messageId").jsonPrimitive.content)
        assertEquals("A bounded excerpt", returnedHit.getValue("excerpt").jsonPrimitive.content)
        assertEquals("next-cursor", result.getValue("nextCursor").jsonPrimitive.content)
        assertTrue(result.getValue("hasMore").jsonPrimitive.content.toBoolean())
        assertFalse(returnedHit.containsKey("content"))
        assertTrue(tool.definition.annotations?.readOnlyHint == true)
        Mockito.verify(service).search(context.user.id, request)
    }

    private fun project(): Project {
        val timestamp = Instant.parse("2026-08-19T10:00:00Z")
        return Project(
            id = Project.Id("project-1"),
            name = "Project",
            createdAt = timestamp,
            lastUsedAt = timestamp,
        )
    }

    private fun hit(project: Project): ConversationSearchHit {
        val timestamp = Instant.parse("2026-08-19T10:00:00Z")
        val conversation = Conversation(
            id = Conversation.Id("conversation-1"),
            projectId = project.id,
            participants = setOf(
                Conversation.Participant.User(User.Id("user-1")),
                Conversation.Participant.Agent(AgentDefinition.Id("agent-1")),
            ),
            displayName = "Conversation",
            currentThread = Conversation.Thread.Id("thread-1"),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        return ConversationSearchHit(
            project = project,
            conversation = conversation,
            matchKind = ConversationSearchHit.MatchKind.MESSAGE,
            threadId = conversation.currentThread,
            messageId = Conversation.Message.Id("message-1"),
            role = Conversation.Message.Role.USER,
            matchedAt = timestamp,
            excerpt = "A bounded excerpt",
        )
    }
}
