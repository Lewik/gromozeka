package com.gromozeka.server.testsupport.llm

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiResponseFormat
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.infrastructure.ai.runtime.AiRuntimeBackend
import com.gromozeka.shared.utils.sha256
import klog.KLoggers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class CassetteAiRuntimeProvider(
    private val backends: List<AiRuntimeBackend>,
    private val settings: AiRuntimeCassetteSettings = AiRuntimeCassetteSettings.fromSystemProperties(),
) : AiRuntimeProvider {
    override fun getRuntime(
        provider: AIProvider,
        modelName: String,
        projectPath: String?,
    ): AiRuntime {
        val backend = backends.firstOrNull { it.supports(provider) }
            ?: error("No AI runtime backend registered for provider $provider")
        val runtime = backend.createRuntime(provider, modelName, projectPath)
        if (settings.mode == AiRuntimeCassetteMode.OFF) return runtime

        return CassetteAiRuntime(
            delegate = runtime,
            store = AiRuntimeCassetteStore(settings.rootDirectory),
            provider = provider.name,
            modelName = modelName,
            projectPath = projectPath,
            mode = settings.mode,
        )
    }
}

internal data class AiRuntimeCassetteSettings(
    val mode: AiRuntimeCassetteMode,
    val rootDirectory: Path,
) {
    companion object {
        private const val MODE_PROPERTY = "gromozeka.llm.cassette.mode"
        private const val DIRECTORY_PROPERTY = "gromozeka.llm.cassette.dir"

        fun fromSystemProperties(): AiRuntimeCassetteSettings {
            val modeProperty = System.getProperty(MODE_PROPERTY)
            val testMode = isTestMode()
            val mode = if (modeProperty == null && testMode) {
                AiRuntimeCassetteMode.REPLAY_ONLY
            } else {
                AiRuntimeCassetteMode.parse(modeProperty)
            }
            val rootDirectory = System.getProperty(DIRECTORY_PROPERTY)
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)
                ?: defaultRootDirectory()
            return AiRuntimeCassetteSettings(mode = mode, rootDirectory = rootDirectory)
        }

        private fun defaultRootDirectory(): Path {
            if (isTestMode()) return Path.of("server", "src", "test", "resources", "llm-cassettes")
            return determineGromozekaHome().resolve("llm-cassettes")
        }

        private fun isTestMode(): Boolean {
            return when ((System.getProperty("GROMOZEKA_MODE") ?: System.getenv("GROMOZEKA_MODE"))?.lowercase()) {
                "test", "e2e" -> true
                else -> System.getProperty("gromozeka.memory.e2e") == "true"
            }
        }

        private fun determineGromozekaHome(): Path {
            val customHome = System.getProperty("GROMOZEKA_HOME")
                ?: System.getenv("GROMOZEKA_HOME")
            if (!customHome.isNullOrBlank()) return Path.of(customHome)

            val mode = System.getProperty("GROMOZEKA_MODE")
                ?: System.getenv("GROMOZEKA_MODE")
            if (mode.equals("dev", ignoreCase = true) || mode.equals("development", ignoreCase = true)) {
                val projectRoot = System.getProperty("gromozeka.project.root")
                    ?: error("DEV mode requires 'gromozeka.project.root' system property to resolve cassette directory")
                return Path.of(projectRoot, "dev-data", "client", ".gromozeka")
            }

            return Path.of(System.getProperty("user.home"), ".gromozeka")
        }
    }
}

internal enum class AiRuntimeCassetteMode {
    OFF,
    REPLAY_ONLY,
    RECORD_MISSING,
    REFRESH;

    companion object {
        fun parse(value: String?): AiRuntimeCassetteMode {
            return when (value?.trim()?.lowercase()) {
                null, "", "off", "false", "disabled" -> OFF
                "replay", "replay-only", "replay_only" -> REPLAY_ONLY
                "record", "record-missing", "record_missing", "true", "on" -> RECORD_MISSING
                "refresh", "overwrite" -> REFRESH
                else -> error(
                    "Unsupported LLM cassette mode '$value'. Use off, replay-only, record-missing, or refresh."
                )
            }
        }
    }
}

private class CassetteAiRuntime(
    private val delegate: AiRuntime,
    private val store: AiRuntimeCassetteStore,
    private val provider: String,
    private val modelName: String,
    private val projectPath: String?,
    private val mode: AiRuntimeCassetteMode,
) : AiRuntime {
    private val log = KLoggers.logger(this)
    override val capabilities: AiRuntimeCapabilities
        get() = delegate.capabilities

    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
        val key = store.keyFor(provider, modelName, projectPath, AiRuntimeCassetteOperation.CALL, request)
        val existing = store.read(key)

        if (existing != null && mode != AiRuntimeCassetteMode.REFRESH) {
            AiRuntimeCassetteUsageRegistry.markUsed(key, AiRuntimeCassetteAccess.REPLAY)
            log.info { "LLM cassette replay: provider=$provider model=$modelName operation=call hash=${key.hash}" }
            return existing.singleResponse(AiRuntimeCassetteReplayContext.from(key, request))
        }
        if (mode == AiRuntimeCassetteMode.REPLAY_ONLY) {
            error("LLM cassette miss: provider=$provider model=$modelName operation=call hash=${key.hash} path=${key.path}")
        }

        log.info { "LLM cassette record: provider=$provider model=$modelName operation=call hash=${key.hash}" }
        val response = delegate.call(request)
        store.write(key, listOf(response))
        AiRuntimeCassetteUsageRegistry.markUsed(key, if (mode == AiRuntimeCassetteMode.REFRESH) AiRuntimeCassetteAccess.REFRESH else AiRuntimeCassetteAccess.RECORD)
        return response
    }

    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> {
        val key = store.keyFor(provider, modelName, projectPath, AiRuntimeCassetteOperation.STREAM, request)
        val existing = store.read(key)

        if (existing != null && mode != AiRuntimeCassetteMode.REFRESH) {
            AiRuntimeCassetteUsageRegistry.markUsed(key, AiRuntimeCassetteAccess.REPLAY)
            log.info { "LLM cassette replay: provider=$provider model=$modelName operation=stream hash=${key.hash}" }
            return existing.responses(AiRuntimeCassetteReplayContext.from(key, request)).asFlow()
        }
        if (mode == AiRuntimeCassetteMode.REPLAY_ONLY) {
            error("LLM cassette miss: provider=$provider model=$modelName operation=stream hash=${key.hash} path=${key.path}")
        }

        return kotlinx.coroutines.flow.flow {
            log.info { "LLM cassette record: provider=$provider model=$modelName operation=stream hash=${key.hash}" }
            val responses = delegate.stream(request).toList()
            store.write(key, responses)
            AiRuntimeCassetteUsageRegistry.markUsed(key, if (mode == AiRuntimeCassetteMode.REFRESH) AiRuntimeCassetteAccess.REFRESH else AiRuntimeCassetteAccess.RECORD)
            responses.forEach { emit(it) }
        }
    }
}

internal enum class AiRuntimeCassetteAccess {
    REPLAY,
    RECORD,
    REFRESH,
}

internal data class AiRuntimeCassetteUsage(
    val provider: String,
    val modelName: String,
    val operation: AiRuntimeCassetteOperation,
    val hash: String,
    val path: Path,
    val accesses: Set<AiRuntimeCassetteAccess>,
)

