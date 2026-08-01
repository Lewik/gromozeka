package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.ai.AiConnection
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import jakarta.annotation.PreDestroy
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.Locale
import java.util.concurrent.TimeUnit
import klog.KLoggers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ClaudeCodeVoiceTranscriptionService internal constructor(
    private val sessionFactory: ClaudeCodeVoiceSessionFactory = PtyClaudeCodeVoiceSessionFactory(),
    private val virtualAudioFactory: ClaudeCodeVirtualAudioFactory = PulseAudioVirtualAudioFactory(),
    private val warmSessionMaxAge: Duration = DEFAULT_WARM_SESSION_MAX_AGE,
    private val nanoTime: () -> Long = { System.nanoTime() },
) {
    private val log = KLoggers.logger(this)
    private val warmScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("claude-code-voice-warmup")
    )
    private val warmMutex = Mutex()
    private val warmSessions = mutableMapOf<ClaudeCodeVoiceSessionKey, WarmClaudeCodeVoiceSession>()

    init {
        require(warmSessionMaxAge.isPositive()) { "Claude Code voice warm session max age must be positive" }
    }

    suspend fun prepareLocalMicrophone(
        connection: AiConnection.ClaudeCode,
        language: String?,
    ) {
        requireLocalMicrophone(connection)
        val key = ClaudeCodeVoiceSessionKey(connection, language.normalizedLanguage())
        val now = nanoTime()
        val stale = mutableListOf<WarmClaudeCodeVoiceSession>()
        val prepared = warmMutex.withLock {
            warmSessions.entries.removeAll { (candidateKey, candidate) ->
                (candidateKey.connection.id == connection.id && candidateKey != key).also { remove ->
                    if (remove) stale += candidate
                }
            }
            warmSessions[key]?.takeIf { it.isExpired(now, warmSessionMaxAge) }?.let { expired ->
                check(warmSessions.remove(key, expired)) { "Claude Code voice warm session changed concurrently" }
                stale += expired
                log.info {
                    "Refreshing expired Claude Code voice session: " +
                        "connection=${connection.id.value} ageMs=${expired.ageMillis(now)}"
                }
            }
            warmSessions.getOrPut(key) { prepareAsync(key) }
        }
        stale.forEach { disposePrepared(it) }
        try {
            prepared.deferred.await().requireAlive()
        } catch (error: Throwable) {
            warmMutex.withLock { warmSessions.remove(key, prepared) }
            withContext(NonCancellable) { disposePrepared(prepared) }
            throw error
        }
    }

    suspend fun startLocalMicrophone(
        connection: AiConnection.ClaudeCode,
        language: String?,
    ): ClaudeCodeVoiceCaptureSession {
        requireLocalMicrophone(connection)
        val key = ClaudeCodeVoiceSessionKey(connection, language.normalizedLanguage())
        val now = nanoTime()
        val stale = mutableListOf<WarmClaudeCodeVoiceSession>()
        val acquired = warmMutex.withLock {
            warmSessions.entries.removeAll { (candidateKey, candidate) ->
                (candidateKey.connection.id == connection.id && candidateKey != key).also { remove ->
                    if (remove) stale += candidate
                }
            }
            val cached = warmSessions.remove(key)
            val current = if (cached == null || cached.isExpired(now, warmSessionMaxAge)) {
                cached?.let(stale::add)
                prepareAsync(key)
            } else {
                cached
            }
            warmSessions[key] = prepareAsync(key)
            AcquiredClaudeCodeVoiceSession(
                session = current,
                source = if (cached === current) "warm" else "fresh",
                ageMillis = current.ageMillis(now),
            )
        }
        stale.forEach { disposePrepared(it) }
        log.info {
            "Acquiring Claude Code voice session: connection=${connection.id.value} " +
                "source=${acquired.source} ageMs=${acquired.ageMillis}"
        }
        val session = acquired.session.deferred.await()
        return try {
            session.beginRecording()
        } catch (error: Throwable) {
            withContext(NonCancellable) { session.dispose() }
            throw error
        }
    }

    suspend fun transcribeAudio(
        connection: AiConnection.ClaudeCode,
        audioData: ByteArray,
        format: SpeechAudioFormat,
        language: String?,
    ): String {
        requireEnabled(connection)
        format.requireValid(audioData)
        require(currentOperatingSystem() == OperatingSystem.LINUX) {
            "Forwarding recorded audio to Claude Code voice transcription is currently supported only on Linux"
        }

        val virtualAudio = virtualAudioFactory.open(audioData, format)
        val prepared = try {
            sessionFactory.prepare(connection, language.normalizedLanguage(), virtualAudio.environment)
        } catch (error: Throwable) {
            withContext(NonCancellable) { virtualAudio.close() }
            throw error
        }
        val session = try {
            prepared.beginRecording()
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                prepared.dispose()
                virtualAudio.close()
            }
            throw error
        }
        return try {
            virtualAudio.play()
            session.stop()
        } catch (error: CancellationException) {
            withContext(NonCancellable) { session.cancel() }
            throw error
        } catch (error: Throwable) {
            withContext(NonCancellable) { session.cancel() }
            throw error
        } finally {
            withContext(NonCancellable) { virtualAudio.close() }
        }
    }

    @PreDestroy
    internal fun shutdown() = runBlocking {
        val prepared = warmMutex.withLock {
            warmSessions.values.toList().also { warmSessions.clear() }
        }
        prepared.forEach { disposePrepared(it) }
        warmScope.cancel()
    }

    private fun requireLocalMicrophone(connection: AiConnection.ClaudeCode) {
        requireEnabled(connection)
        require(currentOperatingSystem() in DIRECT_MICROPHONE_OPERATING_SYSTEMS) {
            "Direct Claude Code microphone transcription is unsupported on ${System.getProperty("os.name")}"
        }
    }

    private fun prepareAsync(
        key: ClaudeCodeVoiceSessionKey,
    ): WarmClaudeCodeVoiceSession = WarmClaudeCodeVoiceSession(
        deferred = warmScope.async {
            sessionFactory.prepare(key.connection, key.language, emptyMap())
        },
        createdAtNanos = nanoTime(),
    )

    private suspend fun disposePrepared(prepared: WarmClaudeCodeVoiceSession) {
        if (!prepared.deferred.isCompleted) prepared.deferred.cancel()
        runCatching { prepared.deferred.await() }.getOrNull()?.dispose()
    }

    private fun requireEnabled(connection: AiConnection.ClaudeCode) {
        require(connection.enabled) { "Claude Code connection is disabled: ${connection.id.value}" }
        require(connection.voiceTranscriptionEnabled) {
            "Claude Code voice transcription is disabled for connection ${connection.id.value}"
        }
    }

    private companion object {
        val DIRECT_MICROPHONE_OPERATING_SYSTEMS = setOf(
            OperatingSystem.LINUX,
            OperatingSystem.MACOS,
            OperatingSystem.WINDOWS,
        )
    }
}

