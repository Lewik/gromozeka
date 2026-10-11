package com.gromozeka.server

import com.gromozeka.application.service.AgentCollaborationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*

class AgentSessionInspectionToolTest {
    private val collaboration = Mockito.mock(AgentCollaborationService::class.java)
    private val source = AgentEndpoint(Conversation.Id("source"), Conversation.Thread.Id("thread"), AgentDefinition.Id("agent"))
    private val actor = User.Id("user")
    private val context = ToolExecutionContext()
    private val contributor = AgentCollaborationToolContributor(collaboration)
    private val tool = contributor.callbacks.single { it.definition.name == "grz_agent_sessions" }

    @Test fun `existing discovery tool has optional inspection selectors and remains server-only`() {
        assertEquals(AGENT_COLLABORATION_TOOL_NAMES, contributor.callbacks.map { it.definition.name }.toSet())
        val schema = Json.parseToJsonElement(tool.definition.inputSchema).jsonObject
        assertEquals(setOf("conversation_id", "agent_id", "details"), schema.getValue("properties").jsonObject.keys)
        assertTrue(schema.getValue("required").jsonArray.isEmpty())
        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(AiToolExecutionScope.SERVER, tool.metadata.executionScope)
        assertFalse(tool.metadata.logInput)
        assertFalse(tool.metadata.visibleToMemoryPipeline)
    }

    @Test fun `empty input still returns an array through the read path only`(): Unit = runBlocking {
        Mockito.`when`(collaboration.source(context)).thenReturn(source to actor)
        Mockito.`when`(collaboration.sessions(source, actor, null, null, false)).thenReturn(JsonArray(emptyList()))
        assertEquals("[]", tool.call("{}", context))
        Mockito.verify(collaboration).source(context)
        Mockito.verify(collaboration).sessions(source, actor, null, null, false)
        Mockito.verifyNoMoreInteractions(collaboration)
    }

    @Test fun `selected details are passed to the existing read operation without sending a request`(): Unit = runBlocking {
        Mockito.`when`(collaboration.source(context)).thenReturn(source to actor)
        val expected = Json.parseToJsonElement("""[{"conversation_id":"target","agent_id":"receiver","runtime":{"foreground_state":"PAUSED"}}]""").jsonArray
        Mockito.`when`(collaboration.sessions(source, actor, "target", "receiver", true)).thenReturn(expected)
        val result = tool.call("""{"conversation_id":"target","agent_id":"receiver","details":true}""", context)
        assertEquals(expected, Json.parseToJsonElement(result))
        Mockito.verify(collaboration).source(context)
        Mockito.verify(collaboration).sessions(source, actor, "target", "receiver", true)
        Mockito.verifyNoMoreInteractions(collaboration)
    }

    @Test fun `invalid selectors and action-like arguments cannot reach session inspection`(): Unit = runBlocking {
        Mockito.`when`(collaboration.source(context)).thenReturn(source to actor)
        for (input in listOf("""{"details":"true"}""", """{"conversation_id":12}""", """{"agent_id":" "}""", """{"trigger_run":true}""")) {
            assertFailsWith<IllegalArgumentException> { tool.call(input, context) }
        }
        assertTrue(Mockito.mockingDetails(collaboration).invocations.none { it.method.name.startsWith("sessions") })
    }
}
