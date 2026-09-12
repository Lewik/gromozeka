package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class OpenAiSubscriptionWebSearchRealTest {
    @Test
    fun searchOpenFindAndOrdinaryToolSurvivePersistedContinuations() = runBlocking {
        if (System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_REAL") != "true") return@runBlocking
        val tokens = Json.parseToJsonElement(File(requireNotNull(System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_AUTH_FILE"))).readText())
            .jsonObject.getValue("tokens").jsonObject
        val accessToken = tokens.getValue("access_token").jsonPrimitive.content
        val claims = Json.parseToJsonElement(Base64.getUrlDecoder().decode(accessToken.split('.')[1]).decodeToString()).jsonObject
        val expiry = claims.getValue("exp").jsonPrimitive.long
        require(expiry > Clock.System.now().epochSeconds + 300) { "Codex access token needs renewal before this test" }
        val session = OpenAiSubscriptionSession(accessToken, "unused", null, tokens.getValue("account_id").jsonPrimitive.content, expiry)
        val model = requireNotNull(System.getenv("GROMOZEKA_OPENAI_SUBSCRIPTION_MODEL"))
        val baseUrl = "https://chatgpt.com/backend-api/codex"
        val profile = OpenAiSubscriptionModelsClient(baseUrl, "1.4.9", 60000, 30000).getProfile(session, model)
        val conversationKey = UUID.randomUUID().toString()
        val connectionId = "live-web-connection"
        val mapper = OpenAiSubscriptionRequestMapper()
        val responseMapper = OpenAiSubscriptionResponseMapper()
        val history = mutableListOf(OpenAiSubscriptionWebSearchTest.userMessage(
            "Verify the OpenAI web search documentation. Follow these steps in order: " +
                "1. Use web.run search_query to find OpenAI's web search documentation, restricting domains to developers.openai.com. " +
                "2. In a separate call, open a reference from that search result. " +
                "3. In a separate call, find the word 'search' within the opened reference. " +
                "4. Call finish_check with the page URL you verified. " +
                "5. Give a brief final answer with the actual page title, a Markdown source link, and the marker returned by finish_check. " +
                "Do not skip steps or invent reference IDs or tool output."
        ))
        val options = AiRuntimeOptions(
            reasoning = AiReasoningConfig(effort = AiReasoningEffort.LOW),
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA,
            responseFormat = AiResponseFormat.JsonSchema("gromozeka_assistant_response", Json.parseToJsonElement("""{
                "type":"object","additionalProperties":false,"properties":{
                "fullText":{"type":"string"},"ttsText":{"type":"string"},"voiceTone":{"type":"string"},
                "attentionRequested":{"type":"boolean"}},"required":["fullText","ttsText","voiceTone","attentionRequested"]}
            """).jsonObject),
        )
        val observed = mutableSetOf<String>()
        var finalText: String? = null
        var transport = OpenAiSubscriptionWebSearchTest.client(baseUrl)
        repeat(18) { iteration ->
            if (finalText != null) return@repeat
            val request = AiRuntimeRequest(listOf("Follow the verification steps precisely. Use tools for evidence. " +
                "You may send short commentary; final answers must follow the configured schema. ttsText and voiceTone are empty; attentionRequested is false."),
                history.toList(), listOf(finishTool), options)
            val pending = OpenAiSubscriptionWebSearch(transport).executePending(request, session, conversationKey, connectionId, model, true)
            val response = pending ?: run {
                val body = mapper.toRequest(request, profile, conversationKey, true, connectionId)
                val parsed = transport.create(session, conversationKey, body, profile, options.assistantResponseFormat)
                responseMapper.toRuntimeResponse(parsed.outputItems, parsed.completed, conversationKey, connectionId,
                    "live-web-$model", model, options.assistantResponseFormat)
            }
            assertFalse(response.outcome.isFailure, "Provider failed: ${response.finishReason}")
            assertNotEquals(AiStepOutcome.REFUSED, response.outcome)
            println("Live web check model=$model lite=${profile.useResponsesLite} step=$iteration searchExecution=${pending != null} outcome=${response.outcome}")
            response.messages.flatMap { it.content }.filterIsInstance<ContentItem.ToolCall>().filter { it.call.name == "web.run" }.forEach {
                observed += it.call.input.jsonObject.keys
            }
            response.messages.flatMap { it.content }.filterIsInstance<ContentItem.ToolResult>().forEach {
                assertFalse(it.isError, "Search failed: ${it.result}")
            }
            history += OpenAiSubscriptionWebSearchTest.persisted(response)
            for (call in response.toolCalls) {
                assertEquals("finish_check", call.call.name)
                assertTrue(call.call.input.jsonObject.getValue("url").jsonPrimitive.content.startsWith("https://"))
                observed += "finish_check"
                history += OpenAiSubscriptionWebSearchTest.message(Conversation.Message.Role.USER, listOf(ContentItem.ToolResult(
                    call.id, call.call.name, listOf(ContentItem.ToolResult.Data.Text("Verified marker: WEB_ROUNDTRIP_OK")),
                )))
            }
            if (response.outcome == AiStepOutcome.COMPLETE) {
                finalText = response.messages.flatMap { it.content }.filterIsInstance<ContentItem.AssistantMessage>()
                    .joinToString("\n") { it.structured.fullText }
            }
            if (pending != null) transport = OpenAiSubscriptionWebSearchTest.client(baseUrl)
        }
        assertNotNull(finalText, "Search did not finish within 18 persisted steps")
        assertTrue("WEB_ROUNDTRIP_OK" in finalText!!)
        assertTrue(Regex("\\[[^]]+]\\(https://[^)]+\\)").containsMatchIn(finalText!!), "Expected a Markdown source link")
        assertTrue(observed.containsAll(listOf("search_query", "open", "find", "finish_check")), "Observed operations: $observed")
        println("Live web check completed model=$model operations=$observed finalChars=${finalText!!.length}")
    }

    private val finishTool = object : AiToolCallback {
        override val definition = AiToolDefinition("finish_check", "Record the verified page URL after search, open, and find have completed.",
            """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"],"additionalProperties":false}""")
        override val metadata = ServerToolMetadata
        override fun call(toolInput: String, context: ToolExecutionContext?): String = error("Synthetic test result supplied by the test")
    }
}
