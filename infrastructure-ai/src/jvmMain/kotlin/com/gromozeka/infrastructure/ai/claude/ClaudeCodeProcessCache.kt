package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.ai.AiReasoningMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal class ProcessClaudeCodeCliExecutor(
    private val executableOverride: String? = null,
    processFactory: ClaudeCodeCliProcessFactory = DefaultClaudeCodeCliProcessFactory(),
    nanoTime: () -> Long = System::nanoTime,
) : ClaudeCodeCliExecutor, ClaudeCodeNativeToolExecutor, AutoCloseable {
    private val processCache = ClaudeCodeProcessCache(processFactory, nanoTime)

    override suspend fun execute(command: ClaudeCodeCommand): ClaudeCodeCliResponse {
        val effectiveCommand = executableOverride
            ?.let { command.copy(executablePath = it) }
            ?: command
        require(effectiveCommand.maxCachedProcesses > 0) {
            "Claude Code cached process limit must be positive"
        }
        require(effectiveCommand.processIdleTtlMinutes > 0) {
            "Claude Code process idle TTL must be positive"
        }

        return if (effectiveCommand.noSessionPersistence || effectiveCommand.cacheKey == null) {
            val process = processCache.startUncached(effectiveCommand)
            try {
                process.execute(effectiveCommand.userPrompt)
            } finally {
                withContext(NonCancellable) {
                    process.close()
                }
            }
        } else {
            val lease = processCache.acquire(effectiveCommand)
            var succeeded = false
            try {
                lease.process.execute(effectiveCommand.userPrompt).also { succeeded = true }
            } finally {
                withContext(NonCancellable) {
                    processCache.release(lease, succeeded)
                }
            }
        }
    }

    override suspend fun executeNativeTool(
        command: ClaudeCodeCommand,
        invocation: ClaudeCodeNativeToolInvocation,
    ): ClaudeCodeNativeToolResponse {
        val effectiveCommand = executableOverride
            ?.let { command.copy(executablePath = it) }
            ?: command
        require(effectiveCommand.noSessionPersistence) {
            "Claude Code native web tools require a one-shot process"
        }
        require(effectiveCommand.cacheKey == null) {
            "Claude Code native web tools cannot use a semantic session cache"
        }
        require(effectiveCommand.nativeTools == setOf(invocation.tool)) {
            "Claude Code native tool command must expose only ${invocation.tool.cliName}"
        }

        val process = processCache.startUncached(effectiveCommand)
        return try {
            process.executeNativeTool(effectiveCommand.userPrompt, invocation)
        } finally {
            withContext(NonCancellable) {
                process.close()
            }
        }
    }

    internal fun buildArgs(command: ClaudeCodeCommand, systemPromptFile: String): List<String> =
        ClaudeCodeProcessArguments.build(
            command = executableOverride
                ?.let { command.copy(executablePath = it) }
                ?: command,
            systemPromptFile = systemPromptFile,
        )

    internal suspend fun shutdown() {
        processCache.close()
    }

    override fun close() {
        runBlocking {
            shutdown()
        }
    }
}

internal fun interface ClaudeCodeCliProcessFactory {
    suspend fun start(command: ClaudeCodeCommand): ClaudeCodeCliProcess
}

internal interface ClaudeCodeCliProcess {
    val sessionId: String?
    val isAlive: Boolean

    suspend fun execute(userPrompt: String): ClaudeCodeCliResponse

    suspend fun executeNativeTool(
        userPrompt: String,
        invocation: ClaudeCodeNativeToolInvocation,
    ): ClaudeCodeNativeToolResponse =
        error("Claude Code process does not support native tool interception")

    suspend fun close()
}

