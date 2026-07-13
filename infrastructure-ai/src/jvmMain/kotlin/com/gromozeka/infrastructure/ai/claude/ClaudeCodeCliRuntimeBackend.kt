package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeOptions
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.springframework.stereotype.Service
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Service
internal class ClaudeCodeCliRuntimeBackend(
    private val sessionStateRepository: ClaudeCodeSessionStateRepository,
) : AiRuntimeBackend {
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    override fun supports(connectionKind: AiConnection.Kind): Boolean =
        connectionKind == AiConnection.Kind.CLAUDE_CODE

    override fun createRuntime(
        connection: AiConnection,
        modelConfiguration: AiModelConfiguration,
        projectPath: String?
    ): AiRuntime {
        require(connection is AiConnection.ClaudeCode) {
            "Claude Code runtime requires claude_code connection, got ${connection::class.simpleName}"
        }

        return ClaudeCodeCliRuntime(
            executor = ProcessClaudeCodeCliExecutor(
                executable = connection.executablePath,
            ),
            connectionId = connection.id.value,
            modelConfigurationId = modelConfiguration.id.value,
            modelName = modelConfiguration.providerModelId,
            projectDirectory = resolveProjectDirectory(projectPath),
            sessionStateRepository = sessionStateRepository,
            sessionLocks = sessionLocks,
        )
    }

    private fun resolveProjectDirectory(projectPath: String?): File? {
        val value = projectPath?.takeIf { it.isNotBlank() } ?: return null
        val directory = File(value).toPath().toAbsolutePath().normalize().toFile()
        require(directory.isDirectory) { "Claude Code project path must be an existing directory: $value" }
        return directory
    }
}

internal class ClaudeCodeCliRuntime(
    private val executor: ClaudeCodeCliExecutor,
    private val connectionId: String,
    private val modelConfigurationId: String,
    private val modelName: String,
    private val projectDirectory: File?,
    private val sessionStateRepository: ClaudeCodeSessionStateRepository,
    private val sessionLocks: ConcurrentHashMap<String, Mutex>,
) : AiRuntime {
    private val log = KLoggers.logger(this)

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        require(request.messages.isNotEmpty()) { "Claude Code CLI request must contain at least one message" }
        validateToolChoice(request.tools, request.options.toolChoice)

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
        val userPrompt = buildUserPrompt(sessionPlan, toolProtocol)
        val schema = toolProtocol?.schema ?: (request.options.responseFormat as? AiResponseFormat.JsonSchema)?.schema

        log.info {
            "Calling Claude Code CLI runtime: model=$modelName messages=${request.messages.size} " +
                "sentMessages=${sessionPlan.messagesToSend.size} resumed=${sessionPlan.resumeSessionId != null} " +
                "tools=${request.tools.size} wrapper=${toolProtocol != null}"
        }

        val command = ClaudeCodeCommand(
            modelName = modelName,
            projectDirectory = projectDirectory,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            jsonSchema = schema,
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
        val projectPath = projectDirectory?.canonicalFile?.absolutePath ?: "no-project"

        return ClaudeCodeSessionState.Key(
            conversationId = Conversation.Id(conversationId),
            threadId = Conversation.Thread.Id(threadId),
            projectId = com.gromozeka.domain.model.Project.Id(projectId),
            projectPathSnapshot = projectPath,
            projectPathFingerprint = sha256(projectPath),
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

    private fun buildUserPrompt(
        plan: ClaudeCodeSessionPlan,
        toolProtocol: ClaudeCodeToolProtocol?,
    ): String {
        val header = if (plan.resumeSessionId == null) {
            "Gromozeka conversation transcript:"
        } else {
            "New Gromozeka messages since the previous Claude Code session turn:"
        }
        return listOf(
            "$header\n\n${messagesToTranscript(plan.messagesToSend)}",
            toolProtocol?.runtimeReminder(),
        ).filterNotNull().joinToString("\n\n")
    }

    private fun toRuntimeResponse(
        cliResponse: ClaudeCodeCliResponse,
        request: AiRuntimeRequest,
        toolProtocol: ClaudeCodeToolProtocol?,
        resumed: Boolean,
    ): AiRuntimeResponse {
        val assistantMessage = if (toolProtocol == null) {
            finalAssistantMessage(
                text = responseText(cliResponse, request.options.responseFormat),
                assistantResponseFormat = request.options.assistantResponseFormat,
                metadata = assistantMetadata(cliResponse, wrapper = false, resumed = resumed),
            )
        } else {
            toolProtocol.toAssistantMessage(cliResponse, request.options, assistantMetadata(cliResponse, wrapper = true, resumed = resumed))
        }

        return AiRuntimeResponse(
            messages = listOf(assistantMessage),
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
    ): AiAssistantMessage =
        AiAssistantMessage(
            content = listOf(
                Conversation.Message.ContentItem.AssistantMessage(
                    structured = AssistantResponseParser.parse(text, assistantResponseFormat),
                    state = Conversation.Message.BlockState.COMPLETE,
                )
            ),
            metadata = metadata,
        )

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

    private fun messagesToTranscript(messages: List<Conversation.Message>): String =
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
                    append(contentItemToTranscript(item))
                    append("\n")
                }

                append("</message>")
            }
        }

    private fun contentItemToTranscript(item: Conversation.Message.ContentItem): String =
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
                    xmlEscape(toolResultText(item)) +
                    "</tool_result>"
            }
            is Conversation.Message.ContentItem.ImageItem -> {
                "<image source=\"${xmlEscape(item.source.type)}\" />"
            }
            is Conversation.Message.ContentItem.ContextCompactionResult -> compactionResultToTranscript(item)
            is Conversation.Message.ContentItem.UnknownJson -> xmlBlock("json", item.json.toString())
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
                is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> "[base64 ${data.mediaType.value}, ${data.data.length} chars]"
                is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> "[url ${data.url}]"
                is Conversation.Message.ContentItem.ToolResult.Data.FileData -> "[file ${data.fileId}]"
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
                "assistant:${structured.fullText}:${structured.ttsText}:${structured.voiceTone}:${structured.failedToParse}"
            }
            is Conversation.Message.ContentItem.ToolCall -> "tool_call:${item.id.value}:${item.call.name}:${item.call.input}"
            is Conversation.Message.ContentItem.ToolResult -> "tool_result:${item.toolUseId.value}:${item.toolName}:${item.isError}:${toolResultText(item)}"
            is Conversation.Message.ContentItem.Thinking -> "thinking:${item.thinking}:${item.signature}"
            is Conversation.Message.ContentItem.System -> "system:${item.level}:${item.content}:${item.toolUseId?.value}"
            is Conversation.Message.ContentItem.ImageItem -> "image:${item.source}"
            is Conversation.Message.ContentItem.ContextCompactionResult -> "compaction:${compactionSignature(item)}"
            is Conversation.Message.ContentItem.UnknownJson -> "json:${item.json}"
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
            projectPathFingerprint,
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

