package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.RevealedSecretRuntimeContext
import com.gromozeka.domain.model.User
import com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SecretArgumentSubstitutorTest {
    @Test
    fun `substitutes only exact named secret string values`() {
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
    fun `command secrets use collision-free generated environment names`() {
        val generatedNames = ArrayDeque(
            listOf(
                "GROMOZEKA_SECRET_IN_COMMAND",
                "GROMOZEKA_SECRET_IN_ENVIRONMENT",
                "GROMOZEKA_SECRET_SAFE",
            )
        )
        val substitutor = SecretArgumentSubstitutor(
            environmentNameGenerator = { generatedNames.removeFirst() },
            inheritedEnvironmentNames = { setOf("GROMOZEKA_SECRET_IN_ENVIRONMENT") },
        )
        val prepared = substitutor.prepare(
            toolName = GRZ_EXECUTE_COMMAND_TOOL_NAME,
            arguments = """{"command":"echo GROMOZEKA_SECRET_IN_COMMAND secret://github-pat secret://github-pat"}""",
            values = mapOf("github-pat" to "actual-token"),
            isWindows = false,
        )
        val command = Json.parseToJsonElement(prepared.arguments)
            .jsonObject
            .getValue("command")
            .jsonPrimitive
            .content

        assertEquals(
            "echo GROMOZEKA_SECRET_IN_COMMAND \${GROMOZEKA_SECRET_SAFE} \${GROMOZEKA_SECRET_SAFE}",
            command,
        )
        assertEquals(mapOf("GROMOZEKA_SECRET_SAFE" to "actual-token"), prepared.secretEnvironment)
        assertEquals("echo GROMOZEKA_SECRET_IN_COMMAND secret://github-pat secret://github-pat", prepared.originalCommand)
    }

    @Test
    fun `public command can be reused without reusing process-local secret variables`() {
        var sequence = 0
        val substitutor = SecretArgumentSubstitutor(
            environmentNameGenerator = { "GROMOZEKA_SECRET_TEST_${++sequence}" },
            inheritedEnvironmentNames = { emptySet() },
        )
        val first = substitutor.prepare(GRZ_EXECUTE_COMMAND_TOOL_NAME, """{"command":"curl secret://github-pat"}""",
            mapOf("github-pat" to "private-test-token"), isWindows = false)
        val second = substitutor.prepare(GRZ_EXECUTE_COMMAND_TOOL_NAME,
            kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive(first.originalCommand)) }.toString(),
            mapOf("github-pat" to "private-test-token"), isWindows = false)
        assertEquals("curl secret://github-pat", second.originalCommand)
        assertFalse(first.arguments == second.arguments)
        assertFalse(second.arguments.contains("private-test-token"))
        assertEquals(listOf("private-test-token"), second.secretEnvironment.values.toList())
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
        assertTrue(
            first.single().instructions.single() is
                Conversation.Message.Instruction.RevealedSecretRuntimeContext
        )
        assertFalse(second.single().instructions.any {
            it is Conversation.Message.Instruction.RevealedSecretRuntimeContext
        })
        val context = (
            first.single().instructions.single() as
                Conversation.Message.Instruction.RevealedSecretRuntimeContext
            ).context
        assertEquals(RevealedSecretRuntimeContext(mapOf("github-pat" to "actual-token")), context)
    }
}
