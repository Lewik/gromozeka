package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
                    inputTokensDetails = OpenAiSubscriptionInputTokensDetails(
                        cachedTokens = 500,
                        cacheWriteTokens = 300,
                    ),
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

        assertEquals(700, response.usage?.promptTokens)
        assertEquals(200, response.usage?.completionTokens)
        assertEquals(600, response.usage?.thinkingTokens)
        assertEquals(300, response.usage?.cacheCreationTokens)
        assertEquals(500, response.usage?.cacheReadTokens)
        assertEquals(2_300, response.usage?.totalTokens)
        assertEquals(1_500, response.contextUsage?.inputTokens)
    }

    @Test
    fun preservesWebSearchCitationsInVisibleAssistantText() {
        val response = mapper.toRuntimeResponse(
            outputItems = listOf(
                buildJsonObject {
                    put("type", "message")
                    put("id", "message-1")
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", """{"fullText":"Kotlin 2.3 was released.","ttsText":"Kotlin was released."}""")
                            put("annotations", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "url_citation")
                                    put("title", "Kotlin release notes")
                                    put("url", "https://kotlinlang.org/docs/releases.html")
                                })
                            })
                        })
                    })
                }
            ),
            completed = null,
            conversationKey = "conversation",
            connectionId = "openai-subscription",
            modelConfigurationId = "gpt-5",
            modelName = "gpt-5",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
        )

        val message = assertIs<Conversation.Message.ContentItem.AssistantMessage>(
            response.messages.single().content.single()
        )
        assertEquals(
            "Kotlin 2.3 was released.\n\nSources:\n" +
                "- [Kotlin release notes](https://kotlinlang.org/docs/releases.html)",
            message.structured.fullText,
        )
        assertEquals("Kotlin was released.", message.structured.ttsText)
    }

    @Test
    fun appendsWebSearchCallSourcesWhenProviderOmitsAnnotations() {
        val response = mapper.toRuntimeResponse(
            outputItems = listOf(
                buildJsonObject {
                    put("type", "web_search_call")
                    put("action", buildJsonObject {
                        put("type", "search")
                        put("sources", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "url")
                                put("url", "https://kotlinlang.org/docs/releases.html")
                            })
                        })
                    })
                },
                buildJsonObject {
                    put("type", "message")
                    put("id", "message-1")
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", "Kotlin 2.3 was released.")
                        })
                    })
                },
            ),
            completed = null,
            conversationKey = "conversation",
            connectionId = "openai-subscription",
            modelConfigurationId = "gpt-5",
            modelName = "gpt-5",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )

        val message = assertIs<Conversation.Message.ContentItem.AssistantMessage>(
            response.messages.single().content.single()
        )
        assertEquals(
            "Kotlin 2.3 was released.\n\nSources:\n" +
                "- <https://kotlinlang.org/docs/releases.html>",
            message.structured.fullText,
        )
    }
}