internal object AiRuntimeCassetteUsageRegistry {
    private val usedByPath = ConcurrentHashMap<String, AiRuntimeCassetteUsage>()

    fun reset() {
        usedByPath.clear()
    }

    fun markUsed(
        key: AiRuntimeCassetteKey,
        access: AiRuntimeCassetteAccess,
    ) {
        val normalizedPath = key.path.toAbsolutePath().normalize()
        val usage = AiRuntimeCassetteUsage(
            provider = key.provider,
            modelName = key.modelName,
            operation = key.operation,
            hash = key.hash,
            path = normalizedPath,
            accesses = setOf(access),
        )
        usedByPath.merge(normalizedPath.toString(), usage) { old, new ->
            old.copy(accesses = old.accesses + new.accesses)
        }
    }

    fun snapshot(): List<AiRuntimeCassetteUsage> =
        usedByPath.values.sortedWith(compareBy<AiRuntimeCassetteUsage> { it.provider }.thenBy { it.modelName }.thenBy { it.operation.serialName }.thenBy { it.hash })
}

internal data class AiRuntimeCassetteUsageReport(
    val reportPath: Path,
    val rootDirectory: Path,
    val usedCount: Int,
    val diskCount: Int,
    val unusedCount: Int,
    val missingUsedCount: Int,
    val deletedCount: Int,
)

internal object AiRuntimeCassetteUsageReporter {
    private const val REPORT_UNUSED_PROPERTY = "gromozeka.llm.cassette.reportUnused"
    private const val DELETE_UNUSED_PROPERTY = "gromozeka.llm.cassette.deleteUnused"

    fun writeReportIfEnabled(
        settings: AiRuntimeCassetteSettings,
        artifactDirectory: Path,
    ): AiRuntimeCassetteUsageReport? {
        val reportUnused = java.lang.Boolean.getBoolean(REPORT_UNUSED_PROPERTY)
        val deleteUnused = java.lang.Boolean.getBoolean(DELETE_UNUSED_PROPERTY)
        if (!reportUnused && !deleteUnused) return null

        return writeReport(
            rootDirectory = settings.rootDirectory,
            artifactDirectory = artifactDirectory,
            deleteUnused = deleteUnused,
        )
    }

    fun writeReport(
        rootDirectory: Path,
        artifactDirectory: Path,
        deleteUnused: Boolean,
    ): AiRuntimeCassetteUsageReport {
        artifactDirectory.createDirectories()
        val root = rootDirectory.toAbsolutePath().normalize()
        val used = AiRuntimeCassetteUsageRegistry.snapshot()
            .filter { it.path.startsWith(root) }
        val usedPaths = used.mapTo(mutableSetOf()) { it.path.toAbsolutePath().normalize() }
        val diskPaths = listCassetteFiles(root)
        val diskPathSet = diskPaths.toSet()
        val unusedPaths = diskPaths.filterNot(usedPaths::contains)
        val missingUsedPaths = usedPaths.filterNot(diskPathSet::contains).sortedBy { it.toString() }
        val deletedPaths = if (deleteUnused) {
            unusedPaths.filter { Files.deleteIfExists(it) }
        } else {
            emptyList()
        }
        val reportPath = artifactDirectory.resolve("llm-cassette-usage.md")

        reportPath.writeText(
            renderReport(
                root = root,
                used = used,
                diskPaths = diskPaths,
                unusedPaths = unusedPaths,
                missingUsedPaths = missingUsedPaths,
                deletedPaths = deletedPaths,
                deleteUnused = deleteUnused,
            )
        )

        return AiRuntimeCassetteUsageReport(
            reportPath = reportPath,
            rootDirectory = root,
            usedCount = used.size,
            diskCount = diskPaths.size,
            unusedCount = unusedPaths.size,
            missingUsedCount = missingUsedPaths.size,
            deletedCount = deletedPaths.size,
        )
    }

    private fun renderReport(
        root: Path,
        used: List<AiRuntimeCassetteUsage>,
        diskPaths: List<Path>,
        unusedPaths: List<Path>,
        missingUsedPaths: List<Path>,
        deletedPaths: List<Path>,
        deleteUnused: Boolean,
    ): String = buildString {
        appendLine("# LLM Cassette Usage")
        appendLine()
        appendLine("root | $root")
        appendLine("used | ${used.size}")
        appendLine("disk | ${diskPaths.size}")
        appendLine("unused | ${unusedPaths.size}")
        appendLine("missingUsed | ${missingUsedPaths.size}")
        appendLine("deleteUnused | $deleteUnused")
        appendLine("deleted | ${deletedPaths.size}")
        appendLine()
        appendLine("## Unused Cassettes")
        appendLine(unusedPaths.joinToString("\n") { "- ${root.relativeReportPath(it)}" }.ifBlank { "- none" })
        appendLine()
        appendLine("## Delete Commands")
        appendLine(unusedPaths.joinToString("\n") { "rm -- '${it.toString().replace("'", "'\"'\"'")}'" }.ifBlank { "- none" })
        appendLine()
        appendLine("## Used Cassettes")
        appendLine(
            used.joinToString("\n") {
                "- ${root.relativeReportPath(it.path)} accesses=${it.accesses.joinToString(",") { access -> access.name.lowercase() }} provider=${it.provider} model=${it.modelName} operation=${it.operation.serialName} hash=${it.hash}"
            }.ifBlank { "- none" }
        )
        appendLine()
        appendLine("## Missing Used Cassettes")
        appendLine(missingUsedPaths.joinToString("\n") { "- ${root.relativeReportPath(it)}" }.ifBlank { "- none" })
        appendLine()
        appendLine("## Deleted Cassettes")
        appendLine(deletedPaths.joinToString("\n") { "- ${root.relativeReportPath(it)}" }.ifBlank { "- none" })
    }

    private fun listCassetteFiles(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        val files = mutableListOf<Path>()
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".json")) {
                    files.add(path.toAbsolutePath().normalize())
                }
            }
        }
        return files.sortedBy { it.toString() }
    }

    private fun Path.relativeReportPath(path: Path): String {
        val normalizedPath = path.toAbsolutePath().normalize()
        return runCatching { relativize(normalizedPath).toString() }.getOrElse { normalizedPath.toString() }
    }
}

