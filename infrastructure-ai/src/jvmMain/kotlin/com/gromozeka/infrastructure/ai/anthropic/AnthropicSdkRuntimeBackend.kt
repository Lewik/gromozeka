package com.gromozeka.infrastructure.ai.anthropic

import com.anthropic.bedrock.backends.BedrockBackend
import com.anthropic.backends.Backend
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.anthropic.models.messages.ThinkingConfigEnabled
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAny
import com.anthropic.models.messages.ToolChoiceNone
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlockParam
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.infrastructure.ai.parsers.AssistantResponseParser
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain
import kotlin.jvm.optionals.getOrNull

@Service
internal class AnthropicSdkRuntimeBackend(
    private val settingsProvider: SettingsProvider,
) : AiRuntimeBackend {

    override fun supports(connectionKind: AiConnection.Kind): Boolean =
        connectionKind == AiConnection.Kind.ANTHROPIC_API || connectionKind == AiConnection.Kind.ANTHROPIC_BEDROCK

    override fun createRuntime(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
        projectPath: String?
    ): AiRuntime {
        val connectionKind = connection.kind
        val client = when (connectionKind) {
            AiConnection.Kind.ANTHROPIC_API -> createDirectAnthropicClient(connection)
            AiConnection.Kind.ANTHROPIC_BEDROCK -> createBedrockAnthropicClient(connection)
            else -> error("Anthropic SDK runtime does not support connection kind $connectionKind")
        }

        return AnthropicSdkRuntime(
            connectionKind,
            modelConfiguration.providerModelId,
            client,
            AnthropicSdkMessageMapper(connectionKind)
        )
    }

    private fun createDirectAnthropicClient(connection: AiConnection): AnthropicClient {
        val builder = AnthropicOkHttpClient.builder()
            .maxRetries(0)

        val apiKey = settingsProvider.resolveSecret((connection as? AiConnection.ApiKeyAiConnection)?.apiKey)
            ?.takeIf { it.isNotBlank() }
        if (apiKey != null) {
            builder.apiKey(apiKey)
        } else {
            builder.fromEnv()
        }

        ((connection as? AiConnection.HttpAiConnection)?.baseUrl ?: "https://api.anthropic.com")
            .takeIf { it.isNotBlank() }
            ?.let(builder::baseUrl)

        return builder.build()
    }

    private fun createBedrockAnthropicClient(connection: AiConnection): AnthropicClient {
        val awsConnection = connection as? AiConnection.AwsAiConnection
            ?: error("Bedrock Anthropic connection must expose AWS settings")
        val region = awsConnection.awsRegion?.takeIf { it.isNotBlank() }
        val baseUrl = (connection as? AiConnection.HttpAiConnection)?.baseUrl?.takeIf { it.isNotBlank() }
        val profile = awsConnection.awsProfile?.takeIf { it.isNotBlank() }
        val standardBackend = createBedrockBackend(region, profile)
        val backend = baseUrl?.let { BaseUrlOverrideBackend(standardBackend, it) } ?: standardBackend

        KLoggers.logger(this).info {
            "Creating Anthropic Bedrock client: " +
                "region=${region ?: "default-chain"} profile=${profile ?: "default-chain"} " +
                "baseUrlOverride=${baseUrl != null}"
        }

        return AnthropicOkHttpClient.builder()
            .maxRetries(0)
            .backend(backend)
            .build()
    }

    private fun createBedrockBackend(region: String?, profile: String?): BedrockBackend {
        val apiKey = System.getenv("AWS_BEARER_TOKEN_BEDROCK")?.takeIf { it.isNotBlank() }
        val resolvedRegion = resolveBedrockRegion(region, profile)
        val builder = BedrockBackend.builder().region(resolvedRegion)

        if (apiKey != null && profile == null) {
            return builder.apiKey(apiKey).build()
        }

        val credentialsProvider = DefaultCredentialsProvider.builder()
            .apply {
                profile?.let(::profileName)
                asyncCredentialUpdateEnabled(true)
            }
            .build()

        credentialsProvider.resolveCredentials()

        return builder
            .awsCredentialsProvider(credentialsProvider)
            .build()
    }

    private fun resolveBedrockRegion(region: String?, profile: String?): Region =
        region
            ?.let(Region::of)
            ?: DefaultAwsRegionProviderChain.builder()
                .apply { profile?.let(::profileName) }
                .build()
                .region
}

