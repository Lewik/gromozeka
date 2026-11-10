package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.TtsTask
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.AiSpeechSynthesisRequest
import com.gromozeka.domain.service.AiSpeechSynthesisResponse
import com.gromozeka.domain.service.AudioController
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.DirectAiTextToSpeechProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.openai.models.audio.speech.SpeechCreateParams
import com.openai.models.audio.speech.SpeechModel
import java.io.ByteArrayOutputStream
import java.io.File
import klog.KLoggers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class TtsService(
    private val settingsProvider: SettingsProvider,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val audioController: AudioController,
    private val workerTargetResolver: ConversationRuntimeWorkerTargetResolver,
    private val remoteClients: List<AiRequestResponseExecutionClient>,
    private val directProvider: OpenAiSpeechSynthesisExecutor,
) {
    private val log = KLoggers.logger(this)

    suspend fun generateSpeech(
        text: String,
        voiceTone: String = "neutral colleague",
    ): File? {
        if (text.isBlank()) return null
        return runCatching {
            val response = synthesizeConfigured(configuredRequest(text, voiceTone))
            File.createTempFile("tts_output", ".${response.fileExtension}").apply {
                writeBytes(response.audioData)
            }
        }.onFailure { error ->
            log.warn(error) { "Failed to generate speech: ${error.message}" }
        }.getOrNull()
    }

    fun streamSpeechPcm(
        text: String,
        voiceTone: String = "neutral colleague",
    ): Flow<TtsAudioChunk> = flow {
        if (text.isBlank()) return@flow

        val configured = configuredRequest(text, voiceTone)
        require(configured.target == AiExecutionTarget.Server) {
            "Streaming speech synthesis requires a Server-targeted runtime"
        }
        directProvider.streamPcm(configured.runtime, configured.request).collect {
            emit(it)
        }
    }

    suspend fun playAudio(audioFile: File) {
        audioController.playAudioFile(audioFile.absolutePath)
    }

    suspend fun stopPlayback() {
        audioController.stopPlayback()
    }

    suspend fun generateAndPlay(task: TtsTask) {
        val audioFile = generateSpeech(task.text, task.tone)
        audioFile?.let {
            playAudio(it)
            it.delete()
        }
    }

    private fun configuredRequest(
        text: String,
        voiceTone: String,
    ): ConfiguredSpeechSynthesis {
        val selection = aiConfigurationProvider.runtimeSelectionFor(
            AiRuntimeAssignment.Purpose.TEXT_TO_SPEECH
        )
        val runtime = aiConfigurationProvider.resolveAiRuntime(selection)
        val settings = settingsProvider.userProfile.speechSettings.textToSpeech
        return ConfiguredSpeechSynthesis(
            target = runtime.connection.executionTarget,
            runtime = runtime,
            request = AiSpeechSynthesisRequest(
                selection = selection,
                text = text,
                voiceTone = voiceTone,
                voice = settings.voice,
                speed = settings.speed,
            ),
        )
    }

    private suspend fun synthesizeConfigured(
        configured: ConfiguredSpeechSynthesis,
    ): AiSpeechSynthesisResponse =
        when (val target = configured.target) {
            AiExecutionTarget.Server -> directProvider.synthesize(configured.runtime, configured.request)
            is AiExecutionTarget.Worker -> remoteClient().synthesize(
                target = workerTargetResolver.requireOnline(
                    ConversationRuntimeWorkerId(target.workerId),
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                ),
                runtime = configured.runtime,
                request = configured.request,
            )
        }

    private fun remoteClient(): AiRequestResponseExecutionClient =
        remoteClients.singleOrNull()
            ?: error(
                if (remoteClients.isEmpty()) {
                    "Worker-targeted speech synthesis requires Worker Gateway transport"
                } else {
                    "Multiple AI request-response transports are configured"
                }
            )

    private data class ConfiguredSpeechSynthesis(
        val target: AiExecutionTarget,
        val runtime: ResolvedAiRuntime,
        val request: AiSpeechSynthesisRequest,
    )
}

@Service
class OpenAiSpeechSynthesisExecutor(
    private val clientFactory: OpenAiSdkClientFactory,
    private val admission: com.gromozeka.domain.service.AiCallAdmission,
) : DirectAiTextToSpeechProvider {
    private fun executor(runtime: ResolvedAiRuntime) = com.gromozeka.infrastructure.ai.runtime.SdkHttpCallExecutor(
        runtime.connection, runtime.modelConfiguration.defaultParameters.timeoutSeconds, admission = admission,
    ) { end -> clientFactory.createInferenceClient(runtime.connection, end) }

    override suspend fun synthesize(
        runtime: ResolvedAiRuntime,
        request: AiSpeechSynthesisRequest,
    ): AiSpeechSynthesisResponse = executor(runtime).call(
        com.gromozeka.domain.model.ai.AiRuntimeOptions(usagePurpose = "TEXT_TO_SPEECH"),
    ) { client, remaining ->
        client.async().audio().speech().create(
            speechParamsBuilder(request, runtime.modelConfiguration.providerModelId)
                .responseFormat(SpeechCreateParams.ResponseFormat.WAV)
                .streamFormat(SpeechCreateParams.StreamFormat.AUDIO).build(),
            com.openai.core.RequestOptions.builder().timeout(remaining).build(),
        ).thenApply { response ->
            val audioData = response.use { httpResponse ->
                httpResponse.body().use { input ->
                    ByteArrayOutputStream().use { output -> input.copyTo(output); output.toByteArray() }
                }
            }
            AiSpeechSynthesisResponse(audioData, "audio/wav", "wav")
        }
    }

    fun streamPcm(runtime: ResolvedAiRuntime, request: AiSpeechSynthesisRequest): Flow<TtsAudioChunk> = channelFlow {
        executor(runtime).call(com.gromozeka.domain.model.ai.AiRuntimeOptions(usagePurpose = "TEXT_TO_SPEECH")) { client, remaining ->
            client.async().audio().speech().create(
                speechParamsBuilder(request, runtime.modelConfiguration.providerModelId)
                    .responseFormat(SpeechCreateParams.ResponseFormat.PCM)
                    .streamFormat(SpeechCreateParams.StreamFormat.AUDIO).build(),
                com.openai.core.RequestOptions.builder().timeout(remaining).build(),
            ).thenCompose { response ->
                val finished = CompletableFuture<Unit>()
                // The HTTP attempt remains active until its body is consumed or closed.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        response.use { httpResponse ->
                            httpResponse.body().use { input ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val read = withContext(Dispatchers.IO) { input.read(buffer) }
                                    if (read < 0) break
                                    if (read > 0) send(TtsAudioChunk(buffer.copyOf(read)))
                                }
                            }
                        }
                        finished.complete(Unit)
                    } catch (error: Throwable) { finished.completeExceptionally(error) }
                }
                finished
            }
        }
    }

    private fun speechParamsBuilder(
        request: AiSpeechSynthesisRequest,
        modelName: String,
    ): SpeechCreateParams.Builder =
        SpeechCreateParams.builder()
            .input(request.text)
            .model(SpeechModel.of(modelName))
            .voice(request.voice)
            .instructions(voiceInstructions(request.voiceTone))
            .speed(request.speed.coerceIn(0.25f, 4.0f).toDouble())

    private fun voiceInstructions(voiceTone: String): String =
        buildString {
            append("Speak naturally and clearly. Preserve the language of the input text.")
            voiceTone.trim().takeIf { it.isNotBlank() }?.let {
                append(" Tone: ")
                append(it)
                append('.')
            }
        }
}

data class TtsAudioChunk(
    val data: ByteArray,
)
