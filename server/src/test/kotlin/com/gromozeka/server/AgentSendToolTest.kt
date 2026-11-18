package com.gromozeka.server

import com.gromozeka.application.service.AgentCollaborationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*

class AgentSendToolTest {
    private val service = Mockito.mock(AgentCollaborationService::class.java)
    private val contributor = AgentCollaborationToolContributor(service)
    private val tool = contributor.callbacks.single { it.definition.name == "grz_agent_send" }
    private val source = AgentContextRef(Conversation.Id("source"), Conversation.Thread.Id("thread"), AgentDefinition.Id("agent"))
    private val actor = User.Id("user")
    private val root = Conversation.Message.Id("root")
    private val context = ToolExecutionContext(mapOf("toolCallId" to "call", TOOL_CONTEXT_TARGET_MESSAGE_ID to root.value))

    @Test fun `one send tool requires an explicit mode without legacy aliases`() {
        val names = contributor.callbacks.map { it.definition.name }
        assertFalse("grz_agent_message" in names)
        assertFalse("grz_agent_request" in names)
        val schema = Json.parseToJsonElement(tool.definition.inputSchema).jsonObject
        assertEquals(setOf("target_conversation_id", "message", "mode"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val mode = schema.getValue("properties").jsonObject.getValue("mode").jsonObject
        assertEquals(setOf("context_only", "activate", "request"), mode.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse("default" in mode)
        assertTrue(tool.metadata.contextRequirements.required.containsAll(setOf(AiToolContextField.TOOL_CALL_ID, AiToolContextField.TARGET_MESSAGE)))
    }

    @Test fun `each mode reaches the service and only request returns an obligation id`(): Unit = runBlocking {
        Mockito.`when`(service.source(context)).thenReturn(source to actor)
        for ((wireMode, mode) in listOf("context_only" to AgentSendMode.CONTEXT_ONLY, "activate" to AgentSendMode.ACTIVATE, "request" to AgentSendMode.REQUEST)) {
            Mockito.`when`(service.send(source, actor, "target", null, "Message", "call", mode, root)).thenReturn("delivery")
            val result = Json.parseToJsonElement(tool.call("""{"target_conversation_id":"target","message":"Message","mode":"$wireMode"}""", context)).jsonObject
            assertEquals("delivery:input", result.getValue("message_id").jsonPrimitive.content)
            assertEquals("queued", result.getValue("status").jsonPrimitive.content)
            assertEquals(mode == AgentSendMode.REQUEST, "request_id" in result)
            if (mode == AgentSendMode.REQUEST) assertEquals("delivery", result.getValue("request_id").jsonPrimitive.content)
        }
    }

    @Test fun `missing or invalid modes fail before any service call`() {
        for (extra in listOf("", ",\"mode\":null", ",\"mode\":true", ",\"mode\":\"unknown\"", ",\"mode\":\"ACTIVATE\"")) {
            assertFails { tool.call("""{"target_conversation_id":"target","message":"Message"$extra}""", context) }
        }
        Mockito.verifyNoInteractions(service)
    }

    @Test fun `invalid destination and message types are not coerced into strings`() {
        val valid = Json.parseToJsonElement("""{"target_conversation_id":"target","message":"Message","mode":"activate"}""").jsonObject
        for ((field, value) in listOf(
            "target_conversation_id" to JsonPrimitive(12),
            "target_conversation_id" to JsonPrimitive(" "),
            "target_agent_id" to JsonPrimitive(false),
            "message" to JsonPrimitive(true),
            "message" to JsonNull,
        )) assertFails { tool.call(JsonObject(valid + (field to value)).toString(), context) }
        Mockito.verifyNoInteractions(service)
    }

    @Test fun `sending cannot lose the originating message identity`(): Unit = runBlocking {
        val missingRoot = ToolExecutionContext(mapOf("toolCallId" to "call"))
        Mockito.`when`(service.source(missingRoot)).thenReturn(source to actor)
        assertFailsWith<IllegalStateException> {
            tool.call("""{"target_conversation_id":"target","message":"Message","mode":"activate"}""", missingRoot)
        }
        assertTrue(Mockito.mockingDetails(service).invocations.none { it.method.name == "send" })
    }
}
