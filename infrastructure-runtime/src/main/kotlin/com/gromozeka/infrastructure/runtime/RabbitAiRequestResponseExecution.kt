package com.gromozeka.infrastructure.runtime

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiEmbeddingVector
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.AiRequestResponseExecutionHandler
import com.gromozeka.domain.service.AiSpeechSynthesisRequest
import com.gromozeka.domain.service.AiSpeechSynthesisResponse
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.shared.utils.sha256
import com.gromozeka.shared.uuid.uuid7
import java.util.Base64
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.rabbit.enabled"], havingValue = "true")
class RabbitAiRequestResponseExecutionClient(
    connectionFactory: ConnectionFactory,
    private val topology: RabbitAiRequestResponseTopology,
    @Value("\${gromozeka.runtime.ai-request-response.timeout-millis:1800000}")
    private val timeoutMillis: Long,
    @Value("\${gromozeka.runtime.ai-request-response.max-message-bytes:67108864}")
    private val maxMessageBytes: Int,
) : AiRequestResponseExecutionClient {
    private val json = aiRequestResponseJson()
    private val rabbitTemplate = RabbitTemplate(connectionFactory).apply {
        setReplyTimeout(timeoutMillis)
        setMandatory(true)
    }

    init {
        require(timeoutMillis > 0) { "AI request-response timeout must be positive" }
        require(maxMessageBytes > 0) { "AI request-response message limit must be positive" }
    }

    override suspend fun call(
        target: ConversationRuntimeWorkerIdentity,
        selection: AiRuntimeSelection,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): AiRuntimeResponse =
        execute(
            target,
            AiRequestResponseOperation.Call(
                selection = selection,
                workspaceRootPath = workspaceRootPath,
                request = request.toWire(),
            ),
        ).requirePayload<AiRequestResponsePayload.Call>().response.toRuntime()

    override suspend fun embed(
        target: ConversationRuntimeWorkerIdentity,
        request: AiEmbeddingRequest,
    ): AiEmbeddingResponse =
        execute(target, AiRequestResponseOperation.Embed(request.toWire()))
            .requirePayload<AiRequestResponsePayload.Embed>()
            .response
            .toRuntime()

    override suspend fun transcribe(
        target: ConversationRuntimeWorkerIdentity,
        request: AiSpeechTranscriptionRequest,
    ): String =
        execute(target, AiRequestResponseOperation.Transcribe(request.toWire()))
            .requirePayload<AiRequestResponsePayload.Transcribe>()
            .text

    override suspend fun synthesize(
        target: ConversationRuntimeWorkerIdentity,
        request: AiSpeechSynthesisRequest,
    ): AiSpeechSynthesisResponse =
        execute(target, AiRequestResponseOperation.Synthesize(request.toWire()))
            .requirePayload<AiRequestResponsePayload.Synthesize>()
            .response
            .toRuntime()

    private suspend fun execute(
        target: ConversationRuntimeWorkerIdentity,
        operation: AiRequestResponseOperation,
    ): AiRequestResponseResult = withContext(Dispatchers.IO) {
        topology.declareExchange()
        val request = AiRequestResponseRequest(
            id = uuid7(),
            target = target,
            operation = operation,
        )
        val body = json.encodeToString(request)
        body.requireWithinLimit(maxMessageBytes, "AI request-response request")
        val responseBody = rabbitTemplate.convertSendAndReceive(
            topology.exchangeName,
            topology.routingKey(target),
            body,
        ) as? String
            ?: error(
                "AI request ${request.id} timed out after ${timeoutMillis}ms on Worker " +
                    "${target.workerId.value}; the outcome is unknown and Gromozeka will not retry it automatically"
            )
        responseBody.requireWithinLimit(maxMessageBytes, "AI request-response response")
        val result = json.decodeFromString<AiRequestResponseResult>(responseBody)
        check(result.requestId == request.id) { "AI request-response correlation mismatch" }
        if (result.status == AiRequestResponseResult.Status.FAILED) {
            error("Worker AI request failed [${result.errorCode}]: ${result.errorMessage}")
        }
        result
    }
}

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.rabbit.enabled", "gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class RabbitAiRequestResponseExecutionConsumer(
    private val connectionFactory: ConnectionFactory,
    private val rabbitTemplate: RabbitTemplate,
    private val topology: RabbitAiRequestResponseTopology,
    private val workerIdentity: ConversationRuntimeWorkerIdentity,
    private val handler: AiRequestResponseExecutionHandler,
    @Value("\${gromozeka.runtime.ai-request-response.max-message-bytes:67108864}")
    private val maxMessageBytes: Int,
) : SmartLifecycle {
    private val log = KLoggers.logger(this)
    private val json = aiRequestResponseJson()
    private var listenerContainer: SimpleMessageListenerContainer? = null

    @Volatile
    private var running = false

    init {
        require(maxMessageBytes > 0) { "AI request-response message limit must be positive" }
    }

    override fun start() {
        if (running) return
        val queueName = topology.declareWorkerQueue(workerIdentity)
        listenerContainer = SimpleMessageListenerContainer(connectionFactory).apply {
            setQueueNames(queueName)
            setPrefetchCount(1)
            acknowledgeMode = AcknowledgeMode.MANUAL
            setMessageListener(ChannelAwareMessageListener { message, channel ->
                val deliveryTag = message.messageProperties.deliveryTag
                val request = runCatching {
                    message.body.size.requireWithinLimit(maxMessageBytes, "AI request-response request")
                    json.decodeFromString<AiRequestResponseRequest>(
                        String(message.body, Charsets.UTF_8)
                    )
                }.getOrElse { error ->
                    log.error(error) { "Rejected invalid AI request-response request: ${error.message}" }
                    channel.basicNack(deliveryTag, false, false)
                    return@ChannelAwareMessageListener
                }
                if (request.target != workerIdentity) {
                    log.error {
                        "Rejected AI request for another Worker session: " +
                            "expected=$workerIdentity actual=${request.target}"
                    }
                    channel.basicNack(deliveryTag, false, false)
                    return@ChannelAwareMessageListener
                }

                channel.basicAck(deliveryTag, false)
                val result = runBlocking {
                    runCatching { execute(request) }
                        .getOrElse { error ->
                            AiRequestResponseResult(
                                requestId = request.id,
                                status = AiRequestResponseResult.Status.FAILED,
                                errorCode = error::class.simpleName ?: "AiRequestFailure",
                                errorMessage = error.message ?: "AI request failed",
                            )
                        }
                }
                sendReply(message, result)
            })
            start()
        }
        running = true
        log.info { "Rabbit AI request-response consumer started: identity=$workerIdentity queue=$queueName" }
    }

    override fun stop() {
        listenerContainer?.stop()
        listenerContainer = null
        topology.deleteWorkerQueue(workerIdentity)
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = 250

    private suspend fun execute(request: AiRequestResponseRequest): AiRequestResponseResult {
        val payload = when (val operation = request.operation) {
            is AiRequestResponseOperation.Call -> AiRequestResponsePayload.Call(
                handler.call(
                    selection = operation.selection,
                    workspaceRootPath = operation.workspaceRootPath,
                    request = operation.request.toRuntime(),
                ).toWire()
            )
            is AiRequestResponseOperation.Embed -> AiRequestResponsePayload.Embed(
                handler.embed(operation.request.toRuntime()).toWire()
            )
            is AiRequestResponseOperation.Transcribe -> AiRequestResponsePayload.Transcribe(
                handler.transcribe(operation.request.toRuntime())
            )
            is AiRequestResponseOperation.Synthesize -> AiRequestResponsePayload.Synthesize(
                handler.synthesize(operation.request.toRuntime()).toWire()
            )
        }
        return AiRequestResponseResult(
            requestId = request.id,
            status = AiRequestResponseResult.Status.SUCCEEDED,
            payload = payload,
        )
    }

    private fun sendReply(
        requestMessage: Message,
        result: AiRequestResponseResult,
    ) {
        val replyTo = requestMessage.messageProperties.replyTo
        if (replyTo.isNullOrBlank()) {
            log.warn { "AI request completed without a reply queue: ${result.requestId}" }
            return
        }
        val body = json.encodeToString(result)
        if (runCatching { body.requireWithinLimit(maxMessageBytes, "AI request-response response") }.isFailure) {
            log.error {
                "AI request completed but its response exceeds the configured message limit: ${result.requestId}"
            }
            return
        }
        val response = MessageBuilder.withBody(body.toByteArray(Charsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
            .setCorrelationId(requestMessage.messageProperties.correlationId)
            .build()
        runCatching { rabbitTemplate.send("", replyTo, response) }
            .onFailure { error ->
                log.error(error) {
                    "AI request completed but its response could not be delivered: " +
                        "request=${result.requestId} status=${result.status}"
                }
            }
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.rabbit.enabled"], havingValue = "true")
class RabbitAiRequestResponseTopology(
    private val connectionFactory: ConnectionFactory,
    @Value("\${gromozeka.runtime.ai-request-response.exchange:gromozeka.ai.request-response}")
    val exchangeName: String,
    @Value("\${gromozeka.runtime.ai-request-response.queue-prefix:gromozeka.ai.request-response}")
    private val queuePrefix: String,
) {
    private val exchangeDeclared = AtomicBoolean(false)

    fun routingKey(identity: ConversationRuntimeWorkerIdentity): String =
        "worker.${identity.workerId.value.sha256().take(32)}.session.${identity.sessionId.value.sha256().take(32)}"

    fun declareWorkerQueue(identity: ConversationRuntimeWorkerIdentity): String {
        declareExchange()
        val queueName = "$queuePrefix.${routingKey(identity)}"
        val admin = RabbitAdmin(connectionFactory)
        val queue = QueueBuilder.nonDurable(queueName).exclusive().autoDelete().build()
        admin.declareQueue(queue)
        admin.declareBinding(
            BindingBuilder.bind(queue)
                .to(DirectExchange(exchangeName, true, false))
                .with(routingKey(identity))
        )
        return queueName
    }

    fun deleteWorkerQueue(identity: ConversationRuntimeWorkerIdentity) {
        RabbitAdmin(connectionFactory).deleteQueue("$queuePrefix.${routingKey(identity)}")
    }

    @Synchronized
    fun declareExchange() {
        if (exchangeDeclared.compareAndSet(false, true)) {
            runCatching {
                RabbitAdmin(connectionFactory).declareExchange(DirectExchange(exchangeName, true, false))
            }.onFailure {
                exchangeDeclared.set(false)
                throw it
            }
        }
    }
}

@Serializable
private data class AiRequestResponseRequest(
    val id: String,
    val target: ConversationRuntimeWorkerIdentity,
    val operation: AiRequestResponseOperation,
)

@Serializable
@JsonClassDiscriminator("operationKind")
private sealed interface AiRequestResponseOperation {
    @Serializable
    @SerialName("call")
    data class Call(
        val selection: AiRuntimeSelection,
        val workspaceRootPath: String?,
        val request: AiRuntimeRequestWire,
    ) : AiRequestResponseOperation

    @Serializable
    @SerialName("embed")
    data class Embed(
        val request: AiEmbeddingRequestWire,
    ) : AiRequestResponseOperation

    @Serializable
    @SerialName("transcribe")
    data class Transcribe(
        val request: AiSpeechTranscriptionRequestWire,
    ) : AiRequestResponseOperation

    @Serializable
    @SerialName("synthesize")
    data class Synthesize(
        val request: AiSpeechSynthesisRequestWire,
    ) : AiRequestResponseOperation
}

@Serializable
private data class AiRequestResponseResult(
    val requestId: String,
    val status: Status,
    val payload: AiRequestResponsePayload? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    init {
        require(
            (status == Status.SUCCEEDED && payload != null && errorCode == null && errorMessage == null) ||
                (status == Status.FAILED && payload == null && !errorCode.isNullOrBlank() && !errorMessage.isNullOrBlank())
        ) {
            "AI request-response result payload does not match status $status"
        }
    }

    @Serializable
    enum class Status {
        SUCCEEDED,
        FAILED,
    }
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
        language = language,
        prompt = prompt,
    )

private fun AiSpeechTranscriptionRequestWire.toRuntime(): AiSpeechTranscriptionRequest =
    AiSpeechTranscriptionRequest(
        audioData = Base64.getDecoder().decode(audioBase64),
        format = format,
        engine = engine,
        selection = selection,
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

private inline fun <reified T : AiRequestResponsePayload> AiRequestResponseResult.requirePayload(): T {
    check(status == AiRequestResponseResult.Status.SUCCEEDED) {
        "AI request-response failed [$errorCode]: $errorMessage"
    }
    return payload as? T
        ?: error("AI request-response payload type mismatch: expected ${T::class.simpleName}")
}

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

private fun aiRequestResponseJson(): Json =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

private fun String.requireWithinLimit(maxBytes: Int, label: String) {
    toByteArray(Charsets.UTF_8).size.requireWithinLimit(maxBytes, label)
}

private fun Int.requireWithinLimit(maxBytes: Int, label: String) {
    require(this <= maxBytes) {
        "$label exceeds the configured limit: $this > $maxBytes bytes"
    }
}
