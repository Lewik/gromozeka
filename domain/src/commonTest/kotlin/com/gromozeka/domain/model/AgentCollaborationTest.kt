package com.gromozeka.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class AgentCollaborationTest {
    @Test fun `address is independent of history but not conversation or agent`() {
        val context = AgentContextRef(Conversation.Id("a"), Conversation.Thread.Id("v1"), AgentDefinition.Id("agent"))
        val next = context.copy(threadId = Conversation.Thread.Id("v2"))
        assertNotEquals(context, next)
        assertEquals(context.address, next.address)
        assertNotEquals(context.address, context.copy(conversationId = Conversation.Id("fork")).address)
        assertNotEquals(context.address, context.copy(agentId = AgentDefinition.Id("other")).address)
    }

    @Test fun `stored request keeps its existing wire shape and historical references`() {
        val stored = """{"id":"existing-request","source":{"conversationId":"a","threadId":"old-a","agentId":"agent-a"},"target":{"conversationId":"b","threadId":"old-b","agentId":"agent-b"},"actorUserId":"user","text":"Task","createdAt":"1970-01-01T00:00:00.001Z"}"""
        val request = Json.decodeFromString<AgentRequest>(stored)
        assertEquals(AgentAddress(Conversation.Id("a"), AgentDefinition.Id("agent-a")), request.source.address)
        assertEquals(Conversation.Thread.Id("old-a"), request.source.threadId)
        assertEquals(Conversation.Thread.Id("old-b"), request.target.threadId)
        assertEquals(Json.parseToJsonElement(stored), Json.parseToJsonElement(Json.encodeToString(request)))
    }
}