internal class ClaudeCodeProcessCache(
    private val processFactory: ClaudeCodeCliProcessFactory,
    private val nanoTime: () -> Long,
    commandQueueCapacity: Int = COMMAND_QUEUE_CAPACITY,
    private val pruneInterval: Duration = PRUNE_INTERVAL,
) {
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("claude-code-process-cache")
    )
    private val lifecycleMutex = Mutex()
    private val commands = Channel<CacheCommand>(commandQueueCapacity)
    private val closeJobs = ConcurrentHashMap.newKeySet<Job>()
    private val actorJob = scope.launch {
        val state = CacheState(processFactory, nanoTime) { process ->
            closeLater(process)
        }
        for (command in commands) {
            when (command) {
                is CacheCommand.Acquire -> state.acquire(command)
                is CacheCommand.Release -> state.release(command)
                CacheCommand.Prune -> state.prune()
                is CacheCommand.Shutdown -> {
                    command.reply.complete(state.removeAll())
                    break
                }
            }
        }
    }
    private val pruneJob = scope.launch {
        while (isActive) {
            delay(pruneInterval)
            commands.send(CacheCommand.Prune)
        }
    }

    suspend fun startUncached(command: ClaudeCodeCommand): ClaudeCodeCliProcess {
        return lifecycleMutex.withLock {
            check(!closed.get()) { "Claude Code process cache is closed" }
            processFactory.start(command)
        }
    }

    suspend fun acquire(command: ClaudeCodeCommand): ClaudeCodeProcessLease {
        val reply = CompletableDeferred<ClaudeCodeProcessLease>()
        lifecycleMutex.withLock {
            check(!closed.get()) { "Claude Code process cache is closed" }
            commands.send(CacheCommand.Acquire(command, reply))
        }
        return try {
            reply.await()
        } catch (exception: CancellationException) {
            if (!reply.completeExceptionally(exception)) {
                val lease = withContext(NonCancellable) {
                    runCatching { reply.await() }.getOrNull()
                }
                if (lease != null) {
                    withContext(NonCancellable) {
                        release(lease, succeeded = false)
                    }
                }
            }
            throw exception
        }
    }

    suspend fun release(lease: ClaudeCodeProcessLease, succeeded: Boolean) {
        val reply = CompletableDeferred<Unit>()
        try {
            val enqueued = lifecycleMutex.withLock {
                if (closed.get()) {
                    false
                } else {
                    commands.send(CacheCommand.Release(lease, succeeded, reply))
                    true
                }
            }
            if (!enqueued) {
                lease.process.close()
                return
            }
            reply.await()
        } catch (error: Throwable) {
            lease.process.close()
            throw error
        }
    }

    suspend fun close() {
        val reply = CompletableDeferred<List<ClaudeCodeCliProcess>>()
        val closing = lifecycleMutex.withLock {
            if (!closed.compareAndSet(false, true)) {
                false
            } else {
                pruneJob.cancel()
                commands.send(CacheCommand.Shutdown(reply))
                true
            }
        }
        if (!closing) return
        pruneJob.cancelAndJoin()
        val processes = reply.await()
        actorJob.join()
        closeJobs.toList().joinAll()
        coroutineScope {
            processes.map { process ->
                launch(Dispatchers.IO) { process.close() }
            }.joinAll()
        }
        commands.close()
        scope.cancel()
    }

    private fun closeLater(process: ClaudeCodeCliProcess) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            process.close()
        }
        closeJobs += job
        job.invokeOnCompletion {
            closeJobs -= job
        }
        job.start()
    }

    private sealed interface CacheCommand {
        data class Acquire(
            val command: ClaudeCodeCommand,
            val reply: CompletableDeferred<ClaudeCodeProcessLease>,
        ) : CacheCommand

        data class Release(
            val lease: ClaudeCodeProcessLease,
            val succeeded: Boolean,
            val reply: CompletableDeferred<Unit>,
        ) : CacheCommand

        data object Prune : CacheCommand

        data class Shutdown(
            val reply: CompletableDeferred<List<ClaudeCodeCliProcess>>,
        ) : CacheCommand
    }

    private class CacheState(
        private val processFactory: ClaudeCodeCliProcessFactory,
        private val nanoTime: () -> Long,
        private val closeLater: (ClaudeCodeCliProcess) -> Unit,
    ) {
        private val entries = mutableMapOf<String, CacheEntry>()

        suspend fun acquire(command: CacheCommand.Acquire) {
            runCatching {
                val policy = command.command.cachePolicy()
                entries.values
                    .filter { it.connectionId == command.command.connectionId }
                    .forEach { it.policy = policy }
                prune()
                val cacheKey = requireNotNull(command.command.cacheKey)
                val launchConfiguration = ClaudeCodeLaunchConfiguration.from(command.command)
                val existing = entries[cacheKey]
                val reusable = existing?.takeIf { entry ->
                    entry.activeCalls == 0 &&
                        entry.process.isAlive &&
                        entry.launchConfiguration == launchConfiguration &&
                        command.command.resumeSessionId != null &&
                        command.command.resumeSessionId == entry.process.sessionId
                }

                if (existing != null && reusable == null) {
                    check(existing.activeCalls == 0) {
                        "Claude Code session process is already executing: $cacheKey"
                    }
                    entries.remove(cacheKey)
                    closeLater(existing.process)
                }

                val entry = reusable?.also {
                    it.policy = policy
                    it.activeCalls = 1
                } ?: CacheEntry(
                    connectionId = command.command.connectionId,
                    launchConfiguration = launchConfiguration,
                    process = processFactory.start(command.command),
                    policy = policy,
                    activeCalls = 1,
                    lastReleasedAtNanos = nanoTime(),
                ).also {
                    entries[cacheKey] = it
                }

                trim(command.command.connectionId, policy.maxCachedProcesses)
                ClaudeCodeProcessLease(cacheKey, entry.process)
            }.onSuccess { lease ->
                if (!command.reply.complete(lease)) {
                    entries.remove(lease.cacheKey)
                    closeLater(lease.process)
                }
            }.onFailure(command.reply::completeExceptionally)
        }

        fun release(command: CacheCommand.Release) {
            runCatching {
                val entry = entries[command.lease.cacheKey]
                if (entry == null || entry.process !== command.lease.process) {
                    closeLater(command.lease.process)
                    return@runCatching
                }

                check(entry.activeCalls == 1) {
                    "Claude Code process lease count is invalid for ${command.lease.cacheKey}: ${entry.activeCalls}"
                }
                entry.activeCalls = 0
                entry.lastReleasedAtNanos = nanoTime()

                if (!command.succeeded || !entry.process.isAlive) {
                    entries.remove(command.lease.cacheKey)
                    closeLater(entry.process)
                } else {
                    trim(entry.connectionId, entry.policy.maxCachedProcesses)
                }
            }.onSuccess {
                command.reply.complete(Unit)
            }.onFailure { error ->
                entries.remove(command.lease.cacheKey)
                closeLater(command.lease.process)
                command.reply.completeExceptionally(error)
            }
        }

        fun prune() {
            val now = nanoTime()
            entries.entries
                .filter { (_, entry) ->
                    entry.activeCalls == 0 &&
                        now - entry.lastReleasedAtNanos >= entry.policy.idleTtl.inWholeNanoseconds
                }
                .map(Map.Entry<String, CacheEntry>::key)
                .forEach(::remove)
        }

        fun removeAll(): List<ClaudeCodeCliProcess> =
            entries.values.map(CacheEntry::process).also {
                entries.clear()
            }

        private fun trim(connectionId: String, maxCachedProcesses: Int) {
            while (entries.values.count { it.connectionId == connectionId } > maxCachedProcesses) {
                val oldestIdle = entries
                    .filterValues { it.connectionId == connectionId && it.activeCalls == 0 }
                    .minByOrNull { it.value.lastReleasedAtNanos }
                    ?.key
                    ?: return
                remove(oldestIdle)
            }
        }

        private fun remove(cacheKey: String) {
            entries.remove(cacheKey)?.process?.let(closeLater)
        }
    }

    private data class CacheEntry(
        val connectionId: String,
        val launchConfiguration: ClaudeCodeLaunchConfiguration,
        val process: ClaudeCodeCliProcess,
        var policy: ClaudeCodeProcessCachePolicy,
        var activeCalls: Int,
        var lastReleasedAtNanos: Long,
    )

    private companion object {
        const val COMMAND_QUEUE_CAPACITY = 1_024
        val PRUNE_INTERVAL = 1.minutes
    }
}

