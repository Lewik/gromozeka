package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiEmbeddingVector
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.AiSpeechSynthesisRequest
import com.gromozeka.domain.service.AiSpeechSynthesisResponse
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.AudioController
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.speech.LocalWhisperTranscriptionService
import com.gromozeka.infrastructure.ai.claude.ClaudeCodeVoiceTranscriptionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpeechExecutionRoutingTest {
    private val selection = AiRuntimeSelection(AiModelConfiguration.Id("speech-model-config"))
    private val workerIdentity = ConversationRuntimeWorkerIdentity(
        workerId = ConversationRuntimeWorkerId("speech-worker"),
        sessionId = ConversationRuntimeWorkerSessionId("speech-session"),
    )

    @Test
    fun `finite speech transcription and synthesis use the exact configured Worker`() = runBlocking {
        val settings = TestSettingsProvider()
        val configuration = TestAiConfigurationProvider(
            selection = selection,
            target = AiExecutionTarget.Worker(workerIdentity.workerId.value),
        )
        val remoteClient = RecordingRemoteClient()
        val resolver = RecordingWorkerTargetResolver(workerIdentity)
        val clientFactory = OpenAiSdkClientFactory(settings)
        val stt = SttService(
            settingsProvider = settings,
            aiConfigurationProvider = configuration,
            directProvider = OpenAiSpeechTranscriptionExecutor(
                clientFactory,
                LocalWhisperTranscriptionService(settings),
                ClaudeCodeVoiceTranscriptionService(),
            ),
            workerTargetResolver = resolver,
            remoteClients = listOf(remoteClient),
        )
        val tts = TtsService(
            settingsProvider = settings,
            aiConfigurationProvider = configuration,
            audioController = NoOpAudioController,
            workerTargetResolver = resolver,
            remoteClients = listOf(remoteClient),
            directProvider = OpenAiSpeechSynthesisExecutor(clientFactory),
        )

        assertEquals(
            "remote transcript",
            stt.transcribe(
                audioData = byteArrayOf(1),
                format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
            ),
        )
        val audioFile = assertNotNull(tts.generateSpeech("Hello"))
        try {
            assertContentEquals(remoteClient.synthesisResponse.audioData, audioFile.readBytes())
        } finally {
            audioFile.delete()
        }

        assertEquals(workerIdentity, remoteClient.transcriptionTarget)
        assertEquals(workerIdentity, remoteClient.synthesisTarget)
        assertEquals(
            listOf(
                workerIdentity.workerId to ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                workerIdentity.workerId to ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
            ),
            resolver.requests,
        )
    }

    @Test
    fun `live speech paths reject Worker-targeted connections without fallback`() = runBlocking {
        val settings = TestSettingsProvider()
        val configuration = TestAiConfigurationProvider(
            selection = selection,
            target = AiExecutionTarget.Worker(workerIdentity.workerId.value),
        )
        val remoteClient = RecordingRemoteClient()
        val resolver = RecordingWorkerTargetResolver(workerIdentity)
        val clientFactory = OpenAiSdkClientFactory(settings)
        val stt = SttService(
            settingsProvider = settings,
            aiConfigurationProvider = configuration,
            directProvider = OpenAiSpeechTranscriptionExecutor(
                clientFactory,
                LocalWhisperTranscriptionService(settings),
                ClaudeCodeVoiceTranscriptionService(),
            ),
            workerTargetResolver = resolver,
            remoteClients = listOf(remoteClient),
        )
        val tts = TtsService(
            settingsProvider = settings,
            aiConfigurationProvider = configuration,
            audioController = NoOpAudioController,
            workerTargetResolver = resolver,
            remoteClients = listOf(remoteClient),
            directProvider = OpenAiSpeechSynthesisExecutor(clientFactory),
        )

        val sttError = assertFailsWith<IllegalArgumentException> {
            stt.transcribeServerOnly(
                audioData = byteArrayOf(1),
                format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
            )
        }
        val ttsError = assertFailsWith<IllegalArgumentException> {
            tts.streamSpeechPcm("Hello").toList()
        }

        assertTrue(sttError.message.orEmpty().contains("Server-targeted"))
        assertTrue(ttsError.message.orEmpty().contains("Server-targeted"))
        assertEquals(0, remoteClient.transcriptionCalls)
        assertEquals(0, remoteClient.synthesisCalls)
    }

    private class TestSettingsProvider : SettingsProvider {
        override val userProfile = UserProfile(
            speechSettings = UserProfile.SpeechSettings(
                speechToText = UserProfile.SpeechSettings.SpeechToText(
                    enabled = true,
                    engine = UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API,
                    mainLanguageCode = "en",
                ),
                textToSpeech = UserProfile.SpeechSettings.TextToSpeech(
                    enabled = true,
                    voice = "marin",
                    speed = 1.0f,
                ),
            )
        )
        override val userDeviceSettings = UserDeviceSettings.Desktop()
        override val mode = AppMode.TEST
        override val homeDirectory = System.getProperty("java.io.tmpdir")
    }

    private class TestAiConfigurationProvider(
        private val selection: AiRuntimeSelection,
        target: AiExecutionTarget,
    ) : AiConfigurationProvider {
        private val connection = AiConnection.OpenAiApi(
            id = AiConnection.Id("speech-connection"),
            displayName = "Speech connection",
            enabled = true,
            executionTarget = target,
        )
        private val modelConfiguration = AiModelConfiguration(
            id = selection.modelConfigurationId,
            connectionId = connection.id,
            providerModelId = "speech-model",
            displayName = "Speech model",
        )
        private val modelSpec = AiModelSpec(
            id = modelConfiguration.providerModelId,
            provider = AiProvider.OPENAI,
            capabilities = setOf(
                AiModelCapability.SPEECH_TO_TEXT,
                AiModelCapability.TEXT_TO_SPEECH,
            ),
        )

        override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(null)
        override val snapshot: AiCatalogSnapshot
            get() = error("Catalog snapshot is outside this test")

        override fun runtimeSelectionFor(purpose: AiRuntimeAssignment.Purpose): AiRuntimeSelection = selection

        override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime {
            assertEquals(modelConfiguration.id, selection.modelConfigurationId)
            return ResolvedAiRuntime(connection, modelConfiguration, modelSpec)
        }
    }

    private class RecordingWorkerTargetResolver(
        private val identity: ConversationRuntimeWorkerIdentity,
    ) : ConversationRuntimeWorkerTargetResolver {
        val requests = mutableListOf<Pair<ConversationRuntimeWorkerId, ConversationRuntimeCapability>>()

        override suspend fun requireRegistered(
            workerId: ConversationRuntimeWorkerId,
            capability: ConversationRuntimeCapability,
        ): ConversationRuntimeWorkerIdentity = requireOnline(workerId, capability)

        override suspend fun requireOnline(
            workerId: ConversationRuntimeWorkerId,
            capability: ConversationRuntimeCapability,
        ): ConversationRuntimeWorkerIdentity {
            requests += workerId to capability
            return identity
        }
    }

    private class RecordingRemoteClient : AiRequestResponseExecutionClient {
        val synthesisResponse = AiSpeechSynthesisResponse(
            audioData = byteArrayOf(1, 2, 3),
            mediaType = "audio/wav",
            fileExtension = "wav",
        )
        var transcriptionTarget: ConversationRuntimeWorkerIdentity? = null
        var synthesisTarget: ConversationRuntimeWorkerIdentity? = null
        var transcriptionCalls = 0
        var synthesisCalls = 0

        override suspend fun call(
            target: ConversationRuntimeWorkerIdentity,
            runtime: ResolvedAiRuntime,
            workspaceRootPath: String?,
            request: AiRuntimeRequest,
        ): AiRuntimeResponse = AiRuntimeResponse(emptyList())

        override suspend fun embed(
            target: ConversationRuntimeWorkerIdentity,
            runtime: ResolvedAiRuntime,
            request: AiEmbeddingRequest,
        ): AiEmbeddingResponse =
            AiEmbeddingResponse(
                modelId = "embedding-model",
                dimensions = 1,
                vectors = listOf(AiEmbeddingVector(0, listOf(1.0f))),
            )

        override suspend fun transcribe(
            target: ConversationRuntimeWorkerIdentity,
            runtime: ResolvedAiRuntime?,
            localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?,
            request: AiSpeechTranscriptionRequest,
        ): String {
            transcriptionCalls += 1
            transcriptionTarget = target
            return "remote transcript"
        }

        override suspend fun synthesize(
            target: ConversationRuntimeWorkerIdentity,
            runtime: ResolvedAiRuntime,
            request: AiSpeechSynthesisRequest,
        ): AiSpeechSynthesisResponse {
            synthesisCalls += 1
            synthesisTarget = target
            return synthesisResponse
        }

        override suspend fun readSubscriptionQuota(
            target: ConversationRuntimeWorkerIdentity,
            request: AiSubscriptionQuotaRequest,
        ): AiSubscriptionQuotaSnapshot = error("Unused subscription quota")
    }

    private data object NoOpAudioController : AudioController {
        override suspend fun playAudioFile(filePath: String) = Unit

        override suspend fun stopPlayback() = Unit
    }
}