private data class ClaudeCodeVoiceSessionKey(
    val connection: AiConnection.ClaudeCode,
    val language: String?,
)

private data class WarmClaudeCodeVoiceSession(
    val deferred: Deferred<ClaudeCodeVoicePreparedSession>,
    val createdAtNanos: Long,
) {
    fun isExpired(nowNanos: Long, maxAge: Duration): Boolean =
        nowNanos - createdAtNanos >= maxAge.inWholeNanoseconds

    fun ageMillis(nowNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((nowNanos - createdAtNanos).coerceAtLeast(0L))
}

private data class AcquiredClaudeCodeVoiceSession(
    val session: WarmClaudeCodeVoiceSession,
    val source: String,
    val ageMillis: Long,
)

private fun String?.normalizedLanguage(): String? = this?.trim()?.takeIf(String::isNotEmpty)

interface ClaudeCodeVoiceCaptureSession {
    suspend fun stop(): String
    suspend fun cancel()
}

internal interface ClaudeCodeVoicePreparedSession {
    val isAlive: Boolean
    suspend fun beginRecording(): ClaudeCodeVoiceCaptureSession
    suspend fun dispose()

    fun requireAlive() {
        check(isAlive) { "Prepared Claude Code voice process is no longer running" }
    }
}

internal fun interface ClaudeCodeVoiceSessionFactory {
    suspend fun prepare(
        connection: AiConnection.ClaudeCode,
        language: String?,
        environment: Map<String, String>,
    ): ClaudeCodeVoicePreparedSession
}

internal interface ClaudeCodeVirtualAudio : AutoCloseable {
    val environment: Map<String, String>
    suspend fun play()
}

internal fun interface ClaudeCodeVirtualAudioFactory {
    suspend fun open(audioData: ByteArray, format: SpeechAudioFormat): ClaudeCodeVirtualAudio
}

private class PtyClaudeCodeVoiceSessionFactory : ClaudeCodeVoiceSessionFactory {
    override suspend fun prepare(
        connection: AiConnection.ClaudeCode,
        language: String?,
        environment: Map<String, String>,
    ): ClaudeCodeVoicePreparedSession = withContext(Dispatchers.IO) {
        val files = ClaudeVoiceSessionFiles.create(language)
        val command = claudeVoiceCommand(connection.executablePath, files.settingsFile)
        val workingDirectory = claudeVoiceWorkingDirectory()
        val process = try {
            Files.createDirectories(workingDirectory)
            PtyProcessBuilder(command.toTypedArray())
                .setEnvironment(System.getenv() + environment)
                .setDirectory(workingDirectory.toString())
                .setConsole(false)
                .setUseWinConPty(true)
                .setInitialColumns(120)
                .setInitialRows(30)
                .start()
        } catch (error: Throwable) {
            files.close()
            throw IllegalStateException("Failed to start Claude Code voice process: ${error.message}", error)
        }

        PtyClaudeCodeVoiceCaptureSession(
            process = process,
            files = files,
            stopTailDuration = if (environment.isEmpty()) DEFAULT_MICROPHONE_STOP_TAIL else Duration.ZERO,
        ).also { session ->
            try {
                session.awaitInteractiveReady()
            } catch (error: Throwable) {
                withContext(NonCancellable) { session.dispose() }
                throw error
            }
        }
    }
}

private class PtyClaudeCodeVoiceCaptureSession(
    private val process: PtyProcess,
    private val files: ClaudeVoiceSessionFiles,
    private val stopTailDuration: Duration,
) : ClaudeCodeVoicePreparedSession, ClaudeCodeVoiceCaptureSession {
    private val log = KLoggers.logger(this)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("claude-code-voice-output")
    )
    private val output = ClaudeVoiceTerminalOutput()
    private val readerJob: Job = scope.launch {
        InputStreamReader(process.inputStream, StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(2_048)
            while (isActive) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (count > 0) output.append(buffer.concatToString(0, count))
            }
        }
    }
    private var closed = false
    private var recording = false
    private var audioActivityCountBeforeRecording = 0L

    override val isAlive: Boolean
        get() = !closed && process.isAlive

    suspend fun awaitInteractiveReady() {
        val startedAtNanos = System.nanoTime()
        var workspaceTrustConfirmed = false
        awaitTerminalState(
            timeoutMillis = START_TIMEOUT_MILLIS,
            timeoutMessage = "Claude Code voice process did not become interactive",
            ready = output::isInteractiveReady,
            onPoll = {
                if (!workspaceTrustConfirmed && output.isWorkspaceTrustPrompt()) {
                    write("\r")
                    workspaceTrustConfirmed = true
                }
            },
        )
        log.info {
            "Claude Code voice process ready: pid=${process.pid()} " +
                "elapsedMs=${elapsedMillis(startedAtNanos)}"
        }
    }

    override suspend fun beginRecording(): ClaudeCodeVoiceCaptureSession {
        check(!closed) { "Claude Code voice session is already closed" }
        check(!recording) { "Claude Code voice session is already recording" }
        val startedAtNanos = System.nanoTime()
        audioActivityCountBeforeRecording = output.audioActivityCount()
        write(" ")
        awaitTerminalState(
            timeoutMillis = START_TIMEOUT_MILLIS,
            timeoutMessage = "Claude Code voice recorder did not become ready",
            ready = {
                output.isRecordingReady() &&
                    output.audioActivityCount() > audioActivityCountBeforeRecording
            },
        )
        recording = true
        log.info {
            "Claude Code voice recording ready: pid=${process.pid()} " +
                "elapsedMs=${elapsedMillis(startedAtNanos)}"
        }
        return this
    }

    override suspend fun stop(): String {
        check(!closed) { "Claude Code voice session is already closed" }
        check(recording) { "Claude Code voice session is not recording" }
        val startedAtNanos = System.nanoTime()
        var processingLogged = false
        val shortTranscriptSubmission = ClaudeVoiceShortTranscriptSubmission(
            initialOutputRevision = output.revision(),
            startedAtNanos = startedAtNanos,
        )
        delay(stopTailDuration)
        write(" ")
        return try {
            val transcript = withTimeoutOrNull(TRANSCRIPTION_TIMEOUT_MILLIS) {
                while (true) {
                    files.readPrompt()?.let { prompt ->
                        return@withTimeoutOrNull prompt.trim()
                    }
                    output.failureMessage()?.let { error(it) }
                    if (!processingLogged && output.isTranscriptionProcessing()) {
                        processingLogged = true
                        log.info {
                            "Claude Code voice transcription processing: pid=${process.pid()} " +
                            "elapsedMs=${elapsedMillis(startedAtNanos)}"
                        }
                    }
                    if (shortTranscriptSubmission.shouldSubmit(
                            outputRevision = output.revision(),
                            processingObserved = output.isTranscriptionProcessing(),
                            nowNanos = System.nanoTime(),
                        )
                    ) {
                        log.info {
                            "Submitting a short Claude Code voice transcript: pid=${process.pid()} " +
                                "elapsedMs=${elapsedMillis(startedAtNanos)}"
                        }
                        write("\r")
                    }
                    requireAlive()
                    delay(POLL_INTERVAL_MILLIS)
                }
                @Suppress("UNREACHABLE_CODE")
                error("Claude Code voice transcription did not complete")
            }
            checkNotNull(transcript) {
                "Claude Code voice transcription timed out: pid=${process.pid()} " +
                    "elapsedMs=${elapsedMillis(startedAtNanos)} ${output.diagnosticState()}"
            }.also {
                log.info {
                    "Claude Code voice transcription completed: pid=${process.pid()} " +
                        "elapsedMs=${elapsedMillis(startedAtNanos)}"
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) {
                "Claude Code voice transcription failed: pid=${process.pid()} " +
                    "elapsedMs=${elapsedMillis(startedAtNanos)} ${output.diagnosticState()}"
            }
            throw error
        } finally {
            closeProcess()
        }
    }

    override suspend fun cancel() {
        if (closed) return
        runCatching { write("\u0003") }
        closeProcess()
    }

    override suspend fun dispose() {
        closeProcess()
    }

    private suspend fun awaitTerminalState(
        timeoutMillis: Long,
        timeoutMessage: String,
        ready: () -> Boolean,
        onPoll: () -> Unit = {},
    ) {
        val completed = withTimeoutOrNull(timeoutMillis) {
            while (!ready()) {
                onPoll()
                output.failureMessage()?.let { error(it) }
                requireProcessAlive()
                delay(POLL_INTERVAL_MILLIS)
            }
            true
        } == true
        check(completed) { "$timeoutMessage: ${output.diagnosticState()}" }
    }

    private fun write(value: String) {
        process.outputStream.write(value.toByteArray(StandardCharsets.UTF_8))
        process.outputStream.flush()
    }

    private fun requireProcessAlive() {
        check(process.isAlive) {
            "Claude Code voice process exited before transcription completed: ${output.diagnosticState()}"
        }
    }

    private suspend fun closeProcess() {
        if (closed) return
        closed = true
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { process.outputStream.close() }
            terminateClaudeVoiceProcessTree(process)
        }
        readerJob.cancel()
        scope.cancel()
        files.close()
    }
}

