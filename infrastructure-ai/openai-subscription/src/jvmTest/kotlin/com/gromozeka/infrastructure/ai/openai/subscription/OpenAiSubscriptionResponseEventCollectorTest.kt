package com.gromozeka.infrastructure.ai.openai.subscription

import klog.KLoggers
import kotlinx.serialization.json.Json
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
    fun ignoresUnparseableNonFinalEvents() {
        collector.accept(payload = "{not-json", eventName = "response.output_text.delta")

        val parsed = collector.toParsedResponse()

        assertFalse(collector.isCompleted)
        assertEquals(emptyList(), parsed.outputItems)
        assertEquals(null, parsed.completed)
    }
}
