package com.gromozeka.server.testsupport.llm

import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiEmbeddingProvider
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiEmbeddingVector
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import klog.KLoggers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class CassetteAiEmbeddingProvider(
    private val delegate: AiEmbeddingProvider,
    private val settingsProvider: SettingsProvider? = null,
    private val userProfile: UserProfile = UserProfile(),
    private val settings: AiRuntimeCassetteSettings = AiRuntimeCassetteSettings.fromSystemProperties(),
) : AiEmbeddingProvider {
    private val log = KLoggers.logger(this)
    private val store = AiEmbeddingCassetteStore(settings.rootDirectory)

    override suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse {
        if (settings.mode == AiRuntimeCassetteMode.OFF) return delegate.embed(request)

        val runtime = resolveRuntime(request.selection)
        val provider = runtime.connection.kind.provider.name
        val modelName = runtime.modelConfiguration.providerModelId
        val key = store.keyFor(provider, modelName, request)
        val existing = store.read(key)

        if (existing != null && settings.mode != AiRuntimeCassetteMode.REFRESH) {
            log.info { "Embedding cassette replay: provider=$provider model=$modelName hash=${key.hash}" }
            return existing.toResponse()
        }
        if (settings.mode == AiRuntimeCassetteMode.REPLAY_ONLY) {
            error("Embedding cassette miss: provider=$provider model=$modelName hash=${key.hash} path=${key.path}")
        }

        log.info { "Embedding cassette record: provider=$provider model=$modelName hash=${key.hash}" }
        val response = delegate.embed(request)
        store.write(key, response)
        return response
    }

    private fun resolveRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime {
        settingsProvider?.let { return it.resolveAiRuntime(selection) }
        val modelConfiguration = userProfile.aiSettings.modelConfigurations.firstOrNull {
            it.id == selection.modelConfigurationId
        } ?: error("AI model configuration not found: ${selection.modelConfigurationId.value}")
        val connection = userProfile.aiSettings.connections.firstOrNull { it.id == modelConfiguration.connectionId }
            ?: error("AI connection not found: ${modelConfiguration.connectionId.value}")
        require(connection.enabled) { "AI connection is disabled: ${connection.id.value}" }
        require(modelConfiguration.enabled) { "AI model configuration is disabled: ${modelConfiguration.id.value}" }
        return ResolvedAiRuntime(connection, modelConfiguration)
    }
}

private class AiEmbeddingCassetteStore(
    private val rootDirectory: Path,
) {
    fun keyFor(
        provider: String,
        modelName: String,
        request: AiEmbeddingRequest,
    ): AiEmbeddingCassetteKey {
        val canonicalRequest = request.toEmbeddingCassetteRequestJson()
        val fingerprint = buildJsonObject {
            put("version", EMBEDDING_CASSETTE_VERSION)
            put("provider", provider)
            put("model", modelName)
            put("operation", "embed")
            put("request", canonicalRequest)
        }
        val hash = embeddingCassetteJson.encodeToString(fingerprint).sha256ForEmbeddingCassette()
        val path = rootDirectory
            .resolve(provider.sanitizeEmbeddingCassettePathPart())
            .resolve(modelName.sanitizeEmbeddingCassettePathPart())
            .resolve("embed")
            .resolve("$hash.json")
        return AiEmbeddingCassetteKey(
            provider = provider,
            modelName = modelName,
            hash = hash,
            canonicalRequest = canonicalRequest,
            path = path,
        )
    }

    fun read(key: AiEmbeddingCassetteKey): AiEmbeddingCassetteFile? {
        if (!key.path.exists()) return null
        val file = embeddingCassetteJson.decodeFromString<AiEmbeddingCassetteFile>(key.path.readText())
        require(file.version == EMBEDDING_CASSETTE_VERSION) {
            "Unsupported embedding cassette version ${file.version} at ${key.path}"
        }
        require(file.hash == key.hash) {
            "Embedding cassette hash mismatch: expected ${key.hash}, file has ${file.hash}, path=${key.path}"
        }
        return file
    }

    fun write(key: AiEmbeddingCassetteKey, response: AiEmbeddingResponse) {
        key.path.parent.createDirectories()
        val file = AiEmbeddingCassetteFile(
            version = EMBEDDING_CASSETTE_VERSION,
            hash = key.hash,
            provider = key.provider,
            model = key.modelName,
            operation = "embed",
            recordedAt = "2026-01-01T00:00:00Z",
            canonicalRequest = key.canonicalRequest.toString(),
            response = response.toCassetteSnapshot(),
        )
        val tmpPath = key.path.resolveSibling("${key.path.fileName}.tmp")
        tmpPath.writeText(embeddingCassetteJson.encodeToString(file))
        Files.move(
            tmpPath,
            key.path,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }
}

private data class AiEmbeddingCassetteKey(
    val provider: String,
    val modelName: String,
    val hash: String,
    val canonicalRequest: kotlinx.serialization.json.JsonObject,
    val path: Path,
)

@Serializable
private data class AiEmbeddingCassetteFile(
    val version: Int,
    val hash: String,
    val provider: String,
    val model: String,
    val operation: String,
    val recordedAt: String,
    val canonicalRequest: String,
    val response: AiEmbeddingResponseSnapshot,
) {
    fun toResponse(): AiEmbeddingResponse =
        AiEmbeddingResponse(
            modelId = response.modelId,
            dimensions = response.dimensions,
            vectors = response.vectors.map {
                AiEmbeddingVector(
                    index = it.index,
                    values = it.values,
                )
            },
            promptTokens = response.promptTokens,
        )
}

@Serializable
private data class AiEmbeddingResponseSnapshot(
    val modelId: String,
    val dimensions: Int,
    val vectors: List<AiEmbeddingVectorSnapshot>,
    val promptTokens: Int? = null,
)

@Serializable
private data class AiEmbeddingVectorSnapshot(
    val index: Int,
    val values: List<Float>,
)

private fun AiEmbeddingResponse.toCassetteSnapshot(): AiEmbeddingResponseSnapshot =
    AiEmbeddingResponseSnapshot(
        modelId = modelId,
        dimensions = dimensions,
        vectors = vectors.sortedBy { it.index }.map {
            AiEmbeddingVectorSnapshot(
                index = it.index,
                values = it.values,
            )
        },
        promptTokens = promptTokens,
    )

private fun AiEmbeddingRequest.toEmbeddingCassetteRequestJson() =
    buildJsonObject {
        put("modelConfigurationId", selection.modelConfigurationId.value)
        putJsonArray("inputs") {
            inputs.forEach { input -> add(kotlinx.serialization.json.JsonPrimitive(input.normalizeEmbeddingCassetteRuntimeText())) }
        }
    }

private fun String.normalizeEmbeddingCassetteRuntimeText(): String =
    replace(Regex("/tmp/gromozeka-e2e-[^/\\s]+"), "/tmp/gromozeka-e2e-<run>")
        .replace(Regex("gromozeka_memory_e2e_[0-9a-fA-F_\\-]+"), "gromozeka_memory_e2e_<run>")

private fun String.sanitizeEmbeddingCassettePathPart(): String =
    replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "unknown" }

private fun String.sha256ForEmbeddingCassette(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private val embeddingCassetteJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

private const val EMBEDDING_CASSETTE_VERSION = 1