private class BaseUrlOverrideBackend(
    private val delegate: Backend,
    private val baseUrl: String,
) : Backend {
    override fun baseUrl(): String = baseUrl
    override fun prepareRequest(request: HttpRequest): HttpRequest = delegate.prepareRequest(request)
    override fun authorizeRequest(request: HttpRequest): HttpRequest = delegate.authorizeRequest(request)
    override fun prepareResponse(response: HttpResponse): HttpResponse = delegate.prepareResponse(response)
    override fun close() = delegate.close()
}

private class AnthropicSdkRuntime(
    private val connectionKind: AiConnection.Kind,
    private val modelName: String,
    private val client: AnthropicClient,
    private val messageMapper: AnthropicSdkMessageMapper,
) : AiRuntime {
    private val log = KLoggers.logger(this)

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        val params = messageMapper.toCreateParams(modelName, request)
        log.info {
            "Calling Anthropic SDK runtime: connectionKind=$connectionKind model=$modelName " +
                "messages=${request.messages.size} tools=${request.tools.size} " +
                "responseFormat=${request.options.responseFormat.logName()}"
        }

        val response = withContext(Dispatchers.IO) {
            client.messages().create(params)
        }

        return messageMapper.toRuntimeResponse(response, request.options.assistantResponseFormat)
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
        emit(call(request))
    }
}