internal class AiRuntimeCassetteStore(
    private val rootDirectory: Path,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val canonicalJson = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun keyFor(
        provider: String,
        modelName: String,
        projectPath: String?,
        operation: AiRuntimeCassetteOperation,
        request: AiRuntimeRequest,
    ): AiRuntimeCassetteKey {
        val runtimeBindings = AiRuntimeCassetteRuntimeBindings.from(request)
        val requestSnapshot = request.toCassetteRequestSnapshot(
            normalize = true,
            includeDebugFields = false,
            runtimeBindings = runtimeBindings,
        )
        val debugRequest = json.encodeToJsonElement(
            request.toCassetteRequestSnapshot(
                normalize = true,
                includeDebugFields = true,
                runtimeBindings = runtimeBindings,
            )
        )
        val canonicalRequest = canonicalizeJson(json.encodeToJsonElement(requestSnapshot))
        val fingerprintPayload = buildJsonObject {
            put("version", CASSETTE_VERSION)
            put("provider", provider)
            put("model", modelName)
            put("operation", operation.serialName)
            put("request", canonicalRequest)
        }
        val hash = canonicalJson.encodeToString(canonicalizeJson(fingerprintPayload)).sha256()
        val path = rootDirectory
            .resolve(provider.sanitizePathPart())
            .resolve(modelName.sanitizePathPart())
            .resolve(operation.serialName)
            .resolve("$hash.json")
        return AiRuntimeCassetteKey(
            provider = provider,
            modelName = modelName,
            projectPath = projectPath,
            operation = operation,
            hash = hash,
            canonicalRequest = canonicalRequest,
            debugRequest = debugRequest,
            runtimeBindings = runtimeBindings,
            path = path,
        )
    }

    fun read(key: AiRuntimeCassetteKey): AiRuntimeCassetteFile? {
        if (!key.path.exists()) return null
        val file = json.decodeFromString<AiRuntimeCassetteFile>(key.path.readText())
        require(file.hash == key.hash) {
            "LLM cassette hash mismatch: expected ${key.hash}, file has ${file.hash}, path=${key.path}"
        }
        require(file.version == CASSETTE_VERSION) {
            "Unsupported LLM cassette version ${file.version} at ${key.path}"
        }
        return file
    }

    fun write(
        key: AiRuntimeCassetteKey,
        responses: List<AiRuntimeResponse>,
    ) {
        require(responses.isNotEmpty()) { "Cannot record an empty LLM cassette response list: ${key.path}" }
        key.path.parent.createDirectories()
        val file = AiRuntimeCassetteFile(
            version = CASSETTE_VERSION,
            hash = key.hash,
            provider = key.provider,
            model = key.modelName,
            operation = key.operation.serialName,
            recordedAt = STABLE_INSTANT,
            projectPath = key.projectPath?.let(::normalizeRuntimeText),
            canonicalRequest = key.canonicalRequest,
            debugRequest = key.debugRequest,
            responses = responses.map { it.toStableCassetteResponse(key.runtimeBindings).toCassetteResponseSnapshot() },
        )
        val tmpPath = key.path.resolveSibling("${key.path.fileName}.tmp")
        tmpPath.writeText(json.encodeToString(file))
        Files.move(
            tmpPath,
            key.path,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        const val CASSETTE_VERSION = 1
    }
}

internal data class AiRuntimeCassetteKey(
    val provider: String,
    val modelName: String,
    val projectPath: String?,
    val operation: AiRuntimeCassetteOperation,
    val hash: String,
    val canonicalRequest: JsonElement,
    val debugRequest: JsonElement,
    val runtimeBindings: AiRuntimeCassetteRuntimeBindings,
    val path: Path,
)

internal data class AiRuntimeCassetteReplayContext(
    val conversationKey: String,
    val currentInstant: String,
    val replaySuffix: String,
    val runtimeBindings: AiRuntimeCassetteRuntimeBindings,
) {
    fun responseId(): String = "cassette_response_$replaySuffix"

    fun messageId(messageIndex: Int): String = "cassette_message_${replaySuffix}_$messageIndex"

    fun toolCallId(messageIndex: Int, contentIndex: Int): String =
        "cassette_tool_call_${replaySuffix}_${messageIndex}_$contentIndex"

    companion object {
        fun from(
            key: AiRuntimeCassetteKey,
            request: AiRuntimeRequest,
        ): AiRuntimeCassetteReplayContext {
            val conversationKey = request.options.toolContext["conversationId"] as? String
                ?: request.options.toolContext["promptCacheKey"] as? String
                ?: "cassette:${key.hash.take(16)}"
            return AiRuntimeCassetteReplayContext(
                conversationKey = conversationKey,
                currentInstant = Clock.System.now().toString(),
                replaySuffix = key.hash.take(16),
                runtimeBindings = AiRuntimeCassetteRuntimeBindings.from(request),
            )
        }
    }
}

internal data class AiRuntimeCassetteRuntimeBindings(
    val actualToPlaceholder: Map<String, String>,
    val placeholderToActual: Map<String, String>,
) {
    fun normalizeText(value: String): String {
        return actualToPlaceholder.entries
            .sortedByDescending { it.key.length }
            .fold(value) { text, (actual, placeholder) -> text.replace(actual, placeholder) }
    }

    fun rehydrateText(value: String): String {
        return placeholderToActual.entries
            .sortedByDescending { it.key.length }
            .fold(value) { text, (placeholder, actual) -> text.replace(placeholder, actual) }
    }

    companion object {
        val EMPTY = AiRuntimeCassetteRuntimeBindings(emptyMap(), emptyMap())

        fun from(request: AiRuntimeRequest): AiRuntimeCassetteRuntimeBindings {
            val requestText = buildString {
                request.systemPrompts.forEach { appendLine(it) }
                request.messages.forEach { message ->
                    message.content.forEach { item ->
                        appendLine(item.extractCassetteRuntimeText())
                    }
                    message.instructions.forEach { instruction ->
                        appendLine(cassetteJson.encodeToString(Conversation.Message.Instruction.serializer(), instruction))
                    }
                    appendLine(message.id.value)
                    appendLine(message.conversationId.value)
                }
                request.options.toolContext.values.forEach { value ->
                    if (value is String) appendLine(value)
                }
            }

            val counters = mutableMapOf<String, Int>()
            val actualToPlaceholder = runtimeScopedIdRegex.findAll(requestText)
                .map { it.value }
                .distinct()
                .associateWith { actual ->
                    val prefix = actual.substringBefore(":")
                    val index = counters.getOrDefault(prefix, 0)
                    counters[prefix] = index + 1
                    "$prefix:<$index>"
                }
            return AiRuntimeCassetteRuntimeBindings(
                actualToPlaceholder = actualToPlaceholder,
                placeholderToActual = actualToPlaceholder.entries.associate { (actual, placeholder) -> placeholder to actual },
            )
        }
    }
}

internal enum class AiRuntimeCassetteOperation(val serialName: String) {
    CALL("call"),
    STREAM("stream"),
}

@Serializable
internal data class AiRuntimeCassetteFile(
    val version: Int,
    val hash: String,
    val provider: String,
    val model: String,
    val operation: String,
    val recordedAt: String,
    val projectPath: String?,
    val canonicalRequest: JsonElement,
    val debugRequest: JsonElement,
    val responses: List<AiRuntimeResponseSnapshot>,
) {
    fun singleResponse(context: AiRuntimeCassetteReplayContext): AiRuntimeResponse {
        require(responses.size == 1) {
            "Call cassette must contain exactly one response, got ${responses.size}"
        }
        return responses.single().toRuntimeResponse(context)
    }

    fun responses(context: AiRuntimeCassetteReplayContext): List<AiRuntimeResponse> =
        responses.map { it.toRuntimeResponse(context) }
}

@Serializable
internal data class AiRuntimeRequestSnapshot(
    val systemPrompts: List<String>,
    val messages: List<ConversationMessageSnapshot>,
    val tools: List<AiToolSnapshot>,
    val options: AiRuntimeOptionsSnapshot,
)

@Serializable
internal data class ConversationMessageSnapshot(
    val id: String? = null,
    val conversationId: String? = null,
    val createdAt: String? = null,
    val role: String,
    val content: List<JsonElement>,
    val instructions: List<JsonElement>,
    val providerMetadata: JsonObject? = null,
    val error: Conversation.Message.GenerationError? = null,
)

@Serializable
internal data class AiToolSnapshot(
    val name: String,
    val description: String,
    val inputSchema: String,
    val returnDirect: Boolean,
)

@Serializable
internal data class AiRuntimeOptionsSnapshot(
    val maxTokens: Int?,
    val thinking: JsonElement?,
    val outputConfig: JsonElement?,
    val autoCompactionThreshold: Int?,
    val toolChoice: JsonElement,
    val responseFormat: JsonElement,
    val toolContext: JsonObject,
)

@Serializable
internal data class AiRuntimeResponseSnapshot(
    val messages: List<AiAssistantMessageSnapshot>,
    val usage: AiUsage? = null,
    val finishReason: String? = null,
    val providerMetadata: JsonObject = JsonObject(emptyMap()),
) {
    fun toRuntimeResponse(context: AiRuntimeCassetteReplayContext): AiRuntimeResponse {
        return AiRuntimeResponse(
            messages = messages.mapIndexed { index, message -> message.toAssistantMessage(context, index) },
            usage = usage,
            finishReason = finishReason,
            providerMetadata = providerMetadata.toPlainMap().rehydrateDynamicMetadata(context),
        )
    }
}

@Serializable
internal data class AiAssistantMessageSnapshot(
    val content: List<JsonElement>,
    val metadata: JsonObject = JsonObject(emptyMap()),
) {
    fun toAssistantMessage(
        context: AiRuntimeCassetteReplayContext,
        messageIndex: Int,
    ): AiAssistantMessage {
        return AiAssistantMessage(
            content = content.mapIndexed { contentIndex, item ->
                cassetteJson.decodeFromJsonElement(Conversation.Message.ContentItem.serializer(), item)
                    .rehydrateDynamicContentItem(context, messageIndex, contentIndex)
            },
            metadata = metadata.toPlainMap().rehydrateDynamicMetadata(context, messageIndex),
        )
    }
}

private fun AiRuntimeRequest.toCassetteRequestSnapshot(
    normalize: Boolean,
    includeDebugFields: Boolean,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): AiRuntimeRequestSnapshot {
    return AiRuntimeRequestSnapshot(
        systemPrompts = systemPrompts.map { it.maybeNormalizeRuntimeText(normalize, runtimeBindings) },
        messages = messages.map { it.toCassetteMessageSnapshot(normalize, includeDebugFields, runtimeBindings) },
        tools = tools.map { it.toCassetteToolSnapshot(runtimeBindings) }.sortedBy { it.name },
        options = options.toCassetteOptionsSnapshot(normalize, runtimeBindings),
    )
}

private fun Conversation.Message.toCassetteMessageSnapshot(
    normalize: Boolean,
    includeDebugFields: Boolean,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): ConversationMessageSnapshot {
    return ConversationMessageSnapshot(
        id = id.value.takeIf { includeDebugFields }?.let { if (normalize) STABLE_MESSAGE_ID else it },
        conversationId = conversationId.value.takeIf { includeDebugFields }
            ?.let { if (normalize) STABLE_CONVERSATION_KEY else it },
        createdAt = createdAt.toString().takeIf { includeDebugFields }
            ?.let { if (normalize) STABLE_INSTANT else it },
        role = role.name,
        content = content.map { cassetteJson.encodeToJsonElement(Conversation.Message.ContentItem.serializer(), it) }
            .map { it.maybeNormalize(normalize, runtimeBindings) },
        instructions = instructions.map { cassetteJson.encodeToJsonElement(Conversation.Message.Instruction.serializer(), it) }
            .map { it.maybeNormalize(normalize, runtimeBindings) },
        providerMetadata = providerMetadata
            .takeIf { includeDebugFields }
            ?.let { if (normalize) normalizeJsonElement(it, runtimeBindings) as JsonObject else it },
        error = error,
    )
}

private fun AiToolCallback.toCassetteToolSnapshot(runtimeBindings: AiRuntimeCassetteRuntimeBindings): AiToolSnapshot {
    return AiToolSnapshot(
        name = definition.name,
        description = normalizeRuntimeText(definition.description, runtimeBindings),
        inputSchema = canonicalizeToolSchema(definition.inputSchema, runtimeBindings),
        returnDirect = metadata.returnDirect,
    )
}

private fun AiRuntimeOptions.toCassetteOptionsSnapshot(
    normalize: Boolean,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): AiRuntimeOptionsSnapshot {
    return AiRuntimeOptionsSnapshot(
        maxTokens = maxTokens,
        thinking = thinking?.let { cassetteJson.encodeToJsonElement(AgentDefinition.ThinkingConfig.serializer(), it) },
        outputConfig = outputConfig?.let { cassetteJson.encodeToJsonElement(AgentDefinition.OutputConfig.serializer(), it) },
        autoCompactionThreshold = autoCompactionThresholdTokens,
        toolChoice = toolChoice.toCassetteJson(),
        responseFormat = responseFormat.toCassetteJson(runtimeBindings),
        toolContext = JsonObject(
            toolContext
                .mapNotNull { (key, value) -> value.toJsonElement(normalize, key, runtimeBindings)?.let { key to it } }
                .toMap()
                .toSortedMap()
        ),
    )
}

private fun AiToolChoice.toCassetteJson(): JsonElement {
    return when (this) {
        AiToolChoice.Auto -> buildJsonObject { put("type", "auto") }
        AiToolChoice.None -> buildJsonObject { put("type", "none") }
        AiToolChoice.RequiredAny -> buildJsonObject { put("type", "required_any") }
        is AiToolChoice.RequiredTool -> buildJsonObject {
            put("type", "required_tool")
            put("name", name)
        }
    }
}

private fun AiResponseFormat.toCassetteJson(runtimeBindings: AiRuntimeCassetteRuntimeBindings): JsonElement {
    return when (this) {
        AiResponseFormat.Text -> buildJsonObject { put("type", "text") }
        is AiResponseFormat.JsonSchema -> buildJsonObject {
            put("type", "json_schema")
            put("name", name)
            put("schema", canonicalizeJson(schema))
            description?.let { put("description", normalizeRuntimeText(it, runtimeBindings)) }
            put("strict", strict)
        }
    }
}

private fun Conversation.Message.ContentItem.extractCassetteRuntimeText(): String {
    return when (this) {
        is Conversation.Message.ContentItem.UserMessage -> text
        is Conversation.Message.ContentItem.ToolCall -> buildString {
            appendLine(id.value)
            appendLine(call.name)
            appendLine(compactCassetteJson.encodeToString(call.input))
        }
        is Conversation.Message.ContentItem.ToolResult -> buildString {
            appendLine(toolUseId.value)
            appendLine(toolName)
            result.forEach { appendLine(it.extractCassetteRuntimeText()) }
        }
        is Conversation.Message.ContentItem.Thinking -> buildString {
            appendLine(thinking)
            signature?.let(::appendLine)
        }
        is Conversation.Message.ContentItem.System -> buildString {
            appendLine(content)
            toolUseId?.value?.let(::appendLine)
        }
        is Conversation.Message.ContentItem.AssistantMessage -> structured.fullText
        is Conversation.Message.ContentItem.ImageItem -> source.extractCassetteRuntimeText()
        is Conversation.Message.ContentItem.UnknownJson -> compactCassetteJson.encodeToString(json)
    }
}

private fun Conversation.Message.ContentItem.ToolResult.Data.extractCassetteRuntimeText(): String {
    return when (this) {
        is Conversation.Message.ContentItem.ToolResult.Data.Text -> content
        is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> mediaType.value
        is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> url
        is Conversation.Message.ContentItem.ToolResult.Data.FileData -> fileId
    }
}

private fun Conversation.Message.ImageSource.extractCassetteRuntimeText(): String {
    return when (this) {
        is Conversation.Message.ImageSource.Base64ImageSource -> mediaType
        is Conversation.Message.ImageSource.UrlImageSource -> url
        is Conversation.Message.ImageSource.FileImageSource -> fileId
    }
}

private fun AiRuntimeResponse.toStableCassetteResponse(runtimeBindings: AiRuntimeCassetteRuntimeBindings): AiRuntimeResponse {
    return copy(
        messages = messages.mapIndexed { index, message -> message.toStableCassetteAssistantMessage(index, runtimeBindings) },
        providerMetadata = providerMetadata.stabilizeDynamicMetadata(runtimeBindings = runtimeBindings),
    )
}

private fun AiAssistantMessage.toStableCassetteAssistantMessage(
    messageIndex: Int,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): AiAssistantMessage {
    return copy(
        content = content.mapIndexed { contentIndex, item ->
            item.toStableCassetteContentItem(messageIndex, contentIndex, runtimeBindings)
        },
        metadata = metadata.stabilizeDynamicMetadata(messageIndex, runtimeBindings),
    )
}

private fun Conversation.Message.ContentItem.toStableCassetteContentItem(
    messageIndex: Int,
    contentIndex: Int,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): Conversation.Message.ContentItem {
    return when (this) {
        is Conversation.Message.ContentItem.UserMessage -> copy(text = normalizeRuntimeText(text, runtimeBindings))
        is Conversation.Message.ContentItem.ToolCall -> copy(
            id = Conversation.Message.ContentItem.ToolCall.Id(stableToolCallId(messageIndex, contentIndex)),
            call = call.copy(input = normalizeJsonElement(call.input, runtimeBindings)),
        )
        is Conversation.Message.ContentItem.ToolResult -> copy(
            toolUseId = Conversation.Message.ContentItem.ToolCall.Id(stableToolCallId(messageIndex, contentIndex)),
            result = result.map { it.toStableCassetteToolResultData(runtimeBindings) },
        )
        is Conversation.Message.ContentItem.Thinking -> copy(
            thinking = normalizeRuntimeText(thinking, runtimeBindings),
            signature = signature?.let { STABLE_SIGNATURE },
        )
        is Conversation.Message.ContentItem.System -> copy(
            content = normalizeRuntimeText(content, runtimeBindings),
            toolUseId = toolUseId?.let { Conversation.Message.ContentItem.ToolCall.Id(stableToolCallId(messageIndex, contentIndex)) },
        )
        is Conversation.Message.ContentItem.AssistantMessage -> copy(
            structured = structured.toStableCassetteStructuredText(runtimeBindings),
        )
        is Conversation.Message.ContentItem.ImageItem -> copy(source = source.toStableCassetteImageSource(runtimeBindings))
        is Conversation.Message.ContentItem.UnknownJson -> copy(json = normalizeJsonElement(json, runtimeBindings))
    }
}

private fun Conversation.Message.ContentItem.ToolResult.Data.toStableCassetteToolResultData(
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
):
    Conversation.Message.ContentItem.ToolResult.Data {
    return when (this) {
        is Conversation.Message.ContentItem.ToolResult.Data.Text -> copy(content = normalizeRuntimeText(content, runtimeBindings))
        is Conversation.Message.ContentItem.ToolResult.Data.FileData -> copy(fileId = STABLE_FILE_ID)
        is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> copy(url = normalizeRuntimeText(url, runtimeBindings))
        is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> this
    }
}

private fun Conversation.Message.ImageSource.toStableCassetteImageSource(
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): Conversation.Message.ImageSource {
    return when (this) {
        is Conversation.Message.ImageSource.Base64ImageSource -> this
        is Conversation.Message.ImageSource.UrlImageSource -> copy(url = normalizeRuntimeText(url, runtimeBindings))
        is Conversation.Message.ImageSource.FileImageSource -> copy(fileId = STABLE_FILE_ID)
    }
}

private fun Conversation.Message.StructuredText.toStableCassetteStructuredText(
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): Conversation.Message.StructuredText {
    return copy(
        fullText = normalizeRuntimeStructuredOutputText(fullText, runtimeBindings),
        ttsText = ttsText?.let { normalizeRuntimeStructuredOutputText(it, runtimeBindings) },
    )
}

private fun Conversation.Message.ContentItem.rehydrateDynamicContentItem(
    context: AiRuntimeCassetteReplayContext,
    messageIndex: Int,
    contentIndex: Int,
): Conversation.Message.ContentItem {
    return when (this) {
        is Conversation.Message.ContentItem.UserMessage -> this
        is Conversation.Message.ContentItem.ToolCall -> copy(
            id = Conversation.Message.ContentItem.ToolCall.Id(rehydrateToolCallId(id.value, context, messageIndex, contentIndex)),
            call = call.copy(input = rehydrateJsonElement(call.input, context)),
        )
        is Conversation.Message.ContentItem.ToolResult -> copy(
            toolUseId = Conversation.Message.ContentItem.ToolCall.Id(
                rehydrateToolCallId(toolUseId.value, context, messageIndex, contentIndex)
            ),
            result = result.map { it.rehydrateDynamicToolResultData(context) },
        )
        is Conversation.Message.ContentItem.Thinking -> copy(
            signature = signature?.let { rehydrateProviderSignature(it, context, messageIndex, contentIndex) },
        )
        is Conversation.Message.ContentItem.System -> copy(
            toolUseId = toolUseId?.let {
                Conversation.Message.ContentItem.ToolCall.Id(
                    rehydrateToolCallId(it.value, context, messageIndex, contentIndex)
                )
            },
        )
        is Conversation.Message.ContentItem.AssistantMessage -> copy(
            structured = structured.rehydrateDynamicStructuredText(context),
        )
        is Conversation.Message.ContentItem.ImageItem -> copy(source = source.rehydrateDynamicImageSource(context))
        is Conversation.Message.ContentItem.UnknownJson -> copy(json = rehydrateJsonElement(json, context))
    }
}

private fun Conversation.Message.StructuredText.rehydrateDynamicStructuredText(
    context: AiRuntimeCassetteReplayContext,
): Conversation.Message.StructuredText {
    return copy(
        fullText = rehydrateRuntimeStructuredOutputText(fullText, context),
        ttsText = ttsText?.let { rehydrateRuntimeStructuredOutputText(it, context) },
    )
}

private fun Conversation.Message.ContentItem.ToolResult.Data.rehydrateDynamicToolResultData(
    context: AiRuntimeCassetteReplayContext,
): Conversation.Message.ContentItem.ToolResult.Data {
    return when (this) {
        is Conversation.Message.ContentItem.ToolResult.Data.Text -> this
        is Conversation.Message.ContentItem.ToolResult.Data.FileData ->
            if (fileId == STABLE_FILE_ID) copy(fileId = "cassette_file_${context.replaySuffix}") else this
        is Conversation.Message.ContentItem.ToolResult.Data.UrlData -> this
        is Conversation.Message.ContentItem.ToolResult.Data.Base64Data -> this
    }
}

private fun Conversation.Message.ImageSource.rehydrateDynamicImageSource(
    context: AiRuntimeCassetteReplayContext,
): Conversation.Message.ImageSource {
    return when (this) {
        is Conversation.Message.ImageSource.Base64ImageSource -> this
        is Conversation.Message.ImageSource.UrlImageSource -> this
        is Conversation.Message.ImageSource.FileImageSource ->
            if (fileId == STABLE_FILE_ID) copy(fileId = "cassette_file_${context.replaySuffix}") else this
    }
}

private fun AiRuntimeResponse.toCassetteResponseSnapshot(): AiRuntimeResponseSnapshot {
    return AiRuntimeResponseSnapshot(
        messages = messages.map { it.toCassetteAssistantMessageSnapshot() },
        usage = usage,
        finishReason = finishReason,
        providerMetadata = providerMetadata.toJsonObject(),
    )
}

private fun AiAssistantMessage.toCassetteAssistantMessageSnapshot(): AiAssistantMessageSnapshot {
    return AiAssistantMessageSnapshot(
        content = content.map { cassetteJson.encodeToJsonElement(Conversation.Message.ContentItem.serializer(), it) },
        metadata = metadata.toJsonObject(),
    )
}

private fun canonicalizeToolSchema(
    inputSchema: String,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): String {
    return runCatching {
        val parsed = cassetteJson.parseToJsonElement(inputSchema)
        compactCassetteJson.encodeToString(canonicalizeJson(parsed))
    }.getOrElse {
        normalizeRuntimeText(inputSchema, runtimeBindings)
    }
}

private fun canonicalizeJson(element: JsonElement): JsonElement {
    return when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonicalizeJson(it.value) })
        is JsonArray -> JsonArray(element.map(::canonicalizeJson))
        is JsonPrimitive -> element
        JsonNull -> JsonNull
    }
}

