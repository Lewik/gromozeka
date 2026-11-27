package com.gromozeka.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class RuntimeJsonTest {
    private val payload = JsonObject(mapOf("kind" to JsonPrimitive("runtime_state")))

    @Test
    fun runtimeMessagesRequireOneRuntimeJsonPayload() {
        val message = Conversation.Message(
            id = Conversation.Message.Id("runtime"),
            conversationId = Conversation.Id("conversation"),
            role = Conversation.Message.Role.RUNTIME,
            content = listOf(Conversation.Message.ContentItem.RuntimeJson(payload)),
            createdAt = Instant.fromEpochMilliseconds(0),
        )

        assertEquals(payload.toString(), message.content.single().userInputTextOrNull())
        assertEquals(message, Json.decodeFromString(Json.encodeToString(message)))
        assertFailsWith<IllegalArgumentException> { message.copy(content = emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            message.copy(content = listOf(Conversation.Message.ContentItem.UserMessage("not runtime JSON")))
        }
        assertFailsWith<IllegalArgumentException> {
            message.copy(content = listOf(Conversation.Message.ContentItem.UnknownJson(payload)))
        }
    }

    @Test
    fun runtimeJsonCannotBeAttachedToAnotherRole() {
        assertFailsWith<IllegalArgumentException> {
            Conversation.Message(
                id = Conversation.Message.Id("user"),
                conversationId = Conversation.Id("conversation"),
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.RuntimeJson(payload)),
                createdAt = Instant.fromEpochMilliseconds(0),
            )
        }
    }
}
