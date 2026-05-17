package com.gromozeka.presentation.services

import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.remote.protocol.RemoteLiveAudioChunk
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DesktopLocalWhisperSpeechToTextService(
    private val settingsService: SettingsService,
) : ClientSideSpeechToTextService {
    private val log = KLoggers.logger(this)

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
            fileExtension = recording.fileExtension,
            mediaType = recording.mediaType,
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
            fileExtension = chunk.fileExtension,
            mediaType = chunk.mediaType,
            language = language.ifBlank { settingsService.userProfile.speechSettings.speechToText.mainLanguageCode },
            prompt = prompt,
            sequenceNumber = chunk.sequenceNumber,
        )

    private suspend fun transcribe(
        audioData: ByteArray,
        fileExtension: String,
        mediaType: String,
        language: String,
        prompt: String?,
        sequenceNumber: Int?,
    ): String = withContext(Dispatchers.IO) {
        check(isEnabled()) { "Client-side Local Whisper is disabled" }
        require(isWav(mediaType, fileExtension)) {
            "Client-side Local Whisper supports only WAV input. Received mediaType=$mediaType fileExtension=$fileExtension"
        }

        val speechToText = settingsService.userProfile.speechSettings.speechToText
        val settings = speechToText.localWhisper
        val runDir = createRunDir()
        try {
            val inputFile = File(runDir, "input.wav")
            inputFile.writeBytes(audioData)
            val modelFile = resolveModelFile(settings)
            val outputPrefix = File(runDir, "transcript")
            val requestedLanguage = language.trim().ifBlank { speechToText.mainLanguageCode }

            val command = buildList {
                add(settings.executablePath)
                add("-m")
                add(modelFile.absolutePath)
                add("-l")
                add(requestedLanguage)
                add("-otxt")
                add("-of")
                add(outputPrefix.absolutePath)
                add("-nt")
                add("-np")
                prompt?.trim()?.takeIf { it.isNotBlank() }?.let {
                    add("--prompt")
                    add(it)
                }
                add(inputFile.absolutePath)
            }

            log.info {
                "Client Local Whisper transcription requested: sequence=$sequenceNumber bytes=${audioData.size} " +
                    "mediaType=$mediaType language=$requestedLanguage model=${modelFile.absolutePath}"
            }

            val result = runProcess(command, settings.timeoutSeconds)
            val transcriptFile = File("${outputPrefix.absolutePath}.txt")
            val transcript = if (transcriptFile.exists()) {
                transcriptFile.readText()
            } else {
                result.stdout
            }.trim()

            log.info {
                "Client Local Whisper transcription completed: sequence=$sequenceNumber textChars=${transcript.length}"
            }

            transcript
        } finally {
            runDir.deleteRecursively()
        }
    }

    private fun createRunDir(): File {
        val parent = File(settingsService.homeDirectory, "cache/local-whisper-client")
        parent.mkdirs()
        return createTempDir(prefix = "run-", directory = parent)
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

    private suspend fun runProcess(command: List<String>, timeoutSeconds: Int): ProcessResult = coroutineScope {
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            throw IllegalStateException("Failed to start command '${command.first()}': ${e.message}", e)
        }

        val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
        val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
        val completed = withContext(Dispatchers.IO) {
            process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        }

        if (!completed) {
            process.destroyForcibly()
            error("Command timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}")
        }

        val exitCode = process.exitValue()
        val output = ProcessResult(stdout.await(), stderr.await())
        if (exitCode != 0) {
            error(
                "Command failed with exit code $exitCode: ${command.joinToString(" ")}\n" +
                    output.stderr.take(4000)
            )
        }

        output
    }

    private fun isWav(mediaType: String, fileExtension: String): Boolean =
        mediaType.contains("wav", ignoreCase = true) ||
            fileExtension.trim().trimStart('.').equals("wav", ignoreCase = true)

    private fun String.expandHome(): String =
        if (startsWith("~/")) {
            System.getProperty("user.home") + removePrefix("~")
        } else {
            this
        }

    private data class ProcessResult(
        val stdout: String,
        val stderr: String,
    )
}