private fun normalizeJsonElement(
    element: JsonElement,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings = AiRuntimeCassetteRuntimeBindings.EMPTY,
    path: List<String> = emptyList(),
): JsonElement {
    return when (element) {
        is JsonObject -> JsonObject(
            element.toSortedMap().mapValues { (key, value) ->
                normalizeJsonElement(value, runtimeBindings, path + key)
            }
        )
        is JsonArray -> JsonArray(
            element.mapIndexed { index, value -> normalizeJsonElement(value, runtimeBindings, path + index.toString()) }
        )
        is JsonPrimitive -> if (element.isString) {
            JsonPrimitive(normalizeRuntimeJsonString(path, element.content, runtimeBindings))
        } else {
            element
        }
        JsonNull -> JsonNull
    }
}

private fun rehydrateJsonElement(
    element: JsonElement,
    context: AiRuntimeCassetteReplayContext,
    path: List<String> = emptyList(),
): JsonElement {
    return when (element) {
        is JsonObject -> JsonObject(
            element.toSortedMap().mapValues { (key, value) ->
                rehydrateJsonElement(value, context, path + key)
            }
        )
        is JsonArray -> JsonArray(element.mapIndexed { index, value -> rehydrateJsonElement(value, context, path + index.toString()) })
        is JsonPrimitive -> if (element.isString && path.isRuntimeFieldPath()) {
            JsonPrimitive(rehydrateRuntimeValueForField(path.last(), element.content, context))
        } else if (element.isString) {
            JsonPrimitive(context.runtimeBindings.rehydrateText(element.content))
        } else {
            element
        }
        JsonNull -> JsonNull
    }
}