internal class ClaudeVoiceTerminalOutput {
    private val lock = Any()
    private val tail = StringBuilder()
    private var revision = 0L
    private var audioActivityCount = 0L

    fun append(text: String) = synchronized(lock) {
        tail.append(text)
        revision += 1
        audioActivityCount += text.count { it in VOICE_AUDIO_LEVEL_CHARACTERS }
        if (tail.length > MAX_TERMINAL_TAIL_CHARS) {
            tail.delete(0, tail.length - MAX_TERMINAL_TAIL_CHARS)
        }
    }

    fun isInteractiveReady(): Boolean = normalizedTail().let { normalized ->
        CLAUDE_CODE_MARKER.containsMatchIn(normalized) && "❯" in normalized
    }

    fun isWorkspaceTrustPrompt(): Boolean = WORKSPACE_TRUST_MARKER.containsMatchIn(normalizedTail())

    fun isRecordingReady(): Boolean = normalizedTail().let { normalized ->
        "REC" in normalized && VOICE_SEND_MARKER.containsMatchIn(normalized)
    }

    fun isTranscriptionProcessing(): Boolean = VOICE_PROCESSING_MARKER.containsMatchIn(normalizedTail())

    fun revision(): Long = synchronized(lock) { revision }

    fun audioActivityCount(): Long = synchronized(lock) { audioActivityCount }

