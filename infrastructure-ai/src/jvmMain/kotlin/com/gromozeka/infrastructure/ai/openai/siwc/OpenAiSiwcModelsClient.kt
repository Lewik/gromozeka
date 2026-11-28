package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.model.ai.AiConnectionModels
import com.gromozeka.domain.model.ai.AiModelCandidate
import com.gromozeka.infrastructure.ai.diagnostics.AiTrafficDumpHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Instant

@Component
internal class OpenAiSiwcModelsClient @Autowired constructor(
    private val accessTokens: OpenAiSiwcAccessTokenProvider,
) {
    private var modelsUrl = MODELS_URL
    private var http = AiTrafficDumpHttpClient(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build(),
        "openai-siwc-models",
    )

    internal constructor(
        accessTokens: OpenAiSiwcAccessTokenProvider,
        modelsUrl: String,
        httpClient: HttpClient,
    ) : this(accessTokens) {
        this.modelsUrl = modelsUrl
        this.http = AiTrafficDumpHttpClient(httpClient, "openai-siwc-models-test")
    }

    suspend fun list(deadline: Instant): AiConnectionModels = withContext(Dispatchers.IO) {
        val deadlineNanos = siwcDeadlineNanos(deadline)
        val token = accessTokens.validAccessToken(deadlineNanos)
        val request = HttpRequest.newBuilder(URI.create(modelsUrl))
            .timeout(Duration.ofNanos(remainingNanos(deadlineNanos)))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val response = http.sendSiwc(request, deadlineNanos, MAX_BODY_BYTES)
        val bytes = response.body()
        if (response.statusCode() !in 200..299) {
            throw OpenAiSiwcException(
                "model_catalog_failed",
                "ChatGPT model listing failed.",
                response.statusCode() >= 500,
                response.statusCode(),
            )
        }
        val root = try {
            Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                ?: error("Expected an object")
        } catch (error: Exception) {
            throw OpenAiSiwcException("invalid_model_catalog", "ChatGPT returned an invalid model catalog.", true, response.statusCode(), error)
        }
        val values = root["models"] as? JsonArray
            ?: throw OpenAiSiwcException("invalid_model_catalog", "ChatGPT returned an invalid model catalog.", true)
        if (values.size > 10_000) {
            throw OpenAiSiwcException("invalid_model_catalog", "ChatGPT model catalog contains too many entries.", true)
        }
        val models = values.mapNotNull { element ->
            val model = element as? JsonObject
                ?: throw OpenAiSiwcException("invalid_model_catalog", "ChatGPT returned an invalid model catalog.", true)
            if ((model["visibility"] as? JsonPrimitive)?.contentOrNull != "list") return@mapNotNull null
            val slug = model.string("slug")
            val displayName = model.string("display_name")
            if (slug.isNullOrBlank() || displayName.isNullOrBlank()) {
                throw OpenAiSiwcException("invalid_model_catalog", "ChatGPT returned an incomplete model catalog.", true)
            }
            try {
                AiModelCandidate(id = slug, displayName = displayName)
            } catch (error: IllegalArgumentException) {
                throw OpenAiSiwcException(
                    "invalid_model_catalog",
                    "ChatGPT returned an invalid model catalog.",
                    true,
                    cause = error,
                )
            }
        }
        if (models.map(AiModelCandidate::id).distinct().size != models.size) {
            throw OpenAiSiwcException("invalid_model_catalog", "ChatGPT model catalog contains duplicate IDs.", true)
        }
        AiConnectionModels(models, Clock.System.now())
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private companion object {
        const val MODELS_URL = "https://api.openai.com/v1/models"
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}