private fun normalizeRuntimeStructuredOutputText(
    value: String,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): String {
    val normalizedText = normalizeRuntimeText(value, runtimeBindings)
    if (!normalizedText.trimStart().startsWith("{") && !normalizedText.trimStart().startsWith("[")) return normalizedText
    val runtimeDate = Clock.System.now().toString().take(10)
    return runCatching {
        val parsed = cassetteJson.parseToJsonElement(normalizedText)
        compactCassetteJson.encodeToString(
            normalizeRuntimeOutputJsonElement(
                element = parsed,
                runtimeBindings = runtimeBindings,
                runtimeDate = runtimeDate,
            )
        )
    }.getOrElse {
        normalizedText
    }
}

private fun normalizeRuntimeOutputJsonElement(
    element: JsonElement,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
    runtimeDate: String,
    path: List<String> = emptyList(),
): JsonElement {
    return when (element) {
        is JsonObject -> JsonObject(
            element.toSortedMap().mapValues { (key, value) ->
                normalizeRuntimeOutputJsonElement(value, runtimeBindings, runtimeDate, path + key)
            }
        )
        is JsonArray -> JsonArray(
            element.mapIndexed { index, value ->
                normalizeRuntimeOutputJsonElement(value, runtimeBindings, runtimeDate, path + index.toString())
            }
        )
        is JsonPrimitive -> if (element.isString) {
            val key = path.lastOrNull()
            val value = element.content
            JsonPrimitive(
                if (key in runtimeOutputInstantFieldNames && value.startsWith(runtimeDate)) {
                    STABLE_INSTANT
                } else {
                    normalizeRuntimeJsonString(path, value, runtimeBindings)
                }
            )
        } else {
            element
        }
        JsonNull -> JsonNull
    }
}

