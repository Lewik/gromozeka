package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.service.SettingsProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class OpenAiSubscriptionAuthServiceTest {

    @Test
    fun concurrentValidSessionRequestsShareSingleRefresh() = runBlocking {
        TokenServer(expectedRefreshTokens = listOf("old-refresh")).use { server ->
            val configService = configService(
                OpenAiSubscriptionConfig(
                    issuer = server.issuer,
                    accessToken = "old-access",
                    refreshToken = "old-refresh",
                    expiresAt = 0,
                )
            )
            val authService = OpenAiSubscriptionAuthService(configService)

            val sessions = coroutineScope {
                listOf(
                    async { authService.getValidSession() },
                    async { authService.getValidSession() },
                ).awaitAll()
            }

            assertEquals(1, server.requestCount.get())
            assertEquals(listOf("new-access-1", "new-access-1"), sessions.map { it.accessToken })
            assertEquals("new-refresh-1", configService.getSession()?.refreshToken)
        }
    }

    @Test
    fun explicitRefreshUsesLatestStoredRefreshTokenWhenCallerHasStaleToken() = runBlocking {
        TokenServer(expectedRefreshTokens = listOf("newer-refresh")).use { server ->
            val configService = configService(
                OpenAiSubscriptionConfig(
                    issuer = server.issuer,
                    accessToken = "newer-access",
                    refreshToken = "newer-refresh",
                    expiresAt = 0,
                )
            )
            val authService = OpenAiSubscriptionAuthService(configService)

            val session = authService.refreshTokens("stale-refresh").getOrThrow()

            assertEquals(1, server.requestCount.get())
            assertEquals("new-access-1", session.accessToken)
            assertEquals("new-refresh-1", session.refreshToken)
            assertEquals("new-refresh-1", configService.getSession()?.refreshToken)
        }
    }

    private fun configService(config: OpenAiSubscriptionConfig): OpenAiSubscriptionConfigService {
        val home = Files.createTempDirectory("gromozeka-openai-subscription-auth-test")
        return OpenAiSubscriptionConfigService(TestSettingsProvider(home.toString())).also {
            it.save(config)
        }
    }

    private class TestSettingsProvider(
        override val homeDirectory: String,
    ) : SettingsProvider {
        override val userProfile: UserProfile = UserProfile()
        override val userDeviceSettings: UserDeviceSettings = UserDeviceSettings.Desktop()
        override val mode: AppMode = AppMode.TEST
    }

    private class TokenServer(
        private val expectedRefreshTokens: List<String>,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestCount = AtomicInteger(0)
        val issuer: String = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/oauth/token", ::handleTokenRequest)
            server.executor = Executors.newCachedThreadPool()
            server.start()
        }

        override fun close() {
            server.stop(0)
        }

        private fun handleTokenRequest(exchange: HttpExchange) {
            try {
                val requestNumber = requestCount.incrementAndGet()
                val form = exchange.requestBody.use { body ->
                    parseForm(body.readBytes().toString(StandardCharsets.UTF_8))
                }
                assertEquals("refresh_token", form["grant_type"])
                val expectedRefreshToken = expectedRefreshTokens.getOrElse(requestNumber - 1) {
                    error("Unexpected extra refresh request #$requestNumber")
                }
                assertEquals(expectedRefreshToken, form["refresh_token"])

                val response = """
                    {
                      "access_token": "new-access-$requestNumber",
                      "refresh_token": "new-refresh-$requestNumber",
                      "expires_in": 3600
                    }
                """.trimIndent()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } catch (error: Throwable) {
                val response = error.message ?: error::class.simpleName ?: "Token server error"
                exchange.sendResponseHeaders(500, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } finally {
                exchange.close()
            }
        }

        private fun parseForm(body: String): Map<String, String> {
            return body.split("&")
                .filter { it.isNotBlank() }
                .associate { part ->
                    val key = part.substringBefore("=")
                    val value = part.substringAfter("=", "")
                    URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
                }
        }
    }
}
