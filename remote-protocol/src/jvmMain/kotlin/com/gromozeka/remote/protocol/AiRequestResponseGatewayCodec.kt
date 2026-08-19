package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiEmbeddingVector
import com.gromozeka.domain.service.AiRequestResponseExecutionHandler
import com.gromozeka.domain.service.AiSpeechSynthesisRequest
import com.gromozeka.domain.service.AiSpeechSynthesisResponse
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object AiRequestResponseGatewayCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encodeCallRequest(
        runtime: ResolvedAiRuntime,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): ByteArray =
        encodeOperation(
            AiRequestResponseOperation.Call(
                runtime = runtime,
                workspaceRootPath = workspaceRootPath,
                request = request.toWire(),
            )
        )

    fun decodeCallResponse(payload: ByteArray): AiRuntimeResponse =
        decodePayload<AiRequestResponsePayload.Call>(payload).response.toRuntime()

    fun encodeEmbeddingRequest(
        runtime: ResolvedAiRuntime,
        request: AiEmbeddingRequest,
    ): ByteArray =
        encodeOperation(
            AiRequestResponseOperation.Embed(
                runtime = runtime,
                request = request.toWire(),
            )
        )

    fun decodeEmbeddingResponse(payload: ByteArray): AiEmbeddingResponse =
        decodePayload<AiRequestResponsePayload.Embed>(payload).response.toRuntime()

    fun encodeTranscriptionRequest(
        runtime: ResolvedAiRuntime?,
        localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
        request: AiSpeechTranscriptionRequest,
    ): ByteArray =
        encodeOperation(
            AiRequestResponseOperation.Transcribe(
                runtime = runtime,
                localWhisperSettings = localWhisperSettings,
                request = request.toWire(),
            )
        )

    fun decodeTranscriptionResponse(payload: ByteArray): String =
        decodePayload<AiRequestResponsePayload.Transcribe>(payload).text

    fun encodeSynthesisRequest(
        runtime: ResolvedAiRuntime,
        request: AiSpeechSynthesisRequest,
    ): ByteArray =
        encodeOperation(
            AiRequestResponseOperation.Synthesize(
                runtime = runtime,
                request = request.toWire(),
            )
        )

    fun decodeSynthesisResponse(payload: ByteArray): AiSpeechSynthesisResponse =
        decodePayload<AiRequestResponsePayload.Synthesize>(payload).response.toRuntime()

    fun encodeSubscriptionQuotaRequest(request: AiSubscriptionQuotaRequest): ByteArray =
        encodeOperation(AiRequestResponseOperation.SubscriptionQuota(request))

    fun decodeSubscriptionQuotaResponse(payload: ByteArray): AiSubscriptionQuotaSnapshot =
        decodePayload<AiRequestResponsePayload.SubscriptionQuota>(payload).snapshot

    suspend fun execute(
        payload: ByteArray,
        handler: AiRequestResponseExecutionHandler,
    ): ByteArray {
        val result = when (val operation = decodeOperation(payload)) {
            is AiRequestResponseOperation.Call -> AiRequestResponsePayload.Call(
                handler.call(
                    runtime = operation.runtime,
                    workspaceRootPath = operation.workspaceRootPath,
                    request = operation.request.toRuntime(),
                ).toWire()
            )

            is AiRequestResponseOperation.Embed -> AiRequestResponsePayload.Embed(
                handler.embed(
                    runtime = operation.runtime,
                    request = operation.request.toRuntime(),
                ).toWire()
            )

            is AiRequestResponseOperation.Transcribe -> AiRequestResponsePayload.Transcribe(
                handler.transcribe(
                    runtime = operation.runtime,
                    localWhisperSettings = operation.localWhisperSettings,
                    request = operation.request.toRuntime(),
                )
            )

            is AiRequestResponseOperation.Synthesize -> AiRequestResponsePayload.Synthesize(
                handler.synthesize(
                    runtime = operation.runtime,
                    request = operation.request.toRuntime(),
                ).toWire()
            )

            is AiRequestResponseOperation.SubscriptionQuota -> AiRequestResponsePayload.SubscriptionQuota(
                handler.readSubscriptionQuota(operation.request)
            )
        }
        return json.encodeToString(AiRequestResponsePayload.serializer(), result).encodeToByteArray()
    }

    private fun encodeOperation(operation: AiRequestResponseOperation): ByteArray =
        json.encodeToString(AiRequestResponseOperation.serializer(), operation).encodeToByteArray()

    private fun decodeOperation(payload: ByteArray): AiRequestResponseOperation =
        json.decodeFromString(AiRequestResponseOperation.serializer(), payload.decodeToString())

    private inline fun <reified T : AiRequestResponsePayload> decodePayload(payload: ByteArray): T =
        json.decodeFromString(AiRequestResponsePayload.serializer(), payload.decodeToString()) as? T
            ?: error("Worker Gateway AI response payload type mismatch: expected ${T::class.simpleName}")
}

