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
import kotlinx.coroutines.withTimeout
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
) {
    private val warmScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("claude-code-voice-warmup")
    )
    private val warmMutex = Mutex()
    private val warmSessions = mutableMapOf<ClaudeCodeVoiceSessionKey, Deferred<ClaudeCodeVoicePreparedSession>>()

    suspend fun prepareLocalMicrophone(
        connection: AiConnection.ClaudeCode,
        language: String?,
    ) {
        requireLocalMicrophone(connection)
        val key = ClaudeCodeVoiceSessionKey(connection, language.normalizedLanguage())
        val stale = mutableListOf<Deferred<ClaudeCodeVoicePreparedSession>>()
        val prepared = warmMutex.withLock {
            warmSessions.entries.removeAll { (candidateKey, candidate) ->
                (candidateKey.connection.id == connection.id && candidateKey != key).also { remove ->
                    if (remove) stale += candidate
                }
            }
            warmSessions.getOrPut(key) { prepareAsync(key) }
        }
        stale.forEach { disposePrepared(it) }
        try {
            prepared.await().requireAlive()
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
        val stale = mutableListOf<Deferred<ClaudeCodeVoicePreparedSession>>()
        val prepared = warmMutex.withLock {
            warmSessions.entries.removeAll { (candidateKey, candidate) ->
                (candidateKey.connection.id == connection.id && candidateKey != key).also { remove ->
                    if (remove) stale += candidate
                }
            }
            val current = warmSessions.remove(key) ?: prepareAsync(key)
            warmSessions[key] = prepareAsync(key)
            current
        }
        stale.forEach { disposePrepared(it) }
        val session = prepared.await()
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
    ): Deferred<ClaudeCodeVoicePreparedSession> = warmScope.async {
        sessionFactory.prepare(key.connection, key.language, emptyMap())
    }

    private suspend fun disposePrepared(prepared: Deferred<ClaudeCodeVoicePreparedSession>) {
        if (!prepared.isCompleted) prepared.cancel()
        runCatching { prepared.await() }.getOrNull()?.dispose()
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

        PtyClaudeCodeVoiceCaptureSession(process, files).also { session ->
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
) : ClaudeCodeVoicePreparedSession, ClaudeCodeVoiceCaptureSession {
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

    override val isAlive: Boolean
        get() = !closed && process.isAlive

    suspend fun awaitInteractiveReady() {
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
    }

    override suspend fun beginRecording(): ClaudeCodeVoiceCaptureSession {
        check(!closed) { "Claude Code voice session is already closed" }
        check(!recording) { "Claude Code voice session is already recording" }
        write(" ")
        awaitTerminalState(
            timeoutMillis = START_TIMEOUT_MILLIS,
            timeoutMessage = "Claude Code voice recorder did not become ready",
            ready = output::isRecordingReady,
        )
        recording = true
        return this
    }

    override suspend fun stop(): String {
        check(!closed) { "Claude Code voice session is already closed" }
        check(recording) { "Claude Code voice session is not recording" }
        val outputRevisionBeforeStop = output.revision()
        write(" ")
        val shortTranscriptSubmission = ClaudeVoiceShortTranscriptSubmission(outputRevisionBeforeStop)
        return try {
            withTimeout(TRANSCRIPTION_TIMEOUT_MILLIS) {
                while (true) {
                    files.readPrompt()?.let { prompt ->
                        return@withTimeout prompt.trim()
                    }
                    output.failureMessage()?.let { error(it) }
                    val shouldSubmitShortTranscript = shortTranscriptSubmission.shouldSubmit(
                        outputRevision = output.revision(),
                        processingObserved = output.isTranscriptionProcessing(),
                        nowNanos = System.nanoTime(),
                    )
                    if (shouldSubmitShortTranscript) {
                        write("\r")
                    }
                    requireAlive()
                    delay(POLL_INTERVAL_MILLIS)
                }
                @Suppress("UNREACHABLE_CODE")
                error("Claude Code voice transcription did not complete")
            }
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
        withContext(Dispatchers.IO) {
            runCatching { process.outputStream.close() }
            if (process.isAlive) process.destroy()
            if (process.isAlive && !process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
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

    fun append(text: String) = synchronized(lock) {
        tail.append(text)
        revision += 1
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
private const val TRANSCRIPTION_TIMEOUT_MILLIS = 180_000L
private const val POLL_INTERVAL_MILLIS = 25L
private const val PROCESS_STOP_TIMEOUT_SECONDS = 3L
private const val AUDIO_COMMAND_TIMEOUT_SECONDS = 15L
private const val VIRTUAL_AUDIO_PLAYBACK_TIMEOUT_SECONDS = 240L
private const val SHORT_TRANSCRIPT_SUBMIT_GRACE_NANOS = 1_500_000_000L

internal class ClaudeVoiceShortTranscriptSubmission(
    private val initialOutputRevision: Long,
    private val graceNanos: Long = SHORT_TRANSCRIPT_SUBMIT_GRACE_NANOS,
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
        if (nowNanos - stableSince < graceNanos) return false
        submitted = true
        return true
    }
}
