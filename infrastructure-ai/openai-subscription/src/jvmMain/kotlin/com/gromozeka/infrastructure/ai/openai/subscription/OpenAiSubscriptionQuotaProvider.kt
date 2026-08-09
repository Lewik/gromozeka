package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaWindow
import com.gromozeka.domain.service.DirectAiSubscriptionQuotaProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.time.Duration.Companion.seconds

@Service
class OpenAiSubscriptionQuotaProvider(
    private val authService: OpenAiSubscriptionAuthService,
    @Value("\${gromozeka.ai.openai-subscription.usage-url:https://chatgpt.com/backend-api/wham/usage}")
    private val usageUrl: String,
    @Value("\${gromozeka.ai.openai-subscription.client-version:1.4.9}")
    private val clientVersion: String,
    @Value("\${gromozeka.ai.openai-subscription.usage-timeout-ms:15000}")
    timeoutMs: Long,
) : DirectAiSubscriptionQuotaProvider {
    private val httpClient = HttpClient.newBuilder().build()
    private val timeout = Duration.ofMillis(timeoutMs)

    init {
        require(timeoutMs > 0) { "OpenAI subscription usage timeout must be positive" }
    }

    override fun supports(request: AiSubscriptionQuotaRequest): Boolean =
        request.connection.kind == AiConnection.Kind.OPENAI_SUBSCRIPTION

    override suspend fun read(request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot =
        withContext(Dispatchers.IO) {
            val connection = request.connection as? AiConnection.OpenAiSubscription
                ?: error("OpenAI subscription quota requires an OpenAI subscription connection")
            fetch(connection, authService.getValidSession(), retryOnUnauthorized = true)
        }

    private suspend fun fetch(
        connection: AiConnection.OpenAiSubscription,
        session: OpenAiSubscriptionSession,
        retryOnUnauthorized: Boolean,
    ): AiSubscriptionQuotaSnapshot {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(usageUrl))
            .timeout(timeout)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("originator", OPENAI_SUBSCRIPTION_ORIGINATOR)
            .header("User-Agent", openAiSubscriptionUserAgent(clientVersion))
            .header("Accept", "application/json")
            .GET()
        session.accountId?.let { requestBuilder.header("ChatGPT-Account-Id", it) }
        val response = httpClient.send(
            requestBuilder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (response.statusCode() == 401 && retryOnUnauthorized) {
            val refreshed = authService.refreshTokens(session.refreshToken).getOrThrow()
            return fetch(connection, refreshed, retryOnUnauthorized = false)
        }
        check(response.statusCode() in 200..299) {
            "OpenAI subscription quota request failed: HTTP ${response.statusCode()}"
        }
        return OpenAiSubscriptionQuotaParser.parse(response.body(), connection)
    }
}

internal object OpenAiSubscriptionQuotaParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        body: String,
        connection: AiConnection.OpenAiSubscription,
        observedAt: Instant = Clock.System.now(),
    ): AiSubscriptionQuotaSnapshot {
        val payload = json.parseToJsonElement(body).jsonObject
        val rateLimit = payload["rate_limit"] as? JsonObject
        val windows = buildList {
            rateLimit?.get("primary_window")?.let { element ->
                (element as? JsonObject)?.let { add(it.toWindow("primary", "Primary")) }
            }
            rateLimit?.get("secondary_window")?.let { element ->
                (element as? JsonObject)?.let { add(it.toWindow("secondary", "Secondary")) }
            }
        }
        return AiSubscriptionQuotaSnapshot(
            connectionId = connection.id,
            observedAt = observedAt,
            windows = windows,
            usageBlocked = rateLimit?.get("limit_reached")?.jsonPrimitive?.booleanOrNull == true ||
                rateLimit?.get("allowed")?.jsonPrimitive?.booleanOrNull == false,
        )
    }

    private fun JsonObject.toWindow(
        id: String,
        displayName: String,
    ): AiSubscriptionQuotaWindow {
        val usedPercent = this["used_percent"]?.jsonPrimitive?.doubleOrNull
            ?: error("OpenAI subscription quota $id did not report usage")
        val durationSeconds = this["limit_window_seconds"]?.jsonPrimitive?.longOrNull
            ?: error("OpenAI subscription quota $id did not report a duration")
        val resetsAt = this["reset_at"]?.jsonPrimitive?.longOrNull
            ?.let { Instant.fromEpochSeconds(it) }
            ?: error("OpenAI subscription quota $id did not report a reset time")
        return AiSubscriptionQuotaWindow(
            id = id,
            displayName = displayName,
            usedPercent = usedPercent.coerceIn(0.0, 100.0),
            startedAt = resetsAt - durationSeconds.seconds,
            resetsAt = resetsAt,
        )
    }
}
