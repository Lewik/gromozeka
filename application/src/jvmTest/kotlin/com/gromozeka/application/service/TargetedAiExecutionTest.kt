package com.gromozeka.application.service

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiEmbeddingVector
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiSpeechSynthesisRequest
import com.gromozeka.domain.service.AiSpeechSynthesisResponse
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.DirectAiEmbeddingProvider
import com.gromozeka.domain.service.DirectAiRuntimeProvider
import com.gromozeka.domain.service.DirectAiSpeechToTextProvider
import com.gromozeka.domain.service.DirectAiTextToSpeechProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TargetedAiExecutionTest {
    private val selection = AiRuntimeSelection(AiModelConfiguration.Id("model-config"))
    private val request = AiRuntimeRequest(systemPrompts = listOf("system"), messages = emptyList())
    private val workerIdentity = ConversationRuntimeWorkerIdentity(
        workerId = ConversationRuntimeWorkerId("worker-1"),
        sessionId = ConversationRuntimeWorkerSessionId("session-1"),
    )

    @Test
    fun `Server-targeted runtime delegates locally`() = runBlocking {
        val directProvider = RecordingDirectRuntimeProvider()
        val remoteClient = RecordingRemoteClient()
        val provider = runtimeProvider(
            target = AiExecutionTarget.Server,
            directProvider = directProvider,
            remoteClient = remoteClient,
        )

        val response = provider.getRuntime(selection, "/workspace").call(request)

        assertSame(directProvider.response, response)
        assertEquals(
            listOf<Pair<AiRuntimeSelection, String?>>(selection to "/workspace"),
            directProvider.runtimeRequests,
        )
        assertNull(remoteClient.callTarget)
    }

    @Test
    fun `Worker-targeted runtime invokes only the exact Worker`() = runBlocking {
        val directProvider = RecordingDirectRuntimeProvider()
        val remoteClient = RecordingRemoteClient()
        val resolver = RecordingWorkerTargetResolver(workerIdentity)
        val provider = runtimeProvider(
            target = AiExecutionTarget.Worker(workerIdentity.workerId.value),
            directProvider = directProvider,
            remoteClient = remoteClient,
            resolver = resolver,
        )

        val response = provider.getRuntime(selection, null).call(request)

        assertSame(remoteClient.callResponse, response)
        assertEquals(workerIdentity, remoteClient.callTarget)
        assertEquals(
            listOf(workerIdentity.workerId to ConversationRuntimeCapability.AI_REQUEST_RESPONSE),
            resolver.requests,
        )
        assertTrue(directProvider.runtimeRequests.isEmpty())
    }

    @Test
    fun `Worker-targeted runtime rejects Server workspace path and streaming`() = runBlocking {
        val provider = runtimeProvider(
            target = AiExecutionTarget.Worker(workerIdentity.workerId.value),
        )

        assertFailsWith<IllegalArgumentException> {
            provider.getRuntime(selection, "/server/path")
        }
        val runtime = provider.getRuntime(selection, null)
        val error = assertFailsWith<UnsupportedOperationException> {
            runtime.stream(request).toList()
        }
        assertTrue(error.message.orEmpty().contains("Server-targeted"))
    }

    @Test
    fun `Worker-targeted runtime never falls back when transport is absent`() = runBlocking {
        val directProvider = RecordingDirectRuntimeProvider()
        val provider = TargetedAiRuntimeProvider(
            directProvider = directProvider,
            configurationProvider = FixedAiConfigurationProvider(
                AiExecutionTarget.Worker(workerIdentity.workerId.value)
            ),
            workerTargetResolver = RecordingWorkerTargetResolver(workerIdentity),
            remoteClients = emptyList(),
        )

        val error = assertFailsWith<IllegalStateException> {
            provider.getRuntime(selection, null).call(request)
        }

        assertTrue(error.message.orEmpty().contains("Rabbit runtime transport"))
        assertTrue(directProvider.runtimeRequests.isEmpty())
    }

    @Test
    fun `Server and Worker embeddings use only their configured target`() = runBlocking {
        val directProvider = RecordingDirectEmbeddingProvider()
        val remoteClient = RecordingRemoteClient()
        val embeddingRequest = AiEmbeddingRequest(selection, listOf("one"))

        val serverProvider = TargetedAiEmbeddingProvider(
            directProvider = directProvider,
            configurationProvider = FixedAiConfigurationProvider(AiExecutionTarget.Server),
            workerTargetResolver = RecordingWorkerTargetResolver(workerIdentity),
            remoteClients = listOf(remoteClient),
        )
        assertSame(directProvider.response, serverProvider.embed(embeddingRequest))
        assertEquals(listOf(embeddingRequest), directProvider.requests)
        assertNull(remoteClient.embeddingTarget)

        val workerProvider = TargetedAiEmbeddingProvider(
            directProvider = directProvider,
            configurationProvider = FixedAiConfigurationProvider(
                AiExecutionTarget.Worker(workerIdentity.workerId.value)
            ),
            workerTargetResolver = RecordingWorkerTargetResolver(workerIdentity),
            remoteClients = listOf(remoteClient),
        )
        assertSame(remoteClient.embeddingResponse, workerProvider.embed(embeddingRequest))
        assertEquals(workerIdentity, remoteClient.embeddingTarget)
        assertEquals(1, directProvider.requests.size)
    }

    @Test
    fun `Worker resolver rejects missing offline and incapable Workers`() = runBlocking {
        val registry = InMemoryConversationRuntimeWorkerRegistry()
        val resolver = DefaultConversationRuntimeWorkerTargetResolver(registry)
        val missingError = assertFailsWith<IllegalStateException> {
            resolver.requireOnline(workerIdentity.workerId, ConversationRuntimeCapability.AI_REQUEST_RESPONSE)
        }
        assertTrue(missingError.message.orEmpty().contains("Worker not found"))

        val now = Clock.System.now()
        assertTrue(
            registry.register(
                registration = ConversationRuntimeWorkerRegistration(
                    identity = workerIdentity,
                    capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
                    tools = emptyList(),
                    environmentProfile = testWorkerEnvironmentProfile(now),
                    version = "test",
                    startedAt = now,
                    lastHeartbeatAt = now,
                ),
                staleBefore = now,
            )
        )
        val capabilityError = assertFailsWith<IllegalArgumentException> {
            resolver.requireOnline(workerIdentity.workerId, ConversationRuntimeCapability.AI_REQUEST_RESPONSE)
        }
        assertTrue(capabilityError.message.orEmpty().contains("does not support"))

        assertTrue(registry.unregister(workerIdentity, now))
        val offlineError = assertFailsWith<IllegalArgumentException> {
            resolver.requireOnline(workerIdentity.workerId, ConversationRuntimeCapability.TOOL_EXECUTION)
        }
        assertTrue(offlineError.message.orEmpty().contains("offline"))
    }

    @Test
    fun `Worker request handler delegates every finite AI operation directly`() = runBlocking {
        val runtimeProvider = RecordingDirectRuntimeProvider()
        val embeddingProvider = RecordingDirectEmbeddingProvider()
        val speechToTextProvider = RecordingDirectSpeechToTextProvider()
        val textToSpeechProvider = RecordingDirectTextToSpeechProvider()
        val handler = DirectAiRequestResponseExecutionHandler(
            runtimeProvider = runtimeProvider,
            embeddingProvider = embeddingProvider,
            speechToTextProvider = speechToTextProvider,
            textToSpeechProvider = textToSpeechProvider,
        )
        val embeddingRequest = AiEmbeddingRequest(selection, listOf("one"))
        val transcriptionRequest = AiSpeechTranscriptionRequest(
            audioData = byteArrayOf(1),
            format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
            engine = UserProfile.SpeechSettings.SpeechToText.Engine.OPENAI_API,
            selection = selection,
            language = "en",
            prompt = null,
        )
        val synthesisRequest = AiSpeechSynthesisRequest(
            selection = selection,
            text = "Hello",
            voiceTone = "neutral",
            voice = "marin",
            speed = 1.0f,
        )

        assertSame(runtimeProvider.response, handler.call(selection, null, request))
        assertSame(embeddingProvider.response, handler.embed(embeddingRequest))
        assertEquals("transcript", handler.transcribe(transcriptionRequest))
        assertSame(textToSpeechProvider.response, handler.synthesize(synthesisRequest))
        assertEquals(listOf(transcriptionRequest), speechToTextProvider.requests)
        assertEquals(listOf(synthesisRequest), textToSpeechProvider.requests)
    }

    private fun runtimeProvider(
        target: AiExecutionTarget,
        directProvider: RecordingDirectRuntimeProvider = RecordingDirectRuntimeProvider(),
        remoteClient: RecordingRemoteClient = RecordingRemoteClient(),
        resolver: ConversationRuntimeWorkerTargetResolver = RecordingWorkerTargetResolver(workerIdentity),
    ) = TargetedAiRuntimeProvider(
        directProvider = directProvider,
        configurationProvider = FixedAiConfigurationProvider(target),
        workerTargetResolver = resolver,
        remoteClients = listOf(remoteClient),
    )

    private inner class FixedAiConfigurationProvider(
        target: AiExecutionTarget,
    ) : AiConfigurationProvider {
        private val connection = AiConnection.OpenAiApi(
            id = AiConnection.Id("connection"),
            displayName = "Connection",
            enabled = true,
            executionTarget = target,
        )
        private val configuration = AiModelConfiguration(
            id = selection.modelConfigurationId,
            connectionId = connection.id,
            providerModelId = "test-model",
            displayName = "Test model",
        )

        override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(null)
        override val snapshot: AiCatalogSnapshot
            get() = error("Catalog snapshot is outside this test")

        override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime {
            assertEquals(configuration.id, selection.modelConfigurationId)
            return ResolvedAiRuntime(connection, configuration)
        }
    }

    private class RecordingDirectRuntimeProvider : DirectAiRuntimeProvider {
        val response = AiRuntimeResponse(messages = emptyList(), finishReason = "local")
        val runtimeRequests = mutableListOf<Pair<AiRuntimeSelection, String?>>()
        private val runtimeCapabilities = AiRuntimeCapabilities(supportsAutoCompaction = true)

        override fun capabilities(selection: AiRuntimeSelection): AiRuntimeCapabilities = runtimeCapabilities

        override fun getRuntime(
            selection: AiRuntimeSelection,
            workspaceRootPath: String?,
        ): AiRuntime {
            runtimeRequests += selection to workspaceRootPath
            return object : AiRuntime {
                override val capabilities = runtimeCapabilities

                override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse = response

                override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flowOf(response)
            }
        }
    }

    private class RecordingDirectEmbeddingProvider : DirectAiEmbeddingProvider {
        val response = AiEmbeddingResponse(
            modelId = "embedding-model",
            dimensions = 2,
            vectors = listOf(AiEmbeddingVector(0, listOf(0.1f, 0.2f))),
        )
        val requests = mutableListOf<AiEmbeddingRequest>()

        override suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse {
            requests += request
            return response
        }
    }

    private class RecordingDirectSpeechToTextProvider : DirectAiSpeechToTextProvider {
        val requests = mutableListOf<AiSpeechTranscriptionRequest>()

        override suspend fun transcribe(request: AiSpeechTranscriptionRequest): String {
            requests += request
            return "transcript"
        }
    }

    private class RecordingDirectTextToSpeechProvider : DirectAiTextToSpeechProvider {
        val response = AiSpeechSynthesisResponse(byteArrayOf(1), "audio/wav", "wav")
        val requests = mutableListOf<AiSpeechSynthesisRequest>()

        override suspend fun synthesize(request: AiSpeechSynthesisRequest): AiSpeechSynthesisResponse {
            requests += request
            return response
        }
    }

    private class RecordingWorkerTargetResolver(
        private val identity: ConversationRuntimeWorkerIdentity,
    ) : ConversationRuntimeWorkerTargetResolver {
        val requests = mutableListOf<Pair<ConversationRuntimeWorkerId, ConversationRuntimeCapability>>()

        override suspend fun requireOnline(
            workerId: ConversationRuntimeWorkerId,
            capability: ConversationRuntimeCapability,
        ): ConversationRuntimeWorkerIdentity {
            requests += workerId to capability
            assertEquals(identity.workerId, workerId)
            return identity
        }
    }

    private class RecordingRemoteClient : AiRequestResponseExecutionClient {
        val callResponse = AiRuntimeResponse(messages = emptyList(), finishReason = "remote")
        val embeddingResponse = AiEmbeddingResponse(
            modelId = "remote-embedding-model",
            dimensions = 2,
            vectors = listOf(AiEmbeddingVector(0, listOf(0.3f, 0.4f))),
        )
        var callTarget: ConversationRuntimeWorkerIdentity? = null
        var embeddingTarget: ConversationRuntimeWorkerIdentity? = null

        override suspend fun call(
            target: ConversationRuntimeWorkerIdentity,
            selection: AiRuntimeSelection,
            workspaceRootPath: String?,
            request: AiRuntimeRequest,
        ): AiRuntimeResponse {
            callTarget = target
            return callResponse
        }

        override suspend fun embed(
            target: ConversationRuntimeWorkerIdentity,
            request: AiEmbeddingRequest,
        ): AiEmbeddingResponse {
            embeddingTarget = target
            return embeddingResponse
        }

        override suspend fun transcribe(
            target: ConversationRuntimeWorkerIdentity,
            request: AiSpeechTranscriptionRequest,
        ): String = "transcript"

        override suspend fun synthesize(
            target: ConversationRuntimeWorkerIdentity,
            request: AiSpeechSynthesisRequest,
        ): AiSpeechSynthesisResponse = AiSpeechSynthesisResponse(byteArrayOf(1), "audio/wav", "wav")
    }
}