    fun failureMessage(): String? {
        val normalized = normalizedTail()
        return FAILURE_MARKERS.firstOrNull(normalized::contains)
    }

    fun diagnosticState(): String =
        "interactive=${isInteractiveReady()} " +
            "trustPrompt=${isWorkspaceTrustPrompt()} " +
            "recording=${isRecordingReady()} " +
            "processing=${isTranscriptionProcessing()} " +
            "knownFailure=${failureMessage() ?: "none"} " +
            "outputRevision=${revision()}"

    private fun normalizedTail(): String = synchronized(lock) {
        tail.toString()
            .replace(OSC_SEQUENCE, "")
            .replace(CSI_SEQUENCE, "")
            .replace('\r', '\n')
            .replace(CONTROL_CHARACTER, "")
    }

    private companion object {
        val CLAUDE_CODE_MARKER = Regex("Claude\\s*Code")
        val WORKSPACE_TRUST_MARKER = Regex("Quick\\s*safety\\s*check", RegexOption.IGNORE_CASE)
        val VOICE_SEND_MARKER = Regex("tap\\s*to\\s*send")
        val VOICE_PROCESSING_MARKER = Regex("Voice:\\s*processing")
        const val VOICE_AUDIO_LEVEL_CHARACTERS = "▁▂▃▄▅▆▇█"
        val FAILURE_MARKERS = listOf(
            "Voice mode requires a Claude.ai account",
            "Voice mode is disabled by your organization's policy",
            "Microphone access is denied",
            "No audio recording tool found",
            "Voice mode requires a microphone",
            "No audio detected from microphone",
            "No speech detected",
            "Voice connection failed",
            "Voice input is failing repeatedly and has been paused",
        )
        val OSC_SEQUENCE = Regex("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)")
        val CSI_SEQUENCE = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        val CONTROL_CHARACTER = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
        const val MAX_TERMINAL_TAIL_CHARS = 65_536
    }
}

