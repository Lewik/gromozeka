package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.RevealedSecretRuntimeContext
import com.gromozeka.domain.model.User
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
