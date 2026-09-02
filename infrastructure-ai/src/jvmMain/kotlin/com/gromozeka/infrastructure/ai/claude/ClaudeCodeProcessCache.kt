package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.ai.AiReasoningMode
import klog.KLoggers
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
    private val log = KLoggers.logger(this)

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

        val startedAt = System.nanoTime()
        return try {
            val response = if (effectiveCommand.noSessionPersistence || effectiveCommand.cacheKey == null) {
                val process = processCache.startUncached(effectiveCommand)
                try {
                    process.execute(
                        effectiveCommand.userPrompt,
                        effectiveCommand.userContentBlocks,
                        effectiveCommand.diagnosticId,
                    )
                } finally {
                    withContext(NonCancellable) {
                        process.close()
                    }
                }
            } else {
                val lease = processCache.acquire(effectiveCommand)
                var succeeded = false
                try {
                    lease.process.execute(
                        effectiveCommand.userPrompt,
                        effectiveCommand.userContentBlocks,
                        effectiveCommand.diagnosticId,
                    ).also { succeeded = true }
                } finally {
                    withContext(NonCancellable) {
                        processCache.release(lease, succeeded)
                    }
                }
            }
            response
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.error(error) {
                "CLAUDE_CODE_TRACE call=${effectiveCommand.diagnosticId} phase=executor_failed " +
                    "elapsedMs=${elapsedMillis(startedAt)} error=${error::class.simpleName} " +
                    "message=${error.message?.redactedDiagnosticPreview()}"
            }
            throw error
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

    suspend fun execute(
        userPrompt: String,
        userContentBlocks: List<JsonObject>,
    ): ClaudeCodeCliResponse {
        check(userContentBlocks.isEmpty()) { "Claude Code process does not support rich user input" }
        return execute(userPrompt)
    }

    suspend fun execute(
        userPrompt: String,
        userContentBlocks: List<JsonObject>,
        diagnosticId: String,
    ): ClaudeCodeCliResponse = execute(userPrompt, userContentBlocks)

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
        private val log = KLoggers.logger(this)
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
                log.debug {
                    "CLAUDE_CODE_TRACE call=${command.command.diagnosticId} phase=cache_acquire " +
                        "cacheKey=${shortFingerprint(cacheKey)} result=${if (reusable != null) "hit" else "miss"} " +
                        "reason=${cacheReuseDecision(existing, command.command, launchConfiguration)} " +
                        "entries=${entries.size} resumeSession=${command.command.resumeSessionId ?: "none"}"
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
                log.debug {
                    "CLAUDE_CODE_TRACE call=${command.command.diagnosticId} phase=cache_lease_acquired " +
                        "cacheKey=${shortFingerprint(cacheKey)} reused=${reusable != null} " +
                        "processAlive=${entry.process.isAlive} session=${entry.process.sessionId ?: "none"}"
                }
                ClaudeCodeProcessLease(cacheKey, entry.process, command.command.diagnosticId)
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
                log.debug {
                    "CLAUDE_CODE_TRACE call=${command.lease.diagnosticId} phase=cache_lease_released " +
                        "cacheKey=${shortFingerprint(command.lease.cacheKey)} succeeded=${command.succeeded} " +
                        "processAlive=${entry.process.isAlive} retained=${command.lease.cacheKey in entries}"
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
            val expiredKeys = entries.entries
                .filter { (_, entry) ->
                    entry.activeCalls == 0 &&
                        now - entry.lastReleasedAtNanos >= entry.policy.idleTtl.inWholeNanoseconds
                }
                .map(Map.Entry<String, CacheEntry>::key)
            expiredKeys.forEach(::remove)
            if (expiredKeys.isNotEmpty()) {
                log.debug {
                    "CLAUDE_CODE_TRACE phase=cache_pruned count=${expiredKeys.size} " +
                        "keys=${expiredKeys.map(::shortFingerprint)} remaining=${entries.size}"
                }
            }
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

        private fun cacheReuseDecision(
            existing: CacheEntry?,
            command: ClaudeCodeCommand,
            launchConfiguration: ClaudeCodeLaunchConfiguration,
        ): String = when {
            existing == null -> "no_entry"
            existing.activeCalls != 0 -> "active_call"
            !existing.process.isAlive -> "process_dead"
            existing.launchConfiguration != launchConfiguration ->
                "launch_configuration_changed:${existing.launchConfiguration.diff(launchConfiguration)}"
            command.resumeSessionId == null -> "resume_session_missing"
            command.resumeSessionId != existing.process.sessionId -> "resume_session_mismatch"
            else -> "compatible"
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
    val diagnosticId: String,
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
    val outputStyle: String?,
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
                outputStyle = command.outputStyle?.settingsValue,
                workspaceDirectory = command.workspaceDirectory?.absoluteFile?.normalize()?.path,
                systemPromptFingerprint = sha256(command.systemPrompt),
                jsonSchemaFingerprint = command.jsonSchema?.toString()?.let(::sha256),
                effort = command.effort?.name,
                reasoningMode = command.reasoningMode?.name,
                nativeTools = command.nativeTools.mapTo(sortedSetOf()) { it.cliName },
            )
    }
}

private fun ClaudeCodeLaunchConfiguration.diff(other: ClaudeCodeLaunchConfiguration): String =
    buildList {
        if (executablePath != other.executablePath) add("executable")
        if (modelName != other.modelName) add("model")
        if (outputStyle != other.outputStyle) add("output_style")
        if (workspaceDirectory != other.workspaceDirectory) add("workspace")
        if (systemPromptFingerprint != other.systemPromptFingerprint) add("system_prompt")
        if (jsonSchemaFingerprint != other.jsonSchemaFingerprint) add("json_schema")
        if (effort != other.effort) add("effort")
        if (reasoningMode != other.reasoningMode) add("reasoning_mode")
        if (nativeTools != other.nativeTools) add("native_tools")
    }.joinToString(",").ifEmpty { "none" }

private class DefaultClaudeCodeCliProcessFactory : ClaudeCodeCliProcessFactory {
    private val log = KLoggers.logger(this)

    override suspend fun start(command: ClaudeCodeCommand): ClaudeCodeCliProcess =
        withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val systemPromptFile = Files.createTempFile("gromozeka-claude-system-", ".md")
            try {
                Files.writeString(systemPromptFile, command.systemPrompt, StandardCharsets.UTF_8)
                val args = ClaudeCodeProcessArguments.build(command, systemPromptFile.toString())
                log.debug {
                    "CLAUDE_CODE_TRACE call=${command.diagnosticId} phase=process_launching " +
                        "workspace=${command.workspaceDirectory?.absolutePath ?: "none"} " +
                        "systemPromptFile=$systemPromptFile systemPromptChars=${command.systemPrompt.length} " +
                        "command=${args.toDiagnosticCommand()} reasoningEnv=${command.reasoningMode.diagnosticEnvironment()}"
                }
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
                log.debug {
                    "CLAUDE_CODE_TRACE call=${command.diagnosticId} phase=process_started " +
                        "launchMs=${elapsedMillis(startedAt)} pid=${process.pid()} alive=${process.isAlive}"
                }
                StreamingClaudeCodeCliProcess(process, systemPromptFile, command.diagnosticId)
            } catch (exception: Throwable) {
                log.error(exception) {
                    "CLAUDE_CODE_TRACE call=${command.diagnosticId} phase=process_launch_failed " +
                        "elapsedMs=${elapsedMillis(startedAt)} error=${exception::class.simpleName} " +
                        "message=${exception.message?.redactedDiagnosticPreview()}"
                }
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
    private val launchDiagnosticId: String,
) : ClaudeCodeCliProcess {
    private val log = KLoggers.logger(this)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val requestMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val stdout: BufferedReader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
    private val stdin: BufferedWriter = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stderrTail = StringBuilder()
    @Volatile
    private var activeDiagnosticId: String = launchDiagnosticId
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
        execute(userPrompt, emptyList())

    override suspend fun execute(
        userPrompt: String,
        userContentBlocks: List<JsonObject>,
    ): ClaudeCodeCliResponse = execute(userPrompt, userContentBlocks, launchDiagnosticId)

    override suspend fun execute(
        userPrompt: String,
        userContentBlocks: List<JsonObject>,
        diagnosticId: String,
    ): ClaudeCodeCliResponse =
        requestMutex.withLock {
            activeDiagnosticId = diagnosticId
            check(isAlive) {
                "Claude Code CLI process is not running${stderrDiagnostic().asDiagnosticSuffix()}"
            }

            coroutineScope {
                val response = async(Dispatchers.IO) {
                    val payload = streamingUserMessage(userPrompt, userContentBlocks)
                    stdin.write(payload)
                    stdin.newLine()
                    stdin.flush()
                    readResult(diagnosticId)
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
        val startedAt = System.nanoTime()
        log.debug {
            "CLAUDE_CODE_TRACE call=$activeDiagnosticId phase=process_closing pid=${process.pid()} alive=${process.isAlive}"
        }
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
        log.debug {
            "CLAUDE_CODE_TRACE call=$activeDiagnosticId phase=process_closed pid=${process.pid()} " +
                "elapsedMs=${elapsedMillis(startedAt)} alive=${process.isAlive}"
        }
    }

    private fun readResult(diagnosticId: String): ClaudeCodeCliResponse {
        val startedAt = System.nanoTime()
        var eventIndex = 0
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
                    error(
                        "Claude Code CLI returned invalid stream JSON: " +
                            "chars=${line.length}, sha256=${shortFingerprint(line)}"
                    )
                }
            eventIndex++
            val now = System.nanoTime()
            val elapsedMs = (now - startedAt) / 1_000_000
            if (eventIndex == 1) {
                log.debug {
                    "CLAUDE_CODE_TRACE call=$diagnosticId phase=first_stdout_event pid=${process.pid()} " +
                        "elapsedMs=$elapsedMs event=$eventIndex ${root.diagnosticEventSummary()}"
                }
            }
            parser.accept(root)?.let { response ->
                log.debug {
                    "CLAUDE_CODE_TRACE call=$diagnosticId phase=result_received pid=${process.pid()} " +
                        "elapsedMs=$elapsedMs events=$eventIndex session=${response.sessionId ?: "none"} " +
                        "finishReason=${response.finishReason} resultChars=${response.result.length}"
                }
                return response
            }
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
                    error(
                        "Claude Code CLI returned invalid stream JSON: " +
                            "chars=${line.length}, sha256=${shortFingerprint(line)}"
                    )
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
        synchronized(stderrTail) {
            stderrTail.lineSequence()
                .filter(String::isNotBlank)
                .joinToString("\n", transform = String::redactedDiagnosticPreview)
                .trim()
        }

    private fun processExitDiagnostic(): String =
        if (process.isAlive) {
            ""
        } else {
            " (exit code ${process.exitValue()})"
        }

    private fun terminateImmediately() {
        log.warn {
            "CLAUDE_CODE_TRACE call=$activeDiagnosticId phase=process_terminate_forcibly pid=${process.pid()}"
        }
        val root = process.toHandle()
        root.descendants()
            .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
            .forEach(ProcessHandle::destroyForcibly)
        root.destroyForcibly()
    }

    private fun streamingUserMessage(
        userPrompt: String,
        userContentBlocks: List<JsonObject> = emptyList(),
    ): String =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("user"),
                "message" to JsonObject(
                    mapOf(
                        "role" to JsonPrimitive("user"),
                        "content" to if (userContentBlocks.isEmpty()) {
                            JsonPrimitive(userPrompt)
                        } else {
                            JsonArray(
                                listOf(
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive(userPrompt),
                                        )
                                    )
                                ) + userContentBlocks
                            )
                        },
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
    private var latestAssistantUsage: JsonObject? = null
    private val latestThinking = linkedMapOf<String, ClaudeCodeThinkingBlock>()
    private val compactionBoundaries = mutableListOf<JsonObject>()

    fun accept(root: JsonObject): ClaudeCodeCliResponse? =
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> {
                acceptAssistant(root)
                null
            }
            "system" -> {
                if (root["subtype"]?.jsonPrimitive?.contentOrNull == "compact_boundary") {
                    compactionBoundaries += root
                }
                null
            }
            "result" -> parseResult(root)
            else -> null
        }

    private fun acceptAssistant(root: JsonObject) {
        val parentToolUseId = root["parent_tool_use_id"]
        if (parentToolUseId != null && parentToolUseId !is JsonNull) return

        val message = root["message"] as? JsonObject ?: return
        latestAssistantUsage = message["usage"] as? JsonObject ?: latestAssistantUsage
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
            error("Claude Code CLI returned an error: ${message.redactedDiagnosticPreview()}")
        }

        val usage = root["usage"] as? JsonObject
        return ClaudeCodeCliResponse(
            result = root["result"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            structuredOutput = root["structured_output"],
            sessionId = root["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            usage = usage,
            contextUsage = usage.latestMessageIteration()
                ?: latestAssistantUsage
                ?: usage?.takeUnless { "iterations" in it },
            finishReason = root["subtype"]?.jsonPrimitive?.contentOrNull,
            raw = root,
            thinking = latestThinking.values.toList(),
            compactionBoundaries = compactionBoundaries.toList(),
        )
    }

    private fun JsonObject?.latestMessageIteration(): JsonObject? =
        (this?.get("iterations") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.lastOrNull { iteration ->
                iteration["type"]?.jsonPrimitive?.contentOrNull == "message"
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

private fun shortFingerprint(value: String): String = sha256(value).take(12)

private fun elapsedMillis(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000

internal fun List<String>.toDiagnosticCommand(): String {
    val result = mutableListOf<String>()
    var index = 0
    while (index < size) {
        val argument = this[index]
        result += argument.toDiagnosticArgument()
        if (argument == "--json-schema" && index + 1 < size) {
            val schema = this[index + 1]
            result += "<schema chars=${schema.length} sha256=${shortFingerprint(schema)}>"
            index += 1
        }
        index += 1
    }
    return result.joinToString(" ")
}

private fun String.toDiagnosticArgument(): String =
    if (isEmpty()) {
        "\"\""
    } else if (any(Char::isWhitespace)) {
        "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
    } else {
        this
    }

private fun AiReasoningMode?.diagnosticEnvironment(): String =
    when (this) {
        null -> "inherited"
        AiReasoningMode.ADAPTIVE -> "thinking=enabled,adaptive=enabled,maxTokens=unset"
        AiReasoningMode.DISABLED -> "thinking=disabled,adaptive=default,maxTokens=unset"
        AiReasoningMode.TOKEN_BUDGET -> "unsupported"
    }

internal fun JsonObject.diagnosticEventSummary(): String {
    val type = this["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    val fields = mutableListOf("type=$type")
    this["subtype"]?.jsonPrimitive?.contentOrNull?.let { fields += "subtype=$it" }
    this["session_id"]?.jsonPrimitive?.contentOrNull?.let { fields += "session=$it" }
    when (type) {
        "assistant", "user" -> {
            val message = this["message"] as? JsonObject
            message?.get("id")?.jsonPrimitive?.contentOrNull?.let { fields += "message=$it" }
            (message?.get("content") as? JsonArray)?.let { fields += it.diagnosticContentSummary() }
            (message?.get("usage") as? JsonObject)?.let { fields += it.diagnosticUsageSummary() }
            fields += "parentToolUse=${this["parent_tool_use_id"] !is JsonNull && this["parent_tool_use_id"] != null}"
        }
        "result" -> {
            fields += "isError=${this["is_error"]?.jsonPrimitive?.booleanOrNull ?: false}"
            fields += "resultChars=${this["result"]?.jsonPrimitive?.contentOrNull?.length ?: 0}"
            fields += "structuredChars=${this["structured_output"]?.toString()?.length ?: 0}"
            (this["usage"] as? JsonObject)?.let { fields += it.diagnosticUsageSummary() }
        }
        "system" -> {
            fields += "keys=${keys.sorted()}"
            (this["tools"] as? JsonArray)?.let { fields += "tools=${it.size}" }
            (this["mcp_servers"] as? JsonArray)?.let { fields += "mcpServers=${it.size}" }
        }
        else -> fields += "keys=${keys.sorted()}"
    }
    return fields.joinToString(" ")
}

private fun JsonArray.diagnosticContentSummary(): String {
    val blocks = mapNotNull { it as? JsonObject }
    val types = blocks
        .groupingBy { it["type"]?.jsonPrimitive?.contentOrNull ?: "unknown" }
        .eachCount()
        .toSortedMap()
    val textChars = blocks.sumOf { it["text"]?.jsonPrimitive?.contentOrNull?.length ?: 0 }
    val thinkingChars = blocks.sumOf { it["thinking"]?.jsonPrimitive?.contentOrNull?.length ?: 0 }
    val toolNames = blocks.mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }.distinct().sorted()
    return "contentTypes=$types textChars=$textChars thinkingChars=$thinkingChars toolNames=$toolNames"
}

private fun JsonObject.diagnosticUsageSummary(): String =
    listOf(
        "input_tokens",
        "output_tokens",
        "cache_creation_input_tokens",
        "cache_read_input_tokens",
    ).joinToString(prefix = "usage={", postfix = "}") { name ->
        "$name=${this[name]?.jsonPrimitive?.contentOrNull ?: "unknown"}"
    }

internal fun String.redactedDiagnosticPreview(): String {
    var result = trim().replace('\n', ' ').replace('\r', ' ')
    diagnosticSecretPatterns.forEach { (pattern, replacement) ->
        result = pattern.replace(result, replacement)
    }
    result = diagnosticStructuredContentPattern.replace(result) { match ->
        "${match.groupValues[1]}=<omitted chars=${match.groupValues[2].length}>"
    }
    return result.take(DIAGNOSTIC_PREVIEW_LIMIT).ifBlank { "<blank>" }
}

private val diagnosticSecretPatterns = listOf(
    Regex("(?i)\\bBearer\\s+[^\\s,;]+") to "Bearer <redacted>",
    Regex("(?i)\\b(sk-(?:proj-|ant-)?|ghp_|github_pat_)[A-Za-z0-9_-]{8,}") to "<redacted-token>",
    Regex("(?i)\\b(api[_-]?key|authorization|password|secret|token)(\\s*[:=]\\s*)[^\\s,;]+") to "$1$2<redacted>",
)
private val diagnosticStructuredContentPattern =
    Regex("(?i)\\b(prompt|message|content|input|result|text)\\s*=\\s*([^\\s]+)")
private const val DIAGNOSTIC_PREVIEW_LIMIT = 2_000

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
            command.outputStyle?.let { outputStyle ->
                add("--settings")
                add(
                    JsonObject(
                        mapOf("outputStyle" to JsonPrimitive(outputStyle.settingsValue))
                    ).toString()
                )
            }
            add(if (command.outputStyle == null) "--system-prompt-file" else "--append-system-prompt-file")
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