private class ClaudeVoiceSessionFiles private constructor(
    private val directory: Path,
    val settingsFile: Path,
    private val hookResultFile: Path,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }

    fun readPrompt(): String? {
        if (!Files.isRegularFile(hookResultFile) || Files.size(hookResultFile) == 0L) return null
        return runCatching {
            json.decodeFromString<ClaudeVoicePromptHookInput>(Files.readString(hookResultFile)).prompt
        }.getOrNull()
    }

    override fun close() {
        if (!Files.exists(directory)) return
        runCatching {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    companion object {
        fun create(language: String?): ClaudeVoiceSessionFiles {
            val directory = Files.createTempDirectory("gromozeka-claude-voice-")
            val hookResultFile = directory.resolve("prompt.json")
            val hookCommand = createHookCommand(directory, hookResultFile)
            val settingsFile = directory.resolve("settings.json")
            val settings = buildJsonObject {
                language?.trim()?.takeIf(String::isNotEmpty)?.let { put("language", it) }
                put("prefersReducedMotion", false)
                putJsonObject("voice") {
                    put("enabled", true)
                    put("mode", "tap")
                }
                putJsonObject("hooks") {
                    putJsonArray("UserPromptSubmit") {
                        add(buildJsonObject {
                            put("hooks", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "command")
                                    put("command", hookCommand)
                                    put("timeout", 10)
                                })
                            })
                        })
                    }
                }
            }
            Files.writeString(
                settingsFile,
                settings.toString(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            return ClaudeVoiceSessionFiles(directory, settingsFile, hookResultFile)
        }

        private fun createHookCommand(directory: Path, resultFile: Path): String =
            if (currentOperatingSystem() == OperatingSystem.WINDOWS) {
                val script = directory.resolve("capture-prompt.cmd")
                Files.writeString(
                    script,
                    "@echo off\r\nmore > \"$resultFile\"\r\nexit /b 2\r\n",
                    StandardOpenOption.CREATE_NEW,
                )
                "\"$script\""
            } else {
                val script = directory.resolve("capture-prompt.sh")
                Files.writeString(
                    script,
                    "#!/bin/sh\ncat > '${resultFile.toString().replace("'", "'\\\"'\\\"'")}'\nexit 2\n",
                    StandardOpenOption.CREATE_NEW,
                )
                script.toFile().setExecutable(true, true)
                "'${script.toString().replace("'", "'\\\"'\\\"'")}'"
            }
    }
}

