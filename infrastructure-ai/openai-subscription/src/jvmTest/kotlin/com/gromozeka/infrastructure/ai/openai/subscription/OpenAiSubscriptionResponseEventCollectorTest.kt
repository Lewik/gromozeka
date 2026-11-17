package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiStepOutcome
import klog.KLoggers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiSubscriptionResponseEventCollectorTest {
    private val collector = OpenAiSubscriptionResponseEventCollector(
        json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        },
        responseMapper = OpenAiSubscriptionResponseMapper(),
        log = KLoggers.logger("OpenAiSubscriptionResponseEventCollectorTest"),
    )

    @Test
    fun completesWhenResponseCompletedArrives() {
        collector.accept(
            """
            {
              "type": "response.completed",
              "response": {
                "id": "resp_test",
                "status": "completed",
                "output": [
                  {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                      { "type": "output_text", "text": "Hello" }
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val parsed = collector.toParsedResponse()

        assertTrue(collector.isCompleted)
        assertEquals("resp_test", parsed.completed?.id)
        assertEquals(1, parsed.outputItems.size)
    }

    @Test
    fun recoversToolCallFromFinalOutputAfterReasoningOnlyStream() {
        acceptItem(reasoning)
        acceptFinal(listOf(reasoning, toolCall))

        assertEquals(listOf(reasoning, toolCall), collector.toParsedResponse().outputItems)
        assertToolCallOutcome()
    }

    @Test
    fun recoversToolCallFromFinalOutputInsteadOfContinuingAfterCommentary() {
        acceptItem(reasoning)
        acceptItem(commentary)
        acceptFinal(listOf(reasoning, commentary, toolCall))

        assertEquals(listOf(reasoning, commentary, toolCall), collector.toParsedResponse().outputItems)
        assertToolCallOutcome()
    }

    @Test
    fun preservesMatchingStreamWithoutDuplicatingToolCalls() {
        listOf(reasoning, commentary, toolCall).forEach(::acceptItem)
        acceptFinal(listOf(reasoning, commentary, toolCall))

        assertEquals(listOf(reasoning, commentary, toolCall), collector.toParsedResponse().outputItems)
        assertToolCallOutcome()
    }

    @Test
    fun usesFinalOrderingAndRemovesDuplicateStreamItems() {
        listOf(toolCall, reasoning, toolCall).forEach(::acceptItem)
        acceptFinal(listOf(reasoning, toolCall))

        val parsed = collector.toParsedResponse()
        assertEquals(listOf(reasoning, toolCall), parsed.outputItems)
        assertEquals(parsed, collector.toParsedResponse())
        assertToolCallOutcome()
    }

    @Test
    fun doesNotExecuteStreamedToolCallAbsentFromNonEmptyFinalOutput() {
        acceptItem(toolCall)
        acceptFinal(listOf(finalMessage))

        val response = runtimeResponse()
        assertEquals(listOf(finalMessage), collector.toParsedResponse().outputItems)
        assertEquals(AiStepOutcome.COMPLETE, response.outcome)
        assertTrue(response.toolCalls.isEmpty())
    }

    @Test
    fun keepsStreamedItemsWhenFinalOutputIsOmitted() {
        listOf(reasoning, toolCall).forEach(::acceptItem)
        acceptFinal(output = null)

        assertEquals(listOf(reasoning, toolCall), collector.toParsedResponse().outputItems)
        assertToolCallOutcome()
    }

    @Test
    fun keepsStreamedItemsWhenFinalOutputIsEmpty() {
        listOf(reasoning, toolCall).forEach(::acceptItem)
        acceptFinal(emptyList())

        assertEquals(listOf(reasoning, toolCall), collector.toParsedResponse().outputItems)
        assertToolCallOutcome()
    }

    @Test
    fun recoveredToolCallsRemainNonExecutableForIncompleteResponse() {
        acceptItem(reasoning)
        acceptFinal(listOf(reasoning, toolCall), status = "incomplete")

        val response = runtimeResponse()
        assertEquals(listOf(reasoning, toolCall), collector.toParsedResponse().outputItems)
        assertEquals(AiStepOutcome.INCOMPLETE, response.outcome)
        assertTrue(response.toolCalls.isEmpty())
    }

    @Test
    fun failsFastOnGenericErrorEvent() {
        val error = assertFailsWith<OpenAiSubscriptionRequestException> {
            collector.accept(
                """
                {
                  "type": "error",
                  "error": {
                    "code": "unsupported_model",
                    "message": "The 'gpt-5.3-codex' model is not supported"
                  }
                }
                """.trimIndent()
            )
        }

        assertEquals(400, error.statusCode)
        assertContains(error.message.orEmpty(), "unsupported_model")
        assertContains(error.message.orEmpty(), "gpt-5.3-codex")
    }

    @Test
    fun failsFastOnNamedErrorEventWithDetailPayload() {
        val error = assertFailsWith<OpenAiSubscriptionRequestException> {
            collector.accept(
                payload = """{"detail":"The 'gpt-5.3-codex' model is not supported when using Codex with a ChatGPT account."}""",
                eventName = "error",
            )
        }

        assertEquals(400, error.statusCode)
        assertContains(error.message.orEmpty(), "ChatGPT account")
    }

    @Test
    fun failsFastOnErrorPayloadWithoutEventType() {
        val error = assertFailsWith<OpenAiSubscriptionRequestException> {
            collector.accept(
                payload = """{"detail":"The 'gpt-5.3-codex' model is not supported when using Codex with a ChatGPT account."}""",
            )
        }

        assertEquals(400, error.statusCode)
        assertContains(error.message.orEmpty(), "gpt-5.3-codex")
    }

    @Test
    fun failsFastOnStringErrorPayload() {
        val error = assertFailsWith<OpenAiSubscriptionRequestException> {
            collector.accept(payload = """{"error":"Temporary backend failure"}""", eventName = "error")
        }

        assertEquals(400, error.statusCode)
        assertContains(error.message.orEmpty(), "Temporary backend failure")
    }

    @Test
    fun failsFastOnResponseFailedEvent() {
        val error = assertFailsWith<OpenAiSubscriptionRequestException> {
            collector.accept(
                """
                {
                  "type": "response.failed",
                  "response": {
                    "id": "resp_failed",
                    "status": "failed",
                    "error": {
                      "code": "bad_request",
                      "message": "Request rejected"
                    }
                  }
                }
                """.trimIndent()
            )
        }

        assertEquals(400, error.statusCode)
        assertContains(error.message.orEmpty(), "bad_request")
        assertContains(error.message.orEmpty(), "Request rejected")
    }

    @Test
    fun includesIncompleteResponseDetails() {
            collector.accept(
                """
                {
                  "type": "response.incomplete",
                  "response": {
                    "id": "resp_incomplete",
                    "status": "incomplete",
                    "incomplete_details": {
                      "reason": "max_output_tokens"
                    }
                  }
                }
                """.trimIndent()
            )
        val completed = collector.toParsedResponse().completed!!
        assertEquals("incomplete", completed.status)
        assertEquals("max_output_tokens", completed.incompleteDetails?.get("reason")?.jsonPrimitive?.contentOrNull)
        assertEquals("resp_incomplete", completed.id)
    }

    @Test
    fun ignoresUnparseableNonFinalEvents() {
        collector.accept(payload = "{not-json", eventName = "response.output_text.delta")

        val parsed = collector.toParsedResponse()

        assertFalse(collector.isCompleted)
        assertEquals(emptyList(), parsed.outputItems)
        assertEquals(null, parsed.completed)
    }

    private fun acceptItem(item: JsonObject) {
        collector.accept(buildJsonObject {
            put("type", "response.output_item.done")
            put("item", item)
        }.toString())
    }

    private fun acceptFinal(output: List<JsonObject>?, status: String = "completed") {
        collector.accept(buildJsonObject {
            put("type", "response.$status")
            put("response", buildJsonObject {
                put("id", "resp_test")
                put("status", status)
                output?.let { put("output", JsonArray(it)) }
            })
        }.toString())
    }

    private fun runtimeResponse() = collector.toParsedResponse().let { parsed ->
        OpenAiSubscriptionResponseMapper().toRuntimeResponse(
            outputItems = parsed.outputItems,
            completed = parsed.completed,
            conversationKey = "conversation",
            connectionId = "connection",
            modelConfigurationId = "model",
            modelName = "model",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )
    }

    private fun assertToolCallOutcome() {
        val response = runtimeResponse()
        assertEquals(AiStepOutcome.TOOL_CALLS, response.outcome)
        assertEquals("call_test", response.toolCalls.single().id.value)
    }

    private val reasoning = Json.parseToJsonElement("""{
        "id":"rs_test","type":"reasoning","encrypted_content":"opaque-reasoning",
        "summary":[{"type":"summary_text","text":"Checking the file"}]
    }""").jsonObject

    private val commentary = Json.parseToJsonElement("""{
        "id":"msg_progress","type":"message","role":"assistant","phase":"commentary",
        "content":[{"type":"output_text","text":"I will read the file."}]
    }""").jsonObject

    private val toolCall = Json.parseToJsonElement("""{
        "id":"fc_test","type":"function_call","call_id":"call_test",
        "name":"read_file","arguments":"{\"path\":\"README.md\"}"
    }""").jsonObject

    private val finalMessage = Json.parseToJsonElement("""{
        "id":"msg_final","type":"message","role":"assistant","phase":"final_answer",
        "content":[{"type":"output_text","text":"Finished."}]
    }""").jsonObject
}
