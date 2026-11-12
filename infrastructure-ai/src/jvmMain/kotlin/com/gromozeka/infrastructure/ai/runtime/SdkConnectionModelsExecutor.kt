package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiConnectionModels
import com.gromozeka.domain.model.ai.AiHttpSettings
import com.gromozeka.domain.model.ai.AiModelCandidate
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.requireGatewayModelListing
import com.gromozeka.domain.service.AiConnectionModelsExecutor
import com.gromozeka.domain.service.SettingsProvider
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Instant

/** Explicit OpenAI-compatible /models discovery, independent of the inference API and model catalog. */
@Service
class SdkConnectionModelsExecutor(
    private val settingsProvider: SettingsProvider,
    private val admission: com.gromozeka.domain.service.AiCallAdmission,
) : AiConnectionModelsExecutor {
    override suspend fun listModels(connection: AiConnection, deadline: Instant): AiConnectionModels {
        connection.requireGatewayModelListing()
        val executor = SdkHttpCallExecutor(connection, timeoutSeconds = 30, maxAttempts = 1, admission = admission) { end ->
            val settings = SdkHttpSupport.settings(connection)
            val baseUrl = requireNotNull((connection as AiConnection.HttpAiConnection).baseUrl)
            val token = SdkHttpSupport.token(connection, settingsProvider,
                if (connection.kind == AiConnection.Kind.ANTHROPIC_API) "ANTHROPIC_API_KEY" else "OPENAI_API_KEY")
            val request = Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegment("models").build())
                .get().header("Accept", "application/json").header("User-Agent", "Gromozeka")
                .apply {
                    if (connection.kind == AiConnection.Kind.ANTHROPIC_API && settings.authentication == AiHttpSettings.Authentication.PROVIDER_DEFAULT)
                        header("x-api-key", token)
                    else header("Authorization", "Bearer $token")
                    SdkHttpSupport.headers(settings).forEach { (name, value) -> header(name, value) }
                }.build()
            val transport = SdkHttpTransport(settings, baseUrl, end)
            SdkInferenceClient(ModelsClient(request, transport), transport) {}
        }
        return executor.call(AiRuntimeOptions(executionDeadline = deadline, usagePurpose = "MODEL_DISCOVERY")) { client, _ -> client.read() }
    }

    private class ModelsClient(val request: Request, val transport: SdkHttpTransport) {
        fun read() = transport.execute(request) {}.thenApply { response ->
            response.use {
                val body = response.body.readNBytes(MAX_BODY_BYTES + 1)
                require(body.size <= MAX_BODY_BYTES) { "Model-list response exceeds the supported size" }
                check(response.response.code in 200..299) { "Model-list HTTP request failed" }
                AiConnectionModels(parseModels(body.decodeToString(throwOnInvalidSequence = true)), Clock.System.now())
            }
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        fun parseModels(body: String): List<AiModelCandidate> {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Expected a model-list object")
            require(root.string("object") == "list") { "Expected an OpenAI-compatible model list" }
            root["has_more"]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull == false) {
                "Paginated model lists are not supported; no partial list was returned"
            } }
            val values = root["data"] as? JsonArray ?: error("Model list has no data array")
            require(values.size <= 10_000) { "Model list exceeds the supported item count" }
            val models = values.map { element ->
                val model = element as? JsonObject ?: error("Invalid model-list item")
                val created = model["created"]?.takeUnless { it is JsonNull }?.let {
                    require(it is JsonPrimitive && !it.isString && it.longOrNull != null) { "Invalid model timestamp" }
                    it.long
                }
                AiModelCandidate(requireNotNull(model.string("id")) { "Model id is missing" },
                    model.string("object"), created, model.string("owned_by"))
            }
            require(models.map { it.id }.distinct().size == models.size) { "Model list contains duplicate ids" }
            return models
        }
        fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it is JsonNull }?.let {
            require(it is JsonPrimitive && it.isString) { "Invalid model-list string field" }
            it.content
        }
    }
}
