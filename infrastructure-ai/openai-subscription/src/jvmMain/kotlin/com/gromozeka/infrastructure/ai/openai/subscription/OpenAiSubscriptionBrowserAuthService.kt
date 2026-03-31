package com.gromozeka.infrastructure.ai.openai.subscription

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

@Service
class OpenAiSubscriptionBrowserAuthService(
    private val authService: OpenAiSubscriptionAuthService,
    private val configService: OpenAiSubscriptionConfigService,
) {
    private val log = KLoggers.logger(this)

    @Volatile
    private var server: HttpServer? = null

    fun beginLogin(): Result<OpenAiSubscriptionAuthorizationUrl> = runCatching {
        ensureServerStarted()
        val authorizationUrl = authService.getBrowserAuthorizationUrl()
        configService.updatePendingAuthorization(authorizationUrl)
        authorizationUrl
    }

    @PreDestroy
    fun shutdown() {
        synchronized(this) {
            server?.stop(0)
            server = null
        }
    }

    private fun ensureServerStarted() {
        synchronized(this) {
            if (server != null) return

            val config = configService.load()
            val redirectUri = URI.create(config.redirectUri)
            val host = redirectUri.host ?: "127.0.0.1"
            val port = if (redirectUri.port != -1) redirectUri.port else 80
            val path = redirectUri.path.takeIf { it.isNotBlank() } ?: "/"

            val httpServer = HttpServer.create(InetSocketAddress(host, port), 0)
            httpServer.createContext(path, ::handleCallback)
            httpServer.executor = Executors.newSingleThreadExecutor()
            httpServer.start()
            server = httpServer

            log.info("OpenAI subscription callback server started on ${config.redirectUri}")
        }
    }

    private fun handleCallback(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                sendHtml(exchange, 405, "Method Not Allowed", "Use a browser to complete the OpenAI login.")
                return
            }

            val params = parseQuery(exchange.requestURI.rawQuery)
            val error = params["error"]
            if (!error.isNullOrBlank()) {
                val description = params["error_description"] ?: "OpenAI returned an authentication error."
                configService.clearPendingAuthorization()
                sendHtml(exchange, 400, "Authentication Failed", "$error: $description")
                return
            }

            val code = params["code"]
            val state = params["state"]
            if (code.isNullOrBlank()) {
                sendHtml(exchange, 400, "Missing Code", "The callback did not include an authorization code.")
                return
            }

            val config = configService.load()
            val verifier = config.pendingVerifier
            val expectedState = config.pendingState
            if (verifier.isNullOrBlank() || expectedState.isNullOrBlank()) {
                sendHtml(exchange, 400, "No Pending Login", "No pending OpenAI browser login was found. Restart Gromozeka and try again.")
                return
            }

            if (state != expectedState) {
                sendHtml(exchange, 400, "State Mismatch", "The login callback state did not match the pending authorization.")
                return
            }

            val result = runBlocking {
                authService.exchangeBrowserCodeForTokens(code, verifier)
            }

            result
                .onSuccess {
                    log.info("OpenAI subscription browser login completed successfully")
                    sendHtml(exchange, 200, "Authentication Complete", "Gromozeka is now connected to your OpenAI subscription. You can close this tab.")
                }
                .onFailure { errorCause ->
                    configService.clearPendingAuthorization()
                    log.error(errorCause) { "OpenAI subscription browser login failed" }
                    sendHtml(exchange, 500, "Authentication Failed", errorCause.message ?: "Token exchange failed.")
                }
        } catch (error: Exception) {
            log.error(error) { "Failed to process OpenAI subscription callback" }
            sendHtml(exchange, 500, "Authentication Failed", error.message ?: "Unexpected callback error.")
        } finally {
            exchange.close()
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()

        return rawQuery.split("&")
            .filter { it.isNotBlank() }
            .associate { part ->
                val key = part.substringBefore("=")
                val value = part.substringAfter("=", "")
                URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
    }

    private fun sendHtml(exchange: HttpExchange, status: Int, title: String, message: String) {
        val body = """
            <html>
              <head>
                <meta charset="utf-8" />
                <title>$title</title>
              </head>
              <body style="font-family: sans-serif; max-width: 640px; margin: 40px auto; line-height: 1.5;">
                <h1>$title</h1>
                <p>$message</p>
              </body>
            </html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { output ->
            output.write(body)
        }
    }
}
