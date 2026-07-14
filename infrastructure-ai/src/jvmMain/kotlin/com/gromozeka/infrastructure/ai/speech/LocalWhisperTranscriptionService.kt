package com.gromozeka.infrastructure.ai.speech

import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.service.SettingsProvider
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.pathString

@Service
class LocalWhisperTranscriptionService(
    private val settingsProvider: SettingsProvider,
) {
    private val log = KLoggers.logger(this)

    suspend fun transcribe(
        audioData: ByteArray,
        language: String,
        prompt: String?,
        settings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper,
    ): String = withContext(Dispatchers.IO) {
        val runDir = createRunDir()
        try {
            val wavFile = File(runDir, "input-whisper.wav")
            wavFile.writeBytes(audioData)
            val modelFile = resolveModelFile(settings)
            val outputPrefix = File(runDir, "transcript")

            val command = buildList {
                add(settings.executablePath)
                add("-m")
                add(modelFile.absolutePath)
                add("-l")
                add(language)
                add("-otxt")
                add("-of")
                add(outputPrefix.absolutePath)
                add("-nt")
                add("-np")
                prompt?.trim()?.takeIf { it.isNotBlank() }?.let {
                    add("--prompt")
                    add(it)
                }
                add(wavFile.absolutePath)
            }

            log.info {
                "Local Whisper transcription requested: bytes=${audioData.size} " +
                    "language=$language promptChars=${prompt?.length ?: 0} model=${modelFile.absolutePath}"
            }

            val result = runProcess(command, settings.timeoutSeconds)
            val transcriptFile = File("${outputPrefix.absolutePath}.txt")
            val transcript = if (transcriptFile.exists()) {
                transcriptFile.readText()
            } else {
                result.stdout
            }.trim()

            log.info {
                "Local Whisper transcription completed: textChars=${transcript.length}"
            }

            transcript
        } finally {
            runDir.deleteRecursively()
        }
    }

    private fun createRunDir(): File {
        val parent = File(settingsProvider.homeDirectory, "cache/local-whisper")
        parent.mkdirs()
        return File(createTempDirectory(parent.toPath(), "run-").pathString)
    }

    private fun resolveModelFile(settings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper): File {
        val configuredPath = settings.modelPath.trim()
        val modelFile = if (configuredPath.isNotBlank()) {
            File(configuredPath.expandHome())
        } else {
            File(settingsProvider.homeDirectory, "models/whisper/ggml-${settings.modelName}.bin")
        }

        if (modelFile.exists()) {
            return modelFile
        }

        error(
            "Local Whisper model file not found: ${modelFile.absolutePath}. " +
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