@Serializable
private data class ClaudeVoicePromptHookInput(
    val prompt: String,
)

private class PulseAudioVirtualAudioFactory : ClaudeCodeVirtualAudioFactory {
    override suspend fun open(
        audioData: ByteArray,
        format: SpeechAudioFormat,
    ): ClaudeCodeVirtualAudio = withContext(Dispatchers.IO) {
        require(format == SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ) {
            "Claude Code virtual audio supports only ${SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ}"
        }
        val directory = Files.createTempDirectory("gromozeka-claude-audio-")
        try {
            val audioFile = directory.resolve("speech.wav")
            Files.write(audioFile, audioData)
            val sinkName = "gromozeka_voice_${java.util.UUID.randomUUID().toString().replace("-", "")}"
            val moduleId = runCommand(
                listOf(
                    "pactl",
                    "load-module",
                    "module-null-sink",
                    "sink_name=$sinkName",
                    "rate=${format.sampleRate}",
                    "channels=${format.channels}",
                    "format=s16le",
                )
            ).trim().takeIf { it.matches(Regex("\\d+")) }
                ?: error("PulseAudio did not return a module id for the Claude Code virtual input")
            PulseAudioVirtualAudio(directory, audioFile, sinkName, moduleId)
        } catch (error: Throwable) {
            deleteDirectory(directory)
            throw error
        }
    }
}

private class PulseAudioVirtualAudio(
    private val directory: Path,
    private val audioFile: Path,
    private val sinkName: String,
    private val moduleId: String,
) : ClaudeCodeVirtualAudio {
    override val environment: Map<String, String> = mapOf(
        "PULSE_SOURCE" to "$sinkName.monitor",
        "AUDIODEV" to "$sinkName.monitor",
    )

    override suspend fun play() {
        withContext(Dispatchers.IO) {
            runCommand(
                listOf("paplay", "--device=$sinkName", audioFile.toString()),
                VIRTUAL_AUDIO_PLAYBACK_TIMEOUT_SECONDS,
            )
        }
    }

    override fun close() {
        runCatching { runCommand(listOf("pactl", "unload-module", moduleId)) }
        deleteDirectory(directory)
    }
}

private fun runCommand(
    command: List<String>,
    timeoutSeconds: Long = AUDIO_COMMAND_TIMEOUT_SECONDS,
): String {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!completed) {
        process.destroyForcibly()
        process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(completed) {
        "Command ${command.first()} timed out after $timeoutSeconds seconds"
    }
    check(process.waitFor() == 0) {
        "Command ${command.first()} failed: ${output.trim().ifBlank { "unknown error" }}"
    }
    return output
}

