package com.gromozeka.infrastructure.ai.openai.subscription

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiSubscriptionModelsClientTest {
    @Test
    fun loadsAccountModelProfileAndCachesCatalog() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/models") { exchange ->
            requests.incrementAndGet()
            assertEquals("Bearer access-token", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("account-1", exchange.requestHeaders.getFirst("ChatGPT-Account-Id"))
            assertEquals("codex_cli_rs", exchange.requestHeaders.getFirst("originator"))
            assertEquals("codex_cli_rs/1.4.9 (Gromozeka; JVM)", exchange.requestHeaders.getFirst("User-Agent"))
            assertEquals("client_version=1.4.9", exchange.requestURI.rawQuery)
            val body = """
                {
                  "models": [
                    {
                      "slug": "gpt-5.5",
                      "supported_reasoning_levels": [{"effort": "xhigh"}],
                      "supports_reasoning_summaries": true,
                      "support_verbosity": true,
                      "default_verbosity": "low",
                      "supports_parallel_tool_calls": true,
                      "use_responses_lite": false
                    },
                    {
                      "slug": "gpt-5.6-sol",
                      "supported_reasoning_levels": [{"effort": "max"}],
                      "supports_reasoning_summaries": true,
                      "support_verbosity": true,
                      "default_verbosity": "low",
                      "supports_parallel_tool_calls": true,
                      "use_responses_lite": true
                    }
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val client = OpenAiSubscriptionModelsClient(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                clientVersion = "1.4.9",
                cacheTtlMs = 300_000,
                timeoutMs = 5_000,
            )
            val session = OpenAiSubscriptionSession(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                idToken = null,
                accountId = "account-1",
                expiresAt = Long.MAX_VALUE,
            )

            val gpt55 = client.getProfile(session, "gpt-5.5")
            val gpt56 = client.getProfile(session, "gpt-5.6-sol")

            assertFalse(gpt55.useResponsesLite)
            assertTrue(gpt55.supportsParallelToolCalls)
            assertTrue(gpt56.useResponsesLite)
            assertEquals(listOf("max"), gpt56.supportedReasoningEfforts)
            assertEquals(1, requests.get())
        } finally {
            server.stop(0)
        }
    }
}
