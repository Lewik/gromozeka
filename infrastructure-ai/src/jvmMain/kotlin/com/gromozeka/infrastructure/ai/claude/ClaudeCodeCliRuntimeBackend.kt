package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.model.ai.ClaudeCodeSessionState
import com.gromozeka.domain.repository.ClaudeCodeSessionStateRepository
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.infrastructure.ai.parsers.AssistantResponseParser
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.springframework.stereotype.Service
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Service
internal class ClaudeCodeCliRuntimeBackend(
    private val sessionStateRepository: ClaudeCodeSessionStateRepository,
    private val executor: ClaudeCodeCliExecutor,
) : AiRuntimeBackend {
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    override fun supports(connectionKind: AiConnection.Kind): Boolean =
        connectionKind == AiConnection.Kind.CLAUDE_CODE

    override fun capabilities(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
    ): AiRuntimeCapabilities = AiRuntimeCapabilities(providerManagedAutoCompaction = true)

    override fun createRuntime(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
        workspaceRootPath: String?
    ): AiRuntime {
        require(connection is AiConnection.ClaudeCode) {
            "Claude Code runtime requires claude_code connection, got ${connection::class.simpleName}"
        }

        return ClaudeCodeCliRuntime(
            executor = executor,
            connectionId = connection.id.value,
            executablePath = connection.executablePath,
            maxCachedProcesses = connection.maxCachedProcesses,
            processIdleTtlMinutes = connection.processIdleTtlMinutes,
            modelConfigurationId = modelConfiguration.id.value,
            modelName = modelConfiguration.providerModelId,
            workspaceDirectory = resolveWorkspaceDirectory(workspaceRootPath),
            sessionStateRepository = sessionStateRepository,
            sessionLocks = sessionLocks,
        )
    }

    private fun resolveWorkspaceDirectory(workspaceRootPath: String?): File? {
        val value = workspaceRootPath?.takeIf { it.isNotBlank() } ?: return null
        val directory = File(value).toPath().toAbsolutePath().normalize().toFile()
        require(directory.isDirectory) { "Claude Code workspace root must be an existing directory: $value" }
        return directory
    }
}