private data class ClaudeCodeSessionPlan(
    val messagesToSend: List<Conversation.Message>,
    val resumeSessionId: String?,
)

internal interface ClaudeCodeCliExecutor {
    suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse
}

internal data class ClaudeCodeCommand(
    val modelName: String,
    val projectDirectory: File?,
    val systemPrompt: String,
    val userPrompt: String,
    val jsonSchema: JsonElement?,
    val resumeSessionId: String?,
    val noSessionPersistence: Boolean,
)

private data class ProcessOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal data class ClaudeCodeCliResponse(
    val result: String,
    val structuredOutput: JsonElement?,
    val sessionId: String?,
    val usage: JsonObject?,
    val finishReason: String?,
    val raw: JsonObject,
)

internal class ProcessClaudeCodeCliExecutor(
    private val executable: String,
) : ClaudeCodeCliExecutor {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse {
        val systemPromptFile = withContext(Dispatchers.IO) {
            Files.createTempFile("gromozeka-claude-system-", ".md")
        }

        try {
            withContext(Dispatchers.IO) {
                Files.writeString(systemPromptFile, command.systemPrompt, StandardCharsets.UTF_8)
            }

            val args = buildArgs(command, systemPromptFile.toString())
            val output = runProcess(args, command.projectDirectory, command.userPrompt)
            if (output.exitCode != 0) {
                val diagnostic = output.stderr.ifBlank { output.stdout }.trim()
                error("Claude Code CLI failed with exit code ${output.exitCode}: $diagnostic")
            }

            return parseCliResponse(output.stdout)
        } finally {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(systemPromptFile)
            }
        }
    }

    private fun buildArgs(command: ClaudeCodeCommand, systemPromptFile: String): List<String> =
        buildList {
            add(executable)
            add("-p")
            add("--safe-mode")
            add("--tools")
            add("")
            add("--disable-slash-commands")
            add("--setting-sources")
            add("")
            add("--output-format")
            add("json")
            add("--system-prompt-file")
            add(systemPromptFile)
            add("--model")
            add(command.modelName)
            command.jsonSchema?.let { schema ->
                add("--json-schema")
                add(schema.toString())
            }
            command.resumeSessionId?.let { sessionId ->
                add("--resume")
                add(sessionId)
            }
            if (command.noSessionPersistence) {
                add("--no-session-persistence")
            }
        }

    private suspend fun runProcess(
        args: List<String>,
        directory: File?,
        stdin: String,
    ): ProcessOutput = coroutineScope {
        val process = try {
            ProcessBuilder(args)
                .apply { directory?.let(::directory) }
                .start()
        } catch (e: IOException) {
            error("Failed to start Claude Code CLI '${args.first()}'. Ensure Claude Code is installed and authorized: ${e.message}")
        }

        val stdout = async(Dispatchers.IO) {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
        val stderr = async(Dispatchers.IO) {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }

        withContext(Dispatchers.IO) {
            process.outputStream.use { stream ->
                stream.write(stdin.toByteArray(StandardCharsets.UTF_8))
            }
        }

        val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
        ProcessOutput(
            exitCode = exitCode,
            stdout = stdout.await(),
            stderr = stderr.await(),
        )
    }

    private fun parseCliResponse(stdout: String): ClaudeCodeCliResponse {
        val trimmed = stdout.trim()
        require(trimmed.isNotBlank()) { "Claude Code CLI returned empty stdout" }

        val root = json.parseToJsonElement(trimmed).jsonObject
        val isError = root["is_error"]?.jsonPrimitive?.booleanOrNull == true
        if (isError) {
            val message = root["result"]?.jsonPrimitive?.contentOrNull ?: trimmed
            error("Claude Code CLI returned an error: $message")
        }

        return ClaudeCodeCliResponse(
            result = root["result"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            structuredOutput = root["structured_output"],
            sessionId = root["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            usage = root["usage"]?.jsonObject,
            finishReason = root["subtype"]?.jsonPrimitive?.contentOrNull,
            raw = root,
        )
    }
}

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
                    "kind" to kindSchema("tool_call"),
                    "action_name" to actionNameSchema(),
                    "arguments" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "additionalProperties" to JsonPrimitive(true),
                        )
                    ),
                )
            ),
            "required" to JsonArray(listOf("kind", "action_name", "arguments").map(::JsonPrimitive)),
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
            appendLine("When an external action is needed, do not execute or wait for it in this invocation.")
            appendLine("Instead, immediately submit response.kind=\"tool_call\", the action name in response.action_name, and its input in response.arguments.")
            appendLine("Gromozeka will execute the action and resume this Claude Code session with its result.")
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
        Submit exactly one object through structured output now. Inside its response field, use kind="tool_call" to request an external action, otherwise kind="final_answer".
        </gromozeka_external_action_reminder>
        """.trimIndent()

    fun toAssistantMessage(
        cliResponse: ClaudeCodeCliResponse,
        options: AiRuntimeOptions,
        metadata: Map<String, Any?>,
    ): AiAssistantMessage {
        val root = wrapperRoot(cliResponse)
        return when (val kind = root["kind"]?.jsonPrimitive?.contentOrNull) {
            "final_answer" -> {
                if (toolChoice is AiToolChoice.RequiredAny || toolChoice is AiToolChoice.RequiredTool) {
                    error("Claude Code returned final_answer while runtime required an external action request")
                }
                val answer = root["final_answer"] ?: error("Claude Code final_answer wrapper missed final_answer")
                AiAssistantMessage(
                    content = listOf(
                        Conversation.Message.ContentItem.AssistantMessage(
                            structured = AssistantResponseParser.parse(finalAnswerText(answer), options.assistantResponseFormat),
                            state = Conversation.Message.BlockState.COMPLETE,
                        )
                    ),
                    metadata = metadata + ("wrapperKind" to kind),
                )
            }

            "tool_call" -> {
                val name = root["action_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: error("Claude Code tool_call wrapper missed action_name")
                require(name in actionNames) { "Claude Code requested unavailable external action: $name" }
                if (toolChoice is AiToolChoice.RequiredTool) {
                    require(name == toolChoice.name) {
                        "Claude Code requested external action $name while runtime required ${toolChoice.name}"
                    }
                }
                val arguments = parseArguments(root["arguments"] ?: JsonObject(emptyMap()))
                AiAssistantMessage(
                    content = listOf(
                        Conversation.Message.ContentItem.ToolCall(
                            id = Conversation.Message.ContentItem.ToolCall.Id("claude-code:${uuid7()}"),
                            call = Conversation.Message.ContentItem.ToolCall.Data(
                                name = name,
                                input = arguments,
                            ),
                            state = Conversation.Message.BlockState.COMPLETE,
                        )
                    ),
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
            AiToolChoice.RequiredAny -> "External action choice: required. You must request one external action."
            is AiToolChoice.RequiredTool -> "External action choice: required. You must request external action ${toolChoice.name}."
        }

    private fun xmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
