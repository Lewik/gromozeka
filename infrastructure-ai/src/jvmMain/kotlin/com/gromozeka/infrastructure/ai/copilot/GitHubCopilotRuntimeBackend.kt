package com.gromozeka.infrastructure.ai.copilot

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.copilot.CopilotSession
import com.github.copilot.SystemMessageMode
import com.github.copilot.generated.AssistantMessageEvent
import com.github.copilot.generated.AssistantMessageToolRequest
import com.github.copilot.generated.AssistantUsageEvent
import com.github.copilot.rpc.BlobAttachment
import com.github.copilot.rpc.MessageOptions
import com.github.copilot.rpc.PermissionRequestResult
import com.github.copilot.rpc.SessionConfig
import com.github.copilot.rpc.SystemMessageConfig
import com.github.copilot.rpc.ToolDefinition
import com.github.copilot.rpc.ToolResultObject
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiContextUsage
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.repository.AiUserCredentialRepository
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.infrastructure.ai.parsers.AssistantResponseParser
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

@Service
internal class GitHubCopilotRuntimeBackend(
    private val clientPool: GitHubCopilotClientPool,
    credentialRepositories: List<AiUserCredentialRepository>,
) : AiRuntimeBackend {
    private val credentialRepository = credentialRepositories.singleOrNull()

    init {
        require(credentialRepositories.size <= 1) {
            "Multiple GitHub Copilot credential repositories are configured"
        }
    }

    override fun supports(connectionKind: AiConnection.Kind): Boolean =
        connectionKind == AiConnection.Kind.GITHUB_COPILOT

    override fun capabilities(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
    ): AiRuntimeCapabilities = AiRuntimeCapabilities()

    override fun createRuntime(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
        workspaceRootPath: String?,
    ): AiRuntime {
        require(connection is AiConnection.GitHubCopilot) {
            "GitHub Copilot runtime requires github_copilot connection, got ${connection::class.simpleName}"
        }
        return GitHubCopilotRuntime(
            connection = connection,
            modelConfiguration = modelConfiguration,
            clientPool = clientPool,
            credentialRepository = credentialRepository,
        )
    }
}