internal class ClaudeCodeCliRuntime(
    private val executor: ClaudeCodeCliExecutor,
    private val connectionId: String,
    private val executablePath: String = "claude",
    private val maxCachedProcesses: Int = AiConnection.ClaudeCode.DEFAULT_MAX_CACHED_PROCESSES,
    private val processIdleTtlMinutes: Int = AiConnection.ClaudeCode.DEFAULT_PROCESS_IDLE_TTL_MINUTES,
    private val modelConfigurationId: String,
    private val modelName: String,
    private val workspaceDirectory: File?,
    private val sessionStateRepository: ClaudeCodeSessionStateRepository,
    private val sessionLocks: ConcurrentHashMap<String, Mutex>,
) : AiRuntime {
    private val log = KLoggers.logger(this)
    override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities(
        providerManagedAutoCompaction = true,
    )

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        require(request.messages.isNotEmpty()) { "Claude Code CLI request must contain at least one message" }
        validateToolChoice(request.tools, request.options.toolChoice)
        validateReasoning(request.options.reasoning)

        val sessionStateKey = sessionStateKey(request)
        return if (sessionStateKey == null) {
            callLocked(request, sessionStateKey = null)
        } else {
            sessionLocks.computeIfAbsent(sessionStateKey.lockKey()) { Mutex() }.withLock {
                callLocked(request, sessionStateKey)
            }
        }
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow {
        emit(call(request))
    }

    private suspend fun callLocked(
        request: AiRuntimeRequest,
        sessionStateKey: ClaudeCodeSessionState.Key?,
    ): AiRuntimeResponse {
        val toolProtocol = request.toolProtocol()
        val sessionPlan = planSession(sessionStateKey, request.messages)
        val systemPrompt = buildSystemPrompt(request, toolProtocol)
        val userInput = buildUserInput(sessionPlan, toolProtocol)
        val schema = toolProtocol?.schema ?: (request.options.responseFormat as? AiResponseFormat.JsonSchema)?.schema

        log.info {
            "Calling Claude Code CLI runtime: model=$modelName messages=${request.messages.size} " +
                "sentMessages=${sessionPlan.messagesToSend.size} resumed=${sessionPlan.resumeSessionId != null} " +
                "tools=${request.tools.size} wrapper=${toolProtocol != null}"
        }

        val command = ClaudeCodeCommand(
            connectionId = connectionId,
            executablePath = executablePath,
            cacheKey = sessionStateKey?.lockKey(),
            maxCachedProcesses = maxCachedProcesses,
            processIdleTtlMinutes = processIdleTtlMinutes,
            modelName = modelName,
            workspaceDirectory = workspaceDirectory,
            systemPrompt = systemPrompt,
            userPrompt = userInput.prompt,
            userContentBlocks = userInput.contentBlocks,
            jsonSchema = schema,
            effort = request.options.reasoning?.effort,
            reasoningMode = request.options.reasoning?.mode,
            resumeSessionId = sessionPlan.resumeSessionId,
            noSessionPersistence = sessionStateKey == null,
        )

        val cliResponse = executor.execute(command)
        val runtimeResponse = toRuntimeResponse(cliResponse, request, toolProtocol, sessionPlan.resumeSessionId != null)

        if (sessionStateKey != null && cliResponse.sessionId != null) {
            saveSessionState(sessionStateKey, cliResponse.sessionId, request.messages, runtimeResponse.messages)
        }

        return runtimeResponse
    }

    private fun validateToolChoice(
        tools: List<AiToolCallback>,
        toolChoice: AiToolChoice,
    ) {
        when (toolChoice) {
            AiToolChoice.Auto,
            AiToolChoice.None -> Unit

            AiToolChoice.RequiredAny -> require(tools.isNotEmpty()) {
                "Claude Code runtime cannot require a tool when no tools are available"
            }

            is AiToolChoice.RequiredTool -> require(tools.any { it.definition.name == toolChoice.name }) {
                "Claude Code runtime required tool is not available: ${toolChoice.name}"
            }
        }
    }

    private fun validateReasoning(reasoning: AiReasoningConfig?) {
        if (reasoning == null) return

        require(reasoning.mode != AiReasoningMode.TOKEN_BUDGET && reasoning.budgetTokens == null) {
            "Claude Code does not support fixed thinking token budgets; use adaptive thinking and effort"
        }
        require(reasoning.display != AiReasoningDisplay.FULL) {
            "Claude Code exposes provider-generated thinking summaries, not full chain of thought"
        }
        require(reasoning.mode != AiReasoningMode.DISABLED || reasoning.display == null) {
            "Claude Code thinking display must be unset when thinking is disabled"
        }
        require(
            reasoning.mode != AiReasoningMode.DISABLED ||
                reasoning.effort !in setOf(AiReasoningEffort.XHIGH, AiReasoningEffort.MAX)
        ) {
            "Claude Code cannot combine disabled thinking with xhigh or maximum effort"
        }
    }

    private fun sessionStateKey(request: AiRuntimeRequest): ClaudeCodeSessionState.Key? {
        val conversationId = request.options.toolContext["conversationId"].contextString()
            ?.takeIf { it.isNotBlank() }
            ?: request.messages.lastOrNull()?.conversationId?.value
            ?: return null
        val threadId = request.options.toolContext["threadId"].contextString()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val projectId = request.options.toolContext["projectId"].contextString()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val workspaceRootPath = workspaceDirectory?.canonicalFile?.absolutePath ?: "no-workspace"

        return ClaudeCodeSessionState.Key(
            conversationId = Conversation.Id(conversationId),
            threadId = Conversation.Thread.Id(threadId),
            projectId = com.gromozeka.domain.model.Project.Id(projectId),
            workspaceRootPathSnapshot = workspaceRootPath,
            workspaceRootPathFingerprint = sha256(workspaceRootPath),
            connectionId = AiConnection.Id(connectionId),
            modelConfigurationId = AiModelConfiguration.Id(modelConfigurationId),
            modelName = modelName,
        )
    }

    private suspend fun planSession(
        sessionKey: ClaudeCodeSessionState.Key?,
        messages: List<Conversation.Message>,
    ): ClaudeCodeSessionPlan {
        val state = sessionKey?.let { sessionStateRepository.find(it) }
        if (state == null) {
            return ClaudeCodeSessionPlan(messagesToSend = messages, resumeSessionId = null)
        }

        val coveredIds = messages.take(state.coveredMessageIds.size).map { it.id }
        if (coveredIds != state.coveredMessageIds) {
            sessionStateRepository.delete(sessionKey)
            return ClaudeCodeSessionPlan(messagesToSend = messages, resumeSessionId = null)
        }

        val generatedTailOffset = state.coveredMessageIds.size
        val generatedTailSize = state.coveredGeneratedAssistantSignatures.size
        val generatedTailSignatures = messages
            .drop(generatedTailOffset)
            .take(generatedTailSize)
            .map(::generatedAssistantMessageSignature)
        if (generatedTailSignatures != state.coveredGeneratedAssistantSignatures) {
            sessionStateRepository.delete(sessionKey)
            return ClaudeCodeSessionPlan(messagesToSend = messages, resumeSessionId = null)
        }

        val knownFingerprint = transcriptFingerprint(
            inputMessages = messages.take(state.coveredMessageIds.size),
            generatedAssistantSignatures = generatedTailSignatures,
        )
        if (knownFingerprint != state.coveredTranscriptFingerprint) {
            sessionStateRepository.delete(sessionKey)
            return ClaudeCodeSessionPlan(messagesToSend = messages, resumeSessionId = null)
        }

        val deltaMessages = messages.drop(generatedTailOffset + generatedTailSize)
        return if (deltaMessages.isEmpty()) {
            ClaudeCodeSessionPlan(messagesToSend = messages, resumeSessionId = null)
        } else {
            ClaudeCodeSessionPlan(messagesToSend = deltaMessages, resumeSessionId = state.claudeSessionId)
        }
    }

    private suspend fun saveSessionState(
        key: ClaudeCodeSessionState.Key,
        claudeSessionId: String,
        inputMessages: List<Conversation.Message>,
        generatedAssistantMessages: List<AiAssistantMessage>,
    ) {
        val now = Clock.System.now()
        val existing = sessionStateRepository.find(key)
        val generatedAssistantSignatures = generatedAssistantMessages.map(::assistantMessageSignature)
        sessionStateRepository.save(
            ClaudeCodeSessionState(
                key = key,
                claudeSessionId = claudeSessionId,
                coveredMessageIds = inputMessages.map { it.id },
                coveredGeneratedAssistantSignatures = generatedAssistantSignatures,
                coveredTranscriptFingerprint = transcriptFingerprint(
                    inputMessages = inputMessages,
                    generatedAssistantSignatures = generatedAssistantSignatures,
                ),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lastUsedAt = now,
            )
        )
    }

    private fun AiRuntimeRequest.toolProtocol(): ClaudeCodeToolProtocol? {
        if (tools.isEmpty() || options.toolChoice is AiToolChoice.None) return null
        return ClaudeCodeToolProtocol(
            tools = tools.sortedBy { it.definition.name },
            toolChoice = options.toolChoice,
            finalAnswerSchema = finalAnswerSchema(options.responseFormat),
        )
    }

    private fun finalAnswerSchema(responseFormat: AiResponseFormat): JsonElement =
        when (responseFormat) {
            AiResponseFormat.Text -> JsonObject(mapOf("type" to JsonPrimitive("string")))
            is AiResponseFormat.JsonSchema -> responseFormat.schema
        }

    private fun buildSystemPrompt(
        request: AiRuntimeRequest,
        toolProtocol: ClaudeCodeToolProtocol?,
    ): String {
        val base = request.systemPrompts
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val protocol = toolProtocol?.instructions().orEmpty()
        return listOf(base, protocol)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private fun buildUserInput(
        plan: ClaudeCodeSessionPlan,
        toolProtocol: ClaudeCodeToolProtocol?,
    ): ClaudeCodeUserInput {
        val header = if (plan.resumeSessionId == null) {
            "Gromozeka conversation transcript:"
        } else {
            "New Gromozeka messages since the previous Claude Code session turn:"
        }
        val attachments = ClaudeCodeAttachmentCollector()
        val prompt = listOf(
            "$header\n\n${messagesToTranscript(plan.messagesToSend, attachments)}",
            toolProtocol?.runtimeReminder(),
        ).filterNotNull().joinToString("\n\n")
        return ClaudeCodeUserInput(prompt, attachments.contentBlocks)
    }

    private fun toRuntimeResponse(
        cliResponse: ClaudeCodeCliResponse,
        request: AiRuntimeRequest,
        toolProtocol: ClaudeCodeToolProtocol?,
        resumed: Boolean,
    ): AiRuntimeResponse {
        val thinking = thinkingContent(cliResponse, request.options.reasoning)
        val assistantMessage = if (toolProtocol == null) {
            finalAssistantMessage(
                text = responseText(cliResponse, request.options.responseFormat),
                assistantResponseFormat = request.options.assistantResponseFormat,
                metadata = assistantMetadata(cliResponse, wrapper = false, resumed = resumed),
                thinking = thinking,
            )
        } else {
            toolProtocol.toAssistantMessage(
                cliResponse = cliResponse,
                options = request.options,
                metadata = assistantMetadata(cliResponse, wrapper = true, resumed = resumed),
                thinking = thinking,
            )
        }

        val compactionMessages = cliResponse.compactionBoundaries.map { boundary ->
            AiAssistantMessage(
                content = listOf(
                    Conversation.Message.ContentItem.ContextCompactionResult(
                        payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState(
                            state = boundary,
                        ),
                        origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.PROVIDER_AUTO,
                        providerScope = Conversation.Message.ContentItem.ContextCompactionResult.ProviderScope(
                            provider = AiConnection.Kind.CLAUDE_CODE.name,
                            connectionId = connectionId,
                            modelConfigurationId = modelConfigurationId,
                            modelName = modelName,
                        ),
                    )
                ),
                metadata = assistantMetadata(cliResponse, wrapper = false, resumed = resumed),
            )
        }

        return AiRuntimeResponse(
            messages = compactionMessages + assistantMessage,
            usage = cliResponse.usage?.toAiUsage(),
            finishReason = cliResponse.finishReason,
            providerMetadata = mapOf(
                "provider" to AiConnection.Kind.CLAUDE_CODE.name,
                "model" to modelName,
                "sessionId" to cliResponse.sessionId,
                "resumed" to resumed,
                "wrapper" to (toolProtocol != null),
            ),
        )
    }

    private fun responseText(
        cliResponse: ClaudeCodeCliResponse,
        responseFormat: AiResponseFormat,
    ): String {
        val structuredOutput = cliResponse.structuredOutput
        return if (responseFormat is AiResponseFormat.JsonSchema && structuredOutput != null) {
            structuredOutput.toString()
        } else {
            cliResponse.result.trim()
        }
    }

    private fun finalAssistantMessage(
        text: String,
        assistantResponseFormat: AiModelConfiguration.AssistantResponseFormat,
        metadata: Map<String, Any?>,
        thinking: List<Conversation.Message.ContentItem.Thinking>,
    ): AiAssistantMessage =
        AiAssistantMessage(
            content = thinking + Conversation.Message.ContentItem.AssistantMessage(
                structured = AssistantResponseParser.parse(text, assistantResponseFormat),
                state = Conversation.Message.BlockState.COMPLETE,
            ),
            metadata = metadata,
        )

    private fun thinkingContent(
        cliResponse: ClaudeCodeCliResponse,
        reasoning: AiReasoningConfig?,
    ): List<Conversation.Message.ContentItem.Thinking> {
        if (reasoning?.mode == AiReasoningMode.DISABLED) return emptyList()

        return cliResponse.thinking.map { block ->
            Conversation.Message.ContentItem.Thinking(
                thinking = when (reasoning?.display) {
                    AiReasoningDisplay.OMITTED -> ""
                    AiReasoningDisplay.FULL,
                    AiReasoningDisplay.SUMMARIZED,
                    null -> block.thinking
                },
                signature = block.signature,
                state = Conversation.Message.BlockState.COMPLETE,
            )
        }
    }

    private fun assistantMetadata(
        cliResponse: ClaudeCodeCliResponse,
        wrapper: Boolean,
        resumed: Boolean,
    ): Map<String, Any?> =
        mapOf(
            "provider" to AiConnection.Kind.CLAUDE_CODE.name,
            "model" to modelName,
            "sessionId" to cliResponse.sessionId,
            "resumed" to resumed,
            "wrapper" to wrapper,
        )

    private fun JsonObject.toAiUsage(): AiUsage =
        AiUsage(
            promptTokens = intField("input_tokens"),
            completionTokens = intField("output_tokens"),
            cacheCreationTokens = intField("cache_creation_input_tokens"),
            cacheReadTokens = intField("cache_read_input_tokens"),
        )

    private fun JsonObject.intField(name: String): Int =
        this[name]?.jsonPrimitive?.longOrNull?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt() ?: 0

    private fun messagesToTranscript(
        messages: List<Conversation.Message>,
        attachments: ClaudeCodeAttachmentCollector,
    ): String =
        messages.joinToString("\n\n") { message ->
            buildString {
                append("<message role=\"")
                append(message.role.name.lowercase())
                append("\" id=\"")
                append(xmlEscape(message.id.value))
                append("\">\n")

                val instructions = message.instructions
                    .joinToString("\n") { it.toXmlLine() }
                    .trim()
                if (instructions.isNotBlank()) {
                    append(instructions)
                    append("\n")
                }

                message.content.forEach { item ->
                    append(contentItemToTranscript(item, attachments))
                    append("\n")
                }

                append("</message>")
            }
        }

    private fun contentItemToTranscript(
        item: Conversation.Message.ContentItem,
        attachments: ClaudeCodeAttachmentCollector,
    ): String =
        when (item) {
            is Conversation.Message.ContentItem.UserMessage -> xmlBlock("text", item.text)
            is Conversation.Message.ContentItem.AssistantMessage -> xmlBlock("text", item.structured.fullText)
            is Conversation.Message.ContentItem.Thinking -> xmlBlock("thinking", item.thinking)
            is Conversation.Message.ContentItem.System -> xmlBlock("system", item.content)
            is Conversation.Message.ContentItem.ToolCall -> {
                "<tool_call id=\"${xmlEscape(item.id.value)}\" name=\"${xmlEscape(item.call.name)}\">" +
                    xmlEscape(item.call.input.toString()) +
                    "</tool_call>"
            }
            is Conversation.Message.ContentItem.ToolResult -> {
                "<tool_result tool_call_id=\"${xmlEscape(item.toolUseId.value)}\" name=\"${xmlEscape(item.toolName)}\" is_error=\"${item.isError}\">" +
                    xmlEscape(toolResultTranscript(item, attachments)) +
                    "</tool_result>"
            }
            is Conversation.Message.ContentItem.ImageItem ->
                attachments.addImage(item.source).toTranscriptMarker("image")
            is Conversation.Message.ContentItem.DocumentItem ->
                attachments.addDocument(item.source).toTranscriptMarker("document")
            is Conversation.Message.ContentItem.ArtifactItem ->
                error("Claude Code received an unmaterialized artifact: ${item.artifact.id.value}")
            is Conversation.Message.ContentItem.ContextCompactionResult -> compactionResultToTranscript(item)
            is Conversation.Message.ContentItem.UnknownJson -> xmlBlock("json", item.json.toString())
        }

    private fun toolResultTranscript(
        toolResult: Conversation.Message.ContentItem.ToolResult,
        attachments: ClaudeCodeAttachmentCollector,
    ): String = toolResult.result.joinToString("\n") { data ->
        when (data) {
            is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.content
            is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                attachments.addBinary(data.data, data.mediaType.value, data.fileName)
                    .toTranscriptMarker("binary tool output")
            is Conversation.Message.ContentItem.ToolResult.Data.UrlData ->
                if (data.mediaType?.value?.startsWith("image/") == true) {
                    attachments.addImage(Conversation.Message.ImageSource.UrlImageSource(data.url))
                        .toTranscriptMarker("image tool output")
                } else {
                    "[url ${data.url}]"
                }
            is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData ->
                error("Claude Code received an unmaterialized tool artifact: ${data.artifact.id.value}")
        }
    }

    private fun compactionResultToTranscript(
        item: Conversation.Message.ContentItem.ContextCompactionResult,
    ): String =
        when (val payload = item.payload) {
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary ->
                xmlBlock("context_compaction_result", payload.text)

            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
                error("Claude Code cannot replay opaque compaction state for provider=${item.providerScope?.provider}")
        }

    private fun xmlBlock(name: String, content: String): String =
        "<$name>${xmlEscape(content)}</$name>"

    private fun toolResultText(toolResult: Conversation.Message.ContentItem.ToolResult): String =
        toolResult.result.joinToString("\n") { data ->
            when (data) {
                is Conversation.Message.ContentItem.ToolResult.Data.Text -> data.content
                is Conversation.Message.ContentItem.ToolResult.Data.Base64Data ->
                    "[base64 ${data.mediaType.value}, sha256=${sha256(data.data)}]"
                is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url ${data.url}]"
                is Conversation.Message.ContentItem.ToolResult.Data.ArtifactData ->
                    error("Claude Code received an unmaterialized tool artifact: ${data.artifact.id.value}")
            }
        }

    private fun transcriptFingerprint(
        inputMessages: List<Conversation.Message>,
        generatedAssistantSignatures: List<String>,
    ): String {
        val signatures = inputMessages.map(::messageSignature) + generatedAssistantSignatures
        val bytes = signatures.joinToString("\u001E").toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun messageSignature(message: Conversation.Message): String =
        buildString {
            append(message.role.name)
            append('\u001F')
            append(message.instructions.joinToString("\u001D") { it.toXmlLine() })
            append('\u001F')
            append(message.content.joinToString("\u001D", transform = ::contentSignature))
        }

    private fun assistantMessageSignature(message: AiAssistantMessage): String =
        "ASSISTANT\u001F\u001F${message.content.joinToString("\u001D", transform = ::contentSignature)}"

    private fun generatedAssistantMessageSignature(message: Conversation.Message): String {
        if (message.role != Conversation.Message.Role.ASSISTANT) return "not_generated_assistant:${message.id.value}"
        return "ASSISTANT\u001F\u001F${message.content.joinToString("\u001D", transform = ::contentSignature)}"
    }

    private fun contentSignature(item: Conversation.Message.ContentItem): String =
        when (item) {
            is Conversation.Message.ContentItem.UserMessage -> "user:${item.text}"
            is Conversation.Message.ContentItem.AssistantMessage -> {
                val structured = item.structured
                "assistant:${structured.fullText}:${structured.ttsText}:${structured.voiceTone}:" +
                    "${structured.attentionRequested}:${structured.failedToParse}"
            }
            is Conversation.Message.ContentItem.ToolCall -> "tool_call:${item.id.value}:${item.call.name}:${item.call.input}"
            is Conversation.Message.ContentItem.ToolResult -> "tool_result:${item.toolUseId.value}:${item.toolName}:${item.isError}:${toolResultText(item)}"
            is Conversation.Message.ContentItem.Thinking -> "thinking:${item.thinking}:${item.signature}"
            is Conversation.Message.ContentItem.System -> "system:${item.level}:${item.content}:${item.toolUseId?.value}"
            is Conversation.Message.ContentItem.ImageItem -> "image:${imageSourceSignature(item.source)}"
            is Conversation.Message.ContentItem.DocumentItem -> "document:${documentSourceSignature(item.source)}"
            is Conversation.Message.ContentItem.ArtifactItem -> "artifact:${item.artifact}"
            is Conversation.Message.ContentItem.ContextCompactionResult -> "compaction:${compactionSignature(item)}"
            is Conversation.Message.ContentItem.UnknownJson -> "json:${item.json}"
        }

    private fun imageSourceSignature(source: Conversation.Message.ImageSource): String =
        when (source) {
            is Conversation.Message.ImageSource.Base64ImageSource ->
                "base64:${source.mediaType}:${sha256(source.data)}"
            is Conversation.Message.ImageSource.UrlImageSource -> "url:${source.url}"
            is Conversation.Message.ImageSource.FileImageSource -> "file:${source.fileId}"
        }

    private fun documentSourceSignature(source: Conversation.Message.DocumentSource): String =
        when (source) {
            is Conversation.Message.DocumentSource.Base64DocumentSource ->
                "base64:${source.mediaType}:${source.fileName}:${sha256(source.data)}"
        }

    private fun compactionSignature(item: Conversation.Message.ContentItem.ContextCompactionResult): String =
        "${item.origin}:${item.providerScope}:${item.sourceMessageIds.joinToString(",") { it.value }}:" +
        when (val payload = item.payload) {
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary ->
                payload.text

            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
                payload.state.toString()
        }

    private fun ClaudeCodeSessionState.Key.lockKey(): String =
        listOf(
            conversationId.value,
            threadId.value,
            projectId.value,
            workspaceRootPathFingerprint,
            connectionId.value,
            modelConfigurationId.value,
            modelName,
        ).joinToString("\u001F")

    private fun Any?.contextString(): String? =
        when (this) {
            null -> null
            is String -> this
            else -> toString()
        }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun xmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

private data class ClaudeCodeAttachmentReference(val index: Int) {
    fun toTranscriptMarker(label: String): String = "[$label attachment_index=$index]"
}

private class ClaudeCodeAttachmentCollector {
    private val blocks = mutableListOf<JsonObject>()

    val contentBlocks: List<JsonObject>
        get() = blocks.toList()

    fun addImage(source: Conversation.Message.ImageSource): ClaudeCodeAttachmentReference =
        add(
            when (source) {
                is Conversation.Message.ImageSource.Base64ImageSource -> imageBlock(
                    sourceType = "base64",
                    sourceFields = mapOf(
                        "media_type" to JsonPrimitive(source.mediaType),
                        "data" to JsonPrimitive(source.data),
                    ),
                )
                is Conversation.Message.ImageSource.UrlImageSource -> imageBlock(
                    sourceType = "url",
                    sourceFields = mapOf("url" to JsonPrimitive(source.url)),
                )
                is Conversation.Message.ImageSource.FileImageSource ->
                    error("Claude Code does not accept a provider-independent image file id")
            }
        )

    fun addDocument(source: Conversation.Message.DocumentSource): ClaudeCodeAttachmentReference =
        when (source) {
            is Conversation.Message.DocumentSource.Base64DocumentSource ->
                addBinary(source.data, source.mediaType, source.fileName)
        }

    fun addBinary(
        data: String,
        mediaType: String,
        fileName: String?,
    ): ClaudeCodeAttachmentReference {
        val normalizedMediaType = mediaType.substringBefore(';').trim().lowercase()
        val block = when {
            normalizedMediaType.startsWith("image/") -> imageBlock(
                sourceType = "base64",
                sourceFields = mapOf(
                    "media_type" to JsonPrimitive(normalizedMediaType),
                    "data" to JsonPrimitive(data),
                ),
            )
            normalizedMediaType == "application/pdf" -> documentBlock(
                fileName = fileName ?: "attachment.pdf",
                source = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("base64"),
                        "media_type" to JsonPrimitive(normalizedMediaType),
                        "data" to JsonPrimitive(data),
                    )
                ),
            )
            normalizedMediaType.startsWith("text/") || normalizedMediaType in CLAUDE_CODE_TEXT_DOCUMENT_TYPES ->
                documentBlock(
                    fileName = fileName ?: "attachment.txt",
                    source = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("text"),
                            "media_type" to JsonPrimitive("text/plain"),
                            "data" to JsonPrimitive(Base64.getDecoder().decode(data).toString(Charsets.UTF_8)),
                        )
                    ),
                )
            else -> error("Claude Code does not support attachment type $normalizedMediaType")
        }
        return add(block)
    }

    private fun add(block: JsonObject): ClaudeCodeAttachmentReference {
        blocks += block
        return ClaudeCodeAttachmentReference(blocks.size)
    }

    private fun imageBlock(
        sourceType: String,
        sourceFields: Map<String, JsonElement>,
    ): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("image"),
            "source" to JsonObject(mapOf("type" to JsonPrimitive(sourceType)) + sourceFields),
        )
    )

    private fun documentBlock(fileName: String, source: JsonObject): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("document"),
            "source" to source,
            "title" to JsonPrimitive(fileName),
        )
    )
}

