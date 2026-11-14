package com.gromozeka.server

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ArtifactRepository
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.shared.uuid.uuid7
import io.github.optimumcode.json.schema.JsonSchema
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import kotlin.time.Clock

internal data class AiCallInput(val type: String, val text: String? = null, val artifactId: String? = null,
    val workspaceMountId: String? = null, val path: String? = null, val mediaType: String? = null)
internal data class AiCallRequest(val modelConfigurationId: String, val prompt: String, val inputs: List<AiCallInput>,
    val responseSchema: JsonObject? = null, val artifactResult: Boolean = false, val maxOutputTokens: Int? = null)
internal data class AiCallResult(val invocationId: String, val configurationId: String, val status: String,
    val output: JsonElement?, val artifact: Artifact.Reference?, val usage: AiUsage?, val errorCode: String? = null) {
    val success: Boolean get() = status == "complete"
}

/** Explicit-input inference only. No history, prompt assembly, memory, agent loop or compaction service. */
@Service
internal class AiCallService(
    private val configurations: AiConfigurationProvider,
    private val runtimes: AiRuntimeProvider,
    private val users: UserDirectoryService,
    private val conversations: ConversationRepository,
    private val projects: ProjectAccessService,
    private val workers: WorkerAccessService,
    private val files: WorkspaceFileReader,
    private val artifacts: ConversationArtifactApplicationService,
    private val artifactRepository: ArtifactRepository,
) {
    suspend fun call(input: AiCallRequest, context: ToolExecutionContext): AiCallResult {
        require(input.prompt.isNotBlank() && input.prompt.length <= MAX_PROMPT_CHARACTERS) { "prompt must contain 1–$MAX_PROMPT_CHARACTERS characters" }
        require(input.inputs.size <= MAX_INPUTS) { "Too many inputs (maximum $MAX_INPUTS)" }
        require(input.maxOutputTokens == null || input.maxOutputTokens > 0) { "max_output_tokens must be positive" }
        val actorId = User.Id(requireNotNull(context.getString(TOOL_CONTEXT_USER_ID)))
        val conversationId = Conversation.Id(requireNotNull(context.getString(TOOL_CONTEXT_CONVERSATION_ID)))
        val agentId = context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)?.let(AgentDefinition::Id)
        val conversation = authorize(actorId, conversationId, agentId)
        val selection = AiRuntimeSelection(AiModelConfiguration.Id(input.modelConfigurationId))
        val resolved = configurations.resolveAiRuntime(selection)
        require(AiModelCapability.TEXT_GENERATION in resolved.modelSpec.capabilities) { "Selected configuration does not generate text" }
        if (resolved.connection.kind == AiConnection.Kind.GITHUB_COPILOT) throw UnsupportedOperationException("Strict tool-free inference is unsupported by Copilot")
        if (input.maxOutputTokens != null && resolved.connection.kind in setOf(AiConnection.Kind.CLAUDE_CODE, AiConnection.Kind.OPENAI_SUBSCRIPTION)) {
            throw UnsupportedOperationException("This adapter does not support a per-call output-token override")
        }
        input.responseSchema?.let {
            require(AiModelCapability.STRUCTURED_OUTPUT in resolved.modelSpec.capabilities) { "Selected model does not support structured output" }
            require(it.toString().encodeToByteArray().size <= MAX_SCHEMA_BYTES) { "response_schema is too large" }
            requireLocalSchema(it)
        }
        val validator = input.responseSchema?.let { schema ->
            try { JsonSchema.fromJsonElement(schema) }
            catch (_: Exception) { throw IllegalArgumentException("Invalid response_schema") }
        }
        suspend fun fence() {
            currentCoroutineContext().ensureActive()
            context.cancellationSignal.throwIfCancellationRequested()
            val current = authorize(actorId, conversationId, agentId)
            check(current.projectId == conversation.projectId) { "Conversation scope changed" }
            check(configurations.resolveAiRuntime(selection) == resolved) { "Model configuration changed during the call" }
            (resolved.connection.executionTarget as? AiExecutionTarget.Worker)?.let { target ->
                val user = requireNotNull(users.findActiveById(actorId))
                workers.requirePermission(user, ConversationRuntimeWorkerId(target.workerId), WorkerPermission.USE, current.projectId)
                workers.requireProjectAccess(ConversationRuntimeWorkerId(target.workerId), current.projectId)
            }
        }
        fence()
        val content = mutableListOf<ContentItem>()
        var inputBytes = input.prompt.encodeToByteArray().size.toLong()
        for (part in input.inputs) {
            fence()
            val remaining = MAX_INPUT_BYTES - inputBytes
            require(remaining > 0) { "AI call input exceeds $MAX_INPUT_BYTES bytes" }
            if (part.type == "text") {
                val text = requireNotNull(part.text)
                inputBytes += text.encodeToByteArray().size
                require(inputBytes <= MAX_INPUT_BYTES) { "AI call input exceeds $MAX_INPUT_BYTES bytes" }
                content += ContentItem.UserMessage(text)
                continue
            }
            val bytes: ByteArray
            val fileName: String
            val mediaType: String
            when (part.type) {
                "file" -> {
                    val file = files.readFile(WorkspacePathReference(WorkspaceMount.Id(requireNotNull(part.workspaceMountId)), requireNotNull(part.path)),
                        WorkspacePathAccessContext(actorId, conversation.projectId), minOf(remaining, MAX_WORKER_WORKSPACE_FILE_BYTES))
                    bytes = file.content.bytes()
                    check(bytes.size.toLong() == file.sizeBytes) { "File payload does not match its declared size" }
                    fileName = file.fileName
                    mediaType = part.mediaType ?: mediaTypeFor(fileName)
                }
                "artifact" -> {
                    val artifact = requireNotNull(artifacts.find(Artifact.Id(requireNotNull(part.artifactId)))) { "Input artifact is unavailable" }
                    require(artifact.conversationId == conversation.id && artifact.projectId == conversation.projectId && artifact.state == Artifact.State.COMMITTED) {
                        "Input artifact is unavailable in this conversation"
                    }
                    require(artifact.sizeBytes?.let { it <= remaining } != false) { "Input artifact exceeds the input limit" }
                    bytes = artifacts.read(artifact.id)
                    fileName = artifact.fileName
                    mediaType = artifact.mediaType
                }
                else -> error("Unexpected input kind")
            }
            inputBytes += bytes.size
            require(inputBytes <= MAX_INPUT_BYTES) { "AI call input exceeds $MAX_INPUT_BYTES bytes" }
            content += contentPart(bytes, fileName, mediaType)
        }
        val invocationId = "ai-call:${uuid7()}"
        val isolated = Conversation.Id(invocationId)
        // Keep usage attribution in trusted toolContext, separate from the new transport namespace.
        // There is no parent thread id, parent prompt stack, replay state or tool catalog.
        val request = AiRuntimeRequest(
            systemPrompts = listOf(input.prompt),
            messages = listOf(Conversation.Message(Conversation.Message.Id(invocationId), isolated,
                role = Conversation.Message.Role.USER,
                content = content.ifEmpty { listOf(ContentItem.UserMessage("Carry out the instruction.")) }, createdAt = Clock.System.now())),
            tools = emptyList(),
            options = AiRuntimeOptions(
                maxOutputTokens = input.maxOutputTokens,
                responseFormat = input.responseSchema?.let { AiResponseFormat.JsonSchema("result", it) } ?: AiResponseFormat.Text,
                assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                toolChoice = AiToolChoice.None, toolAccess = ToolAccessPolicy.AllowOnly(),
                usagePurpose = AI_ISOLATED_CALL_PURPOSE,
                toolContext = buildMap {
                    put(TOOL_CONTEXT_USER_ID, actorId.value)
                    put(TOOL_CONTEXT_CONVERSATION_ID, conversation.id.value)
                    put(TOOL_CONTEXT_PROJECT_ID, conversation.projectId.value)
                    agentId?.let { put(TOOL_CONTEXT_AGENT_DEFINITION_ID, it.value) }
                    put("aiCallDiagnosticId", invocationId)
                },
            ),
        )
        resolved.modelSpec.requireSupportsInputs(request.messages)
        fence()
        val response = runtimes.getRuntime(selection, null).call(request)
        fence()
        val blocks = response.messages.flatMap { it.content }
        check(blocks.none { it is ContentItem.ToolCall || it is ContentItem.ToolResult || it is ContentItem.ContextCompactionResult }) {
            "Isolated AI call returned a tool operation or compaction instead of an answer"
        }
        val text = blocks.filterIsInstance<ContentItem.AssistantMessage>().joinToString("\n") { it.structured.fullText }
        var status = when (response.outcome) {
            AiStepOutcome.COMPLETE -> "complete"
            AiStepOutcome.REFUSED -> "refused"
            AiStepOutcome.INCOMPLETE -> "incomplete"
            else -> "failed"
        }
        var code: String? = if (status == "complete") null else "AI_CALL_${status.uppercase()}"
        var output: JsonElement = JsonPrimitive(text)
        if (status == "complete" && text.isBlank()) { status = "failed"; code = "AI_CALL_EMPTY_RESPONSE" }
        if (status == "complete" && validator != null) {
            val parsed = try {
                strictJson.readTree(text)
                Json.parseToJsonElement(text).takeIf { validator.validate(it) { } }
            } catch (_: Exception) { null }
            if (parsed == null) { status = "invalid_output"; code = "AI_CALL_INVALID_OUTPUT" }
            else output = parsed
        }
        if (!input.artifactResult) return AiCallResult(invocationId, input.modelConfigurationId, status, output, null, response.usage, code)
        val jsonOutput = validator != null && status == "complete"
        val upload = ArtifactUpload("$invocationId.${if (jsonOutput) "json" else "txt"}".replace(':', '-'),
            if (jsonOutput) "application/json" else "text/plain", (if (jsonOutput) output.toString() else text).encodeToByteArray(), Artifact.Purpose.TOOL_OUTPUT)
        val artifact = artifacts.upload(conversation, actorId, upload)
        artifactRepository.commit(listOf(artifact.id), Clock.System.now())
        return AiCallResult(invocationId, input.modelConfigurationId, status, null, artifact.reference(), response.usage, code)
    }

    private suspend fun authorize(actor: User.Id, conversationId: Conversation.Id, agent: AgentDefinition.Id?): Conversation {
        val user = requireNotNull(users.findActiveById(actor)) { "An active user is required" }
        require(user.canUseAi) { "AI access is not allowed" }
        val conversation = requireNotNull(conversations.findById(conversationId)) { "Conversation is unavailable" }
        projects.requirePermission(actor, conversation.projectId, ProjectPermission.READ)
        require(Conversation.Participant.User(actor) in conversation.participants) { "Conversation is unavailable" }
        require(agent == null || Conversation.Participant.Agent(agent) in conversation.participants) { "Calling Agent is not in the conversation" }
        return conversation
    }

    private fun contentPart(bytes: ByteArray, name: String, media: String): ContentItem {
        val type = media.substringBefore(';').trim().lowercase()
        return when {
            type in setOf("image/png", "image/jpeg", "image/webp", "image/gif") ->
                ContentItem.ImageItem(Conversation.Message.ImageSource.Base64ImageSource(BinaryContent.fromBytes(bytes).base64, type))
            type == "application/pdf" -> ContentItem.DocumentItem(
                Conversation.Message.DocumentSource.Base64DocumentSource(BinaryContent.fromBytes(bytes).base64, type, name))
            type.startsWith("text/") || type in setOf("application/json", "application/xml", "application/yaml", "application/x-ndjson") -> {
                val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (_: CharacterCodingException) { throw IllegalArgumentException("Text input must be valid UTF-8") }
                ContentItem.UserMessage(text)
            }
            else -> throw IllegalArgumentException("Unsupported input media type: $type")
        }
    }

    private fun requireLocalSchema(value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, child) ->
                if (key == "\$ref" || key == "\$dynamicRef") require(child is JsonPrimitive && child.isString && child.content.startsWith('#')) {
                    "response_schema permits only local references"
                }
                requireLocalSchema(child)
            }
            is JsonArray -> value.forEach(::requireLocalSchema)
            else -> Unit
        }
    }

    private fun mediaTypeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "pdf" -> "application/pdf"
        else -> "text/plain"
    }

    companion object {
        const val MAX_PROMPT_CHARACTERS = 64_000
        const val MAX_INPUTS = 32
        const val MAX_INPUT_BYTES = 12L * 1024 * 1024
        const val MAX_SCHEMA_BYTES = 256 * 1024
        private val strictJson = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    }
}