private fun rehydrateRuntimeStructuredOutputText(
    value: String,
    context: AiRuntimeCassetteReplayContext,
): String {
    val rehydratedText = context.runtimeBindings.rehydrateText(value)
    if (!rehydratedText.trimStart().startsWith("{") && !rehydratedText.trimStart().startsWith("[")) return rehydratedText
    return runCatching {
        val parsed = cassetteJson.parseToJsonElement(rehydratedText)
        compactCassetteJson.encodeToString(rehydrateRuntimeOutputJsonElement(parsed, context))
    }.getOrElse {
        rehydratedText
    }
}

private fun rehydrateRuntimeOutputJsonElement(
    element: JsonElement,
    context: AiRuntimeCassetteReplayContext,
    path: List<String> = emptyList(),
): JsonElement {
    return when (element) {
        is JsonObject -> JsonObject(
            element.toSortedMap().mapValues { (key, value) ->
                rehydrateRuntimeOutputJsonElement(value, context, path + key)
            }
        )
        is JsonArray -> JsonArray(
            element.mapIndexed { index, value -> rehydrateRuntimeOutputJsonElement(value, context, path + index.toString()) }
        )
        is JsonPrimitive -> if (element.isString) {
            val key = path.lastOrNull()
            val value = element.content
            JsonPrimitive(
                if (key in runtimeOutputInstantFieldNames && value == STABLE_INSTANT) {
                    context.currentInstant
                } else {
                    context.runtimeBindings.rehydrateText(value)
                }
            )
        } else {
            element
        }
        JsonNull -> JsonNull
    }
}

private fun JsonElement.maybeNormalize(
    normalize: Boolean,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): JsonElement {
    return if (normalize) normalizeJsonElement(this, runtimeBindings) else this
}