@Serializable
@JsonClassDiscriminator("operationKind")
private sealed interface AiRequestResponseOperation {
    @Serializable
    @SerialName("call")
    data class Call(
        val runtime: ResolvedAiRuntime,
        val workspaceRootPath: String?,
        val request: AiRuntimeRequestWire,
    ) : AiRequestResponseOperation

    @Serializable
    @SerialName("embed")
    data class Embed(
        val runtime: ResolvedAiRuntime,
        val request: AiEmbeddingRequestWire,
    ) : AiRequestResponseOperation

    @Serializable
    @SerialName("transcribe")
    data class Transcribe(
        val runtime: ResolvedAiRuntime?,
        val localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
        val request: AiSpeechTranscriptionRequestWire,
    ) : AiRequestResponseOperation

    @Serializable
    @SerialName("synthesize")
    data class Synthesize(
        val runtime: ResolvedAiRuntime,
        val request: AiSpeechSynthesisRequestWire,
    ) : AiRequestResponseOperation

    @Serializable
    @SerialName("subscription_quota")
    data class SubscriptionQuota(
        val request: AiSubscriptionQuotaRequest,
    ) : AiRequestResponseOperation
}

@Serializable
@JsonClassDiscriminator("payloadKind")
private sealed interface AiRequestResponsePayload {
    @Serializable
    @SerialName("call")
    data class Call(val response: AiRuntimeResponseWire) : AiRequestResponsePayload

    @Serializable
    @SerialName("embed")
    data class Embed(val response: AiEmbeddingResponseWire) : AiRequestResponsePayload

    @Serializable
    @SerialName("transcribe")
    data class Transcribe(val text: String) : AiRequestResponsePayload

    @Serializable
    @SerialName("synthesize")
    data class Synthesize(val response: AiSpeechSynthesisResponseWire) : AiRequestResponsePayload

    @Serializable
    @SerialName("subscription_quota")
    data class SubscriptionQuota(
        val snapshot: AiSubscriptionQuotaSnapshot,
    ) : AiRequestResponsePayload
}

@Serializable
private data class AiRuntimeRequestWire(
    val systemPrompts: List<String>,
    val messages: List<Conversation.Message>,
    val tools: List<AiToolDescriptor>,
    val options: AiRuntimeOptionsWire,
)

@Serializable
private data class AiRuntimeOptionsWire(
    val maxOutputTokens: Int?,
    val reasoning: AiReasoningConfig?,
    val autoCompactionThresholdTokens: Int?,
    val toolChoice: ToolChoiceWire,
    val responseFormat: ResponseFormatWire,
    val assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    val toolContext: JsonObject,
    val usagePurpose: String? = null,
)

@Serializable
private data class ToolChoiceWire(
    val kind: Kind,
    val requiredToolName: String? = null,
) {
    @Serializable
    enum class Kind {
        AUTO,
        NONE,
        REQUIRED_ANY,
        REQUIRED_TOOL,
    }
}

@Serializable
private data class ResponseFormatWire(
    val kind: Kind,
    val name: String? = null,
    val schema: JsonObject? = null,
    val description: String? = null,
    val strict: Boolean = true,
) {
    @Serializable
    enum class Kind {
        TEXT,
        JSON_SCHEMA,
    }
}

@Serializable
private data class AiRuntimeResponseWire(
    val messages: List<AiAssistantMessageWire>,
    val usage: AiUsage?,
    val contextUsage: com.gromozeka.domain.model.ai.AiContextUsage? = null,
    val finishReason: String?,
    val providerMetadata: JsonObject,
)

@Serializable
private data class AiAssistantMessageWire(
    val content: List<Conversation.Message.ContentItem>,
    val metadata: JsonObject,
)

@Serializable
private data class AiEmbeddingRequestWire(
    val selection: AiRuntimeSelection,
    val inputs: List<String>,
)

@Serializable
private data class AiEmbeddingResponseWire(
    val modelId: String,
    val dimensions: Int,
    val vectors: List<AiEmbeddingVectorWire>,
    val promptTokens: Int?,
)

@Serializable
private data class AiEmbeddingVectorWire(
    val index: Int,
    val values: List<Float>,
)

