package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.RevealedSecretRuntimeContext
import com.gromozeka.domain.model.User
import com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SecretArgumentSubstitutorTest {
    @Test
    fun `substitutes only exact named secret string values for ordinary tools`() {
        val result = SecretArgumentSubstitutor().substitute(
            arguments = """{"token":"secret://github-pat","command":"echo secret://github-pat","nested":["secret://github-pat"]}""",
            values = mapOf("github-pat" to "actual-token"),
        )
        val parsed = Json.parseToJsonElement(result).jsonObject
        assertEquals("actual-token", parsed.getValue("token").jsonPrimitive.content)
        assertEquals("echo secret://github-pat", parsed.getValue("command").jsonPrimitive.content)
        assertEquals("actual-token", parsed.getValue("nested").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `multiple named secrets reach only the requested environment without shell interpolation`() {
        val values = mapOf("github-pat" to "first ' \" % & ${'$'}value\nשלום", "mcp-token" to "second-test-token")
        val input = """{"command":"python3 client.py","secret_env":{"GH_TOKEN":"secret://github-pat","SERVICE_TOKEN":"secret://mcp-token","OTHER_TOKEN":"secret://github-pat"}}"""
        val prepared = SecretArgumentSubstitutor().prepare(GRZ_EXECUTE_COMMAND_TOOL_NAME, input, values)
        assertEquals("""{"command":"python3 client.py"}""", prepared.arguments)
        assertEquals(mapOf("GH_TOKEN" to values.getValue("github-pat"), "SERVICE_TOKEN" to values.getValue("mcp-token"),
            "OTHER_TOKEN" to values.getValue("github-pat")), prepared.secretEnvironment)
        assertFalse(prepared.arguments.contains("secret://"))
        assertFalse(prepared.arguments.contains("second-test-token"))
        assertTrue(input.contains("secret://github-pat"))
    }

    @Test
    fun `commands without secret bindings retain their exact shell source`() {
        val input = """{"command":"python3 -c 'import os; print(os.getcwd())'"}"""
        val prepared = SecretArgumentSubstitutor().prepare(GRZ_EXECUTE_COMMAND_TOOL_NAME, input, emptyMap())
        assertEquals(input, prepared.arguments)
        assertTrue(prepared.secretEnvironment.isEmpty())
    }

    @Test
    fun `inline references fail with migration guidance instead of being expanded`() {
        for (command in listOf("curl -u secret://github-pat", "secret://github-pat", "P=secret://github-pat tool")) {
            val input = kotlinx.serialization.json.buildJsonObject {
                put("command", kotlinx.serialization.json.JsonPrimitive(command))
            }.toString()
            val failure = assertFailsWith<IllegalArgumentException> {
                SecretArgumentSubstitutor().prepare(GRZ_EXECUTE_COMMAND_TOOL_NAME, input, mapOf("github-pat" to "private-test-token"))
            }
            assertContains(requireNotNull(failure.message), "secret_env")
            assertFalse(failure.message.orEmpty().contains("private-test-token"))
        }
    }

    @Test
    fun `invalid secret bindings fail without echoing supplied values`() {
        val bindings = listOf(
            "null", "[]", "\"private-test-token\"", """{"TOKEN":123}""", """{"TOKEN":null}""",
            """{"TOKEN":"private-test-token"}""", """{"TOKEN":"secret://UPPER"}""",
            """{"BAD=NAME":"secret://github-pat"}""", """{"TOKEN":"secret://github-pat","token":"secret://github-pat"}""",
            """{"GRZ_SLOT":"secret://github-pat"}""", """{"grz_mcp_context":"secret://github-pat"}""",
            """{"GROMOZEKA_COMMAND_FILE":"secret://github-pat"}""",
            """{"GRZ_MCP_TOKEN":"secret://github-pat"}""", """{"grz_mcp_url":"secret://github-pat"}""",
            """{"GRZ_MCP_CA_BUNDLE":"secret://github-pat"}""",
        )
        for (binding in bindings) {
            val failure = assertFailsWith<Exception> {
                SecretArgumentSubstitutor().prepare(GRZ_EXECUTE_COMMAND_TOOL_NAME,
                    """{"command":"python3 client.py","secret_env":$binding}""", mapOf("github-pat" to "private-test-token"))
            }
            assertContains(requireNotNull(failure.message), "secret_env")
            assertFalse(failure.message.orEmpty().contains("private-test-token"))
        }
    }

    @Test
    fun `unresolved or NUL-containing secrets fail rather than launch without authentication`() {
        val input = """{"command":"gh api user","secret_env":{"GH_TOKEN":"secret://github-pat"}}"""
        assertFailsWith<IllegalStateException> { SecretArgumentSubstitutor().prepare(GRZ_EXECUTE_COMMAND_TOOL_NAME, input, emptyMap()) }
        assertFailsWith<IllegalArgumentException> {
            SecretArgumentSubstitutor().prepare(GRZ_EXECUTE_COMMAND_TOOL_NAME, input, mapOf("github-pat" to "invalid\u0000value"))
        }
    }

    @Test
    fun `each invocation resolves current values and exposes no credential in prepared arguments`() {
        val input = """{"command":"gh api user","secret_env":{"GH_TOKEN":"secret://github-pat"}}"""
        val first = SecretArgumentSubstitutor().prepare(GRZ_EXECUTE_COMMAND_TOOL_NAME, input, mapOf("github-pat" to "first-token"))
        val second = SecretArgumentSubstitutor().prepare(GRZ_EXECUTE_COMMAND_TOOL_NAME, input, mapOf("github-pat" to "rotated-token"))
        assertEquals(first.arguments, second.arguments)
        assertEquals(mapOf("GH_TOKEN" to "first-token"), first.secretEnvironment)
        assertEquals(mapOf("GH_TOKEN" to "rotated-token"), second.secretEnvironment)
    }

    @Test
    fun `monitor filter cannot bypass command secret rules through exact substitution`() {
        assertFailsWith<IllegalArgumentException> {
            SecretArgumentSubstitutor().prepare("grz_monitor_command", """{"filter_command":"secret://github-pat"}""",
                mapOf("github-pat" to "private-test-token"))
        }
    }

    @Test
    fun `revealed secrets enrich one model request without changing source messages`() {
        val service = PendingSecretRevealService()
        val conversationId = Conversation.Id("conversation")
        val userId = User.Id("user")
        val message = Conversation.Message(
            id = Conversation.Message.Id("message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("use it")),
            createdAt = Clock.System.now(),
        )
        service.queue(conversationId, userId, mapOf("github-pat" to "actual-token"))
        val first = service.consume(conversationId, userId, listOf(message))
        val second = service.consume(conversationId, userId, listOf(message))
        assertTrue(message.instructions.isEmpty())
        assertTrue(first.single().instructions.single() is Conversation.Message.Instruction.RevealedSecretRuntimeContext)
        assertFalse(second.single().instructions.any { it is Conversation.Message.Instruction.RevealedSecretRuntimeContext })
        val context = (first.single().instructions.single() as Conversation.Message.Instruction.RevealedSecretRuntimeContext).context
        assertEquals(RevealedSecretRuntimeContext(mapOf("github-pat" to "actual-token")), context)
    }
}