internal class GitHubCopilotRuntime(
    private val connection: AiConnection.GitHubCopilot,
    private val modelConfiguration: AiModelConfiguration,
    private val clientPool: GitHubCopilotClientPool,
    private val credentialRepository: AiUserCredentialRepository?,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : AiRuntime {
    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        require(request.messages.isNotEmpty()) { "GitHub Copilot request must contain at least one message" }
        validateOptions(request)
        val toolPlan = buildToolPlan(request)
        val token = resolveToken(request)
        val handle = clientPool.acquire(connection)
        val sessionId = "gromozeka-${UUID.randomUUID()}"
        var session: CopilotSession? = null
        var usageSubscription: Closeable? = null
        var callFailure: Throwable? = null

        try {
            val usageEvents = CopyOnWriteArrayList<AssistantUsageEvent.AssistantUsageEventData>()
            session = handle.client.createSession(
                buildSessionConfig(request, toolPlan, token, handle, sessionId)
            ).awaitCancellable()
            usageSubscription = session.on(AssistantUsageEvent::class.java) { event ->
                event.data?.let(usageEvents::add)
            }
            val mappedRequest = GitHubCopilotRequestMapper().map(request.messages)
            val response = session.sendAndWait(
                MessageOptions()
                    .setPrompt(mappedRequest.prompt)
                    .setAttachments(mappedRequest.attachments),
                connection.requestTimeoutSeconds.toLong() * 1_000L,
            ).awaitCancellable(onCancellation = { session.abort() })
                ?: error("GitHub Copilot completed without an assistant message")
            return response.toRuntimeResponse(request, toolPlan, usageEvents, sessionId)
        } catch (error: Throwable) {
            callFailure = error
            if (error !is CancellationException) {
                runCatching { clientPool.discardIfUnhealthy(connection, handle) }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
            }
            throw error
        } finally {
            val cleanupFailure = withContext(NonCancellable) {
                cleanupSession(handle, session, usageSubscription, sessionId)
            }
            if (cleanupFailure != null) {
                if (callFailure == null) {
                    throw cleanupFailure
                }
                callFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
        emit(call(request))
    }

    private suspend fun resolveToken(request: AiRuntimeRequest): String? =
        when (connection.authMode) {
            AiConnection.GitHubCopilotAuthMode.SERVER_CLI -> null
            AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN -> {
                val repository = requireNotNull(credentialRepository) {
                    "GitHub Copilot per-user authentication is unavailable on this execution target"
                }
                val userId = request.options.toolContext[TOOL_CONTEXT_USER_ID]
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(User::Id)
                    ?: error("GitHub Copilot per-user authentication requires the runtime user id")
                repository.find(userId, connection.id)?.secret
                    ?: error("GitHub Copilot is not authorized for the current user")
            }
        }

    private fun validateOptions(request: AiRuntimeRequest) {
        require(request.options.maxOutputTokens == null) {
            "GitHub Copilot SDK does not expose a per-request maximum output token control"
        }
        require(request.options.autoCompactionThresholdTokens == null) {
            "GitHub Copilot runtime does not support Gromozeka auto-compaction"
        }
        val reasoning = request.options.reasoning ?: return
        require(reasoning.mode != AiReasoningMode.TOKEN_BUDGET && reasoning.budgetTokens == null) {
            "GitHub Copilot does not expose a fixed reasoning token budget"
        }
        require(reasoning.mode != AiReasoningMode.DISABLED || reasoning.effort == null) {
            "GitHub Copilot disabled reasoning cannot be combined with reasoning effort"
        }
    }

    private fun buildToolPlan(request: AiRuntimeRequest): GitHubCopilotToolPlan {
        require(request.tools.none { it.definition.name == FINAL_ANSWER_TOOL }) {
            "GitHub Copilot tool name is reserved: $FINAL_ANSWER_TOOL"
        }
        val externalTools = when (val choice = request.options.toolChoice) {
            AiToolChoice.Auto -> request.tools
            AiToolChoice.None -> emptyList()
            AiToolChoice.RequiredAny -> {
                require(request.tools.isNotEmpty()) {
                    "GitHub Copilot cannot require a tool when no tools are available"
                }
                request.tools
            }
            is AiToolChoice.RequiredTool -> listOf(
                request.tools.firstOrNull { it.definition.name == choice.name }
                    ?: error("GitHub Copilot required tool is not available: ${choice.name}")
            )
        }
        val allowsFinalAnswer = request.options.toolChoice is AiToolChoice.Auto ||
            request.options.toolChoice is AiToolChoice.None
        val definitions = externalTools.map(::externalToolDefinition) +
            if (allowsFinalAnswer) listOf(finalAnswerToolDefinition(request.options.responseFormat)) else emptyList()
        return GitHubCopilotToolPlan(
            definitions = definitions,
            externalToolNames = externalTools.map { it.definition.name }.toSet(),
            allowsFinalAnswer = allowsFinalAnswer,
            requiredToolName = (request.options.toolChoice as? AiToolChoice.RequiredTool)?.name,
        )
    }

    private fun externalToolDefinition(tool: AiToolCallback): ToolDefinition =
        terminalTool(
            name = tool.definition.name,
            description = tool.definition.description,
            schema = objectMapper.readValue(tool.definition.inputSchema, MAP_TYPE),
        )

    private fun finalAnswerToolDefinition(responseFormat: AiResponseFormat): ToolDefinition {
        val answerSchema: Any = when (responseFormat) {
            AiResponseFormat.Text -> mapOf("type" to "string")
            is AiResponseFormat.JsonSchema -> objectMapper.readValue(responseFormat.schema.toString(), MAP_TYPE)
        }
        return terminalTool(
            name = FINAL_ANSWER_TOOL,
            description = "Finish this Gromozeka model step with the exact assistant response payload.",
            schema = mapOf(
                "type" to "object",
                "properties" to mapOf("answer" to answerSchema),
                "required" to listOf("answer"),
                "additionalProperties" to false,
            ),
        )
    }

    private fun terminalTool(
        name: String,
        description: String,
        schema: Map<String, Any>,
    ): ToolDefinition = ToolDefinition.create(
        name,
        description,
        schema,
    ) {
        CompletableFuture.completedFuture<Any>(ToolResultObject.success("Accepted by Gromozeka"))
    }.isTerminal(true).skipPermission(true)

    private fun buildSessionConfig(
        request: AiRuntimeRequest,
        toolPlan: GitHubCopilotToolPlan,
        token: String?,
        handle: GitHubCopilotClientHandle,
        sessionId: String,
    ): SessionConfig {
        val config = SessionConfig()
            .setSessionId(sessionId)
            .setClientName("gromozeka")
            .setModel(modelConfiguration.providerModelId)
            .setTools(toolPlan.definitions)
            .setAvailableTools(toolPlan.definitions.map { it.name() })
            .setSystemMessage(
                SystemMessageConfig()
                    .setMode(SystemMessageMode.REPLACE)
                    .setContent(systemPrompt(request, toolPlan))
            )
            .setWorkingDirectory(handle.workingDirectory.toString())
            .setEnableSessionTelemetry(false)
            .setEnableCitations(false)
            .setSkipCustomInstructions(true)
            .setEnableConfigDiscovery(false)
            .setSkipEmbeddingRetrieval(true)
            .setEnableOnDemandInstructionDiscovery(false)
            .setEnableFileHooks(false)
            .setEnableHostGitOperations(false)
            .setEnableSessionStore(false)
            .setEnableSkills(false)
            .setEnableMcpApps(false)
            .setOnPermissionRequest { _, _ ->
                CompletableFuture.completedFuture(
                    PermissionRequestResult.reject("Gromozeka permits only explicitly supplied terminal tools")
                )
            }

        request.options.reasoning?.toEffort()?.let(config::setReasoningEffort)
        request.options.reasoning?.toSummary()?.let(config::setReasoningSummary)
        token?.let(config::setGitHubToken)
        return config
    }

    private fun systemPrompt(
        request: AiRuntimeRequest,
        toolPlan: GitHubCopilotToolPlan,
    ): String = buildString {
        request.systemPrompts.filter(String::isNotBlank).forEach { prompt ->
            appendLine(prompt)
            appendLine()
        }
        appendLine("<gromozeka_copilot_runtime>")
        appendLine("This is exactly one finite Gromozeka model step.")
        appendLine("Use only the tools explicitly available in this session.")
        appendLine("Every available tool is terminal: request the tool once and stop immediately.")
        appendLine("Never perform tool work yourself and never invent a tool result.")
        if (toolPlan.allowsFinalAnswer) {
            appendLine("When no external tool is needed, call $FINAL_ANSWER_TOOL with the exact final response payload.")
        } else {
            appendLine("A final answer is forbidden in this step; call one available external tool.")
        }
        toolPlan.requiredToolName?.let { name ->
            appendLine("You must call the required external tool $name.")
        }
        appendLine("Do not emit ordinary assistant text before or instead of the terminal tool call.")
        appendLine("</gromozeka_copilot_runtime>")
    }.trim()

    private fun AiReasoningConfig.toEffort(): String? =
        if (mode == AiReasoningMode.DISABLED) "none" else effort?.name?.lowercase()

    private fun AiReasoningConfig.toSummary(): String? =
        when {
            mode == AiReasoningMode.DISABLED -> "none"
            display == AiReasoningDisplay.OMITTED -> "none"
            display == AiReasoningDisplay.SUMMARIZED -> "concise"
            display == AiReasoningDisplay.FULL -> "detailed"
            else -> null
        }

    private fun AssistantMessageEvent.toRuntimeResponse(
        request: AiRuntimeRequest,
        toolPlan: GitHubCopilotToolPlan,
        usageEvents: List<AssistantUsageEvent.AssistantUsageEventData>,
        sessionId: String,
    ): AiRuntimeResponse {
        val data = data ?: error("GitHub Copilot assistant message missed data")
        val reportedModels = buildSet {
            data.model()?.let(::add)
            usageEvents.mapNotNullTo(this) { it.model() }
        }
        require(reportedModels.all { it == modelConfiguration.providerModelId }) {
            "GitHub Copilot returned ${reportedModels.joinToString()} instead of requested model " +
                modelConfiguration.providerModelId
        }
        require(data.content().isNullOrBlank()) {
            "GitHub Copilot returned ordinary assistant text instead of the terminal response protocol"
        }
        val toolRequests = data.toolRequests().orEmpty()
        require(toolRequests.isNotEmpty()) {
            "GitHub Copilot returned no terminal tool request"
        }
        val finalAnswers = toolRequests.filter { it.name() == FINAL_ANSWER_TOOL }
        val externalRequests = toolRequests.filterNot { it.name() == FINAL_ANSWER_TOOL }
        require(finalAnswers.size <= 1) { "GitHub Copilot returned multiple final answers" }
        require(finalAnswers.isEmpty() || externalRequests.isEmpty()) {
            "GitHub Copilot mixed a final answer with external tool calls"
        }

        val thinking = data.reasoningText()
            ?.takeIf { it.isNotBlank() && request.options.reasoning?.display != AiReasoningDisplay.OMITTED }
            ?.takeIf { request.options.reasoning?.mode != AiReasoningMode.DISABLED }
            ?.let { Conversation.Message.ContentItem.Thinking(it) }
        val metadata = providerMetadata(data, sessionId)
        val assistantMessage = if (finalAnswers.isNotEmpty()) {
            require(toolPlan.allowsFinalAnswer) {
                "GitHub Copilot returned a final answer while an external tool was required"
            }
            val answer = finalAnswers.single().answerText()
            AiAssistantMessage(
                content = listOfNotNull(thinking) + Conversation.Message.ContentItem.AssistantMessage(
                    structured = AssistantResponseParser.parse(answer, request.options.assistantResponseFormat)
                ),
                metadata = metadata + ("terminalKind" to "final_answer"),
            )
        } else {
            externalRequests.forEach { toolRequest ->
                require(toolRequest.name() in toolPlan.externalToolNames) {
                    "GitHub Copilot requested unavailable external tool: ${toolRequest.name()}"
                }
                toolPlan.requiredToolName?.let { required ->
                    require(toolRequest.name() == required) {
                        "GitHub Copilot requested ${toolRequest.name()} while runtime required $required"
                    }
                }
            }
            AiAssistantMessage(
                content = listOfNotNull(thinking) + externalRequests.map(::toToolCall),
                metadata = metadata + ("terminalKind" to "tool_call"),
            )
        }

        return AiRuntimeResponse(
            messages = listOf(assistantMessage),
            usage = usageEvents.toUsage(),
            contextUsage = usageEvents.toContextUsage(),
            finishReason = usageEvents.mapNotNull { it.finishReason() }.lastOrNull(),
            providerMetadata = metadata,
        )
    }

    private fun AssistantMessageToolRequest.answerText(): String {
        val root = argumentsJson().jsonObject
        val answer = root["answer"] ?: error("GitHub Copilot final answer missed answer")
        return if (answer is JsonPrimitive && answer.isString) answer.content else answer.toString()
    }

    private fun toToolCall(request: AssistantMessageToolRequest): Conversation.Message.ContentItem.ToolCall =
        Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id(
                request.toolCallId()?.takeIf(String::isNotBlank) ?: "github-copilot:${UUID.randomUUID()}"
            ),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = request.name(),
                input = request.argumentsJson(),
            ),
        )

    private fun AssistantMessageToolRequest.argumentsJson(): JsonElement =
        when (val value = arguments()) {
            null -> JsonObject(emptyMap())
            is String -> runCatching { JSON.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
            else -> JSON.parseToJsonElement(objectMapper.writeValueAsString(value))
        }

    private fun providerMetadata(
        data: AssistantMessageEvent.AssistantMessageEventData,
        sessionId: String,
    ): Map<String, Any?> = mapOf(
        "provider" to AiConnection.Kind.GITHUB_COPILOT.name,
        "model" to modelConfiguration.providerModelId,
        "reportedModel" to data.model(),
        "connectionId" to connection.id.value,
        "authMode" to connection.authMode.name,
        "sessionId" to sessionId,
        "messageId" to data.messageId(),
        "interactionId" to data.interactionId(),
        "requestId" to data.requestId(),
        "clientRequestId" to data.clientRequestId(),
        "serviceRequestId" to data.serviceRequestId(),
        "apiCallId" to data.apiCallId(),
    )

    private fun List<AssistantUsageEvent.AssistantUsageEventData>.toUsage(): AiUsage? {
        if (isEmpty()) return null
        val input = sumOf { it.inputTokens() ?: 0L }
        val output = sumOf { it.outputTokens() ?: 0L }
        val cacheRead = sumOf { it.cacheReadTokens() ?: 0L }
        val cacheWrite = sumOf { it.cacheWriteTokens() ?: 0L }
        val reasoning = sumOf { it.reasoningTokens() ?: 0L }
        return AiUsage(
            promptTokens = (input - cacheRead - cacheWrite).coerceAtLeast(0).toIntTokens(),
            completionTokens = (output - reasoning).coerceAtLeast(0).toIntTokens(),
            thinkingTokens = reasoning.toIntTokens(),
            cacheCreationTokens = cacheWrite.toIntTokens(),
            cacheReadTokens = cacheRead.toIntTokens(),
        )
    }

    private fun List<AssistantUsageEvent.AssistantUsageEventData>.toContextUsage(): AiContextUsage? {
        val event = lastOrNull { usage ->
            usage.initiator() == null && usage.interactionType() !in NON_PRIMARY_INTERACTION_TYPES
        } ?: return null
        return AiContextUsage((event.inputTokens() ?: 0L).toIntTokens())
    }

    private fun Long.toIntTokens(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    private suspend fun cleanupSession(
        handle: GitHubCopilotClientHandle,
        session: CopilotSession?,
        usageSubscription: Closeable?,
        sessionId: String,
    ): Throwable? {
        var cleanupFailure: Throwable? = null
        fun collect(error: Throwable) {
            if (cleanupFailure == null) cleanupFailure = error else cleanupFailure?.addSuppressed(error)
        }
        runCatching { usageSubscription?.close() }.exceptionOrNull()?.let(::collect)
        runCatching { session?.close() }.exceptionOrNull()?.let(::collect)
        runCatching {
            handle.client.deleteSession(sessionId).awaitCancellable(cancelFutureOnCancellation = false)
        }.exceptionOrNull()?.let(::collect)
        return cleanupFailure
    }

    private companion object {
        const val FINAL_ANSWER_TOOL = "gromozeka_final_answer"
        val NON_PRIMARY_INTERACTION_TYPES = setOf(
            "conversation-subagent",
            "conversation-sampling",
            "conversation-background",
            "conversation-compaction",
        )
        val JSON = Json { isLenient = true; ignoreUnknownKeys = true }
        val MAP_TYPE = object : TypeReference<Map<String, Any>>() {}
    }
}

private data class GitHubCopilotToolPlan(
    val definitions: List<ToolDefinition>,
    val externalToolNames: Set<String>,
    val allowsFinalAnswer: Boolean,
    val requiredToolName: String?,
)

private data class GitHubCopilotMappedRequest(
    val prompt: String,
    val attachments: List<BlobAttachment>,
)

private class GitHubCopilotRequestMapper {
    private val attachments = mutableListOf<BlobAttachment>()

    fun map(messages: List<Conversation.Message>): GitHubCopilotMappedRequest =
        GitHubCopilotMappedRequest(
            prompt = buildString {
                appendLine("Gromozeka conversation transcript:")
                appendLine()
                append(messages.joinToString("\n\n", transform = ::messageToTranscript))
            },
            attachments = attachments.toList(),
        )

    private fun messageToTranscript(message: Conversation.Message): String = buildString {
        append("<message role=\"")
        append(message.role.name.lowercase())
        append("\" id=\"")
        append(xmlEscape(message.id.value))
        appendLine("\">")
        message.instructions
            .joinToString("\n") { it.toXmlLine() }
            .takeIf(String::isNotBlank)
            ?.let(::appendLine)
        message.content.forEach { item -> appendLine(contentToTranscript(item)) }
        append("</message>")
    }

    private fun contentToTranscript(item: Conversation.Message.ContentItem): String =
        when (item) {
            is Conversation.Message.ContentItem.UserMessage -> xmlBlock("text", item.text)
            is Conversation.Message.ContentItem.AssistantMessage -> xmlBlock("text", item.structured.fullText)
            is Conversation.Message.ContentItem.Thinking -> xmlBlock("thinking", item.thinking)
            is Conversation.Message.ContentItem.System -> xmlBlock("system", item.content)
            is Conversation.Message.ContentItem.ToolCall ->
                "<tool_call id=\"${xmlEscape(item.id.value)}\" name=\"${xmlEscape(item.call.name)}\">" +
                    xmlEscape(item.call.input.toString()) +
                    "</tool_call>"
            is Conversation.Message.ContentItem.ToolResult ->
                "<tool_result tool_call_id=\"${xmlEscape(item.toolUseId.value)}\" " +
                    "name=\"${xmlEscape(item.toolName)}\" is_error=\"${item.isError}\">" +
                    xmlEscape(item.result.joinToString("\n", transform = ::toolResultToTranscript)) +
                    "</tool_result>"
            is Conversation.Message.ContentItem.ImageItem -> addImage(item.source).marker("image")
            is Conversation.Message.ContentItem.DocumentItem -> addDocument(item.source).marker("document")
            is Conversation.Message.ContentItem.ArtifactItem ->
                error("GitHub Copilot received an unmaterialized artifact: ${item.artifact.id.value}")
            is Conversation.Message.ContentItem.ContextCompactionResult -> when (val payload = item.payload) {
                is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary ->
                    xmlBlock("context_compaction_result", payload.text)
                is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
                    error("GitHub Copilot cannot replay opaque provider compaction state")
            }
            is Conversation.Message.ContentItem.UnknownJson -> xmlBlock("json", item.json.toString())
        }

    private fun toolResultToTranscript(data: Conversation.Message.ContentItem.ToolResult.Data): String =
        when (data) {
            is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.content
            is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                addBlob(data.data, data.mediaType.value, data.fileName ?: "tool-output.bin").marker("binary tool output")
            is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url ${data.url}]"
            is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData ->
                error("GitHub Copilot received an unmaterialized tool artifact: ${data.artifact.id.value}")
        }

    private fun addImage(source: Conversation.Message.ImageSource): AttachmentReference =
        when (source) {
            is Conversation.Message.ImageSource.Base64ImageSource ->
                addBlob(source.data, source.mediaType, "image-${attachments.size + 1}")
            is Conversation.Message.ImageSource.UrlImageSource ->
                error("GitHub Copilot accepts only materialized base64 image inputs")
            is Conversation.Message.ImageSource.FileImageSource ->
                error("GitHub Copilot does not accept a provider-independent image file id")
        }

    private fun addDocument(source: Conversation.Message.DocumentSource): AttachmentReference =
        when (source) {
            is Conversation.Message.DocumentSource.Base64DocumentSource ->
                addBlob(source.data, source.mediaType, source.fileName)
        }

    private fun addBlob(data: String, mediaType: String, displayName: String): AttachmentReference {
        attachments += BlobAttachment()
            .setData(data)
            .setMimeType(mediaType.substringBefore(';').trim().lowercase())
            .setDisplayName(displayName)
        return AttachmentReference(attachments.size)
    }

    private fun xmlBlock(name: String, content: String): String = "<$name>${xmlEscape(content)}</$name>"

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private data class AttachmentReference(val index: Int) {
        fun marker(label: String): String = "[$label attachment_index=$index]"
    }
}