@Serializable
private data class AiSpeechTranscriptionRequestWire(
    val audioBase64: String,
    val format: SpeechAudioFormat,
    val engine: UserProfile.SpeechSettings.SpeechToText.Engine,
    val selection: AiRuntimeSelection?,
    val claudeCodeConnection: AiConnection.ClaudeCode?,
    val language: String?,
    val prompt: String?,
)

@Serializable
private data class AiSpeechSynthesisRequestWire(
    val selection: AiRuntimeSelection,
    val text: String,
    val voiceTone: String,
    val voice: String,
    val speed: Float,
)

@Serializable
private data class AiSpeechSynthesisResponseWire(
    val audioBase64: String,
    val mediaType: String,
    val fileExtension: String,
)

private fun AiRuntimeRequest.toWire(): AiRuntimeRequestWire =
    AiRuntimeRequestWire(
        systemPrompts = systemPrompts,
        messages = messages,
        tools = tools.map { AiToolDescriptor(it.definition, it.metadata) },
        options = options.toWire(),
    )

private fun AiRuntimeRequestWire.toRuntime(): AiRuntimeRequest =
    AiRuntimeRequest(
        systemPrompts = systemPrompts,
        messages = messages,
        tools = tools.map(::DescriptorOnlyAiToolCallback),
        options = options.toRuntime(),
    )

private fun AiRuntimeOptions.toWire(): AiRuntimeOptionsWire =
    AiRuntimeOptionsWire(
        maxOutputTokens = maxOutputTokens,
        reasoning = reasoning,
        autoCompactionThresholdTokens = autoCompactionThresholdTokens,
        toolChoice = toolChoice.toWire(),
        responseFormat = responseFormat.toWire(),
        assistantResponseFormat = assistantResponseFormat,
        toolContext = toolContext.toJsonObject(),
        usagePurpose = usagePurpose,
    )

private fun AiRuntimeOptionsWire.toRuntime(): AiRuntimeOptions =
    AiRuntimeOptions(
        maxOutputTokens = maxOutputTokens,
        reasoning = reasoning,
        autoCompactionThresholdTokens = autoCompactionThresholdTokens,
        toolChoice = toolChoice.toRuntime(),
        responseFormat = responseFormat.toRuntime(),
        assistantResponseFormat = assistantResponseFormat,
        toolContext = toolContext.mapValues { (_, value) -> value.toRuntimeValue() },
        usagePurpose = usagePurpose,
    )

private fun AiToolChoice.toWire(): ToolChoiceWire =
    when (this) {
        AiToolChoice.Auto -> ToolChoiceWire(ToolChoiceWire.Kind.AUTO)
        AiToolChoice.None -> ToolChoiceWire(ToolChoiceWire.Kind.NONE)
        AiToolChoice.RequiredAny -> ToolChoiceWire(ToolChoiceWire.Kind.REQUIRED_ANY)
        is AiToolChoice.RequiredTool -> ToolChoiceWire(ToolChoiceWire.Kind.REQUIRED_TOOL, name)
    }

private fun ToolChoiceWire.toRuntime(): AiToolChoice =
    when (kind) {
        ToolChoiceWire.Kind.AUTO -> AiToolChoice.Auto
        ToolChoiceWire.Kind.NONE -> AiToolChoice.None
        ToolChoiceWire.Kind.REQUIRED_ANY -> AiToolChoice.RequiredAny
        ToolChoiceWire.Kind.REQUIRED_TOOL -> AiToolChoice.RequiredTool(
            requireNotNull(requiredToolName) { "Required tool choice name is missing" }
        )
    }

private fun AiResponseFormat.toWire(): ResponseFormatWire =
    when (this) {
        AiResponseFormat.Text -> ResponseFormatWire(ResponseFormatWire.Kind.TEXT)
        is AiResponseFormat.JsonSchema -> ResponseFormatWire(
            kind = ResponseFormatWire.Kind.JSON_SCHEMA,
            name = name,
            schema = schema,
            description = description,
            strict = strict,
        )
    }

private fun ResponseFormatWire.toRuntime(): AiResponseFormat =
    when (kind) {
        ResponseFormatWire.Kind.TEXT -> AiResponseFormat.Text
        ResponseFormatWire.Kind.JSON_SCHEMA -> AiResponseFormat.JsonSchema(
            name = requireNotNull(name) { "JSON schema response format name is missing" },
            schema = requireNotNull(schema) { "JSON schema response format schema is missing" },
            description = description,
            strict = strict,
        )
    }

private fun AiRuntimeResponse.toWire(): AiRuntimeResponseWire =
    AiRuntimeResponseWire(
        messages = messages.map { AiAssistantMessageWire(it.content, it.metadata.toJsonObject()) },
        usage = usage,
        contextUsage = contextUsage,
        finishReason = finishReason,
        providerMetadata = providerMetadata.toJsonObject(),
    )