private val CLAUDE_CODE_TEXT_DOCUMENT_TYPES = setOf(
    "application/json",
    "application/xml",
    "application/x-yaml",
    "application/yaml",
)

private data class ClaudeCodeUserInput(
    val prompt: String,
    val contentBlocks: List<JsonObject>,
)

private data class ClaudeCodeSessionPlan(
    val messagesToSend: List<Conversation.Message>,
    val resumeSessionId: String?,
)

internal interface ClaudeCodeCliExecutor {
    suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse
}

internal interface ClaudeCodeNativeToolExecutor {
    suspend fun executeNativeTool(
        command: ClaudeCodeCommand,
        invocation: ClaudeCodeNativeToolInvocation,
    ): ClaudeCodeNativeToolResponse
}

internal enum class ClaudeCodeNativeTool(val cliName: String) {
    WEB_SEARCH("WebSearch"),
    WEB_FETCH("WebFetch"),
}

internal data class ClaudeCodeNativeToolInvocation(
    val tool: ClaudeCodeNativeTool,
    val input: JsonObject,
)

internal data class ClaudeCodeNativeToolResponse(
    val tool: ClaudeCodeNativeTool,
    val input: JsonObject,
    val result: JsonElement,
)

internal data class ClaudeCodeCommand(
    val connectionId: String = "claude-code",
    val executablePath: String = "claude",
    val cacheKey: String? = null,
    val maxCachedProcesses: Int = AiConnection.ClaudeCode.DEFAULT_MAX_CACHED_PROCESSES,
    val processIdleTtlMinutes: Int = AiConnection.ClaudeCode.DEFAULT_PROCESS_IDLE_TTL_MINUTES,
    val modelName: String,
    val workspaceDirectory: File?,
    val systemPrompt: String,
    val userPrompt: String,
    val userContentBlocks: List<JsonObject> = emptyList(),
    val jsonSchema: JsonElement?,
    val effort: AiReasoningEffort?,
    val reasoningMode: AiReasoningMode?,
    val resumeSessionId: String?,
    val noSessionPersistence: Boolean,
    val nativeTools: Set<ClaudeCodeNativeTool> = emptySet(),
)

