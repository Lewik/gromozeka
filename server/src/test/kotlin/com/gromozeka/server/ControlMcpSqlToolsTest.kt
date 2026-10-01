package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ExternalConversationChannel
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.RuntimeSqlAccessDeniedException
import com.gromozeka.domain.service.RuntimeSqlRequest
import com.gromozeka.domain.service.RuntimeSqlResult
import com.gromozeka.domain.service.RuntimeSqlService
import com.gromozeka.domain.service.RuntimeSqlStatementResult
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolCancellationSignal
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class ControlMcpSqlToolsTest {
    private val owner = testControlMcpCaller().user
    private val agent = AgentDefinition.Id("test-agent")
    private val privateConversation = Conversation(
        id = Conversation.Id("private"), projectId = Project.Id("project"),
        participants = setOf(Conversation.Participant.User(owner.id), Conversation.Participant.Agent(agent)),
        currentThread = Conversation.Thread.Id("thread"), createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )
    private val conversations = Mockito.mock(ConversationRepository::class.java)
    private val executor = RecordingSql()
    private val provider = ControlMcpSqlTools(executor, conversations)
    private val tool get() = provider.tools.single()
    private val query = buildJsonObject { put("sql", "SELECT 1") }

    @Test
    fun `tool is privileged destructive and not idempotent or read only`() {
        assertEquals("grz_sql", tool.definition.name)
        assertEquals(ControlMcpAccessPolicy.SERVER_OWNER, tool.accessPolicy)
        assertEquals(false, tool.definition.annotations?.readOnlyHint)
        assertEquals(true, tool.definition.annotations?.destructiveHint)
        assertEquals(false, tool.definition.annotations?.idempotentHint)
    }

    @Test
    fun `direct authenticated owner call receives SQL results and explicit execution options`() = runBlocking {
        val result = tool.invokeStructured(testControlMcpContext(), buildJsonObject {
            put("sql", "UPDATE example SET value=1"); put("max_rows", 25); put("timeout_seconds", 0)
        })
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(owner.id, executor.actor)
        assertEquals(RuntimeSqlRequest("UPDATE example SET value=1", 25, 0), executor.request)
        assertEquals(1, executor.calls)
    }

    @Test
    fun `member cannot reach the SQL executor`() = runBlocking {
        assertEquals(JsonPrimitive(false), tool.invokeStructured(testControlMcpContext(User.Role.MEMBER), query)["success"])
        assertEquals(0, executor.calls)
    }

    @Test
    fun `agent call without trusted conversation is denied even with a forged argument`() = runBlocking {
        val result = tool.invokeStructured(ControlMcpCallContext(owner, agent), buildJsonObject {
            put("sql", "SELECT 1"); put("conversationId", "private")
        })
        assertEquals(JsonPrimitive(false), result["success"])
        assertEquals(0, executor.calls)
    }

    @Test
    fun `Telegram is denied by stored channel regardless of calling owner's privileges`() = runBlocking {
        Mockito.`when`(conversations.findById(privateConversation.id)).thenReturn(privateConversation.copy(
            externalChannel = ExternalConversationChannel("telegram", "bot", "group")))
        val result = tool.invokeStructured(ControlMcpCallContext(owner, agent, privateConversation.id), query)
        assertEquals(JsonPrimitive(false), result["success"])
        assertEquals(0, executor.calls)
    }

    @Test
    fun `conversation adapter forwards trusted origin and cancellation to SQL`() = runBlocking {
        Mockito.`when`(conversations.findById(privateConversation.id)).thenReturn(privateConversation)
        val users = object : UserDirectoryService {
            override suspend fun findActiveById(id: User.Id) = owner.takeIf { it.id == id }
            override suspend fun listActive() = listOf(owner)
        }
        val callback = ControlMcpConversationToolContributor(ControlMcpToolCatalog(listOf(provider)), users).callbacks.single()
        val cancellation = ToolCancellationSignal { }
        val result = controlMcpJson.parseToJsonElement(callback.call(query.toString(), ToolExecutionContext(mapOf(
            TOOL_CONTEXT_USER_ID to owner.id.value,
            TOOL_CONTEXT_AGENT_DEFINITION_ID to agent.value,
            TOOL_CONTEXT_CONVERSATION_ID to privateConversation.id.value,
        ), cancellation))).jsonObject
        assertEquals(JsonPrimitive(true), result["success"])
        assertTrue(executor.cancellation === cancellation)
        Mockito.`when`(conversations.findById(privateConversation.id)).thenReturn(privateConversation.copy(
            externalChannel = ExternalConversationChannel("telegram", "bot", "group")))
        val rejected = controlMcpJson.parseToJsonElement(callback.call(query.toString(), ToolExecutionContext(mapOf(
            TOOL_CONTEXT_USER_ID to owner.id.value,
            TOOL_CONTEXT_AGENT_DEFINITION_ID to agent.value,
            TOOL_CONTEXT_CONVERSATION_ID to privateConversation.id.value,
        )))).jsonObject
        assertEquals(JsonPrimitive(false), rejected["success"])
        assertEquals(1, executor.calls)
    }

    @Test
    fun `missing conversation and disconnected agent fail closed`() = runBlocking {
        assertEquals(JsonPrimitive(false), tool.invokeStructured(ControlMcpCallContext(owner, agent, privateConversation.id), query)["success"])
        Mockito.`when`(conversations.findById(privateConversation.id)).thenReturn(privateConversation.copy(
            participants = setOf(Conversation.Participant.User(owner.id))))
        assertEquals(JsonPrimitive(false), tool.invokeStructured(ControlMcpCallContext(owner, agent, privateConversation.id), query)["success"])
        assertEquals(0, executor.calls)
    }

    @Test
    fun `runtime login rejection is returned as forbidden without retry`() = runBlocking {
        executor.denied = true
        val result = tool.invokeStructured(testControlMcpContext(), query)
        assertEquals(JsonPrimitive(false), result["success"])
        assertEquals(JsonPrimitive("forbidden"), result.getValue("error").jsonObject["code"])
        assertEquals(1, executor.calls)
    }

    private class RecordingSql : RuntimeSqlService {
        var calls = 0
        var denied = false
        var actor: User.Id? = null
        var request: RuntimeSqlRequest? = null
        var cancellation: ToolCancellationSignal? = null
        override suspend fun execute(actor: User.Id, request: RuntimeSqlRequest, cancellation: ToolCancellationSignal): RuntimeSqlResult {
            calls++
            if (denied) throw RuntimeSqlAccessDeniedException("Multiple logins")
            this.actor = actor; this.request = request; this.cancellation = cancellation
            return RuntimeSqlResult(listOf(RuntimeSqlStatementResult(updateCount = 1)), false, false)
        }
    }
}
