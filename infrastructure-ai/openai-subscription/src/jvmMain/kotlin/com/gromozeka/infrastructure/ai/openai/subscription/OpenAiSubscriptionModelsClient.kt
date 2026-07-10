package com.gromozeka.infrastructure.ai.openai.subscription

import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
class OpenAiSubscriptionModelsClient(
    @Value("\${gromozeka.ai.openai-subscription.base-url:https://chatgpt.com/backend-api/codex}")
    private val baseUrl: String,
    @Value("\${gromozeka.ai.openai-subscription.client-version:1.4.9}")
    private val clientVersion: String,
    @Value("\${gromozeka.ai.openai-subscription.models-cache-ttl-ms:300000}")
    private val cacheTtlMs: Long,
    @Value("\${gromozeka.ai.openai-subscription.models-timeout-ms:30000}")
    private val timeoutMs: Long,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()
    private val cache = ConcurrentHashMap<String, CachedModels>()

    fun getProfile(
        session: OpenAiSubscriptionSession,
        modelName: String,
    ): OpenAiSubscriptionModelProfile {
        val models = getModels(session)
        return models.singleOrNull { it.slug == modelName }?.toProfile()
            ?: throw OpenAiSubscriptionRequestException(
                statusCode = 400,
                message = "OpenAI subscription model is not available for this account: $modelName",
            )
    }

    private fun getModels(session: OpenAiSubscriptionSession): List<OpenAiSubscriptionRemoteModel> {
        val now = System.currentTimeMillis()
        val cacheKey = session.accountId ?: session.accessToken.hashCode().toString()
        cache[cacheKey]?.takeIf { now - it.loadedAtMs < cacheTtlMs.coerceAtLeast(0L) }?.let {
            return it.models
        }

        val models = fetchModels(session)
        cache[cacheKey] = CachedModels(loadedAtMs = now, models = models)
        return models
    }

    private fun fetchModels(session: OpenAiSubscriptionSession): List<OpenAiSubscriptionRemoteModel> {
        val encodedVersion = URLEncoder.encode(clientVersion, StandardCharsets.UTF_8)
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}/models?client_version=$encodedVersion"))
            .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(1L)))
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("originator", OPENAI_SUBSCRIPTION_ORIGINATOR)
            .header("User-Agent", openAiSubscriptionUserAgent(clientVersion))
            .header("Accept", "application/json")
            .GET()

        session.accountId?.let { requestBuilder.header("ChatGPT-Account-Id", it) }

        val response = try {
            httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OpenAiSubscriptionTransportException("Interrupted while loading OpenAI subscription models", error)
        } catch (error: Exception) {
            throw OpenAiSubscriptionTransportException("Failed to load OpenAI subscription models", error)
        }

        if (response.statusCode() == 401) {
            throw OpenAiSubscriptionUnauthorizedException("OpenAI subscription models request is unauthorized")
        }
        if (response.statusCode() !in 200..299) {
            throw OpenAiSubscriptionRequestException(
                statusCode = response.statusCode(),
                message = "OpenAI subscription models request failed: HTTP ${response.statusCode()}",
            )
        }

        return try {
            json.decodeFromString<OpenAiSubscriptionModelsResponse>(response.body()).models
        } catch (error: Exception) {
            throw OpenAiSubscriptionTransportException("OpenAI subscription models response is invalid", error)
        }
    }

    private data class CachedModels(
        val loadedAtMs: Long,
        val models: List<OpenAiSubscriptionRemoteModel>,
    )
}