internal data class ClaudeCodeThinkingBlock(
    val thinking: String,
    val signature: String?,
)

internal data class ClaudeCodeCliResponse(
    val result: String,
    val structuredOutput: JsonElement?,
    val sessionId: String?,
    val usage: JsonObject?,
    val finishReason: String?,
    val raw: JsonObject,
    val thinking: List<ClaudeCodeThinkingBlock> = emptyList(),
    val compactionBoundaries: List<JsonObject> = emptyList(),
)

private class ClaudeCodeToolProtocol(
    private val tools: List<AiToolCallback>,
    private val toolChoice: AiToolChoice,
    finalAnswerSchema: JsonElement,
) {
    val schema: JsonObject = buildSchema(finalAnswerSchema)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val actionNames = tools.map { it.definition.name }.toSet()

    private fun buildSchema(finalAnswerSchema: JsonElement): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "additionalProperties" to JsonPrimitive(false),
                "properties" to JsonObject(
                    mapOf("response" to responseSchema(finalAnswerSchema))
                ),
                "required" to JsonArray(listOf(JsonPrimitive("response"))),
            )
        )

    private fun responseSchema(finalAnswerSchema: JsonElement): JsonObject =
        when (toolChoice) {
            AiToolChoice.Auto -> JsonObject(
                mapOf(
                    "anyOf" to JsonArray(
                        listOf(
                            finalAnswerBranch(finalAnswerSchema),
                            externalActionBranch(),
                        )
                    ),
                )
            )

            AiToolChoice.None -> finalAnswerBranch(finalAnswerSchema)
            AiToolChoice.RequiredAny,
            is AiToolChoice.RequiredTool -> externalActionBranch()
        }

    private fun finalAnswerBranch(finalAnswerSchema: JsonElement): JsonObject =
        JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "additionalProperties" to JsonPrimitive(false),
            "properties" to JsonObject(
                mapOf(
                    "kind" to kindSchema("final_answer"),
                    "final_answer" to finalAnswerSchema,
                )
            ),
            "required" to JsonArray(listOf("kind", "final_answer").map(::JsonPrimitive)),
        ))

    private fun externalActionBranch(): JsonObject =
        JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "additionalProperties" to JsonPrimitive(false),
            "properties" to JsonObject(
                mapOf(
                    "kind" to kindSchema("tool_calls"),
                    "tool_calls" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("array"),
                            "minItems" to JsonPrimitive(1),
                            "items" to externalActionSchema(),
                        )
                    ),
                )
            ),
            "required" to JsonArray(listOf("kind", "tool_calls").map(::JsonPrimitive)),
        ))

    private fun externalActionSchema(): JsonObject =
        JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "additionalProperties" to JsonPrimitive(false),
            "properties" to JsonObject(
                mapOf(
                    "action_name" to actionNameSchema(),
                    "arguments" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "additionalProperties" to JsonPrimitive(true),
                        )
                    ),
                )
            ),
            "required" to JsonArray(listOf("action_name", "arguments").map(::JsonPrimitive)),
        ))

    private fun kindSchema(kind: String): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(listOf(JsonPrimitive(kind))),
            )
        )

    private fun actionNameSchema(): JsonObject {
        val allowedActionNames = when (toolChoice) {
            is AiToolChoice.RequiredTool -> listOf(toolChoice.name)
            AiToolChoice.Auto,
            AiToolChoice.RequiredAny,
            AiToolChoice.None -> tools.map { it.definition.name }
        }
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(allowedActionNames.map(::JsonPrimitive)),
            )
        )
    }

    fun instructions(): String =
        buildString {
            appendLine("<gromozeka_external_action_protocol>")
            appendLine("The entries below are external Gromozeka actions, not Claude Code tools.")
            appendLine("Never invoke an external action name through Claude Code native tool use, even when the user explicitly asks to call it.")
            appendLine("Claude Code native tools are disabled. Gromozeka owns external action execution.")
            appendLine("Submit exactly one object through the structured-output mechanism matching the provided JSON schema.")
            appendLine("The object has one required response field. Put the selected response branch inside it.")
            appendLine("When external actions are needed, do not execute or wait for them in this invocation.")
            appendLine("Instead, immediately submit response.kind=\"tool_calls\" and put every action request in response.tool_calls.")
            appendLine("Each entry must contain the action name in action_name and its input in arguments.")
            appendLine("Group every independent external action that can run now into the same response.")
            appendLine("Do not group an action that depends on another action's result into the same response.")
            appendLine("Gromozeka will execute the batch concurrently and resume this Claude Code session with all results.")
            appendLine("Submit response.kind=\"final_answer\" only when no external action is needed.")
            appendLine("For final_answer, return the exact assistant payload required by the normal Gromozeka response contract.")
            appendLine(toolChoiceInstruction())
            appendLine("<external_actions>")
            tools.forEach { tool ->
                appendLine("<action name=\"${xmlEscape(tool.definition.name)}\">")
                appendLine("<description>${xmlEscape(tool.definition.description)}</description>")
                appendLine("<input_schema>${xmlEscape(tool.definition.inputSchema)}</input_schema>")
                appendLine("</action>")
            }
            appendLine("</external_actions>")
            appendLine("</gromozeka_external_action_protocol>")
        }

    fun runtimeReminder(): String =
        """
        <gromozeka_external_action_reminder>
        External action names are not Claude Code tools. Never invoke them through native tool use.
        Submit exactly one object through structured output now. Inside its response field, use kind="tool_calls" with every currently independent external action, otherwise kind="final_answer".
        </gromozeka_external_action_reminder>
        """.trimIndent()

    fun toAssistantMessage(
        cliResponse: ClaudeCodeCliResponse,
        options: AiRuntimeOptions,
        metadata: Map<String, Any?>,
        thinking: List<Conversation.Message.ContentItem.Thinking>,
    ): AiAssistantMessage {
        val root = wrapperRoot(cliResponse)
        return when (val kind = root["kind"]?.jsonPrimitive?.contentOrNull) {
            "final_answer" -> {
                if (toolChoice is AiToolChoice.RequiredAny || toolChoice is AiToolChoice.RequiredTool) {
                    error("Claude Code returned final_answer while runtime required an external action request")
                }
                val answer = root["final_answer"] ?: error("Claude Code final_answer wrapper missed final_answer")
                AiAssistantMessage(
                    content = thinking + Conversation.Message.ContentItem.AssistantMessage(
                        structured = AssistantResponseParser.parse(finalAnswerText(answer), options.assistantResponseFormat),
                        state = Conversation.Message.BlockState.COMPLETE,
                    ),
                    metadata = metadata + ("wrapperKind" to kind),
                )
            }

            "tool_calls" -> {
                val calls = root["tool_calls"]?.jsonArray
                    ?: error("Claude Code tool_calls wrapper missed tool_calls")
                require(calls.isNotEmpty()) { "Claude Code tool_calls wrapper must contain at least one action" }
                val toolCalls = calls.mapIndexed { index, callElement ->
                    val call = callElement.jsonObject
                    val name = call["action_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: error("Claude Code tool_calls wrapper missed action_name at index $index")
                    require(name in actionNames) { "Claude Code requested unavailable external action: $name" }
                    if (toolChoice is AiToolChoice.RequiredTool) {
                        require(name == toolChoice.name) {
                            "Claude Code requested external action $name while runtime required ${toolChoice.name}"
                        }
                    }
                    val arguments = parseArguments(call["arguments"] ?: JsonObject(emptyMap()))
                    Conversation.Message.ContentItem.ToolCall(
                        id = Conversation.Message.ContentItem.ToolCall.Id("claude-code:${uuid7()}"),
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = name,
                            input = arguments,
                        ),
                        state = Conversation.Message.BlockState.COMPLETE,
                    )
                }
                AiAssistantMessage(
                    content = thinking + toolCalls,
                    metadata = metadata + ("wrapperKind" to kind),
                )
            }

            else -> error("Claude Code returned unsupported wrapper kind: $kind")
        }
    }

    private fun wrapperRoot(cliResponse: ClaudeCodeCliResponse): JsonObject {
        val structured = cliResponse.structuredOutput
        val envelope = if (structured is JsonObject) {
            structured
        } else {
            json.parseToJsonElement(cliResponse.result).jsonObject
        }
        return envelope["response"]?.jsonObject
            ?: error("Claude Code structured-output wrapper missed response")
    }

    private fun finalAnswerText(answer: JsonElement): String =
        if (answer is JsonPrimitive && answer.isString) {
            answer.content
        } else {
            answer.toString()
        }

    private fun parseArguments(arguments: JsonElement): JsonElement =
        if (arguments is JsonPrimitive && arguments.isString) {
            json.parseToJsonElement(arguments.content)
        } else {
            arguments
        }

    private fun toolChoiceInstruction(): String =
        when (toolChoice) {
            AiToolChoice.Auto -> "External action choice: auto. Request an external action only when useful."
            AiToolChoice.None -> "External action choice: none. Do not request external actions."
            AiToolChoice.RequiredAny -> "External action choice: required. You must request at least one external action."
            is AiToolChoice.RequiredTool -> "External action choice: required. You must request at least one ${toolChoice.name} external action."
        }

    private fun xmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