private fun AiRuntimeResponseWire.toRuntime(): AiRuntimeResponse =
    AiRuntimeResponse(
        messages = messages.map { message ->
            AiAssistantMessage(
                content = message.content,
                metadata = message.metadata.mapValues { (_, value) -> value.toRuntimeValue() },
            )
        },
        usage = usage,
        contextUsage = contextUsage,
        finishReason = finishReason,
        providerMetadata = providerMetadata.mapValues { (_, value) -> value.toRuntimeValue() },
    )

private fun AiEmbeddingRequest.toWire(): AiEmbeddingRequestWire =
    AiEmbeddingRequestWire(selection, inputs)

private fun AiEmbeddingRequestWire.toRuntime(): AiEmbeddingRequest =
    AiEmbeddingRequest(selection, inputs)

private fun AiEmbeddingResponse.toWire(): AiEmbeddingResponseWire =
    AiEmbeddingResponseWire(
        modelId = modelId,
        dimensions = dimensions,
        vectors = vectors.map { AiEmbeddingVectorWire(it.index, it.values) },
        promptTokens = promptTokens,
    )

private fun AiEmbeddingResponseWire.toRuntime(): AiEmbeddingResponse =
    AiEmbeddingResponse(
        modelId = modelId,
        dimensions = dimensions,
        vectors = vectors.map { AiEmbeddingVector(it.index, it.values) },
        promptTokens = promptTokens,
    )

private fun AiSpeechTranscriptionRequest.toWire(): AiSpeechTranscriptionRequestWire =
    AiSpeechTranscriptionRequestWire(
        audioBase64 = Base64.getEncoder().encodeToString(audioData),
        format = format,
        engine = engine,
        selection = selection,
        claudeCodeConnection = claudeCodeConnection,
        language = language,
        prompt = prompt,
    )

private fun AiSpeechTranscriptionRequestWire.toRuntime(): AiSpeechTranscriptionRequest =
    AiSpeechTranscriptionRequest(
        audioData = Base64.getDecoder().decode(audioBase64),
        format = format,
        engine = engine,
        selection = selection,
        claudeCodeConnection = claudeCodeConnection,
        language = language,
        prompt = prompt,
    )

private fun AiSpeechSynthesisRequest.toWire(): AiSpeechSynthesisRequestWire =
    AiSpeechSynthesisRequestWire(selection, text, voiceTone, voice, speed)

private fun AiSpeechSynthesisRequestWire.toRuntime(): AiSpeechSynthesisRequest =
    AiSpeechSynthesisRequest(selection, text, voiceTone, voice, speed)

private fun AiSpeechSynthesisResponse.toWire(): AiSpeechSynthesisResponseWire =
    AiSpeechSynthesisResponseWire(
        audioBase64 = Base64.getEncoder().encodeToString(audioData),
        mediaType = mediaType,
        fileExtension = fileExtension,
    )

private fun AiSpeechSynthesisResponseWire.toRuntime(): AiSpeechSynthesisResponse =
    AiSpeechSynthesisResponse(
        audioData = Base64.getDecoder().decode(audioBase64),
        mediaType = mediaType,
        fileExtension = fileExtension,
    )

private data class DescriptorOnlyAiToolCallback(
    private val descriptor: AiToolDescriptor,
) : AiToolCallback {
    override val definition: AiToolDefinition = descriptor.definition
    override val metadata: AiToolMetadata = descriptor.metadata

    override fun call(toolInput: String, context: ToolExecutionContext?): String =
        error("Descriptor-only AI tool callbacks cannot be executed on a provider Worker")
}

private fun Map<String, Any?>.toJsonObject(): JsonObject =
    JsonObject(mapValues { (_, value) -> value.toJsonElement() })

private fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Byte -> JsonPrimitive(toInt())
        is Short -> JsonPrimitive(toInt())
        is Int -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is Float -> JsonPrimitive(this)
        is Double -> JsonPrimitive(this)
        is Number -> JsonPrimitive(toString())
        is Map<*, *> -> JsonObject(
            entries.associate { (key, value) ->
                require(key is String) { "AI runtime metadata map keys must be strings" }
                key to value.toJsonElement()
            }
        )
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        is Array<*> -> JsonArray(map { it.toJsonElement() })
        else -> JsonPrimitive(toString())
    }

private fun JsonElement.toRuntimeValue(): Any? =
    when (this) {
        JsonNull -> null
        is JsonObject -> mapValues { (_, value) -> value.toRuntimeValue() }
        is JsonArray -> map { it.toRuntimeValue() }
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }
    }