internal data class ClaudeCodeProcessLease(
    val cacheKey: String,
    val process: ClaudeCodeCliProcess,
)

private data class ClaudeCodeProcessCachePolicy(
    val maxCachedProcesses: Int,
    val idleTtl: Duration,
)

private fun ClaudeCodeCommand.cachePolicy(): ClaudeCodeProcessCachePolicy =
    ClaudeCodeProcessCachePolicy(
        maxCachedProcesses = maxCachedProcesses,
        idleTtl = processIdleTtlMinutes.minutes,
    )

private data class ClaudeCodeLaunchConfiguration(
    val executablePath: String,
    val modelName: String,
    val workspaceDirectory: String?,
    val systemPromptFingerprint: String,
    val jsonSchemaFingerprint: String?,
    val effort: String?,
    val reasoningMode: String?,
    val nativeTools: Set<String>,
) {
    companion object {
        fun from(command: ClaudeCodeCommand): ClaudeCodeLaunchConfiguration =
            ClaudeCodeLaunchConfiguration(
                executablePath = command.executablePath,
                modelName = command.modelName,
                workspaceDirectory = command.workspaceDirectory?.absoluteFile?.normalize()?.path,
                systemPromptFingerprint = sha256(command.systemPrompt),
                jsonSchemaFingerprint = command.jsonSchema?.toString()?.let(::sha256),
                effort = command.effort?.name,
                reasoningMode = command.reasoningMode?.name,
                nativeTools = command.nativeTools.mapTo(sortedSetOf()) { it.cliName },
            )
    }
}

