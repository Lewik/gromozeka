package com.gromozeka.infrastructure.ai.openai.siwc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jakarta.annotation.PreDestroy
import klog.KLoggers
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service
internal class OpenAiSiwcBrowserAuthService(
    private val authService: OpenAiSiwcAuthService,
) {
    private val log = KLoggers.logger(this)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "openai-siwc-auth-expiry").apply { isDaemon = true }
    }

    @Volatile
    private var pending: Pending? = null

    fun beginLogin(): Result<OpenAiSiwcAuthService.Authorization> = runCatching {
        synchronized(this) {
            check(pending == null) { "A ChatGPT sign-in is already pending" }
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            val callbackExecutor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "openai-siwc-callback").apply { isDaemon = true }
            }
            try {
                val port = server.address.port
                val authorization = authService.prepareAuthorization("http://127.0.0.1:$port$OPENAI_SIWC_CALLBACK_PATH")
                val created = Pending(server, callbackExecutor, authorization)
                server.createContext(OPENAI_SIWC_CALLBACK_PATH) { exchange -> handle(created, exchange) }
                server.executor = callbackExecutor
                pending = created
                server.start()
                created.expiry = scheduler.schedule(
                    { expire(created) },
                    AUTHORIZATION_TTL_MINUTES,
                    TimeUnit.MINUTES,
                )
                authorization
            } catch (error: Exception) {
                server.stop(0)
                callbackExecutor.shutdownNow()
                throw error
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        cancelLogin()
        scheduler.shutdownNow()
    }

    fun cancelLogin() {
        val cancelled = synchronized(this) {
            val current = pending
            pending = null
            current
        }
        cancelled?.expiry?.cancel(false)
        cancelled?.server?.stop(0)
        cancelled?.callbackExecutor?.shutdownNow()
    }

    internal fun isLoginPending(): Boolean = pending != null

    private fun handle(login: Pending, exchange: HttpExchange) {
        try {
            if (pending !== login || exchange.requestMethod != "GET" ||
                exchange.requestURI.path != OPENAI_SIWC_CALLBACK_PATH ||
                exchange.requestHeaders.getFirst("Host") != "127.0.0.1:${login.server.address.port}") {
                send(exchange, 404, "Not Found", "No matching ChatGPT sign-in was found.")
                return
            }
            val parameters = parseQuery(exchange.requestURI.rawQuery)
            val returnedState = parameters.single("state")
            if (returnedState == null || !MessageDigest.isEqual(
                    returnedState.toByteArray(StandardCharsets.UTF_8),
                    login.authorization.state.toByteArray(StandardCharsets.UTF_8),
                )) {
                send(exchange, 400, "Invalid Sign-In", "The ChatGPT sign-in state did not match.")
                return
            }
            parameters.single("error")?.let { error ->
                send(exchange, 400, "Sign-In Failed", "ChatGPT rejected sign-in: ${html(error.take(120))}")
                finish(login)
                return
            }
            val code = parameters.single("code")
            if (code.isNullOrBlank()) {
                send(exchange, 400, "Sign-In Failed", "ChatGPT did not return an authorization code.")
                finish(login)
                return
            }
            authService.completeAuthorization(login.authorization, code, parameters.single("client_id"))
            send(exchange, 200, "ChatGPT Connected", "Gromozeka can now use your ChatGPT plan. You can close this tab.")
            log.info("ChatGPT SIWC sign-in completed")
            finish(login)
        } catch (error: Exception) {
            log.error(error) { "ChatGPT SIWC sign-in failed" }
            send(exchange, 500, "Sign-In Failed", html(error.message ?: "ChatGPT sign-in failed."))
            finish(login)
        } finally {
            exchange.close()
        }
    }

    private fun finish(login: Pending) {
        if (!removePending(login)) return
        Thread {
            login.server.stop(0)
            login.callbackExecutor.shutdown()
        }.apply { isDaemon = true; start() }
    }

    private fun expire(login: Pending) {
        if (!removePending(login)) return
        login.server.stop(0)
        login.callbackExecutor.shutdownNow()
        log.warn("ChatGPT SIWC sign-in expired before the callback arrived")
    }

    private fun removePending(login: Pending): Boolean = synchronized(this) {
        if (pending !== login) return@synchronized false
        pending = null
        login.expiry?.cancel(false)
        true
    }

    private fun parseQuery(raw: String?): Map<String, List<String>> = raw.orEmpty().split('&')
        .filter(String::isNotBlank)
        .groupBy(
            keySelector = { URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) },
            valueTransform = { URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8) },
        )

    private fun Map<String, List<String>>.single(name: String): String? = get(name)?.singleOrNull()

    private fun send(exchange: HttpExchange, status: Int, title: String, message: String) {
        val body = """
            <!doctype html><html lang="en"><meta charset="utf-8"><title>${html(title)}</title>
            <style>body{font:17px system-ui;max-width:36rem;margin:18vh auto;padding:24px;color:#202123}h1{font-size:26px}</style>
            <h1>${html(title)}</h1><p>$message</p></html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun html(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character
            })
        }
    }

    private data class Pending(
        val server: HttpServer,
        val callbackExecutor: ExecutorService,
        val authorization: OpenAiSiwcAuthService.Authorization,
        var expiry: ScheduledFuture<*>? = null,
    )

    private companion object {
        const val AUTHORIZATION_TTL_MINUTES = 10L
    }
}