internal class AnthropicSdkMessageMapper(
    private val connectionKind: AiConnection.Kind,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun toCreateParams(modelName: String, request: AiRuntimeRequest): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(modelName)
            .maxTokens((request.options.maxOutputTokens ?: DEFAULT_MAX_TOKENS).toLong())

        val systemPrompt = request.systemPrompts
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        if (systemPrompt.isNotBlank()) {
            if (connectionKind == AiConnection.Kind.ANTHROPIC_API) {
                builder.systemOfTextBlockParams(
                    listOf(
                        TextBlockParam.builder()
                            .text(systemPrompt)
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build()
                    )
                )
            } else {
                builder.system(systemPrompt)
            }
        }

        val messages = request.messages.mapNotNull(::toMessageParam)
        require(messages.isNotEmpty()) { "Anthropic request must contain at least one user or assistant message" }
        builder.messages(messages)

        if (connectionKind == AiConnection.Kind.ANTHROPIC_API) {
            builder.cacheControl(CacheControlEphemeral.builder().build())
        }

        applyTools(builder, request.tools, request.options.toolChoice)
        applyThinking(builder, request.options.reasoning)
        applyOutputConfig(builder, request.options)

        return builder.build()
    }

    fun toRuntimeResponse(
        message: Message,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): AiRuntimeResponse {
        val content = message.content().flatMap { toContentItems(it, assistantResponseFormat) }
        val assistantMessages = if (content.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AiAssistantMessage(
                    content = content,
                    metadata = mapOf(
                        "provider" to connectionKind.name,
                        "model" to message.model().asString(),
                        "messageId" to message.id(),
                    )
                )
            )
        }

        return AiRuntimeResponse(
            messages = assistantMessages,
            usage = toAiUsage(message.usage()),
            finishReason = message.stopReason().getOrNull()?.asString(),
            providerMetadata = mapOf(
                "provider" to connectionKind.name,
                "model" to message.model().asString(),
                "messageId" to message.id(),
                "contentBlockCount" to message.content().size,
            )
        )
    }

    private fun toMessageParam(message: Conversation.Message): MessageParam? {
        return when (message.role) {
            Conversation.Message.Role.USER -> toUserMessageParam(message)
            Conversation.Message.Role.ASSISTANT -> toAssistantMessageParam(message)
            Conversation.Message.Role.SYSTEM -> null
        }
    }

    private fun toUserMessageParam(message: Conversation.Message): MessageParam? {
        val blocks = mutableListOf<ContentBlockParam>()
        val text = userText(message)
        if (text.isNotBlank()) {
            blocks.add(textBlock(text))
        }

        message.content
            .filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
            .forEach { toolResult ->
                blocks.add(toolResultBlock(toolResult))
            }

        return blocks.takeIf { it.isNotEmpty() }?.let {
            MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(it)
                .build()
        }
    }

    private fun toAssistantMessageParam(message: Conversation.Message): MessageParam? {
        val toolResults = message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
        if (toolResults.isNotEmpty()) {
            return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(toolResults.map(::toolResultBlock))
                .build()
        }

        val blocks = mutableListOf<ContentBlockParam>()

        message.content
            .filterIsInstance<Conversation.Message.ContentItem.Thinking>()
            .mapNotNull(::thinkingBlock)
            .forEach(blocks::add)

        val text = message.content
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .map { it.structured.fullText } +
            message.content
                .filterIsInstance<Conversation.Message.ContentItem.ContextCompactionResult>()
                .map { it.toAnthropicText() }
        val textContent = text.joinToString("\n")
        if (textContent.isNotBlank()) {
            blocks.add(textBlock(textContent))
        }

        message.content
            .filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
            .forEach { toolCall ->
                blocks.add(toolUseBlock(toolCall))
            }

        return blocks.takeIf { it.isNotEmpty() }?.let {
            MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(it)
                .build()
        }
    }

    private fun Conversation.Message.ContentItem.ContextCompactionResult.toAnthropicText(): String =
        when (val payload = payload) {
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary ->
                "Earlier conversation compact:\n${payload.text.trim()}"

            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
                error("Anthropic runtime cannot replay opaque compaction state for provider=${providerScope?.provider}")
        }

    private fun userText(message: Conversation.Message): String {
        val instructionsPrefix = message.instructions
            .joinToString("\n") { it.toXmlLine() }
        val text = message.content
            .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            .joinToString("\n") { it.text }

        return listOf(instructionsPrefix, text)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun textBlock(text: String): ContentBlockParam =
        ContentBlockParam.ofText(
            TextBlockParam.builder()
                .text(text)
                .build()
        )

    private fun thinkingBlock(thinking: Conversation.Message.ContentItem.Thinking): ContentBlockParam? {
        val signature = thinking.signature?.takeIf { it.isNotBlank() } ?: return null
        if (thinking.thinking.isBlank()) {
            return null
        }

        return ContentBlockParam.ofThinking(
            ThinkingBlockParam.builder()
                .thinking(thinking.thinking)
                .signature(signature)
                .build()
        )
    }

    private fun toolUseBlock(toolCall: Conversation.Message.ContentItem.ToolCall): ContentBlockParam =
        ContentBlockParam.ofToolUse(
            ToolUseBlockParam.builder()
                .id(toolCall.id.value)
                .name(toolCall.call.name)
                .input(toolUseInput(toolCall.call.input))
                .build()
        )

    private fun toolResultBlock(toolResult: Conversation.Message.ContentItem.ToolResult): ContentBlockParam =
        ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder()
                .toolUseId(toolResult.toolUseId.value)
                .content(toolResultText(toolResult))
                .isError(toolResult.isError)
                .build()
        )

    private fun toolResultText(toolResult: Conversation.Message.ContentItem.ToolResult): String =
        toolResult.result.joinToString("\n") { data ->
            when (data) {
                is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.content
                is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> "[base64 ${data.mediaType.value}, ${data.data.length} chars]"
                is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url ${data.url}]"
                is Conversation.Message.ContentItem.ToolResult.Data.FileData -> "[file ${data.fileId}]"
            }
        }

    private fun toolUseInput(input: JsonElement): ToolUseBlockParam.Input {
        val builder = ToolUseBlockParam.Input.builder()
        if (input is JsonObject) {
            builder.putAllAdditionalProperties(input.toAnthropicProperties())
        } else {
            builder.putAdditionalProperty("value", input.toAnthropicJsonValue())
        }
        return builder.build()
    }

    private fun applyTools(
        builder: MessageCreateParams.Builder,
        tools: List<AiToolCallback>,
        toolChoice: AiToolChoice,
    ) {
        if (tools.isNotEmpty() && toolChoice !is AiToolChoice.None) {
            builder.tools(tools.map(::toAnthropicTool))
        }

        when (toolChoice) {
            AiToolChoice.Auto -> Unit
            AiToolChoice.None -> builder.toolChoice(ToolChoiceNone.builder().build())
            AiToolChoice.RequiredAny -> builder.toolChoice(ToolChoiceAny.builder().build())
            is AiToolChoice.RequiredTool -> builder.toolToolChoice(toolChoice.name)
        }
    }

    private fun toAnthropicTool(callback: AiToolCallback): ToolUnion {
        val schema = json.parseToJsonElement(callback.definition.inputSchema)
        require(schema is JsonObject) {
            "Tool ${callback.definition.name} input schema must be a JSON object"
        }

        val inputSchema = Tool.InputSchema.builder()
            .putAllAdditionalProperties(schema.toAnthropicProperties())
            .build()

        return ToolUnion.ofTool(
            Tool.builder()
                .name(callback.definition.name)
                .description(callback.definition.description)
                .inputSchema(inputSchema)
                .build()
        )
    }

    private fun applyThinking(
        builder: MessageCreateParams.Builder,
        reasoning: AiReasoningConfig?,
    ) {
        when (reasoning?.mode) {
            null -> Unit
            AiReasoningMode.ADAPTIVE -> builder.thinking(
                ThinkingConfigAdaptive.builder()
                    .display(adaptiveDisplay(reasoning.display))
                    .build()
            )
            AiReasoningMode.TOKEN_BUDGET -> builder.thinking(
                ThinkingConfigEnabled.builder()
                    .budgetTokens((reasoning.budgetTokens ?: DEFAULT_THINKING_BUDGET_TOKENS).toLong())
                    .display(enabledDisplay(reasoning.display))
                    .build()
            )
            AiReasoningMode.DISABLED -> builder.thinking(ThinkingConfigDisabled.builder().build())
        }
    }

    private fun applyOutputConfig(
        builder: MessageCreateParams.Builder,
        options: AiRuntimeOptions,
    ) {
        val outputConfigBuilder = OutputConfig.builder()
        var hasOutputConfig = false

        options.reasoning?.effort?.let { effort ->
            outputConfigBuilder.effort(OutputConfig.Effort.of(effort.name.lowercase()))
            hasOutputConfig = true
        }

        val responseFormat = options.responseFormat
        if (responseFormat is AiResponseFormat.JsonSchema) {
            require(supportsNativeStructuredOutput()) {
                "Connection kind $connectionKind does not support Anthropic native structured output"
            }
            outputConfigBuilder.format(
                JsonOutputFormat.builder()
                    .schema(
                        JsonOutputFormat.Schema.builder()
                            .putAllAdditionalProperties(responseFormat.schema.toAnthropicProperties())
                            .build()
                    )
                    .build()
            )
            hasOutputConfig = true
        }

        if (hasOutputConfig) {
            builder.outputConfig(outputConfigBuilder.build())
        }
    }

    private fun supportsNativeStructuredOutput(): Boolean =
        connectionKind == AiConnection.Kind.ANTHROPIC_API

    private fun adaptiveDisplay(display: AiReasoningDisplay?): ThinkingConfigAdaptive.Display =
        when (display) {
            AiReasoningDisplay.OMITTED -> ThinkingConfigAdaptive.Display.OMITTED
            AiReasoningDisplay.FULL,
            AiReasoningDisplay.SUMMARIZED,
            null -> ThinkingConfigAdaptive.Display.SUMMARIZED
        }

    private fun enabledDisplay(display: AiReasoningDisplay?): ThinkingConfigEnabled.Display =
        when (display) {
            AiReasoningDisplay.OMITTED -> ThinkingConfigEnabled.Display.OMITTED
            AiReasoningDisplay.FULL,
            AiReasoningDisplay.SUMMARIZED,
            null -> ThinkingConfigEnabled.Display.SUMMARIZED
        }

    private fun toContentItems(
        block: com.anthropic.models.messages.ContentBlock,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
    ): List<Conversation.Message.ContentItem> {
        val items = mutableListOf<Conversation.Message.ContentItem>()

        block.thinking().getOrNull()?.let { thinking ->
            items.add(
                Conversation.Message.ContentItem.Thinking(
                    thinking = thinking.thinking(),
                    signature = thinking.signature(),
                    state = Conversation.Message.BlockState.COMPLETE
                )
            )
        }

        block.redactedThinking().getOrNull()?.let { redacted ->
            items.add(
                Conversation.Message.ContentItem.Thinking(
                    thinking = "",
                    signature = redacted.data(),
                    state = Conversation.Message.BlockState.COMPLETE
                )
            )
        }

        block.text().getOrNull()?.let { text ->
            items.add(
                Conversation.Message.ContentItem.AssistantMessage(
                    structured = AssistantResponseParser.parse(text.text(), assistantResponseFormat),
                    state = Conversation.Message.BlockState.COMPLETE
                )
            )
        }

        block.toolUse().getOrNull()?.let { toolUse ->
            items.add(
                Conversation.Message.ContentItem.ToolCall(
                    id = Conversation.Message.ContentItem.ToolCall.Id(toolUse.id()),
                    call = Conversation.Message.ContentItem.ToolCall.Data(
                        name = toolUse.name(),
                        input = toolUse._input().toKotlinxJsonElement()
                    ),
                    state = Conversation.Message.BlockState.COMPLETE
                )
            )
        }

        return items
    }

    private fun toAiUsage(usage: com.anthropic.models.messages.Usage): AiUsage =
        AiUsage(
            promptTokens = usage.inputTokens().toIntClamped(),
            completionTokens = usage.outputTokens().toIntClamped(),
            cacheCreationTokens = usage.cacheCreationInputTokens().orElse(0L).toIntClamped(),
            cacheReadTokens = usage.cacheReadInputTokens().orElse(0L).toIntClamped(),
        )

    private fun JsonObject.toAnthropicProperties(): Map<String, JsonValue> =
        mapValues { (_, value) -> value.toAnthropicJsonValue() }

    private fun JsonElement.toAnthropicJsonValue(): JsonValue =
        JsonValue.from(toJsonCompatibleValue())

    private fun JsonElement.toJsonCompatibleValue(): Any? =
        when (this) {
            JsonNull -> null
            is JsonObject -> mapValues { (_, value) -> value.toJsonCompatibleValue() }
            is JsonArray -> map { it.toJsonCompatibleValue() }
            is JsonPrimitive -> when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                doubleOrNull != null -> doubleOrNull
                else -> content
            }
        }

    private fun JsonValue.toKotlinxJsonElement(): JsonElement =
        accept(
            object : JsonValue.Visitor<JsonElement> {
                override fun visitNull(): JsonElement = JsonNull
                override fun visitMissing(): JsonElement = JsonNull
                override fun visitBoolean(value: Boolean): JsonElement = JsonPrimitive(value)
                override fun visitNumber(value: Number): JsonElement =
                    when (value) {
                        is Byte, is Short, is Int, is Long -> JsonPrimitive(value.toLong())
                        else -> JsonPrimitive(value.toDouble())
                    }

                override fun visitString(value: String): JsonElement = JsonPrimitive(value)
                override fun visitArray(values: List<JsonValue>): JsonElement =
                    JsonArray(values.map { it.toKotlinxJsonElement() })

                override fun visitObject(values: Map<String, JsonValue>): JsonElement =
                    JsonObject(values.mapValues { (_, value) -> value.toKotlinxJsonElement() })
            }
        )

    private fun Long.toIntClamped(): Int =
        coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    companion object {
        private const val DEFAULT_MAX_TOKENS = 8192
        private const val DEFAULT_THINKING_BUDGET_TOKENS = 16_000
    }
}

private fun AiResponseFormat.logName(): String =
    when (this) {
        AiResponseFormat.Text -> "text"
        is AiResponseFormat.JsonSchema -> "json_schema:$name"
    }