private class DefaultClaudeCodeCliProcessFactory : ClaudeCodeCliProcessFactory {
    override suspend fun start(command: ClaudeCodeCommand): ClaudeCodeCliProcess =
        withContext(Dispatchers.IO) {
            val systemPromptFile = Files.createTempFile("gromozeka-claude-system-", ".md")
            try {
                Files.writeString(systemPromptFile, command.systemPrompt, StandardCharsets.UTF_8)
                val args = ClaudeCodeProcessArguments.build(command, systemPromptFile.toString())
                val process = try {
                    ProcessBuilder(args)
                        .apply {
                            command.workspaceDirectory?.let(::directory)
                            environment().applyClaudeCodeReasoningMode(command.reasoningMode)
                        }
                        .start()
                } catch (exception: IOException) {
                    error(
                        "Failed to start Claude Code CLI '${args.first()}'. " +
                            "Ensure Claude Code is installed and authorized: ${exception.message}"
                    )
                }
                StreamingClaudeCodeCliProcess(process, systemPromptFile)
            } catch (exception: Throwable) {
                Files.deleteIfExists(systemPromptFile)
                throw exception
            }
        }
}

internal fun MutableMap<String, String>.applyClaudeCodeReasoningMode(mode: AiReasoningMode?) {
    when (mode) {
        null -> Unit
        AiReasoningMode.ADAPTIVE -> {
            remove("CLAUDE_CODE_DISABLE_THINKING")
            remove("CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING")
            remove("MAX_THINKING_TOKENS")
        }
        AiReasoningMode.DISABLED -> {
            put("CLAUDE_CODE_DISABLE_THINKING", "1")
            remove("CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING")
            remove("MAX_THINKING_TOKENS")
        }
        AiReasoningMode.TOKEN_BUDGET ->
            error("Claude Code does not support fixed thinking token budgets")
    }
}

