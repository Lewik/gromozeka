package com.gromozeka.presentation.services

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.remote.protocol.RemoteLiveAudioChunk
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.time.TimeSource

class DesktopLocalWhisperSpeechToTextService(
    private val settingsService: SettingsService,
) : ClientSideSpeechToTextService {
    private val log = KLoggers.logger(this)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val serverLock = ReentrantLock()

    @Volatile
    private var serverHandle: WhisperServerHandle? = null

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread(
                { stopServer() },
                "gromozeka-local-whisper-shutdown"
            )
        )
    }

    override fun isEnabled(): Boolean =
        settingsService.userProfile.speechSettings.speechToText.engine ==
            UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER

    override suspend fun transcribe(recording: RemoteAudioRecording): String {
        val audioBytes = ByteArrayOutputStream().use { output ->
            recording.chunks
                .sortedBy { it.sequenceNumber }
                .forEach { output.write(it.data) }
            output.toByteArray()
        }

        return transcribe(
            audioData = audioBytes,
            format = recording.format,
            language = settingsService.userProfile.speechSettings.speechToText.mainLanguageCode,
            prompt = null,
            sequenceNumber = null,
        )
    }

    override suspend fun transcribe(
        chunk: RemoteLiveAudioChunk,
        language: String,
        prompt: String?,
    ): String =
        transcribe(
            audioData = chunk.data,
            format = chunk.format,
            language = language.ifBlank { settingsService.userProfile.speechSettings.speechToText.mainLanguageCode },
            prompt = prompt,
            sequenceNumber = chunk.sequenceNumber,
        )

    private suspend fun transcribe(
        audioData: ByteArray,
        format: SpeechAudioFormat,
        language: String,
        prompt: String?,
        sequenceNumber: Int?,
    ): String = withContext(Dispatchers.IO) {
        check(isEnabled()) { "Client-side Local Whisper is disabled" }
        format.requireValid(audioData)

        val speechToText = settingsService.userProfile.speechSettings.speechToText
        val settings = speechToText.localWhisper
        val requestedLanguage = language.trim().ifBlank { speechToText.mainLanguageCode }
        val server = ensureServerStarted(settings)
        val audioContext = settings.audioContextFramesForWavBytes(audioData.size)
        val audioDurationSeconds = wavDurationSeconds(audioData.size)

        log.info {
            "Client Local Whisper transcription requested: sequence=$sequenceNumber bytes=${audioData.size} " +
                "format=$format language=$requestedLanguage audioCtx=$audioContext url=${server.inferenceUrl}"
        }

        val startedAt = TimeSource.Monotonic.markNow()
        val transcript = postInference(
            server = server,
            audioData = audioData,
            language = requestedLanguage,
            prompt = prompt,
            audioContext = audioContext,
            timeoutSeconds = settings.timeoutSeconds,
        )
        val elapsed = startedAt.elapsedNow()
        val rtf = audioDurationSeconds?.takeIf { it > 0.0 }?.let { elapsed.inWholeMilliseconds / 1000.0 / it }

        log.info {
            "Client Local Whisper transcription completed: sequence=$sequenceNumber textChars=${transcript.length} " +
                "elapsedMs=${elapsed.inWholeMilliseconds} audioSec=${audioDurationSeconds?.formatSeconds()} rtf=${rtf?.formatRatio()}"
        }

        transcript
    }

    private fun ensureServerStarted(settings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper): WhisperServerHandle {
        val modelFile = resolveModelFile(settings)
        val serverExecutable = deriveServerExecutable(settings.executablePath)

        serverHandle
            ?.takeIf { it.process.isAlive && it.matches(serverExecutable, modelFile) }
            ?.let { return it }

        return serverLock.withLock {
            serverHandle
                ?.takeIf { it.process.isAlive && it.matches(serverExecutable, modelFile) }
                ?.let { return it }

            stopServerLocked()?.let { stopHandle(it) }
            val port = findFreePort()
            val command = listOf(
                serverExecutable,
                "-m",
                modelFile.absolutePath,
                "--host",
                "127.0.0.1",
                "--port",
                port.toString(),
            )

            log.info {
                "Starting client Local Whisper server: executable=$serverExecutable port=$port model=${modelFile.absolutePath}"
            }

            val process = try {
                ProcessBuilder(command).start()
            } catch (e: IOException) {
                throw IllegalStateException("Failed to start Local Whisper server '$serverExecutable': ${e.message}", e)
            }

            drainProcessOutput(process, "stdout")
            drainProcessOutput(process, "stderr")

            val handle = WhisperServerHandle(
                process = process,
                port = port,
                executablePath = serverExecutable,
                modelPath = modelFile.absolutePath,
            )
            try {
                waitForReady(handle, settings)
                serverHandle = handle
            } catch (error: Throwable) {
                stopHandle(handle)
                throw error
            }
            log.info { "Client Local Whisper server ready: port=$port" }
            handle
        }
    }

    private fun waitForReady(
        server: WhisperServerHandle,
        settings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper,
    ) {
        val startupTimeoutSeconds = settings.serverStartupTimeoutSeconds.toLong()
        val deadline = System.nanoTime() + Duration.ofSeconds(startupTimeoutSeconds).toNanos()
        var lastError: Throwable? = null

        while (System.nanoTime() < deadline) {
            if (!server.process.isAlive) {
                error("Local Whisper server exited before becoming ready with code ${server.process.exitValue()}")
            }

            try {
                val request = HttpRequest.newBuilder(server.rootUrl)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() in 200..499) {
                    return
                }
            } catch (error: Throwable) {
                lastError = error
            }

            Thread.sleep(ServerPollIntervalMillis)
        }

        throw IllegalStateException("Local Whisper server was not ready after ${startupTimeoutSeconds}s", lastError)
    }

    private fun postInference(
        server: WhisperServerHandle,
        audioData: ByteArray,
        language: String,
        prompt: String?,
        audioContext: Int?,
        timeoutSeconds: Int,
    ): String {
        val multipart = MultipartBodyBuilder()
            .field("language", language)
            .field("response_format", "text")
            .field("no_timestamps", "true")
            .apply {
                audioContext?.let { field("audio_ctx", it.toString()) }
                prompt?.trim()?.takeIf { it.isNotBlank() }?.let { field("prompt", it) }
            }
            .file(
                name = "file",
                filename = "audio.wav",
                contentType = "audio/wav",
                data = audioData,
            )
            .build()

        val request = HttpRequest.newBuilder(server.inferenceUrl)
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .header("Content-Type", "multipart/form-data; boundary=${multipart.boundary}")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            error("Local Whisper server inference failed: status=${response.statusCode()} body=${response.body().take(2000)}")
        }

        return extractTextFromBody(response.body()).trim()
    }

    private fun extractTextFromBody(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) {
            return trimmed
        }

        return runCatching {
            Json.parseToJsonElement(trimmed)
                .jsonObject["text"]
                ?.jsonPrimitive
                ?.content
                ?: trimmed
        }.getOrElse { trimmed }
    }

    private fun deriveServerExecutable(executablePath: String): String {
        val expandedPath = executablePath.trim().expandHome()
        val executableFile = File(expandedPath)
        val name = executableFile.name
        val serverName = when {
            name.equals("whisper-server", ignoreCase = true) ||
                name.equals("whisper-server.exe", ignoreCase = true) -> name
            name.endsWith(".exe", ignoreCase = true) -> "whisper-server.exe"
            else -> "whisper-server"
        }

        return executableFile.parentFile
            ?.resolve(serverName)
            ?.path
            ?: serverName
    }

    private fun resolveModelFile(settings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper): File {
        val configuredPath = settings.modelPath.trim()
        val modelFile = if (configuredPath.isNotBlank()) {
            File(configuredPath.expandHome())
        } else {
            File(settingsService.homeDirectory, "models/whisper/ggml-${settings.modelName}.bin")
        }

        if (modelFile.exists()) {
            return modelFile
        }

        error(
            "Client Local Whisper model file not found: ${modelFile.absolutePath}. " +
                "Install it with: mkdir -p '${modelFile.parentFile.absolutePath}' && " +
                "curl -L 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${modelFile.name}' " +
                "-o '${modelFile.absolutePath}'"
        )
    }

    private fun stopServer() {
        val handle = serverLock.withLock {
            stopServerLocked()
        } ?: return

        stopHandle(handle)
    }

    private fun stopHandle(handle: WhisperServerHandle) {
        log.info { "Stopping client Local Whisper server: port=${handle.port}" }
        handle.process.destroy()
        if (!handle.process.waitFor(ServerShutdownGraceMillis, TimeUnit.MILLISECONDS)) {
            handle.process.destroyForcibly()
        }
    }

    private fun stopServerLocked(): WhisperServerHandle? {
        val current = serverHandle
        serverHandle = null
        return current
    }

    private fun drainProcessOutput(process: Process, streamName: String) {
        val stream = if (streamName == "stdout") process.inputStream else process.errorStream
        thread(
            isDaemon = true,
            name = "gromozeka-local-whisper-$streamName",
        ) {
            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    log.info { "[whisper-server][$streamName] $line" }
                }
            }
        }
    }

    private fun findFreePort(): Int =
        ServerSocket(0).use { it.localPort }

    private fun wavDurationSeconds(audioBytes: Int): Double? {
        val pcmBytes = audioBytes - WavHeaderBytes
        if (pcmBytes <= 0) return null
        return pcmBytes / WavBytesPerSecond
    }

    private fun Double.formatSeconds(): String =
        format(1)

    private fun Double.formatRatio(): String =
        format(2)

    private fun Double.format(decimals: Int): String {
        val factor = when (decimals) {
            0 -> 1.0
            1 -> 10.0
            else -> 100.0
        }
        return (kotlin.math.round(this * factor) / factor).toString()
    }

    private fun String.expandHome(): String =
        if (startsWith("~/")) {
            System.getProperty("user.home") + removePrefix("~")
        } else {
            this
        }

    private data class WhisperServerHandle(
        val process: Process,
        val port: Int,
        val executablePath: String,
        val modelPath: String,
    ) {
        val rootUrl: URI = URI.create("http://127.0.0.1:$port/")
        val inferenceUrl: URI = URI.create("http://127.0.0.1:$port/inference")

        fun matches(executablePath: String, modelFile: File): Boolean =
            this.executablePath == executablePath && this.modelPath == modelFile.absolutePath
    }

    private data class MultipartBody(
        val boundary: String,
        val body: ByteArray,
    )

    private class MultipartBodyBuilder {
        private val boundary = "----Gromozeka${UUID.randomUUID().toString().replace("-", "")}"
        private val output = ByteArrayOutputStream()

        fun field(name: String, value: String): MultipartBodyBuilder = apply {
            partHeader(name = name, filename = null, contentType = "text/plain; charset=utf-8")
            output.write(value.encodeToByteArray())
            output.write(CrLf)
        }

        fun file(
            name: String,
            filename: String,
            contentType: String,
            data: ByteArray,
        ): MultipartBodyBuilder = apply {
            partHeader(name = name, filename = filename, contentType = contentType)
            output.write(data)
            output.write(CrLf)
        }

        fun build(): MultipartBody {
            output.write("--$boundary--".encodeToByteArray())
            output.write(CrLf)
            return MultipartBody(boundary = boundary, body = output.toByteArray())
        }

        private fun partHeader(
            name: String,
            filename: String?,
            contentType: String,
        ) {
            output.write("--$boundary".encodeToByteArray())
            output.write(CrLf)
            val disposition = buildString {
                append("Content-Disposition: form-data; name=\"")
                append(name)
                append("\"")
                if (filename != null) {
                    append("; filename=\"")
                    append(filename)
                    append("\"")
                }
            }
            output.write(disposition.encodeToByteArray())
            output.write(CrLf)
            output.write("Content-Type: $contentType".encodeToByteArray())
            output.write(CrLf)
            output.write(CrLf)
        }

        companion object {
            private val CrLf = "\r\n".encodeToByteArray()
        }
    }

    private companion object {
        private const val ServerPollIntervalMillis = 500L
        private const val ServerShutdownGraceMillis = 3_000L
        private const val WavHeaderBytes = 44
        private const val WavBytesPerSecond = 32_000.0
    }
}
