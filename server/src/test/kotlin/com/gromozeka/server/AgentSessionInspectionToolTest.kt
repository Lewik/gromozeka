package com.gromozeka.server

import com.gromozeka.application.service.AgentCollaborationService
import com.gromozeka.application.service.AgentSessionQuery
import com.gromozeka.domain.model.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class AgentSessionInspectionToolTest {
    private val collaboration = Mockito.mock(AgentCollaborationService::class.java)
    private val source = AgentEndpoint(Conversation.Id("source"), Conversation.Thread.Id("thread"), AgentDefinition.Id("agent"))
    private val actor = User.Id("user")
    private val context = ToolExecutionContext()
    private val contributor = AgentCollaborationToolContributor(collaboration)
    private val tool = contributor.callbacks.single { it.definition.name == "grz_agent_sessions" }
    private val emptyPage = Json.parseToJsonElement("""{"sessions":[],"next_cursor":null}""").jsonObject

    @Test fun `discovery schema has optional filters and remains server-only`() {
        assertEquals(AGENT_COLLABORATION_TOOL_NAMES - AGENT_CONTEXT_QUERY_TOOL_NAME, contributor.callbacks.map { it.definition.name }.toSet())
        val schema = Json.parseToJsonElement(tool.definition.inputSchema).jsonObject
        val properties = schema.getValue("properties").jsonObject
        assertEquals(setOf("conversation_id", "agent_id", "details", "open_only", "query", "query_mode", "last_message_at", "limit", "cursor"), properties.keys)
        assertTrue(schema.getValue("required").jsonArray.isEmpty())
        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
        assertTrue(properties.getValue("open_only").jsonObject.getValue("default").jsonPrimitive.boolean)
        val range = properties.getValue("last_message_at").jsonObject
        assertEquals(setOf("gt", "lt"), range.getValue("properties").jsonObject.keys)
        assertFalse("required" in range)
        assertFalse(range.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(AgentSessionQuery.DEFAULT_LIMIT, properties.getValue("limit").jsonObject.getValue("default").jsonPrimitive.int)
        assertEquals(AiToolExecutionScope.SERVER, tool.metadata.executionScope)
        assertFalse(tool.metadata.logInput)
        assertFalse(tool.metadata.visibleToMemoryPipeline)
    }

    @Test fun `empty input returns a page using open tabs by default through the read path only`(): Unit = runBlocking {
        Mockito.`when`(collaboration.source(context)).thenReturn(source to actor)
        Mockito.`when`(collaboration.sessions(source, actor, null, null, false, AgentSessionQuery())).thenReturn(emptyPage)
        assertEquals(emptyPage, Json.parseToJsonElement(tool.call("{}", context)))
        Mockito.verify(collaboration).source(context)
        Mockito.verify(collaboration).sessions(source, actor, null, null, false, AgentSessionQuery())
        Mockito.verifyNoMoreInteractions(collaboration)
    }

    @Test fun `selected details are passed to the read operation without sending a request`(): Unit = runBlocking {
        Mockito.`when`(collaboration.source(context)).thenReturn(source to actor)
        val expected = Json.parseToJsonElement("""{"sessions":[{"conversation_id":"target","agent_id":"receiver","runtime":{"foreground_state":"PAUSED"}}],"next_cursor":null}""").jsonObject
        Mockito.`when`(collaboration.sessions(source, actor, "target", "receiver", true, AgentSessionQuery())).thenReturn(expected)
        val result = tool.call("""{"conversation_id":"target","agent_id":"receiver","details":true}""", context)
        assertEquals(expected, Json.parseToJsonElement(result))
        Mockito.verify(collaboration).source(context)
        Mockito.verify(collaboration).sessions(source, actor, "target", "receiver", true, AgentSessionQuery())
        Mockito.verifyNoMoreInteractions(collaboration)
    }

    @Test fun `time bounds are independent and other filters preserve their values`(): Unit = runBlocking {
        Mockito.`when`(collaboration.source(context)).thenReturn(source to actor)
        val gt = Instant.parse("2026-11-01T00:00:00Z")
        val lt = Instant.parse("2026-11-06T00:00:00Z")
        for ((input, query) in listOf(
            """{"last_message_at":{}}""" to AgentSessionQuery(),
            """{"last_message_at":null}""" to AgentSessionQuery(),
            """{"last_message_at":{"gt":"2026-11-01T02:00:00+02:00"}}""" to AgentSessionQuery(messageGt = gt),
            """{"last_message_at":{"lt":"2026-11-06T00:00:00Z"}}""" to AgentSessionQuery(messageLt = lt),
            """{"last_message_at":{"gt":"2026-11-01T00:00:00Z","lt":"2026-11-06T00:00:00Z"}}""" to AgentSessionQuery(messageGt = gt, messageLt = lt),
            """{"open_only":false,"query":"^Architect","query_mode":"regex","limit":2,"cursor":"opaque"}""" to
                AgentSessionQuery(openOnly = false, query = "^Architect", queryMode = AgentSessionQuery.Mode.REGEX, limit = 2, cursor = "opaque"),
        )) {
            Mockito.`when`(collaboration.sessions(source, actor, null, null, false, query)).thenReturn(emptyPage)
            assertEquals(emptyPage, Json.parseToJsonElement(tool.call(input, context)))
        }
    }

    @Test fun `invalid selectors filters and action-like arguments cannot reach inspection`(): Unit = runBlocking {
        Mockito.`when`(collaboration.source(context)).thenReturn(source to actor)
        for (input in listOf(
            """{"details":"true"}""", """{"conversation_id":12}""", """{"agent_id":" "}""", """{"trigger_run":true}""",
            """{"open_only":null}""", """{"open_only":"true"}""", """{"limit":0}""", """{"limit":201}""", """{"limit":1.5}""", """{"limit":"2"}""",
            """{"query_mode":"fuzzy"}""", """{"query_mode":null}""", """{"query_mode":"regex"}""", """{"query":"[","query_mode":"regex"}""",
            """{"query":"(?<=x)y","query_mode":"regex"}""", """{"last_message_at":[]}""", """{"last_message_at":{"gte":"2026-11-01T00:00:00Z"}}""",
            """{"last_message_at":{"gt":null}}""", """{"last_message_at":{"gt":"not-a-date"}}""",
            """{"last_message_at":{"gt":"2026-11-06T00:00:00Z","lt":"2026-11-01T00:00:00Z"}}""",
        )) assertFailsWith<IllegalArgumentException>(input) { tool.call(input, context) }
        assertTrue(Mockito.mockingDetails(collaboration).invocations.none { it.method.name.startsWith("sessions") })
    }
}