private class StreamingClaudeCodeCliProcess(
    private val process: Process,
    private val systemPromptFile: Path,
) : ClaudeCodeCliProcess {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val requestMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val stdout: BufferedReader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
    private val stdin: BufferedWriter = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stderrTail = StringBuilder()
    private val stderrJob: Job = ioScope.launch {
        process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach(::appendStderr)
        }
    }

    @Volatile
    override var sessionId: String? = null
        private set

    override val isAlive: Boolean
        get() = !closed.get() && process.isAlive

    override suspend fun execute(userPrompt: String): ClaudeCodeCliResponse =
        requestMutex.withLock {
            check(isAlive) {
                "Claude Code CLI process is not running${stderrDiagnostic().asDiagnosticSuffix()}"
            }

            coroutineScope {
                val response = async(Dispatchers.IO) {
                    stdin.write(streamingUserMessage(userPrompt))
                    stdin.newLine()
                    stdin.flush()
                    readResult()
                }
                try {
                    response.await()
                } catch (exception: CancellationException) {
                    runCatching(::terminateImmediately)
                    throw exception
                }
            }.also { response ->
                sessionId = response.sessionId
            }
        }

    override suspend fun executeNativeTool(
        userPrompt: String,
        invocation: ClaudeCodeNativeToolInvocation,
    ): ClaudeCodeNativeToolResponse =
        requestMutex.withLock {
            check(isAlive) {
                "Claude Code CLI process is not running${stderrDiagnostic().asDiagnosticSuffix()}"
            }

            coroutineScope {
                val response = async(Dispatchers.IO) {
                    stdin.write(streamingUserMessage(userPrompt))
                    stdin.newLine()
                    stdin.flush()
                    readNativeToolResult(invocation)
                }
                try {
                    response.await()
                } catch (exception: CancellationException) {
                    runCatching(::terminateImmediately)
                    throw exception
                }
            }
        }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            runCatching { stdin.close() }
            if (process.isAlive && !process.waitFor(GRACEFUL_CLOSE_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
            }
            if (process.isAlive && !process.waitFor(TERMINATE_SECONDS, TimeUnit.SECONDS)) {
                terminateImmediately()
            }
            runCatching { stdout.close() }
            runCatching { Files.deleteIfExists(systemPromptFile) }
        }
        stderrJob.cancelAndJoin()
        ioScope.cancel()
    }

    private fun readResult(): ClaudeCodeCliResponse {
        val parser = ClaudeCodeResultStreamParser()
        while (true) {
            val line = stdout.readLine()
                ?: error(
                    "Claude Code CLI stream closed before returning a result" +
                        processExitDiagnostic() +
                        stderrDiagnostic().asDiagnosticSuffix()
                )
            if (line.isBlank()) continue
            val root = runCatching { json.parseToJsonElement(line).jsonObject }
                .getOrElse {
                    error("Claude Code CLI returned invalid stream JSON: $line")
                }
            parser.accept(root)?.let { return it }
        }
    }

    private fun readNativeToolResult(
        invocation: ClaudeCodeNativeToolInvocation,
    ): ClaudeCodeNativeToolResponse {
        val parser = ClaudeCodeNativeToolStreamParser(invocation)
        while (true) {
            val line = stdout.readLine()
                ?: error(
                    "Claude Code CLI stream closed before returning ${invocation.tool.cliName} result" +
                        processExitDiagnostic() +
                        stderrDiagnostic().asDiagnosticSuffix()
                )
            if (line.isBlank()) continue
            val root = runCatching { json.parseToJsonElement(line).jsonObject }
                .getOrElse {
                    error("Claude Code CLI returned invalid stream JSON: $line")
                }
            parser.accept(root)?.let { return it }
        }
    }

    private fun appendStderr(line: String) {
        synchronized(stderrTail) {
            stderrTail.appendLine(line)
            if (stderrTail.length > STDERR_TAIL_LIMIT) {
                stderrTail.delete(0, stderrTail.length - STDERR_TAIL_LIMIT)
            }
        }
    }

    private fun stderrDiagnostic(): String =
        synchronized(stderrTail) { stderrTail.toString().trim() }

    private fun processExitDiagnostic(): String =
        if (process.isAlive) {
            ""
        } else {
            " (exit code ${process.exitValue()})"
        }

    private fun terminateImmediately() {
        val root = process.toHandle()
        root.descendants()
            .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
            .forEach(ProcessHandle::destroyForcibly)
        root.destroyForcibly()
    }

    private fun streamingUserMessage(userPrompt: String): String =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("user"),
                "message" to JsonObject(
                    mapOf(
                        "role" to JsonPrimitive("user"),
                        "content" to JsonPrimitive(userPrompt),
                    )
                ),
                "parent_tool_use_id" to JsonNull,
            )
        ).toString()

    private fun String.asDiagnosticSuffix(): String =
        if (isBlank()) "" else ": $this"

    private companion object {
        const val GRACEFUL_CLOSE_SECONDS = 1L
        const val TERMINATE_SECONDS = 1L
        const val STDERR_TAIL_LIMIT = 64 * 1024
    }
}

internal class ClaudeCodeResultStreamParser {
    private var latestMessageId: String? = null
    private val latestThinking = linkedMapOf<String, ClaudeCodeThinkingBlock>()