private const val STABLE_INSTANT = "1970-01-01T00:00:00Z"
private const val STABLE_ID = "<id>"
private const val STABLE_UUID = "<uuid>"
private const val STABLE_CONVERSATION_KEY = "<conversation-key>"
private const val STABLE_PROMPT_CACHE_KEY = "<prompt-cache-key>"
private const val STABLE_RESPONSE_ID = "resp_<id>"
private const val STABLE_MESSAGE_ID = "msg_<id>"
private const val STABLE_TOOL_CALL_ID_PREFIX = "tool-call"
private const val STABLE_FILE_ID = "file_<id>"
private const val STABLE_SIGNATURE = "<provider-signature>"
private const val STABLE_ENCRYPTED_CONTENT = "<encrypted-content>"

private val runtimeOutputInstantFieldNames = setOf(
    "valid_from",
    "valid_to",
    "created_at",
    "updated_at",
    "observed_at",
    "recorded_at",
    "validFrom",
    "validTo",
    "createdAt",
    "updatedAt",
    "observedAt",
    "recordedAt",
)

private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
private const val INSTANT_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z"
private val runtimeCurrentTimeRegex = Regex("(?m)(Current time:\\s*)$INSTANT_PATTERN")
private val runtimeLabeledInstantRegex = Regex(
    "\\b(validFrom|validTo|createdAt|updatedAt|observedAt|recordedAt|firstSeenAt|lastSeenAt|timestamp)=" +
        "($INSTANT_PATTERN|null)"
)
private val runtimeJsonInstantFieldRegex = Regex(
    "(\"(?:valid_from|valid_to|created_at|updated_at|observed_at|recorded_at|first_seen_at|last_seen_at|" +
        "validFrom|validTo|createdAt|updatedAt|observedAt|recordedAt|firstSeenAt|lastSeenAt|timestamp)\"\\s*:\\s*\")" +
        "($INSTANT_PATTERN|null)(\")"
)
private val runtimeScopedIdRegex =
    Regex("\\b(project|chat|memory|source|claim|note|task|entity|episode|profile|run):[A-Za-z0-9][A-Za-z0-9._:-]*")
private val runtimeTargetMessageIdRegex = Regex("(?m)(Target message id:\\s*)$UUID_PATTERN")
private val gromozekaE2eDirectoryRegex = Regex("(/[^\\s\"']*)?gromozeka-e2e-[0-9]+")

private fun normalizeRuntimeJsonString(
    path: List<String>,
    value: String,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): String {
    return if (path.isRuntimeFieldPath()) {
        stableRuntimeValueForField(path.last(), value)
    } else {
        normalizeRuntimeText(value, runtimeBindings)
    }
}

private fun List<String>.isRuntimeFieldPath(): Boolean {
    val key = lastOrNull() ?: return false
    val previousKey = dropLast(1).lastOrNull()

    if (key in setOf("target_message_id", "targetMessageId")) return true
    if ("input" in this) return false

    if (key == "id") return true
    if (key == "value" && previousKey in setOf("id", "conversationId", "toolUseId", "fileId")) return true
    return key in setOf(
        "id",
        "conversationId",
        "createdAt",
        "updatedAt",
        "observedAt",
        "recordedAt",
        "timestamp",
        "messageId",
        "responseId",
        "conversationKey",
        "promptCacheKey",
        "toolUseId",
        "fileId",
        "target_message_id",
        "targetMessageId",
        "targetTabId",
        "encrypted_content",
        "signature",
    )
}

private fun stableRuntimeValueForField(
    fieldName: String,
    value: String,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings = AiRuntimeCassetteRuntimeBindings.EMPTY,
): String {
    return when (fieldName) {
        "conversationId", "conversationKey" -> STABLE_CONVERSATION_KEY
        "promptCacheKey" -> STABLE_PROMPT_CACHE_KEY
        "createdAt", "updatedAt", "observedAt", "recordedAt", "timestamp" -> STABLE_INSTANT
        "responseId" -> STABLE_RESPONSE_ID
        "messageId" -> STABLE_MESSAGE_ID
        "toolUseId" -> STABLE_TOOL_CALL_ID_PREFIX
        "id" -> STABLE_ID
        "fileId" -> STABLE_FILE_ID
        "target_message_id", "targetMessageId" -> STABLE_UUID
        "targetTabId" -> STABLE_ID
        "encrypted_content" -> STABLE_ENCRYPTED_CONTENT
        "signature" -> STABLE_SIGNATURE
        "value" -> STABLE_ID
        else -> normalizeRuntimeText(value, runtimeBindings)
    }
}

private fun rehydrateRuntimeValueForField(
    fieldName: String,
    value: String,
    context: AiRuntimeCassetteReplayContext,
): String {
    return when (fieldName) {
        "conversationId", "conversationKey" -> context.conversationKey
        "promptCacheKey" -> context.conversationKey.removePrefix("memory:")
        "createdAt", "updatedAt", "observedAt", "recordedAt", "timestamp" -> context.currentInstant
        "responseId" -> context.responseId()
        "messageId" -> context.messageId(0)
        "toolUseId" -> rehydrateToolCallId(value, context, 0, 0)
        "id" -> if (value == STABLE_ID) "cassette_id_${context.replaySuffix}" else value
        "fileId" -> if (value == STABLE_FILE_ID) "cassette_file_${context.replaySuffix}" else value
        "target_message_id", "targetMessageId" -> if (value == STABLE_UUID) {
            "cassette_message_${context.replaySuffix}_target"
        } else {
            value
        }
        "targetTabId" -> if (value == STABLE_ID) "cassette_tab_${context.replaySuffix}" else value
        "encrypted_content" -> if (value == STABLE_ENCRYPTED_CONTENT) {
            "cassette_encrypted_content_${context.replaySuffix}"
        } else {
            value
        }
        "signature" -> rehydrateProviderSignature(value, context, 0, 0)
        "value" -> if (value == STABLE_ID) "cassette_id_${context.replaySuffix}" else value
        else -> value
    }
}

private fun stableToolCallId(
    messageIndex: Int,
    contentIndex: Int,
): String = "$STABLE_TOOL_CALL_ID_PREFIX:$messageIndex:$contentIndex"

private fun rehydrateToolCallId(
    value: String,
    context: AiRuntimeCassetteReplayContext,
    fallbackMessageIndex: Int,
    fallbackContentIndex: Int,
): String {
    if (!value.startsWith("$STABLE_TOOL_CALL_ID_PREFIX:")) {
        return if (value == STABLE_TOOL_CALL_ID_PREFIX) {
            context.toolCallId(fallbackMessageIndex, fallbackContentIndex)
        } else {
            value
        }
    }

    val parts = value.split(":")
    val messageIndex = parts.getOrNull(1)?.toIntOrNull() ?: fallbackMessageIndex
    val contentIndex = parts.getOrNull(2)?.toIntOrNull() ?: fallbackContentIndex
    return context.toolCallId(messageIndex, contentIndex)
}

private fun rehydrateProviderSignature(
    value: String,
    context: AiRuntimeCassetteReplayContext,
    messageIndex: Int,
    contentIndex: Int,
): String {
    return if (value == STABLE_SIGNATURE) {
        "cassette_signature_${context.replaySuffix}_${messageIndex}_$contentIndex"
    } else {
        value
    }
}

