package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenAiSubscriptionResponseMapperTest {
    private val mapper = OpenAiSubscriptionResponseMapper()

    @Test
    fun mapsProviderCompactionOutputToExplicitCompactionContentItem() {
        val response = mapper.toRuntimeResponse(
            outputItems = listOf(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("compaction"),
                        "encrypted_content" to JsonPrimitive("encrypted-compact"),
                    )
                )
            ),
            completed = null,
            conversationKey = "conversation",
            connectionId = "openai-subscription",
            modelConfigurationId = "gpt-5",
            modelName = "gpt-5",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )

        val compaction = assertIs<Conversation.Message.ContentItem.ContextCompactionResult>(
            response.messages.single().content.single()
        )
        val payload = assertIs<Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState>(
            compaction.payload
        )
        val replayItem = payload.state.getValue("replay_item").jsonObject

        assertEquals(Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO, compaction.origin)
        assertEquals(AiConnection.Kind.OPENAI_SUBSCRIPTION.name, compaction.providerScope?.provider)
        assertEquals("compaction", replayItem.getValue("type").jsonPrimitive.contentOrNull)
        assertEquals("encrypted-compact", replayItem.getValue("encrypted_content").jsonPrimitive.contentOrNull)
    }

    @Test
    fun separatesVisibleCompletionTokensFromReasoningTokens() {
        val response = mapper.toRuntimeResponse(
            outputItems = emptyList(),
            completed = OpenAiSubscriptionCompletedResponse(
                id = "response",
                usage = OpenAiSubscriptionUsage(
                    inputTokens = 1_500,
                    inputTokensDetails = OpenAiSubscriptionInputTokensDetails(cachedTokens = 500),
                    outputTokens = 800,
                    outputTokensDetails = OpenAiSubscriptionOutputTokensDetails(reasoningTokens = 600),
                ),
            ),
            conversationKey = "conversation",
            connectionId = "openai-subscription",
            modelConfigurationId = "gpt-5",
            modelName = "gpt-5",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )

        assertEquals(1_000, response.usage?.promptTokens)
        assertEquals(200, response.usage?.completionTokens)
        assertEquals(600, response.usage?.thinkingTokens)
        assertEquals(500, response.usage?.cacheReadTokens)
        assertEquals(2_300, response.usage?.totalTokens)
    }
}