    fun accept(root: JsonObject): ClaudeCodeCliResponse? =
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> {
                acceptAssistant(root)
                null
            }
            "result" -> parseResult(root)
            else -> null
        }

    private fun acceptAssistant(root: JsonObject) {
        val parentToolUseId = root["parent_tool_use_id"]
        if (parentToolUseId != null && parentToolUseId !is JsonNull) return

        val message = root["message"] as? JsonObject ?: return
        val content = message["content"] as? JsonArray ?: return
        val messageId = message["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        if (messageId != null && messageId != latestMessageId) {
            latestMessageId = messageId
            latestThinking.clear()
        }
        val thinking = content.mapNotNull(::parseThinking)
        if (messageId == null && thinking.isNotEmpty()) {
            latestThinking.clear()
        }
        thinking.forEach { block ->
            latestThinking[block.signature ?: "summary:${block.thinking}"] = block
        }
    }

    private fun parseThinking(element: JsonElement): ClaudeCodeThinkingBlock? {
        val block = element as? JsonObject ?: return null
        return when (block["type"]?.jsonPrimitive?.contentOrNull) {
            "thinking" -> ClaudeCodeThinkingBlock(
                thinking = block["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                signature = block["signature"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            )
            "redacted_thinking" -> ClaudeCodeThinkingBlock(
                thinking = "",
                signature = block["data"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            )
            else -> null
        }
    }

    private fun parseResult(root: JsonObject): ClaudeCodeCliResponse {
        val isError = root["is_error"]?.jsonPrimitive?.booleanOrNull == true
        if (isError) {
            val message = root["result"]?.jsonPrimitive?.contentOrNull ?: root.toString()
            error("Claude Code CLI returned an error: $message")
        }

        return ClaudeCodeCliResponse(
            result = root["result"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            structuredOutput = root["structured_output"],
            sessionId = root["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            usage = root["usage"] as? JsonObject,
            finishReason = root["subtype"]?.jsonPrimitive?.contentOrNull,
            raw = root,
            thinking = latestThinking.values.toList(),
        )
    }
}

internal class ClaudeCodeNativeToolStreamParser(
    private val invocation: ClaudeCodeNativeToolInvocation,
) {
    private var toolUseId: String? = null

    fun accept(root: JsonObject): ClaudeCodeNativeToolResponse? {
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> acceptAssistant(root)
            "user" -> return acceptUser(root)
            "result" -> error(
                "Claude Code completed without returning ${invocation.tool.cliName} result"
            )
        }
        return null
    }

    private fun acceptAssistant(root: JsonObject) {
        val contents = (root["message"] as? JsonObject)
            ?.get("content") as? JsonArray
            ?: return
        contents.forEach { item ->
            val toolUse = item as? JsonObject ?: return@forEach
            if (toolUse["type"]?.jsonPrimitive?.contentOrNull != "tool_use") {
                return@forEach
            }
            check(toolUseId == null) {
                "Claude Code invoked more than one native tool"
            }
            val actualName = toolUse["name"]?.jsonPrimitive?.contentOrNull
            require(actualName == invocation.tool.cliName) {
                "Claude Code invoked $actualName instead of ${invocation.tool.cliName}"
            }
            val actualInput = toolUse["input"] as? JsonObject
                ?: error("Claude Code ${invocation.tool.cliName} call missed object input")
            require(actualInput == invocation.input) {
                "Claude Code changed ${invocation.tool.cliName} input: " +
                    "expected=${invocation.input}, actual=$actualInput"
            }
            toolUseId = toolUse["id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: error("Claude Code ${invocation.tool.cliName} call missed id")
        }
    }

    private fun acceptUser(root: JsonObject): ClaudeCodeNativeToolResponse? {
        val expectedToolUseId = toolUseId ?: return null
        val contents = (root["message"] as? JsonObject)
            ?.get("content") as? JsonArray
            ?: return null
        val toolResult = contents
            .mapNotNull { it as? JsonObject }
            .singleOrNull {
                it["type"]?.jsonPrimitive?.contentOrNull == "tool_result" &&
                    it["tool_use_id"]?.jsonPrimitive?.contentOrNull == expectedToolUseId
            }
            ?: return null
        val result = root["tool_use_result"]
            ?.takeUnless { it is JsonNull }
            ?: toolResult["content"]
            ?: JsonNull
        return ClaudeCodeNativeToolResponse(
            tool = invocation.tool,
            input = invocation.input,
            result = result,
        )
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private object ClaudeCodeProcessArguments {
    fun build(command: ClaudeCodeCommand, systemPromptFile: String): List<String> =
        buildList {
            require(command.nativeTools.isEmpty() || command.jsonSchema == null) {
                "Claude Code native tools cannot be combined with structured output"
            }
            val nativeToolNames = command.nativeTools
                .map { it.cliName }
                .sorted()
            add(command.executablePath)
            add("-p")
            add("--safe-mode")
            add("--tools")
            add(nativeToolNames.joinToString(","))
            if (nativeToolNames.isNotEmpty()) {
                add("--allowedTools")
                add(nativeToolNames.joinToString(","))
            }
            add("--disable-slash-commands")
            add("--setting-sources")
            add("")
            add("--input-format")
            add("stream-json")
            add("--output-format")
            add("stream-json")
            add("--verbose")
            add("--system-prompt-file")
            add(systemPromptFile)
            add("--model")
            add(command.modelName)
            command.effort?.let { effort ->
                add("--effort")
                add(effort.name.lowercase())
            }
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
}
