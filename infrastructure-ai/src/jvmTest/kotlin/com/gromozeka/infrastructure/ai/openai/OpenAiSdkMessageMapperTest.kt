package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class OpenAiSdkMessageMapperTest {
    @Test
    fun `serialized request keeps system prompt before unchanged history`() {
        for ((kind, role) in listOf(
            AiConnection.Kind.OPENAI_COMPATIBLE to "system",
            AiConnection.Kind.OPENAI_API to "developer",
        )) {
            val mapper = OpenAiSdkMessageMapper(kind)
            val historyOnly = serializedBody(mapper, emptyList()).getValue("messages").jsonArray
            val body = serializedBody(mapper, listOf("First instruction.", "  ", "Second instruction."))
            val messages = body.getValue("messages").jsonArray

            assertEquals("gateway/model", body.getValue("model").jsonPrimitive.content)
            assertEquals(listOf(role, "user", "assistant", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertEquals("First instruction.\n\nSecond instruction.", messages.first().jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(historyOnly, JsonArray(messages.drop(1)))
        }
    }

    @Test
    fun `serialized request omits empty system prompt`() {
        for (kind in listOf(AiConnection.Kind.OPENAI_COMPATIBLE, AiConnection.Kind.OPENAI_API)) {
            val mapper = OpenAiSdkMessageMapper(kind)
            val body = serializedBody(mapper, listOf("", "  ", "\n"))
            val messages = body.getValue("messages").jsonArray

            assertEquals(listOf("user", "assistant", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertEquals(serializedBody(mapper, emptyList()), body)
        }
    }

    private fun serializedBody(mapper: OpenAiSdkMessageMapper, systemPrompts: List<String>): JsonObject {
        val firstUser = Conversation.Message(
            id = Conversation.Message.Id("user-1"),
            conversationId = Conversation.Id("conversation"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("First question.")),
            createdAt = Instant.fromEpochMilliseconds(0),
        )
        val history = listOf(
            firstUser,
            firstUser.copy(
                id = Conversation.Message.Id("assistant-1"),
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(Conversation.Message.ContentItem.AssistantMessage(
                    Conversation.Message.StructuredText(fullText = "Earlier answer."),
                )),
            ),
            firstUser.copy(
                id = Conversation.Message.Id("user-2"),
                content = listOf(Conversation.Message.ContentItem.UserMessage("Next question.")),
            ),
        )
        val params = mapper.toCreateParams("gateway/model", AiRuntimeRequest(
            systemPrompts = systemPrompts,
            messages = history,
            tools = emptyList(),
        ))
        return Json.parseToJsonElement(com.openai.core.jsonMapper().writeValueAsString(params._body())).jsonObject
    }
}