private fun normalizeRuntimeText(
    value: String,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings = AiRuntimeCassetteRuntimeBindings.EMPTY,
): String {
    return runtimeBindings.normalizeText(value)
        .replace(runtimeCurrentTimeRegex) { match -> "${match.groupValues[1]}<instant>" }
        .replace(runtimeLabeledInstantRegex) { match -> "${match.groupValues[1]}=$STABLE_INSTANT" }
        .replace(runtimeJsonInstantFieldRegex) { match -> "${match.groupValues[1]}$STABLE_INSTANT${match.groupValues[3]}" }
        .replace(runtimeTargetMessageIdRegex) { match -> "${match.groupValues[1]}$STABLE_UUID" }
        .replace(runtimeScopedIdRegex) { match ->
            val prefix = match.value.substringBefore(":")
            "$prefix:<id>"
        }
        .replace(gromozekaE2eDirectoryRegex) { match ->
            val suffix = match.value.substringAfterLast("gromozeka-e2e-")
            if (suffix.all { it.isDigit() }) "gromozeka-e2e-<id>" else match.value
        }
}

private fun String.maybeNormalizeRuntimeText(
    normalize: Boolean,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): String {
    return if (normalize) normalizeRuntimeText(this, runtimeBindings) else this
}

private fun Map<String, Any?>.stabilizeDynamicMetadata(
    messageIndex: Int? = null,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): Map<String, Any?> {
    return mapValues { (key, value) -> value.stabilizeDynamicMetadataValue(key, messageIndex, runtimeBindings) }
        .toSortedMap()
}

private fun Any?.stabilizeDynamicMetadataValue(
    key: String,
    messageIndex: Int?,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings,
): Any? {
    return when (this) {
        null -> null
        is JsonElement -> normalizeJsonElement(this, runtimeBindings)
        is Map<*, *> -> entries
            .mapNotNull { (nestedKey, nestedValue) ->
                (nestedKey as? String)?.let {
                    it to nestedValue.stabilizeDynamicMetadataValue(it, messageIndex, runtimeBindings)
                }
            }
            .toMap()
            .toSortedMap()
        is Iterable<*> -> map { it.stabilizeDynamicMetadataValue(key, messageIndex, runtimeBindings) }
        is Array<*> -> map { it.stabilizeDynamicMetadataValue(key, messageIndex, runtimeBindings) }
        is String -> when (key) {
            "conversationKey" -> STABLE_CONVERSATION_KEY
            "responseId" -> STABLE_RESPONSE_ID
            "messageId" -> STABLE_MESSAGE_ID
            "createdAt", "updatedAt", "observedAt", "recordedAt", "timestamp" -> STABLE_INSTANT
            "encrypted_content" -> STABLE_ENCRYPTED_CONTENT
            "signature" -> STABLE_SIGNATURE
            "fileId" -> STABLE_FILE_ID
            else -> normalizeRuntimeText(this, runtimeBindings)
        }
        else -> this
    }
}

private fun Map<String, Any?>.rehydrateDynamicMetadata(
    context: AiRuntimeCassetteReplayContext,
    messageIndex: Int? = null,
): Map<String, Any?> {
    return mapValues { (key, value) -> value.rehydrateDynamicMetadataValue(key, context, messageIndex) }
        .toSortedMap()
}

private fun Any?.rehydrateDynamicMetadataValue(
    key: String,
    context: AiRuntimeCassetteReplayContext,
    messageIndex: Int?,
): Any? {
    return when (this) {
        null -> null
        is JsonElement -> rehydrateJsonElement(this, context)
        is Map<*, *> -> entries
            .mapNotNull { (nestedKey, nestedValue) ->
                (nestedKey as? String)?.let { it to nestedValue.rehydrateDynamicMetadataValue(it, context, messageIndex) }
            }
            .toMap()
            .toSortedMap()
        is Iterable<*> -> map { it.rehydrateDynamicMetadataValue(key, context, messageIndex) }
        is Array<*> -> map { it.rehydrateDynamicMetadataValue(key, context, messageIndex) }
        is String -> when (key) {
            "conversationKey" -> context.conversationKey
            "responseId" -> context.responseId()
            "messageId" -> context.messageId(messageIndex ?: 0)
            "createdAt", "updatedAt", "observedAt", "recordedAt", "timestamp" -> context.currentInstant
            "encrypted_content" -> if (this == STABLE_ENCRYPTED_CONTENT) {
                "cassette_encrypted_content_${context.replaySuffix}"
            } else {
                this
            }
            "signature" -> rehydrateProviderSignature(this, context, messageIndex ?: 0, 0)
            "fileId" -> if (this == STABLE_FILE_ID) "cassette_file_${context.replaySuffix}" else this
            else -> this
        }
        else -> this
    }
}

private fun Map<String, Any?>.toJsonObject(): JsonObject {
    return JsonObject(
        mapNotNull {
            (key, value) -> value.toJsonElement(
                normalize = false,
                fieldName = key,
                runtimeBindings = AiRuntimeCassetteRuntimeBindings.EMPTY,
            )?.let { key to it }
        }
            .toMap()
            .toSortedMap()
    )
}

private fun Any?.toJsonElement(
    normalize: Boolean,
    fieldName: String? = null,
    runtimeBindings: AiRuntimeCassetteRuntimeBindings = AiRuntimeCassetteRuntimeBindings.EMPTY,
): JsonElement? {
    return when (this) {
        null -> JsonNull
        is JsonElement -> if (normalize) normalizeJsonElement(this, runtimeBindings) else this
        is String -> JsonPrimitive(if (normalize) stableRuntimeValueForField(fieldName.orEmpty(), this, runtimeBindings) else this)
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is Float -> JsonPrimitive(this)
        is Double -> JsonPrimitive(this)
        is Number -> JsonPrimitive(toString())
        is Map<*, *> -> JsonObject(
            entries
                .mapNotNull { (key, value) ->
                    (key as? String)?.let {
                        value.toJsonElement(normalize, key, runtimeBindings)?.let { jsonValue -> key to jsonValue }
                    }
                }
                .toMap()
                .toSortedMap()
        )
        is Iterable<*> -> JsonArray(mapNotNull { it.toJsonElement(normalize, runtimeBindings = runtimeBindings) })
        is Array<*> -> JsonArray(mapNotNull { it.toJsonElement(normalize, runtimeBindings = runtimeBindings) })
        else -> JsonPrimitive(if (normalize) stableRuntimeValueForField(fieldName.orEmpty(), toString(), runtimeBindings) else toString())
    }
}

private fun JsonObject.toPlainMap(): Map<String, Any?> {
    return mapValues { (_, value) -> value.toPlainValue() }
}

private fun JsonElement.toPlainValue(): Any? {
    return when (this) {
        JsonNull -> null
        is JsonObject -> toPlainMap()
        is JsonArray -> map { it.toPlainValue() }
        is JsonPrimitive -> when {
            isString -> content
            jsonPrimitive.contentOrNull == null -> null
            runCatching { boolean }.isSuccess -> boolean
            runCatching { int }.isSuccess -> int
            runCatching { long }.isSuccess -> long
            runCatching { double }.isSuccess -> double
            else -> content
        }
    }
}

private fun String.sanitizePathPart(): String {
    return replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "unknown" }
}

private val cassetteJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val compactCassetteJson = Json {
    encodeDefaults = true
    explicitNulls = true
}