private fun deleteDirectory(directory: Path) {
    if (!Files.exists(directory)) return
    runCatching {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

internal fun claudeVoiceCommand(executablePath: String, settingsFile: Path): List<String> {
    val arguments = claudeVoiceArguments(settingsFile)
    return if (currentOperatingSystem() == OperatingSystem.WINDOWS) {
        val commandLine = buildString {
            append("call ")
            append(windowsQuote(executablePath))
            arguments.forEach { argument ->
                append(' ')
                append(windowsQuote(argument))
            }
        }
        listOf("cmd.exe", "/D", "/S", "/C", commandLine)
    } else {
        listOf(executablePath) + arguments
    }
}

internal fun claudeVoiceArguments(settingsFile: Path): List<String> =
    listOf(
        "--no-chrome",
        "--setting-sources",
        "",
        "--tools",
        "",
        "--settings",
        settingsFile.toString(),
    )

private fun claudeVoiceWorkingDirectory(): Path {
    val home = System.getProperty("GROMOZEKA_HOME")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: System.getenv("GROMOZEKA_HOME")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        ?: Path.of(System.getProperty("user.home"), ".gromozeka").toString()
    return Path.of(home, "claude-code-voice-workspace")
}

private fun windowsQuote(value: String): String =
    "\"${value.replace("\"", "\"\"")}\""

private enum class OperatingSystem {
    LINUX,
    MACOS,
    WINDOWS,
    OTHER,
}

private fun currentOperatingSystem(): OperatingSystem {
    val name = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return when {
        "linux" in name -> OperatingSystem.LINUX
        "mac" in name || "darwin" in name -> OperatingSystem.MACOS
        "windows" in name -> OperatingSystem.WINDOWS
        else -> OperatingSystem.OTHER
    }
}

private const val START_TIMEOUT_MILLIS = 30_000L
private const val TRANSCRIPTION_TIMEOUT_MILLIS = 30_000L
private const val POLL_INTERVAL_MILLIS = 25L
private const val PROCESS_STOP_TIMEOUT_SECONDS = 3L
private const val AUDIO_COMMAND_TIMEOUT_SECONDS = 15L
private const val VIRTUAL_AUDIO_PLAYBACK_TIMEOUT_SECONDS = 240L
private val DEFAULT_WARM_SESSION_MAX_AGE = 10.minutes
private val DEFAULT_MICROPHONE_STOP_TAIL = 750.milliseconds
private val SHORT_TRANSCRIPT_SUBMIT_MINIMUM_DELAY = 5.seconds
private val SHORT_TRANSCRIPT_OUTPUT_STABILITY = 750.milliseconds

internal class ClaudeVoiceShortTranscriptSubmission(
    private val initialOutputRevision: Long,
    private val startedAtNanos: Long,
    private val minimumDelay: Duration = SHORT_TRANSCRIPT_SUBMIT_MINIMUM_DELAY,
    private val outputStability: Duration = SHORT_TRANSCRIPT_OUTPUT_STABILITY,
) {
    private var processingObserved = false
    private var lastOutputRevision = initialOutputRevision
    private var outputStableSinceNanos: Long? = null
    private var submitted = false

    fun shouldSubmit(
        outputRevision: Long,
        processingObserved: Boolean,
        nowNanos: Long,
    ): Boolean {
        if (submitted) return false
        this.processingObserved = this.processingObserved || processingObserved
        if (!this.processingObserved || outputRevision <= initialOutputRevision) return false
        if (outputRevision != lastOutputRevision) {
            lastOutputRevision = outputRevision
            outputStableSinceNanos = nowNanos
            return false
        }
        val stableSince = outputStableSinceNanos ?: nowNanos.also {
            outputStableSinceNanos = it
        }
        if (nowNanos - startedAtNanos < minimumDelay.inWholeNanoseconds) return false
        if (nowNanos - stableSince < outputStability.inWholeNanoseconds) return false
        submitted = true
        return true
    }
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

internal fun terminateClaudeVoiceProcessTree(process: Process) {
    val root = ProcessHandle.of(process.pid()).orElse(null)
    if (root == null) {
        process.destroyForcibly()
        process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return
    }
    repeat(3) {
        root.descendants()
            .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
            .forEach(ProcessHandle::destroyForcibly)
        root.destroyForcibly()
        root.onExit()
            .completeOnTimeout(root, PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .get()
        if (!root.isAlive) return
    }
}
